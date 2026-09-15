# cline2api 出口代理（egress proxy）

本项目为 Cline2API 提供「账号 / API Key 走代理」所需的两个同构反代程序，
都可作为管理后台「代理设置」里配置的出口代理被绑定到具体账号/API Key。

两种版本逻辑完全一致：**混合代理 + 流式传输 + 隐私头 + token 鉴权 + fetch 自动降级**。

## 目录

- `claudeflare-worker/worker.js` — Cloudflare Worker 版（claudeflare 风格）
- `node/node-server.js` — 独立 Node.js 版（无需 Cloudflare 环境）

---

## 统一契约（三端一致）

当 Cline2API 命中某条代理时，会把「本该发给上游」的请求转发到该代理：

| Header | 含义 | 透传时 |
|---|---|---|
| `X-Forward-Target` | 真实上游完整 URL（中继目标） | 剥掉 |
| `X-Proxy-Token` | 代理服务自身鉴权 | 剥掉 |
| `Authorization` 及其它头 | 真实上游凭据 / 业务头 | **原样保留转发** |

代理在真正访问目标前还会过滤 `CF-*`、`X-Forwarded-For`、`X-Real-IP` 等特征头，
并伪装成常见浏览器 `User-Agent`，实现「规避访问者 IP」与降低反代特征。

---

## Cloudflare Worker 版

```bash
cd claudeflare-worker
npx wrangler deploy
# 设置鉴权 token（务必）
npx wrangler secret put PROXY_TOKEN
```

部署后得到形如 `https://你的工程.workers.dev` 的地址，即为「代理设置」里的 Base URL。

本地单测可不依赖 wrangler secret，用环境变量：

```bash
PROXY_TOKEN=xxx npx wrangler dev
```

### 设计要点
- **`shouldForceFetch()`**：对 `*.openai.com` / `*.anthropic.com` / `api.cline.bot` /
  `opencode.ai` 等同样受 Cloudflare 网络保护的目标，Socket 直连基本必挂，自动改走
  同网 `fetch()`。
- **Socket 分支**：`connect({ hostname, port })` 建 TCP，`new Response(socket.readable)`
  直接泵给响应体；任一步失败立即回退 `fetch()`。
- **Fetch 分支**：`duplex:"half"` + 直接返回 `upstream.body`，SSE 逐块透传。

---

## Node.js 独立版

```bash
cd node
npm install          # 无第三方依赖，仅声明；可跳过
PROXY_TOKEN=xxx node node-server.js --port 8788 --mode auto
```

参数：`--port`（默认 8788）、`--mode auto|socket|fetch`。也可用环境变量
`PORT` / `PROXY_TOKEN` / `PROXY_MODE`。

Node 拥有完整 TLS 能力，因此 **Socket 模式是真实可用**的：默认 Socket 直连，
仅对 OpenAI/Claude/opencode 等被 CF 保护 host 走 fetch；Socket 失败同样自动降级 fetch。

### 额外能力
- 支持老式代理形态：绝对 URI（`curl -x http://ip:8788 https://...`）与 `CONNECT` 隧道。
- 也可直接作为普通反向代理直连某目标（不带 `X-Forward-Target` 时按请求 Host 处理）。

---

## 测试

两个服务都能被 Cline2API 管理后台当作代理使用：在「代理设置」新建一条
（Worker 选 CF Worker / Node 选 Node 类型，Base URL + Token），到「连接通道」为
对应账号/API Key 选择该代理即可。
