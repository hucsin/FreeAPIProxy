/**
 * ============================================================================
 *  Claudeflare 风格 Cloudflare Worker 反代 —— cline2api egress proxy
 * ============================================================================
 * 混合代理模式 + 流式传输 + 隐私头处理 + 鉴权 token + fetch 自动降级。
 *
 * 设计要点：
 *  1. 混合代理模式
 *     根据「目标主机 + 模式」在 connect() TCP Socket 与 fetch() 之间切换。
 *     核心函数 shouldForceFetch() 确保对 OpenAI / Anthropic(Claude) 这类同样
 *     部署在 Cloudflare 网络上的目标自动降级为 fetch()——Socket 直连它们会被
 *     Cloudflare 边缘网络限制，同网络 fetch() 反而更稳。
 *
 *  2. 流式传输
 *     - Socket 模式：把 TCP socket.readable 泵给响应体（new Response(socket.readable)）。
 *     - Fetch  模式：请求体 duplex:"half"，响应体把 upstream.body 直接透传。
 *     对 AI 对话（SSE / 流式分块）至关重要。
 *
 *  3. 隐私头处理
 *     转发时过滤 CF-* / X-Forwarded-For 等暴露来源的特征头，并伪装常见浏览器
 *     User-Agent，实现「规避访问者 IP」与降低反代特征。
 *
 *  4. 健壮性 + 鉴权
 *     Socket 失败立即降级 fetch()。X-Proxy-Token 校验，防滥用。
 *
 *  5. egress 中继（cline2api 绑定代理）
 *     当请求携带 X-Forward-Target（完整上游 URL）时本 Worker 作为中继转发。
 *     账号/apikey「走代理」依赖此契约。X-Proxy-Token 为代理自身鉴权，
 *     透传时会被剥掉；真正的上游凭据（Authorization 等）原样转发。
 *
 * 鉴权：Worker 环境用 wrangler secret（PROXY_TOKEN）。本地 mock 用环境变量。
 * ============================================================================
 */

// 该默认 token 会被 env.PROXY_TOKEN 覆盖；仅用于本地直连调试。
let PROXY_TOKEN = globalThis.PROXY_TOKEN || self.PROXY_TOKEN || '';

// 伪装的常见浏览器 UA（降低反代特征）。
const FAKE_UA =
  'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 ' +
  '(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36';

// 受 Cloudflare 网络保护 / 同网，Socket 直连基本必挂，必须走 fetch() 的主机（小写）。
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

/** 复制并过滤隐私/控制头；必要时补齐 UA 与上游鉴权。 */
function buildForwardHeaders(headers, ctx) {
  const out = new Headers();
  const blockedExact = new Set([
    'x-forwarded-for',
    'x-forwarded-proto',
    'x-forwarded-host',
    'x-real-ip',
    'true-client-ip',
    'forwarded',
    'via',
    'x-proxy-token', // 代理自身鉴权，透传剥掉
    'x-forward-target', // 中继控制头，透传剥掉
    'x-upstream-auth', // 内部承载，用完剥掉
    'x-upstream-user-agent', // 内部承载，用完剥掉
  ]);
  headers.forEach((value, key) => {
    const k = key.toLowerCase();
    if (blockedExact.has(k)) return;
    if (k.startsWith('cf-') || k.startsWith('cf_')) return;
    out.set(key, value);
  });
  // 若上游鉴权由 x-upstream-auth 提供而原请求没有 Authorization，则落位
  if (ctx.upstreamAuth && !out.has('Authorization')) {
    out.set('Authorization', ctx.upstreamAuth);
  }
  if (ctx.upstreamUserAgent && !out.has('User-Agent')) {
    out.set('User-Agent', ctx.upstreamUserAgent);
  }
  //if (!out.has('User-Agent')) out.set('User-Agent', FAKE_UA);
  return out;
}

// 下游响应需要剥掉的 hop-by-hop / 危险头（避免分帧冲突与头注入）。
const RESP_HOP = new Set([
  'connection',
  'keep-alive',
  'transfer-encoding',
  'upgrade',
  'trailer',
  'trailers',
  'te',
  'expect',
]);

// 过滤上游响应头：剥 hop-by-hop、Connection 声明的头、proxy-*、via。
function filterResponseHeaders(headers) {
  const out = new Headers();
  const conn = new Set();
  const connVal = headers.get('connection');
  if (connVal) {
    connVal.split(',').forEach((c) => {
      const t = c.trim().toLowerCase();
      if (t) conn.add(t);
    });
  }
  headers.forEach((value, key) => {
    const k = key.toLowerCase();
    if (RESP_HOP.has(k) || conn.has(k) || k.startsWith('proxy-') || k === 'via') return;
    out.set(key, value);
  });
  // Headers.forEach 只给每个 key 一个值；set-cookie 多值时用 getSetCookie 补齐
  if (typeof headers.getSetCookie === 'function') {
    const sc = headers.getSetCookie();
    if (sc && sc.length) {
      out.delete('set-cookie');
      sc.forEach((v) => out.append('set-cookie', v));
    }
  }
  return out;
}

function jsonError(status, message) {
  return new Response(JSON.stringify({ error: { message, type: 'proxy_error' } }), {
    status,
    headers: { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*' },
  });
}

// 浏览器跨域（CORS）放行：仅当请求带 Origin 才回，回声该 Origin 并允许凭据；
// 服务端到服务端（无 Origin）返回 null，保持透传干净。
function corsForRequest(request) {
  const origin = request.headers.get('Origin');
  if (!origin) return null;
  const acr = request.headers.get('Access-Control-Request-Headers');
  const h = new Headers();
  h.set('Access-Control-Allow-Origin', origin);
  h.set('Vary', 'Origin');
  h.set('Access-Control-Allow-Credentials', 'true');
  h.set('Access-Control-Allow-Methods', 'GET,HEAD,POST,PUT,PATCH,DELETE,OPTIONS');
  h.set('Access-Control-Allow-Headers', acr || 'Content-Type,Authorization,X-Proxy-Token,X-Forward-Target,X-Proxy-Mode,Range');
  h.set('Access-Control-Max-Age', '86400');
  return h;
}
function mergeCors(base, cors) {
  if (!cors) return base;
  const out = new Headers(base);
  cors.forEach((v, k) => out.set(k, v));
  return out;
}

/** 校验 token：恒定时间比较。 */
function tokenOk(provided) {
  const t = (PROXY_TOKEN || '').trim();
  if (!t) {
    // 未配置 token：默认拒绝，避免裸奔（需显式设 ALLOW_OPEN=1 才开放）
    return globalThis.PROXY_ALLOW_OPEN === true;
  }
  if (!provided) return false;
  const p = provided.trim();
  if (p.length !== t.length) return false;
  let d = 0;
  for (let i = 0; i < p.length; i++) d |= p.charCodeAt(i) ^ t.charCodeAt(i);
  return d === 0;
}

export default {
  async fetch(request, env) {
    if (env && env.PROXY_TOKEN) PROXY_TOKEN = env.PROXY_TOKEN;
    if (request.method === 'OPTIONS') {
      return new Response(null, {
        status: 204,
        headers: mergeCors(new Headers(), corsForRequest(request)),
      });
    }

    // —— 鉴权 ——
    const headerToken =
      request.headers.get('x-proxy-token') ||
      (request.headers.get('Authorization') || '').replace(/^Bearer\s+/i, '');
    if (!tokenOk(headerToken)) {
      return jsonError(401, 'invalid or missing proxy token');
    }

    // —— 目标 URL ——
    const forwardTarget = request.headers.get('x-forward-target');
    let targetURL;
    try {
      targetURL = forwardTarget
        ? new URL(forwardTarget)
        : new URL(request.url);
    } catch {
      return jsonError(400, 'invalid target url');
    }
    if (targetURL.protocol !== 'http:' && targetURL.protocol !== 'https:') {
      return jsonError(400, 'unsupported target protocol');
    }

    const host = targetURL.host;
    const mode =
      (env && env.PROXY_MODE) || new URL(request.url).searchParams.get('_mode') || 'auto';

    // —— 读取 body（Socket 与 fetch 复用）——
    let body = null;
    if (request.method === 'POST' || request.method === 'PUT' || request.method === 'PATCH') {
      body = await request.arrayBuffer();
    }

    // Socket 直连分支（可自动降级 fetch）
    if (!shouldForceFetch(host, mode)) {
      try {
        const isHttps = targetURL.protocol === 'https:';
        const port = targetURL.port || (isHttps ? 443 : 80);
        const socket = connect({ hostname: targetURL.hostname, port });
        const writer = socket.writable.getWriter();
        const enc = new TextEncoder();
        const path = targetURL.pathname + targetURL.search;
        const method = body !== null ? request.method : 'GET';
        let head = `${method} ${path} HTTP/1.1\r\nHost: ${targetURL.host}\r\nConnection: close\r\n`;
        if (body !== null) head += `Content-Length: ${body.byteLength}\r\n`;
        head += '\r\n';
        await writer.write(enc.encode(head));
        if (body !== null) await writer.write(new Uint8Array(body));
        writer.releaseLock();
        return new Response(socket.readable, {
          status: 200,
          headers: mergeCors({'Content-Type': 'application/octet-stream'}, corsForRequest(request)),
        });
      } catch (socketErr) {
        // —— 自动回退 fetch，保证可用 ——
        try {
          return await doFetch(targetURL, request, body);
        } catch (e2) {
          return jsonError(502, 'upstream unreachable (socket+fetch): ' + String(e2));
        }
      }
    }

    // Fetch 分支
    try {
      return await doFetch(targetURL, request, body);
    } catch (e) {
      return jsonError(502, 'upstream fetch failed: ' + String(e));
    }
  },
};

async function doFetch(targetURL, request, body) {
  const ctx = {
    upstreamAuth: request.headers.get('x-upstream-auth') || '',
    upstreamUserAgent: request.headers.get('x-upstream-user-agent') || '',
  };
  const fwdHeaders = buildForwardHeaders(request.headers, ctx);
  const init = {
    method: request.method,
    headers: fwdHeaders,
    redirect: 'manual',
  };
  if (body !== null && body !== undefined) {
    init.body = body;
    // 若 body 是流则需要 duplex；我们已统一读成 ArrayBuffer，非流。保留兼容判断。
    if (typeof body === 'object' && typeof body.getReader === 'function') init.duplex = 'half';
  }
  const upstream = await fetch(targetURL.toString(), init);
  return new Response(upstream.body, {
    status: upstream.status,
    statusText: upstream.statusText,
    headers: mergeCors(filterResponseHeaders(upstream.headers), corsForRequest(request)),
  });
}
