<?php
/**
 * ============================================================================
 *  Claudeflare 风格反代 —— PHP 独立版 (cline2api egress proxy)
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
 *   2) egress 中继（cline2api 绑定代理契约）：请求带 X-Forward-Target 完整上游 URL
 *      + X-Proxy-Token，转发到目标并剥掉 X-Proxy-Token/X-Forward-Target 等内部头，
 *      保留真实上游凭据（Authorization）。
 *      另有 X-Upstream-Auth / X-Upstream-User-Agent 两个内部承载头：宿主把上游真正的
 *      Authorization / User-Agent 放进它们；本代理剥掉后，在转发时还原为对应原名再发给上游。
 *
 * 鉴权：X-Proxy-Token 头 或 Authorization: Bearer <token> 或 ?token=。
 * 说明：PHP 无真正的任意 TLS Socket 半双工隧道，因此用 cURL 实现流式转发，
 *      行为对齐 node/worker 的 fetch 分支（SSE 逐块透传），对 AI 上游足够。
 * ============================================================================
 */

// ---------- 配置（优先环境变量） ----------
$PROXY_TOKEN = getenv('PROXY_TOKEN') ?: 'EhgGBRMKEhgGBRMKA2s8JzQeDEZQA2s8JzQeDEZQ';         // 代理自身鉴权 token
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
    'x-proxy-token', 'x-forward-target', 'x-upstream-auth', 'x-upstream-user-agent',
    'user-agent', // 不透传客户端 UA（避免泄漏真实浏览器标识给上游）；
                  // 需要 UA 到上游的场景（如 opencode 免费层门禁要求 UA 含 opencode/<ver>）
                  // 由 X-Upstream-User-Agent 显式声明，proxy_forward_headers() 会还原为真正的 User-Agent
    'host', 'connection', 'proxy-connection', 'keep-alive',
    'content-length', // cURL 设置 POSTFIELDS 时自行计算 Content-Length，避免与透传值冲突
];

// 下游响应需要剥掉的 hop-by-hop / 危险头（避免分帧冲突与头注入）。
// 注意：content-length / content-encoding 也在此处理，因为 cURL 会自动解压，
// 透传原 content-length 会导致客户端按旧长度截断（典型的“响应被截断”根因）。
$RESP_HOP = [
    'connection', 'keep-alive', 'transfer-encoding', 'upgrade',
    'trailer', 'trailers', 'te', 'expect',
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
        header('X-Accel-Buffering: no');
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

/** 过滤后的转发头；宿主（byetPrepareReq）把上游 Authorization / User-Agent 分别改写为
 *  X-Upstream-Auth / X-Upstream-User-Agent 透传，这里还原为真正的 Authorization / User-Agent 再发给上游。 */
function proxy_forward_headers() {
    global $BLOCKED_HEADERS;
    $h = proxy_request_headers();
    // x-upstream-auth / x-upstream-user-agent 会被 $BLOCKED_HEADERS 过滤掉，需先从原始请求头里取出。
    $upAuth = $h['x-upstream-auth'] ?? '';
    $upUA   = $h['x-upstream-user-agent'] ?? '';
    $out = [];
    foreach ($h as $k => $v) {
        if ($v === '' || $v === null) continue;
        if ($k === 'x-upstream-auth' || $k === 'x-upstream-user-agent') continue; // 已单独提取，避免又被透传
        if (in_array($k, $BLOCKED_HEADERS, true)) continue;
        if (strpos($k, 'cf-') === 0 || strpos($k, 'cf_') === 0) continue;
        $out[$k] = $v;
    }
    if ($upAuth !== '') {
        $has = false;
        foreach (array_keys($out) as $k) if (strcasecmp($k, 'Authorization') === 0) { $has = true; break; }
        if (!$has) $out['Authorization'] = $upAuth;
    }
    if ($upUA !== '') $out['User-Agent'] = $upUA;   // 还原上游真正的 UA（opencode 门禁依赖）
    return $out;
}

/** 浏览器跨域（CORS）放行：仅当请求带 Origin 头才回（回声该 Origin 并允许凭据）；
 * 服务端到服务端（无 Origin）不做任何事，保持透传干净。 */
function proxy_emit_cors() {
    $origin = $_SERVER['HTTP_ORIGIN'] ?? '';
    if ($origin === '') return;
    $acr = $_SERVER['HTTP_ACCESS_CONTROL_REQUEST_HEADERS'] ?? '';
    if ($acr === '') $acr = 'Content-Type,Authorization,X-Proxy-Token,X-Forward-Target,X-Proxy-Mode,Range';
    header('Access-Control-Allow-Origin: ' . $origin);
    header('Vary: Origin');
    header('Access-Control-Allow-Credentials: true');
    header('Access-Control-Allow-Methods: GET,HEAD,POST,PUT,PATCH,DELETE,OPTIONS');
    header('Access-Control-Allow-Headers: ' . $acr);
    header('Access-Control-Max-Age: 86400');
}

/** 过滤并落地上游响应头：剥 hop-by-hop、Connection 声明的头、proxy-*、via，
 *  以及 content-encoding / content-length（cURL 已自动解压，透传会与实际 body
 *  长度不符，导致客户端按旧长度截断或 ERR_CONTENT_LENGTH_MISMATCH）。 */
function proxy_emit_response_headers($respHeaders) {
    global $RESP_HOP;
    // 浏览器跨域放行（带 Origin 才有效）
    proxy_emit_cors();
    // 告诉 Nginx 等反代不要缓冲，保证 SSE 流式逐块下发。
    header('X-Accel-Buffering: no');
    // Connection 声明点名的头也要剥（case-insensitive）。
    $connSet = [];
    foreach ($respHeaders as [$name, $val]) {
        if (strcasecmp(trim($name), 'connection') === 0) {
            foreach (explode(',', $val) as $c) {
                $t = strtolower(trim($c));
                if ($t !== '') $connSet[$t] = true;
            }
        }
    }
    if (!headers_sent()) {
        foreach ($respHeaders as [$name, $val]) {
            $lk = strtolower($name);
            if (in_array($lk, $RESP_HOP, true)) continue;
            if (isset($connSet[$lk])) continue;
            if (strpos($lk, 'proxy-') === 0) continue;
            if ($lk === 'via') continue;
            // cURL 自动解压后，content-encoding / content-length 已与 body 不符，必须剥。
            if ($lk === 'content-encoding' || $lk === 'content-length') continue;
            header($name . ': ' . $val, false);
        }
    }
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

/** 关闭 PHP / Web 服务器侧的各类输出缓冲，保证流式逐块下发。 */
function proxy_disable_buffering() {
    @ini_set('output_buffering', 'off');
    @ini_set('zlib.output_compression', 'off');
    @ini_set('implicit_flush', '1');
    @ini_set('display_errors', '0'); // 避免 PHP 警告污染流式 body
    while (ob_get_level() > 0) { @ob_end_flush(); }
    @ob_implicit_flush(true);
}

// ============================================================================
//  主流程
// ============================================================================

// 长流式转发不受 PHP 默认 max_execution_time 限制。
@set_time_limit(0);
proxy_disable_buffering();

if (($_SERVER['REQUEST_METHOD'] ?? 'GET') === 'OPTIONS') { proxy_emit_cors(); http_response_code(204); exit; }
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
// 客户端 UA 由 X-Upstream-User-Agent 还原而来；未提供时为空串，维持既有「不泄漏浏览器标识」行为。
$fwdUA = $fwdHeaders['User-Agent'] ?? '';

// --- cURL 流式转发：header 回调先于 body 回调，故先收集响应头，首次写 body 前落发 ---
$respStatus = 502;
$respHeaders = [];
$flushSent = false;

$onHeader = function ($curl, $hdr) use (&$respStatus, &$respHeaders) {
    $len = strlen($hdr);
    // 状态行（含 1xx 中间响应）。仅记录最新状态码，头集合在最终响应段重置。
    if (preg_match('#^HTTP/\S+\s+(\d+)#i', $hdr, $m)) {
        $respStatus = (int)$m[1];
        // 1xx 是中间响应，其头不应与最终响应混在一起。
        if ($respStatus >= 100 && $respStatus < 200) {
            $respHeaders = [];
        } else {
            // 最终响应开始，清掉可能残留的 1xx 头。
            $respHeaders = [];
        }
        return $len;
    }
    $trim = trim($hdr);
    if ($trim === '') return $len;
    if (strpos($hdr, ':') !== false) {
        $name = trim(substr($hdr, 0, strpos($hdr, ':')));
        $val  = trim(substr($hdr, strpos($hdr, ':') + 1));
        $respHeaders[] = [$name, $val];
    }
    return $len;
};

$onBody = function ($curl, $chunk) use (&$flushSent, &$respStatus, &$respHeaders) {
    if (!$flushSent) {
        $flushSent = true;
        if (!headers_sent()) http_response_code($respStatus);
        proxy_emit_response_headers($respHeaders);
    }
    echo $chunk;
    if (function_exists('ob_flush')) @ob_flush();
    flush();
    return strlen($chunk);
};

$ch = curl_init($targetURL);
if ($ch === false) proxy_bad(502, 'curl_init failed');

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
    CURLOPT_ENCODING       => '',   // 自动协商并解压；响应侧已剥 content-encoding/length
    CURLOPT_NOSIGNAL       => true,
    CURLOPT_USERAGENT      => $fwdUA, // 由 X-Upstream-User-Agent 还原；为空则不带 UA（不再有 cURL 默认值）
]);
if ($body !== null) {
    curl_setopt($ch, CURLOPT_POSTFIELDS, $body);
    if ($method !== 'POST') {
        curl_setopt($ch, CURLOPT_CUSTOMREQUEST, $method);
        curl_setopt($ch, CURLOPT_POST, true);
    }
}

if (curl_exec($ch) === false) {
    $err  = curl_error($ch);
    $code = curl_getinfo($ch, CURLINFO_HTTP_CODE);
    curl_close($ch);
    // 若仍未发头，返回 502；否则错误已在流中体现
    if (!$flushSent) proxy_bad(502, 'upstream failed: ' . $err . ' (http ' . $code . ')');
    exit;
}
curl_close($ch);

// 空响应体（如流在 body 回调前结束）时补发头
if (!$flushSent) {
    if (!headers_sent()) {
        http_response_code($respStatus);
        proxy_emit_response_headers($respHeaders);
    }
}
exit;