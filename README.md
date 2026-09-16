# FreeAPI 出口代理（egress proxy）

本项目为 FreeAPI 提供「账号 / API Key 走代理」所需的多个同构反代程序，
都可作为管理后台「代理设置」里配置的出口代理被绑定到具体账号/API Key。

四端逻辑完全一致：**混合代理 + 流式传输 + 隐私头 + token 鉴权 + fetch 自动降级**。

## 目录

- `claudeflare-worker/worker.js` — Cloudflare Worker 版（claudeflare 风格）
- `node/node-server.js` — 独立 Node.js 版（无需 Cloudflare 环境）
- `php/proxy.php` — 独立 PHP 版（可跑内置服务器或 Apache/Nginx+PHP-FPM）
- `go/go-proxy.go` — 独立 Go 版（编译为单文件二进制，性能最好）

---

## 统一契约（四端一致）

当 FreeAPI 命中某条代理时，会把「本该发给上游」的请求转发到该代理：

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

## PHP 独立版

依赖极少（PHP >= 5.4，需开启 curl），任意带 PHP 的 VPS / 虚拟主机都能跑：

```bash
cd php
PROXY_TOKEN=xxx php -S 0.0.0.0:8788 proxy.php
```

也可把 `proxy.php` 部署为 **Apache / Nginx+PHP-FPM** 的入口脚本，Base URL 指向该脚本即可。

参数：`--port`（默认 8788）、`PROXY_MODE=auto|fetch`。也可用环境变量
`PORT` / `PROXY_TOKEN` / `PROXY_MODE` / `PROXY_ALLOW_OPEN`。

### 设计要点
- 无任意 TLS Socket 半双工隧道，PHP 版用 **cURL 流式转发**实现 SSE 逐块透传，
  行为对齐 node/worker 的 fetch 分支，对 AI 上游足够。
- 仅内置服务器 SAPI 下可解析绝对 URI 老式代理；PHP-FPM 下依赖 `X-Forward-Target` 中继。
- 主动透传自定义头（如 `x-goog-api-key`、自定义鉴权头），脚免获取头时以
  `$_SERVER['HTTP_*']` 为主、`getallheaders()` 为辅。

---

## Go 独立版

编译为单文件二进制，无运行时依赖、内存占用最小，适合长期作为 systemd 服务跑在低配 VPS：

```bash
cd go
CGO_ENABLED=0 go build -o go-proxy go-proxy.go
PROXY_TOKEN=xxx PORT=8788 ./go-proxy                 # 或加 --port / --mode 参数
```

### 一键安装 / 管理（Linux）

项目内置了交互式安装脚本 `go/install.sh`，支持自动装 Go、从 GitHub 拉源码编译，
并用 systemd 以 `freeapi` 服务名托管，提供安装 / 暂停 / 重启 / 更新菜单：

```bash
sudo bash install.sh      # 交互菜单；也可 bash install.sh 4 直达更新
```

### 设计要点
- 仅用 Go 标准库，`CGO_ENABLED=0 go build go-proxy.go` 即可产出静态二进制，开箱即用。
- Go 拥有完整 TLS 能力，**Socket 模式真实可用**：默认直连，仅对 OpenAI/Claude/opencode
  等被 CF 保护的 host 走 fetch，Socket 失败自动降级。
- 支持老式代理形态：绝对 URI 与 `CONNECT` 隧道，等同 Node 版行为。
- systemd 停止时通过 SIGTERM 优雅关闭。

---

## 测试

四个服务都能被 FreeAPI 管理后台当作代理使用：在「代理设置」新建一条
（Worker 选 CF Worker / Node 选 Node / PHP 选 PHP / Go 选 Go 类型，Base URL + Token），
到「连接通道」为对应账号/API Key 选择该代理即可。
