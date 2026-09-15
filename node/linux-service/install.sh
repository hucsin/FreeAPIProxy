#!/bin/sh
# ============================================================================
#  FreeAPIProxy —— 一键安装为 Linux systemd 常驻服务（开机自启）
#
#  纯 POSIX sh 编写（兼容 dash/bash/zsh），可直接：
#      sudo sh install.sh           # 或
#      sudo ./install.sh            # （需已 chmod +x）
#
#  前置条件（脚本会自动检查）：
#    - root 权限
#    - node >= 18（Node 18 才有全局 fetch；脚本会自动解析 node 的绝对路径写入服务单元，
#      即使装在 ~/.nvm 等用户目录，也会自动以 root 运行，无需手动改路径）
#    - systemd 发行版（Ubuntu/Debian/CentOS/Fedora/Rocky 等现代 Linux）
#
#  用法：
#    sudo ./install.sh            # 部署并 start + enable（首次无 env 会用随机 token 生成）
#    sudo ./install.sh --no-start # 只部署，不启动/不 enable（便于你先改 env）
#
#  产出：
#    /opt/freeapiproxy/                     代码目录（node-server.js / package.json）
#    /etc/freeapiproxy/freeapiproxy.env       密钥环境文件（权限 600）
#    /etc/systemd/system/freeapiproxy.service  systemd 单元（名字即 freeapiproxy）
#    运行账号 freeapiproxy（系统账户，禁登录）
# ============================================================================
set -eu

# ---- 用法解析：记录是否 --no-start，遇 -h/--help 直接退出 ----
START=1
for a in "$@"; do
  case "$a" in
    --no-start) START=0 ;;
    -h|--help)
      echo "用法: sudo $0 [--no-start]"
      exit 0
      ;;
  esac
done

# ---------- 1. 预检 ----------
if [ "$(id -u)" -ne 0 ]; then
  echo "✗ 需要 root 权限，请用: sudo $0" >&2
  exit 1
fi

if command -v node >/dev/null 2>&1; then
  NODE_VER="$(node -v)"
  if ! node -e 'process.exit(process.versions.node.split(".")[0]>=18?0:1)' 2>/dev/null; then
    echo "✗ 检测到 node ${NODE_VER}，但本服务需要 node>=18（Node 18 才有全局 fetch）。" >&2
    exit 1
  fi
  echo "✓ node ${NODE_VER}"
else
  echo "✗ 未在 PATH 找到 node。请先安装 node>=18，或把 node 所在目录加入 PATH/软链到 /usr/local/bin。" >&2
  exit 1
fi

SD_VER="$(systemctl --version 2>/dev/null | head -1 || echo '未检测到 systemd')"
echo "✓ systemd: ${SD_VER}"

# ---------- 2. 定位本脚本同目录的资源 ----------
# 兼容两种调用：sh install.sh（$0=install.sh）与 /abs/path/install.sh（$0=绝对路径）
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

SRC_JS="${SCRIPT_DIR}/../node-server.js"   # linux-service 的上层就是 node/
SRC_PKG="${SCRIPT_DIR}/../package.json"
if [ ! -f "${SRC_JS}" ]; then
  # 允许把本服务目录单独拷走时，回退到同目录找
  SRC_JS="${SCRIPT_DIR}/node-server.js"
  SRC_PKG="${SCRIPT_DIR}/package.json"
fi
if [ ! -f "${SRC_JS}" ]; then
  echo "✗ 找不到 node-server.js（期望位于 ${SCRIPT_DIR}/../node-server.js 或 ${SCRIPT_DIR}/）。" >&2
  echo "  请把整个 server-proxy/node 目录完整拷贝后再执行。" >&2
  exit 1
fi

# ---------- 3. 创建系统账户（已存在则跳过） ----------
if ! id freeapiproxy >/dev/null 2>&1; then
  useradd --system --no-create-home --shell /usr/sbin/nologin freeapiproxy
  echo "✓ 已创建系统账户 freeapiproxy"
else
  echo "✓ 系统账户 freeapiproxy 已存在"
fi

# ---------- 4. 部署代码到 /opt/freeapiproxy ----------
INSTALL_DIR=/opt/freeapiproxy
mkdir -p "${INSTALL_DIR}"
install -m 0644 "${SRC_JS}" "${INSTALL_DIR}/node-server.js"
install -m 0644 "${SRC_PKG}" "${INSTALL_DIR}/package.json"
chown -R freeapiproxy:freeapiproxy "${INSTALL_DIR}"
echo "✓ 代码已部署到 ${INSTALL_DIR}"

# ---------- 5. 密钥环境文件（存在则不覆盖，保留旧 token） ----------
ENV_DIR=/etc/freeapiproxy
ENV_FILE="${ENV_DIR}/freeapiproxy.env"
mkdir -p "${ENV_DIR}"
if [ -f "${ENV_FILE}" ]; then
  echo "✓ 已存在 ${ENV_FILE}，保留不动（如需换 token 请手动编辑后 systemctl restart freeapiproxy）"
else
  # 从模板复制；若模板缺失则用默认 + 随机 token
  TOKEN="$(head -c24 /dev/urandom | base64 | tr -d '/+=' | head -c32)"
  if [ -f "${SCRIPT_DIR}/freeapiproxy.env.example" ]; then
    sed "s/^PROXY_TOKEN=.*/PROXY_TOKEN=${TOKEN}/" \
        "${SCRIPT_DIR}/freeapiproxy.env.example" > "${ENV_FILE}"
  else
    printf 'PROXY_TOKEN=%s\n# PROXY_MODE=auto\n# PORT=8788\n' "${TOKEN}" > "${ENV_FILE}"
  fi
  echo "✓ 已生成 ${ENV_FILE}（随机 token 已写入）"
  echo "  ※ 请打开该文件核对，并把此 token 填入管理后台「代理设置」对应 Node 代理的 Token 字段。"
fi
chmod 0600 "${ENV_FILE}"
chown root:root "${ENV_FILE}"

# ---------- 6. 生成 systemd 单元（自动适配 node 路径与运行用户） ----------
UNIT=/etc/systemd/system/freeapiproxy.service
SRC_UNIT="${SCRIPT_DIR}/freeapiproxy.service"
if [ ! -f "${SRC_UNIT}" ]; then
  echo "✗ 缺少 ${SRC_UNIT}，跳过单元安装。" >&2
  exit 1
fi

# 解析 node 真实绝对路径：systemd 环境的 PATH 很窄，`/usr/bin/env node` 可能解析到
# 旧版本；nvm 等装在用户目录的 node 更必须写死真实路径，否则报 203/EXEC。
NODE_BIN="$(command -v node)"
NODE_BIN="$(readlink -f "${NODE_BIN}" 2>/dev/null || echo "${NODE_BIN}")"

# ProtectHome=yes 会把 /root、/home 对服务进程挂成完全不可访问（对 root 用户同样生效），
# 因此 node 装在 /root|/home 下（如 ~/.nvm）时必须降级为 read-only 并以 root 运行，
# 否则即使 ExecStart 填了绝对路径也会 203/EXEC；node 在系统目录时保留最小权限账户。
case "${NODE_BIN}" in
  /root/*|/home/*)
    RUN_USER=root
    PROTECT_HOME=read-only
    ;;
  *)
    RUN_USER=freeapiproxy
    PROTECT_HOME=yes
    ;;
esac

sed -e "s|^ExecStart=.*|ExecStart=${NODE_BIN} /opt/freeapiproxy/node-server.js --port 8788 --mode auto|" \
    -e "s|^User=.*|User=${RUN_USER}|" \
    -e "s|^Group=.*|Group=${RUN_USER}|" \
    -e "s|^ProtectHome=.*|ProtectHome=${PROTECT_HOME}|" \
    "${SRC_UNIT}" > "${UNIT}"
chmod 0644 "${UNIT}"
echo "✓ 已生成单元 ${UNIT}"
echo "    node:          ${NODE_BIN}"
echo "    run as user:   ${RUN_USER}   (ProtectHome=${PROTECT_HOME})"

systemctl daemon-reload

# ---------- 7. 启动 + 开机自启 ----------
if [ "${START}" = "1" ]; then
  systemctl enable --now freeapiproxy.service
  echo "✓ 已 enable 开机自启并启动 freeapiproxy"
  sleep 1
  systemctl --no-pager status freeapiproxy.service || true
  echo
  echo "查看实时日志: journalctl -u freeapiproxy -f"
else
  echo "(--no-start) 未启动。手动操作:"
  echo "  systemctl daemon-reload"
  echo "  systemctl enable freeapiproxy.service   # 开机自启"
  echo "  systemctl start  freeapiproxy.service   # 启动"
fi
echo "完成。"
