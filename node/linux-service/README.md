# freeapi — Node egress proxy 的 Linux systemd 服务

把 `node/node-server.js` 这个 Node 独立反代跑成开机自启的 systemd 服务，服务名 **`freeapi`**。

## 文件

| 文件 | 作用 |
|---|---|
| `freeapi.service` | systemd 单元（服务名即 freeapi） |
| `freeapi.env.example` | 密钥环境文件模板（含 `PROXY_TOKEN` 等说明） |
| `install.sh` | 一键部署：装代码 + 生成密钥文件 + 装单元 + `enable --now` 自启 |

> 这三个文件要放在 `node/linux-service/` 下使用，因为 `install.sh`
> 会从上一级 `node/` 拷贝 `node-server.js` / `package.json`。

## 快速开始（在目标 Linux 机器上）

先准备 `node >= 18.13`（Node 18.13+ 才有全局 `fetch` 且支持 `compress:false`，
才能让上游 `Content-Encoding` 原样透传），然后把整个 `node` 目录拷到目标机
（或直接 git clone 该项目），再执行：

```bash
cd node/linux-service
sudo ./install.sh
```

脚本会：
1. 预检 root 权限 + `node >= 18.13` + systemd；
2. 创建系统账户 `freeapi`；
3. 把 `node-server.js` / `package.json` 拷到 `/opt/freeapi`；
4. 生成 `/etc/freeapi/freeapi.env`（权限 600，内置 32 位随机 `PROXY_TOKEN`）；
5. 安装 `/etc/systemd/system/freeapi.service`；
6. `systemctl enable --now freeapi` → 已开机自启并启动。

### 只部署、暂不启动
```bash
sudo ./install.sh --no-start
# 之后手动：
systemctl daemon-reload
systemctl enable --now freeapi
```

## 日常管理

```bash
systemctl status freeapi          # 状态
journalctl -u freeapi -f          # 实时日志
systemctl restart freeapi         # 重启
systemctl stop  freeapi           # 停止
systemctl disable freeapi         # 取消开机自启
```

## 配置密钥 / 端口

编辑 `/etc/freeapi/freeapi.env`（仅 root 可读）：

```ini
PROXY_TOKEN=你的高强度随机串
# PROXY_MODE=auto      # auto|socket|fetch
# PORT=8788
```

改完 `systemctl restart freeapi` 生效。
**该 token 必须与 FreeAPI 管理后台「代理设置」里该 Node 代理的 Token 字段一致**，
绑定到具体账号/API Key 后才能正确鉴权。

## 常见问题

- **找不到 node**：确认 `node -v >= 18.13` 且在 root 的 PATH 里；用 nvm 装的可在
  `freeapi.service` 里把 `ExecStart` 的 `/usr/bin/env node` 换成 node 绝对路径。
- **SELinux 拦截出站连接**（Fedora/CentOS/RHEL 默认 Enforcing）：若日志出现 AVC 拦截，
  按你的策略域放行该服务的出站网络访问，或对该服务关闭 SELinux 约束。
- **想直接看落地日志文件**：把 unit 中 `StandardOutput/StandardError` 从 `journal` 改为
  `append:/var/log/freeapi/node-proxy.log`（先建文件并 `chown freeapi:freeapi`）。
- **卸载**：`systemctl disable --now freeapi && rm /etc/systemd/system/freeapi.service
  && systemctl daemon-reload && rm -rf /opt/freeapi /etc/freeapi && userdel freeapi`

> ⚠️ `PROXY_ALLOW_OPEN=1`（无 token 时开放）在 unit/env 里默认关闭。生产环境切勿开放，
> 否则任何能访问该端口的人都可用它当中转。