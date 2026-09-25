#!/usr/bin/env bash
#
# FreeApiGo - Linux 安装/管理脚本（systemd）
# ============================================================================
# 功能：
#   1) 交互式菜单：安装 / 暂停服务 / 重启服务 / 更新服务
#   2) 安装时做环境检查，自动安装 Go（apt/yum/dnf 或官方二进制包）
#   3) 从 GitHub 拉取 go-proxy.go，本地编译后用 systemd(systemctl) 管理
#   4) 服务名固定为 freeapi（/etc/systemd/system/freeapi.service）
#
# 用法：
#   sudo bash install.sh          # 进入交互式菜单
#   sudo bash install.sh 1        # 直达安装（也支持 2/3/4/q）
#
# 免 root（无需 systemd）的 Alpine/busybox 场景请改用同目录 install-alpine.sh
# ============================================================================

set -euo pipefail

# ---------------------------------------------------------------------------
# 全局配置
# ---------------------------------------------------------------------------
SRC_URL="https://raw.githubusercontent.com/hucsin/FreeAPIProxy/refs/heads/main/go/go-proxy.go"
APP_DIR="/opt/freeapi"
BIN_NAME="go-proxy"
BIN_PATH="${APP_DIR}/${BIN_NAME}"
SRC_PATH="${APP_DIR}/go-proxy.go"
CONF_PATH="/etc/freeapi.conf"
SERVICE_NAME="freeapi"
SERVICE_FILE="/etc/systemd/system/${SERVICE_NAME}.service"
GO_VERSION_URL="https://go.dev/VERSION?m=text"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'

log_info()  { echo -e "${GREEN}[INFO]${NC}  $*"; }
log_warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
log_err()   { echo -e "${RED}[ERROR]${NC} $*"; }
log_step()  { echo -e "${CYAN}==>${NC} $*"; }

# ---------------------------------------------------------------------------
# 工具函数
# ---------------------------------------------------------------------------
die() { log_err "$*"; exit 1; }

need_root() {
    [[ $EUID -eq 0 ]] || die "请使用 root 权限运行（sudo bash install.sh）"
}

need_cmd() {
    command -v "$1" >/dev/null 2>&1 || die "缺少命令: $1，请先安装"
}

have() { command -v "$1" >/dev/null 2>&1; }

get_arch() {
    case "$(uname -m)" in
        x86_64|amd64) echo amd64 ;;
        aarch64|arm64) echo arm64 ;;
        armv7l) echo armv6l ;;
        *) echo "$(uname -m)" ;;
    esac
}

get_latest_go_version() {
    local v
    if have curl; then
        v="$(curl -fsSL --max-time 20 "$GO_VERSION_URL" | head -n1 || true)"
    elif have wget; then
        v="$(wget -qO - --timeout=20 "$GO_VERSION_URL" | head -n1 || true)"
    fi
    [[ -n "${v:-}" ]] && echo "$v" || echo "go1.22.5"
}

download_file() { # <url> <dest>
    local url="$1" dest="$2"
    if have curl; then
        curl -fSL --retry 3 --connect-timeout 15 -o "$dest" "$url"
    elif have wget; then
        wget -q --tries=3 --timeout=15 -O "$dest" "$url"
    else
        die "需要 curl 或 wget 来下载文件"
    fi
}

# ---------------------------------------------------------------------------
# Go 环境检查与自动安装
# ---------------------------------------------------------------------------
check_go() {
    if have go; then
        log_info "已检测到 Go：$(go version)"
        return 0
    fi
    log_warn "未检测到 Go，开始自动安装..."
    install_go
}

install_go() {
    log_step "安装 Go 编译环境"

    # 优先尝试系统包管理器
    if have apt-get; then
        log_info "使用 apt-get 安装 golang"
        export DEBIAN_FRONTEND=noninteractive
        apt-get update -qq
        apt-get install -y golang-go curl 2>/dev/null || apt-get install -y golang curl
        have go && { log_info "Go 安装完成：$(go version)"; return 0; }
    fi
    if have dnf; then
        log_info "使用 dnf 安装 golang"
        dnf install -y golang || dnf install -y golang-bin
        have go && { log_info "Go 安装完成：$(go version)"; return 0; }
    fi
    if have yum; then
        log_info "使用 yum 安装 golang"
        yum install -y golang || yum install -y golang-bin
        have go && { log_info "Go 安装完成：$(go version)"; return 0; }
    fi
    if have apk; then
        log_info "使用 apk 安装 golang"
        apk add --no-cache go
        have go && { log_info "Go 安装完成：$(go version)"; return 0; }
    fi

    # 包管理器不可用 / 失败，改用官方二进制包安装到 /usr/local/go
    if [[ ! -d /usr/local/go ]]; then
        need_cmd curl
        need_cmd tar
        local ver arch
        ver="$(get_latest_go_version)"
        arch="$(get_arch)"
        local tarball="go.tar.gz"
        log_info "下载官方 Go 二进制包: ${ver}.linux-${arch}（version: ${ver}）"
        download_file "https://go.dev/dl/${ver}.linux-${arch}.tar.gz" "/tmp/${tarball}"
        log_info "解压到 /usr/local/go ..."
        rm -rf /usr/local/go
        tar -C /usr/local -xzf "/tmp/${tarball}"
        rm -f "/tmp/${tarball}"
        ln -sf /usr/local/go/bin/go /usr/local/bin/go
        ln -sf /usr/local/go/bin/gofmt /usr/local/bin/gofmt
        log_info "Go 安装完成：$(go version)"
    else
        ln -sf /usr/local/go/bin/go /usr/local/bin/go 2>/dev/null || true
    fi
    have go || die "Go 自动安装失败，请手动安装 Go 后重试"
}

# ---------------------------------------------------------------------------
# 拉取源码并本地编译
# ---------------------------------------------------------------------------
fetch_and_build() {
    log_step "下载 go-proxy.go 源码"
    mkdir -p "$APP_DIR"
    download_file "$SRC_URL" "$SRC_PATH"
    log_info "源码已保存到 $SRC_PATH"

    check_go

    log_step "本地编译（CGO_ENABLED=0）"
    local tmpdir build_ok=1
    tmpdir="$(mktemp -d)"
    trap 'rm -rf "$tmpdir"' RETURN
    cp "$SRC_PATH" "$tmpdir/go-proxy.go"
    # 关闭模块下载 + 锁定本地工具链，避免访问 proxy.golang.org / 下载工具链卡住
    ( cd "$tmpdir" && GO111MODULE=off GOPROXY=off GOTOOLCHAIN=local GOFLAGS= \
        CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags "-s -w" -o go-proxy go-proxy.go ) || build_ok=0
    if [[ "$build_ok" -eq 0 || ! -f "$tmpdir/go-proxy" ]]; then
        log_err "编译失败（低配机器可能因内存不足被杀）。可加 swap 后重试，或改用免 root 的 install-alpine.sh。"
        return 1
    fi
    mv -f "$tmpdir/go-proxy" "$BIN_PATH"
    chmod +x "$BIN_PATH"
    log_info "编译产物：$BIN_PATH"
}

# ---------------------------------------------------------------------------
# systemd service 管理
# ---------------------------------------------------------------------------
write_service() { # <token> <port>
    local token="$1" port="$2"
    cat > "$SERVICE_FILE" <<EOF
[Unit]
Description=FreeApiGo (${SERVICE_NAME}) reverse proxy
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
WorkingDirectory=${APP_DIR}
Environment=PROXY_TOKEN=${token}
Environment=PORT=${port}
EnvironmentFile=-${CONF_PATH}
ExecStart=${BIN_PATH}
Restart=always
RestartSec=3
LimitNOFILE=65536

[Install]
WantedBy=multi-user.target
EOF
    systemctl daemon-reload
    log_info "已生成 systemd 服务：$SERVICE_NAME"
}

prompt_token() {
    local tok
    echo -e "${CYAN}设置代理鉴权 Token${NC}" >&2
    echo -e "  （留空表示不设 Token；注意：不设 Token 时服务默认拒绝连接，除非设置 PROXY_ALLOW_OPEN=1）" >&2
    read -r -p "  PROXY_TOKEN > " tok
    echo "$tok"
}

prompt_port() {
    local p
    echo -e "${CYAN}设置监听端口${NC}" >&2
    read -r -p "  PORT（默认 8788）> " p
    [[ -z "$p" ]] && p=8788
    echo "$p"
}

is_installed() {
    [[ -f "$BIN_PATH" ]] && [[ -f "$SERVICE_FILE" ]]
}

# ---------------------------------------------------------------------------
# 菜单操作
# ---------------------------------------------------------------------------
do_install() {
    need_root
    need_cmd systemctl

    if is_installed && systemctl list-unit-files "$SERVICE_NAME.service" >/dev/null 2>&1; then
        log_warn "检测到已安装的服务，将覆盖安装并重启。"
    fi

    local token port
    token="$(prompt_token)"
    port="$(prompt_port)"

    # fetch_and_build 内部会做 check_go（环境检查 + 必要时自动安装 Go）
    fetch_and_build

    log_step "配置并启动 systemd 服务"
    write_service "$token" "$port"
    systemctl enable "$SERVICE_NAME" >/dev/null 2>&1 || true
    systemctl restart "$SERVICE_NAME"
    systemctl is-active "$SERVICE_NAME" >/dev/null 2>&1
    log_info "服务已启动并设置为开机自启。"
    echo
    log_info "配置可通过编辑 $CONF_PATH 覆盖（如 PROXY_MODE、PROXY_ALLOW_OPEN 等）。"
    show_status
}

do_pause() {
    need_root
    if systemctl list-unit-files "$SERVICE_NAME.service" >/dev/null 2>&1; then
        systemctl stop "$SERVICE_NAME"
        log_info "服务已暂停（systemctl stop $SERVICE_NAME）。"
    else
        log_warn "服务 $SERVICE_NAME 未安装。"
    fi
}

do_restart() {
    need_root
    if systemctl list-unit-files "$SERVICE_NAME.service" >/dev/null 2>&1; then
        systemctl restart "$SERVICE_NAME"
        log_info "服务已重启。"
        show_status
    else
        log_warn "服务 $SERVICE_NAME 未安装，请先选择 [1] 安装。"
    fi
}

do_update() {
    need_root
    if ! is_installed; then
        log_warn "尚未安装，无法更新，请先选择 [1] 安装。"
        return
    fi
    log_step "更新：拉取最新源码并重新编译"
    fetch_and_build
    log_step "重启服务以生效"
    systemctl restart "$SERVICE_NAME"
    log_info "更新完成，服务已重启。"
    show_status
}

show_status() {
    echo
    echo -e "${CYAN}----------- 服务状态 -----------${NC}"
    if systemctl list-unit-files "$SERVICE_NAME.service" >/dev/null 2>&1; then
        systemctl status "$SERVICE_NAME" --no-pager -l || true
    else
        echo "未安装 systemd 服务 $SERVICE_NAME"
    fi
    echo -e "${CYAN}--------------------------------${NC}"
}

show_menu() {
    echo
    echo "============================================"
    echo "       FreeApiGo 管理脚本"
    echo "============================================"
    echo -e "  ${GREEN}[1]${NC} 安装服务（检查环境 + 编译 + 启动）"
    echo -e "  ${GREEN}[2]${NC} 暂停服务"
    echo -e "  ${GREEN}[3]${NC} 重启服务"
    echo -e "  ${GREEN}[4]${NC} 更新服务（重新拉取源码编译并重启）"
    echo -e "  ${GREEN}[q]${NC} 退出"
    echo "============================================"
}

# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------
main() {
    if [[ $# -eq 1 ]] && [[ "$1" =~ ^[1-4qQ]$ ]]; then
        case "$1" in
            1) do_install ;;
            2) do_pause ;;
            3) do_restart ;;
            4) do_update ;;
            q|Q) exit 0 ;;
        esac
        return
    fi

    while true; do
        show_menu
        read -r -p "请选择要执行的操作 [1/2/3/4/q]: " choice
        case "$choice" in
            1) do_install ;;
            2) do_pause ;;
            3) do_restart ;;
            4) do_update ;;
            q|Q) echo "退出。"; break ;;
            *) log_warn "无效选项，请重新选择。" ;;
        esac
        echo
    done
}

main "$@"