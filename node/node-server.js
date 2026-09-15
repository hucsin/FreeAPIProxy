/**
 * ============================================================================
 *  Claudeflare 风格反代 —— Node.js 独立版 (FreeAPI egress proxy)
 * ============================================================================
 * 与 Cloudflare Worker 版 worker.js 同构：混合代理 + 流式 + 隐私头 + token。
 *
 * 差异（运行环境所致）：
 *  - Cloudflare 无真正 TCP Socket 直连任意 TLS 目标的稳定通道 → 自动 fetch 降级；
 *  - Node 拥有完整 TLS 能力 → 「Socket 模式」是真实、可用的（默认 socket 直连，
 *    仅对 OpenAI/Claude 等被 CF 保护的 host 强制 fetch）。两者行为一致地可降级。
 *
 * 支持三种入站形态：
 *   1) HTTP 绝对 URI（RFC7230 老代理）：curl -x http://127.0.0.1:8788 https://api...
 *   2) CONNECT 隧道：用于浏览器/system proxy 的 https。
 *   3) egress 中继（FreeAPI 绑定代理契约）：请求带 X-Forward-Target 完整上游 URL
 *      + X-Proxy-Token，服务转发到目标并在透传时剥掉 X-Proxy-Token/X-Forward-Target
 *      等内部头，保留真实上游凭据。
 *
 * 鉴权：X-Proxy-Token 头 或 Authorization: Bearer <token> 或 ?token=。
 * 启动：
 *   PROXY_TOKEN=xxx node node-server.js [--port 8788] [--mode auto|socket|fetch]
 * ============================================================================
 */
import http from 'node:http';
import https from 'node:https';
import net from 'node:net';
import { URL } from 'node:url';
const argv = process.argv.slice(2);
const argOf = (name, def) => {
  const i = argv.indexOf(name);
  return i >= 0 && argv[i + 1] ? argv[i + 1] : def;
};
const PORT = Number(process.env.PORT || argOf('--port', 8788));
const PROXY_TOKEN = process.env.PROXY_TOKEN || '';
const MODE = process.env.PROXY_MODE || argOf('--mode', 'auto');

const FAKE_UA =
  'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36';

const FORCE_FETCH_HOSTS = [
  'api.openai.com',
  'api.anthropic.com',
  '.anthropic.com',
  '.claude.ai',
  '.openai.com',
  'api.cline.bot',
  '.cline.bot',
  // opencode/zen 上游同样受 Cloudflare 保护：让它和 OpenAI/Claude 一致，走 fetch()
  // 透传，避免裸 TLS Socket 指纹被 CF 风控、保证 SSE 逐块稳定到达。
  'opencode.ai',
  '.opencode.ai',
];

function shouldForceFetch(host, mode) {
  if (mode === 'fetch') return true;
  if (!host) return false;
  const h = host.split(':')[0].toLowerCase();
  for (const d of FORCE_FETCH_HOSTS) {
    const dd = d.toLowerCase();
    if (dd.startsWith('.') ? h.endsWith(dd) : h === dd) return true;
  }
  return false;
}

const BLOCKED = new Set([
  'x-forwarded-for', 'x-forwarded-proto', 'x-forwarded-host', 'x-real-ip',
  'true-client-ip', 'forwarded', 'via', 'proxy-authorization',
  'x-proxy-token', 'x-forward-target', 'x-upstream-auth',
]);

function buildForwardHeaders(headers, ctx) {
  const out = {};
  for (const [k, v] of Object.entries(headers || {})) {
    const lk = k.toLowerCase();
    if (BLOCKED.has(lk)) continue;
    if (lk.startsWith('cf-') || lk.startsWith('cf_')) continue;
    out[k] = v;
  }
  if (ctx && ctx.upstreamAuth && !Object.keys(out).some((k) => k.toLowerCase() === 'authorization')) {
    out.Authorization = ctx.upstreamAuth;
  }
  //if (!Object.keys(out).some((k) => k.toLowerCase() === 'user-agent')) {
    //out['User-Agent'] = FAKE_UA;
  //}
  return out;
}

function timedEq(a, b) {
  if (a.length !== b.length) return false;
  let d = 0;
  for (let i = 0; i < a.length; i++) d |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return d === 0;
}

function requestToken(headers, url) {
  if (headers['x-proxy-token']) return headers['x-proxy-token'];
  const a = headers['authorization'] || '';
  if (a.startsWith('Bearer ')) return a.slice(7);
  if (url) return new URL(url, 'http://x').searchParams.get('token') || '';
  return '';
}

function authOk(headers, url) {
  if (!PROXY_TOKEN) return process.env.PROXY_ALLOW_OPEN === '1'; // 默认禁裸奔
  return timedEq(requestToken(headers, url) || '', PROXY_TOKEN);
}

function bad(res, status, msg) {
  if (res.headersSent) { res.end(); return; }
  res.writeHead(status, { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*' });
  res.end(JSON.stringify({ error: { message: msg, type: 'proxy_error' } }));
}

/* ---------------- fetch 模式（原生流式透传） ---------------- */
async function forwardViaFetch(target, method, fwdHeaders, bodyBuffer, res) {
  const init = { method, headers: fwdHeaders, redirect: 'manual' };
  if (bodyBuffer !== null && bodyBuffer !== undefined) init.body = bodyBuffer;
  const upstream = await fetch(target.toString(), init);
  const outHeaders = {};
  upstream.headers.forEach((v, k) => { outHeaders[k] = v; });
  res.writeHead(upstream.status || 502, outHeaders);
  if (upstream.body) {
    const reader = upstream.body.getReader();
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      res.write(Buffer.from(value));
    }
  }
  res.end();
}

/* ---------------- Socket 模式：真实 TCP/TLS 直连 + 双工泵送 ----------------
 * 用上游 http.IncomingMessage 语义需要手写解析头，这里用 node 的 http 客户端能力
 * 简化：不手写，直接复用 node:http.request/https.request 建立「原始」连接流式回传。
 */
function forwardViaSocket(target, method, fwdHeaders, bodyBuffer, res) {
  return new Promise((resolve, reject) => {
    const isHttps = target.protocol === 'https:';
    const port = target.port || (isHttps ? 443 : 80);
    const mod = isHttps ? https : http;
    const req = mod.request(
      {
        host: target.hostname,
        port,
        method,
        path: target.pathname + target.search,
        headers: { ...fwdHeaders, Host: target.host },
        servername: isHttps ? target.hostname : undefined,
      },
      (upstream) => {
        res.writeHead(upstream.statusCode || 502, upstream.headers);
        upstream.pipe(res);
        upstream.on('end', () => { res.end(); resolve(); });
      }
    );
    req.on('error', (e) => reject(e));
    if (bodyBuffer !== null && bodyBuffer !== undefined) req.write(bodyBuffer);
    req.end();
  });
}
// 顶层 import 已在文件头部统一声明。

/* ---------------- CONNECT 隧道 ---------------- */
function handleConnect(req, clientSocket) {
  if (!authOk(req.headers)) {
    clientSocket.write('HTTP/1.1 407 Proxy Authentication Required\r\n\r\n');
    clientSocket.end();
    return;
  }
  const hp = req.url.split(':');
  const host = hp[0];
  const port = Number(hp[1]) || 443;
  const upstream = net.connect(port, host, () => {
    clientSocket.write('HTTP/1.1 200 Connection Established\r\n\r\n');
    upstream.pipe(clientSocket);
    clientSocket.pipe(upstream);
  });
  upstream.on('error', () => { try { clientSocket.write('HTTP/1.1 502 Bad Gateway\r\n\r\n'); } catch {} clientSocket.end(); });
  clientSocket.on('error', () => upstream.destroy());
}

/* ---------------- 主服务 ---------------- */
const server = http.createServer((req, res) => {
  res.setHeader('Access-Control-Allow-Origin', '*');
  if (req.method === 'OPTIONS') { res.writeHead(204); res.end(); return; }
  if (req.method === 'CONNECT') { handleConnect(req, req.socket); return; }
  if (!authOk(req.headers, req.url)) { bad(res, 401, 'invalid or missing proxy token'); return; }

  // 目标
  const fwd = req.headers['x-forward-target'];
  let target;
  try {
    if (fwd) target = new URL(fwd);
    else if (/^https?:\/\//.test(req.url)) target = new URL(req.url);
    else target = new URL(req.url, 'http://' + (req.headers.host || 'localhost'));
  } catch { bad(res, 400, 'invalid target url'); return; }
  if (target.protocol !== 'http:' && target.protocol !== 'https:') { bad(res, 400, 'unsupported target protocol'); return; }

  const chunks = [];
  req.on('data', (c) => chunks.push(c));
  req.on('error', () => {});
  req.on('end', async () => {
    const bodyBuffer = ['POST', 'PUT', 'PATCH'].includes(req.method) ? Buffer.concat(chunks) : null;
    const fwdHeaders = buildForwardHeaders(req.headers, { upstreamAuth: req.headers['x-upstream-auth'] || '' });
    const mode = req.headers['x-proxy-mode'] || MODE;
    try {
      if (!shouldForceFetch(target.host, mode)) {
        try { await forwardViaSocket(target, req.method, fwdHeaders, bodyBuffer, res); return; }
        catch (socketErr) { await forwardViaFetch(target, req.method, fwdHeaders, bodyBuffer, res); return; }
      }
      await forwardViaFetch(target, req.method, fwdHeaders, bodyBuffer, res);
    } catch (e) {
      bad(res, 502, 'upstream failed: ' + String(e && e.message || e));
    }
  });
});

server.listen(PORT, () => {
  console.log(`[cline2api egress] Node proxy listening :${PORT}  mode=${MODE}`);
  console.log(`[cline2api egress] token: ${PROXY_TOKEN ? 'configured' : 'EMPTY (open mode disabled by default)'}`);
});
