<?php
/**
 * ============================================================================
 *  Claudeflare 风格反代 —— PHP 独立版 (FreeAPI egress proxy)
 * ============================================================================
 * 与 node/node-server.js 及 claudeflare-worker/worker.js 同构：
 * 混合代理 + 流式传输 + 隐私头 + token 鉴权 + 自动降级。
 *
 * 运行方式（PHP >= 5.4，需开启 curl）：
 *   PROXY_TOKEN=xxx php -S 0.0.0.0:8788 proxy.php
 *
 * 也可作为 Apache/Nginx+PHP-FPM 入口脚本部署（Base URL 指向本脚本）。
 *
 * 支持两种入站形态：
 *   1) HTTP 绝对 URI（RFC7230 老代理）：curl -x http://127.0.0.1:8788 https://api...
 *      （仅内置服务器 SAPI 下可解析绝对 URI；FPM 下需用 X-Forward-Target）
 *   2) egress 中继（FreeAPI 绑定代理契约）：请求带 X-Forward-Target 完整上游 URL
 *      + X-Proxy-Token，转发到目标并剥掉 X-Proxy-Token/X-Forward-Target 等内部头，
 *      保留真实上游凭据（Authorization）。
 *
 * 鉴权：X-Proxy-Token 头 或 Authorization: Bearer <token> 或 ?token=。
 * 说明：PHP 无真正的任意 TLS Socket 半双工隧道，因此用 cURL 实现流式转发，
 *      行为对齐 node/worker 的 fetch 分支（SSE 逐块透传），对 AI 上游足够。
 * ============================================================================
 */

// ---------- 配置（优先环境变量） ----------
$PROXY_TOKEN = getenv('PROXY_TOKEN') ?: '';         // 代理自身鉴权 token
$PROXY_MODE  = getenv('PROXY_MODE')  ?: 'auto';     // auto|fetch（socket 不可用，等同 fetch）
$ALLOW_OPEN  = getenv('PROXY_ALLOW_OPEN') === '1';  // 未配置 token 时默认禁裸奔

// 受 Cloudflare 保护 / 同网主机（'.x' 前缀 = 子域通配）。
$FORCE_FETCH_HOSTS = [
    'api.openai.com', '.openai.com',
    'api.anthropic.com', '.anthropic.com', '.claude.ai',
    'api.cline.bot', '.cline.bot',
    'opencode.ai', '.opencode.ai',
];

// 转发时剥掉的特征 / 控制 / 内部鉴权头（小写）。
$BLOCKED_HEADERS = [
    'x-forwarded-for', 'x-forwarded-proto', 'x-forwarded-host', 'x-real-ip',
    'true-client-ip', 'forwarded', 'via', 'proxy-authorization',
    'x-proxy-token', 'x-forward-target', 'x-upstream-auth',
    'user-agent', // 不透传客户端 UA（避免泄漏真实浏览器标识给上游）
    'host', 'connection', 'proxy-connection', 'keep-alive',
    'content-length', // cURL 设置 POSTFIELDS 时自行计算 Content-Length，避免与透传值冲突
];

// ---------- 工具函数 ----------

// proxy_request_headers 汇总本次请求的全部请求头（小写键）。
// 部分 SAPI（openresty/PHP-FPM 在特定配置下）getallheaders() 读不到自定义头，
// 因此以 $_SERVER['HTTP_*'] 为主、getallheaders() 为辅，保证第三方自定义
// header（如 x-goog-api-key、自定义鉴权头）能可靠透传。
function proxy_request_headers() {
    $out = [];
    if (function_exists('getallheaders')) {
        foreach (getallheaders() as $k => $v) $out[strtolower($k)] = $v;
    }
    foreach ($_SERVER as $k => $v) {
        if (strpos($k, 'HTTP_') === 0) {
            $name = strtolower(str_replace('_', '-', substr($k, 5)));
            if (isset($v)) $out[$name] = $v;
        }
    }
    if (isset($_SERVER['CONTENT_TYPE']))   $out['content-type'] = $_SERVER['CONTENT_TYPE'];
    if (isset($_SERVER['CONTENT_LENGTH'])) $out['content-length'] = $_SERVER['CONTENT_LENGTH'];
    return $out;
}

function proxy_bad($status, $message) {
    if (!headers_sent()) {
        header('Content-Type: application/json');
        header('Access-Control-Allow-Origin: *');
        http_response_code($status);
    }
    echo json_encode(['error' => ['message' => $message, 'type' => 'proxy_error']]);
    exit;
}

function proxy_hash_equals($a, $b) {
    if (!is_string($a) || !is_string($b)) return false;
    if (strlen($a) !== strlen($b)) return false;
    $d = 0;
    for ($i = 0, $n = strlen($a); $i < $n; $i++) $d |= ord($a[$i]) ^ ord($b[$i]);
    return $d === 0;
}

function proxy_request_token() {
    // 优先认 X-Proxy-Token 头（getallheaders 与 $_SERVER 双来源，保证 openresty 可读）。
    $h = proxy_request_headers();
    if (isset($h['x-proxy-token']) && $h['x-proxy-token'] !== '') return $h['x-proxy-token'];
    $auth = $h['authorization'] ?? ($_SERVER['HTTP_AUTHORIZATION'] ?? '');
    if (stripos($auth, 'Bearer ') === 0) return trim(substr($auth, 7));
    if (isset($_GET['token'])) return (string)$_GET['token'];
    return '';
}

function proxy_auth_ok() {
    global $PROXY_TOKEN, $ALLOW_OPEN;
    if ($PROXY_TOKEN === '') return $ALLOW_OPEN;
    return proxy_hash_equals(proxy_request_token(), $PROXY_TOKEN);
}

function proxy_should_force_fetch($host) {
    global $PROXY_MODE, $FORCE_FETCH_HOSTS;
    if ($PROXY_MODE === 'fetch') return true;
    if ($host === '') return false;
    $h = strtolower(explode(':', $host)[0]);
    foreach ($FORCE_FETCH_HOSTS as $d) {
        $dd = strtolower($d);
        if ($dd[0] === '.') { if (substr($h, -strlen($dd)) === $dd) return true; }
        elseif ($h === $dd) return true;
    }
    return false;
}

/** 过滤后的转发头；Go 侧（byetPrepareReq）把上游 Authorization 改写为
 *  X-Upstream-Auth 透传，这里还原为真正的 Authorization 再发给上游。 */
function proxy_forward_headers() {
    global $BLOCKED_HEADERS;
    $h = proxy_request_headers();
    // x-upstream-auth 会被 $BLOCKED_HEADERS 过滤掉，需先从原始请求头里取出。
    $upAuth = $h['x-upstream-auth'] ?? '';
    $out = [];
    foreach ($h as $k => $v) {
        if ($v === '' || $v === null) continue;
        if ($k === 'x-upstream-auth') continue; // 已单独提取，避免又被透传
        if (in_array($k, $BLOCKED_HEADERS, true)) continue;
        if (strpos($k, 'cf-') === 0 || strpos($k, 'cf_') === 0) continue;
        $out[$k] = $v;
    }
    if ($upAuth !== '') {
        $has = false;
        foreach (array_keys($out) as $k) if (strcasecmp($k, 'Authorization') === 0) { $has = true; break; }
        if (!$has) $out['Authorization'] = $upAuth;
    }
    return $out;
}

/** 供 cURL CURLOPT_HTTPHEADER 使用的 'K: V' 数组。 */
function proxy_header_list($assoc) {
    $list = [];
    foreach ($assoc as $k => $v) $list[] = $k . ': ' . $v;
    return $list;
}

/** 解析目标 URL：X-Forward-Target 优先，否则绝对 URI，否则按 Host 拼。 */
function proxy_target(&$host) {
    $fwd = $_SERVER['HTTP_X_FORWARD_TARGET'] ?? '';
    if ($fwd !== '')               $raw = $fwd;
    elseif (preg_match('#^https?://#i', $_SERVER['REQUEST_URI'] ?? '')) $raw = $_SERVER['REQUEST_URI'];
    else                           $raw = 'http://' . ($_SERVER['HTTP_HOST'] ?? 'localhost') . ($_SERVER['REQUEST_URI'] ?? '/');
    $target = @parse_url($raw);
    if (!$target || !isset($target['scheme'], $target['host'])) return null;
    $scheme = strtolower($target['scheme']);
    if ($scheme !== 'http' && $scheme !== 'https') return null;
    $port = isset($target['port']) ? ':' . $target['port'] : '';
    $path = (isset($target['path']) && $target['path'] !== '') ? $target['path'] : '/';
    $q    = (isset($target['query']) && $target['query'] !== '') ? '?' . $target['query'] : '';
    $host = $target['host'] . $port;
    return [
        'scheme' => $scheme,
        'host'   => $target['host'],
        'port'   => $port,
        'target' => $scheme . '://' . $target['host'] . $port . $path . $q,
    ];
}

// ============================================================================
//  主流程
// ============================================================================

header('Access-Control-Allow-Origin: *');

if (($_SERVER['REQUEST_METHOD'] ?? 'GET') === 'OPTIONS') { http_response_code(204); exit; }
if (!proxy_auth_ok()) proxy_bad(401, 'invalid or missing proxy token');

$host = '';
$t = proxy_target($host);
if ($t === null) proxy_bad(400, 'invalid target url');

$method  = $_SERVER['REQUEST_METHOD'] ?? 'GET';
$body    = null;
if ($method === 'POST' || $method === 'PUT' || $method === 'PATCH') {
    $body = file_get_contents('php://input');
    if ($body === false) $body = '';
}

$fwdHeaders = proxy_forward_headers();
$targetURL  = $t['target'];

// --- cURL 流式转发：header 回调先于 body 回调，故先收集响应头，首次写 body 前落发 ---
$respStatus = 502;
$respHeaders = [];
$headersDone = false;

$onHeader = function ($curl, $hdr) use (&$respStatus, &$respHeaders, &$headersDone) {
    $len = strlen($hdr);
    if (preg_match('#^HTTP/\S+\s+(\d+)#i', $hdr, $m)) { $respStatus = (int)$m[1]; return $len; }
    $trim = trim($hdr);
    if ($trim === '') { $headersDone = true; return $len; }
    if (strpos($hdr, ':') !== false) {
        $name = trim(substr($hdr, 0, strpos($hdr, ':')));
        $val  = trim(substr($hdr, strpos($hdr, ':') + 1));
        $lk   = strtolower($name);
        if ($lk === 'transfer-encoding' || $lk === 'connection') return $len;
        // 多个同名头合并
        $respHeaders[] = [$name, $val];
    }
    return $len;
};

$flushSent = false;
$onBody = function ($curl, $chunk) use (&$flushSent, &$respStatus, &$respHeaders) {
    if (!$flushSent) {
        $flushSent = true;
        if (!headers_sent()) {
            http_response_code($respStatus);
            $skip = ['content-encoding'];
            foreach ($respHeaders as [$name, $val]) {
                if (in_array(strtolower($name), $skip, true)) continue;
                header($name . ': ' . $val, false);
            }
        }
    }
    echo $chunk;
    if (function_exists('ob_flush')) ob_flush();
    flush();
    return strlen($chunk);
};

$ch = curl_init($targetURL);
curl_setopt_array($ch, [
    CURLOPT_CUSTOMREQUEST  => $method,
    CURLOPT_RETURNTRANSFER => false,
    CURLOPT_HEADER         => false,
    CURLOPT_HEADERFUNCTION => $onHeader,
    CURLOPT_WRITEFUNCTION  => $onBody,
    CURLOPT_FOLLOWLOCATION => false,
    CURLOPT_HTTPHEADER     => proxy_header_list($fwdHeaders),
    CURLOPT_TIMEOUT        => 0,
    CURLOPT_CONNECTTIMEOUT => 15,
    CURLOPT_SSL_VERIFYPEER => true,
    CURLOPT_SSL_VERIFYHOST => 2,
    CURLOPT_ENCODING       => '',
    CURLOPT_NOSIGNAL       => true,
    CURLOPT_USERAGENT      => '', // 已剥透传，置空避免 cURL 默认的 "curl/x.x" 泄漏
]);
if ($body !== null) {
    curl_setopt($ch, CURLOPT_POSTFIELDS, $body);
    if ($method !== 'POST') { curl_setopt($ch, CURLOPT_CUSTOMREQUEST, $method); curl_setopt($ch, CURLOPT_POST, true); }
}

if (curl_exec($ch) === false) {
    $err = curl_error($ch);
    $code = curl_getinfo($ch, CURLINFO_HTTP_CODE);
    curl_close($ch);
    // 若仍未发头，返回 502；否则错误已在流中体现
    if (!$flushSent) proxy_bad(502, 'upstream failed: ' . $err);
    exit;
}
curl_close($ch);

// 空响应体（如流在 body 回调前结束）时补发头
if (!$flushSent) {
    if (!headers_sent()) {
        http_response_code($respStatus);
        foreach ($respHeaders as [$name, $val]) header($name . ': ' . $val, false);
    }
}
exit;