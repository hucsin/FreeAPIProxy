#!/bin/sh
#
# FreeApiGo - Alpine Linux 免 root 安装/管理脚本（POSIX sh / busybox 兼容）
# ============================================================================
# 说明：
#   - 全程不需要 root：安装到 ~/.local/freeapi，Go 编译用 ~/.local/go
#   - 服务用 nohup + pidfile 托管，提供 start/stop/restart/status/update
#   - 开机自启：优先写入 @reboot crontab，无 crontab 则回退到 ~/.profile
#
# 用法（交互菜单）：
#   sh install-alpine.sh
# 或用子命令（供 cron 或 -e 调用）：
#   sh install-alpine.sh install|start|stop|restart|status|update
# ============================================================================

# ---------------------------------------------------------------------------
# 配置
# ---------------------------------------------------------------------------
SRC_URL="https://raw.githubusercontent.com/hucsin/FreeAPIProxy/refs/heads/main/go/go-proxy.go"
BASE_DIR="${HOME}/.local/freeapi"
BIN_NAME="go-proxy"
BIN_PATH="${BASE_DIR}/${BIN_NAME}"
SRC_PATH="${BASE_DIR}/go-proxy.go"
CONF_PATH="${BASE_DIR}/freeapi.conf"
LOG_PATH="${BASE_DIR}/freeapi.log"
PID_PATH="${BASE_DIR}/freeapi.pid"
GO_HOME="${HOME}/.local/go"
SELF="$0"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'

# ---------------------------------------------------------------------------
# 通用
# ---------------------------------------------------------------------------
die() { printf '%b\n' "${RED}[ERROR]${NC} $*" >&2; exit 1; }
log_info(){ printf '%b\n' "${GREEN}[INFO]${NC}  $*"; }
log_warn(){ printf '%b\n' "${YELLOW}[WARN]${NC}  $*"; }
log_step(){ printf '%b\n' "${CYAN}==>${NC} $*"; }

have() { command -v "$1" >/dev/null 2>&1; }

mk_dirs(){ mkdir -p "$BASE_DIR" "$GO_HOME" "$GO_HOME/bin"; }

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
    if have wget; then v="$(wget -q -O - https://go.dev/VERSION?m=text 2>/dev/null | head -n1)"; fi
    [ -n "${v:-}" ] && echo "$v" || echo "go1.22.5"
}

download_file() { # <url> <dest>
    if have wget; then wget -q --tries=3 -T 20 -O "$2" "$1"
    elif have curl; then curl -fsSL --retry 3 --connect-timeout 15 -o "$2" "$1"
    else die "需要 wget 或 curl"
    fi
}

path_add_go() {
    case ":$PATH:" in
        *":$GO_HOME/bin:"*) : ;;
        *) PATH="$GO_HOME/bin:$PATH" ;;
    esac
    export PATH
}

# ---------------------------------------------------------------------------
# Go 装到用户目录（免 root）
# ---------------------------------------------------------------------------
check_go() {
    path_add_go
    if have go; then log_info "检测到 Go：$(go version)"; return 0; fi
    log_warn "未检测到 Go，安装到 $GO_HOME ..."
    install_local_go
    path_add_go
    have go || die "Go 安装失败，请手动安装后重试"
}

install_local_go() {
    local ver arch tarball
    ver="$(get_latest_go_version)"
    arch="$(get_arch)"
    tarball="$HOME/.local/go-${ver}.tar.gz"

    log_info "下载官方 Go 包：${ver}.linux-${arch}"
    download_file "https://go.dev/dl/${ver}.linux-${arch}.tar.gz" "$tarball" \
        || die "下载 Go 失败"
    log_info "解压到 $HOME/.local ..."
    mkdir -p "$HOME/.local"
    tar -C "$HOME/.local" -xzf "$tarball"
    rm -f "$tarball"
    # 官方包解压为 .local/go；若目录名非 go，改名
    [ -d "$HOME/.local/go/bin" ] || { [ -d "$HOME/.local/go${ver}/bin" ] && mv "$HOME/.local/go${ver}" "$HOME/.local/go"; }
    log_info "Go 已安装：$($GO_HOME/bin/go version)"
}

# ---------------------------------------------------------------------------
# 拉源码 + 编译
# ---------------------------------------------------------------------------
fetch_and_build() {
    log_step "下载 go-proxy.go 源码"
    mk_dirs
    download_file "$SRC_URL" "$SRC_PATH"
    log_info "源码：$SRC_PATH"

    check_go

    log_step "本地编译（CGO_ENABLED=0）"
    local tmpdir
    tmpdir="$(mktemp -d)"
    cp "$SRC_PATH" "$tmpdir/go-proxy.go"
    ( cd "$tmpdir" && CGO_ENABLED=0 GOOS=linux $GO_HOME/bin/go build -trimpath -ldflags "-s -w" -o go-proxy go-proxy.go )
    mv -f "$tmpdir/go-proxy" "$BIN_PATH"
    rm -rf "$tmpdir"
    chmod +x "$BIN_PATH"
    log_info "编译产物：$BIN_PATH"
}

# ---------------------------------------------------------------------------
# 配置文件（免 root，写到用户目录）
# ---------------------------------------------------------------------------
prompt_token() {
    printf '%b\n' "${CYAN}设置代理鉴权 Token${NC}" >&2
    printf '%b\n' "  （留空表示不设 Token；注意：不设 Token 时服务默认拒绝连接，除非设置 PROXY_ALLOW_OPEN=1）" >&2
    printf '  PROXY_TOKEN > '
    read token
    echo "$token"
}

prompt_port() {
    printf '%b\n' "${CYAN}设置监听端口${NC}" >&2
    printf '  PORT（默认 8788）> '
    read port
    [ -z "$port" ] && port=8788
    echo "$port"
}

write_conf() { # <token> <port>
    local esc
    esc=$(printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g')
    printf 'PROXY_TOKEN="%s"\n' "$esc" > "$CONF_PATH"
    printf 'PORT=%s\n' "$2" >> "$CONF_PATH"
    chmod 700 "$CONF_PATH"
    log_info "配置已写入 $CONF_PATH"
}

# ---------------------------------------------------------------------------
# 服务管理（nohup + pidfile）
# ---------------------------------------------------------------------------
is_running() {
    [ -f "$PID_PATH" ] && kill -0 "$(cat "$PID_PATH")" 2>/dev/null
}

pid_of() { [ -f "$PID_PATH" ] && cat "$PID_PATH"; }

start_service() {
    mk_dirs
    if is_running; then
        log_info "服务已在运行（pid $(pid_of)）：$BASE_DIR"
        return 0
    fi
    [ -f "$CONF_PATH" ] && . "$CONF_PATH" 2>/dev/null
    PORT="${PORT:-8788}"
    PROXY_TOKEN="${PROXY_TOKEN:-}"
    log_step "启动 freeapi 代理（端口 $PORT，日志 $LOG_PATH）"
    PORT="$PORT" PROXY_TOKEN="$PROXY_TOKEN" nohup "$BIN_PATH" >>"$LOG_PATH" 2>&1 &
    echo $! > "$PID_PATH"
    sleep 1
    if is_running; then
        log_info "已启动，pid $(pid_of)"
    else
        log_err "启动失败，请查看日志：$LOG_PATH"
    fi
}

stop_service() {
    if ! is_running; then
        log_info "服务未在运行。"
        rm -f "$PID_PATH"
        return 0
    fi
    local pid
    pid="$(pid_of)"
    kill "$pid" 2>/dev/null
    # 等待最多 10 秒优雅退出（SIGTERM）
    local i=0
    while kill -0 "$pid" 2>/dev/null && [ "$i" -lt 10 ]; do sleep 1; i=$((i+1)); done
    kill -0 "$pid" 2>/dev/null && { log_warn "未在超时内退出，强制结束"; kill -9 "$pid" 2>/dev/null; }
    rm -f "$PID_PATH"
    log_info "服务已停止。"
}

restart_service() {
    stop_service
    start_service
}

status_service() {
    if is_running; then
        echo "freeapi 状态：运行中（pid $(pid_of)）"
        log_path=$LOG_PATH; pid_path=$PID_PATH
        echo "  二进制：$BIN_PATH"
        echo "  配置  ：$CONF_PATH"
        echo "  日志  ：$LOG_PATH"
        # 打印近期日志尾部
        [ -f "$LOG_PATH" ] && tail -n 5 "$LOG_PATH" 2>/dev/null
    else
        echo "freeapi 状态：未运行"
        [ -f "$PID_PATH" ] && echo "  残留 pidfile：$(pid_of)"
        [ -f "$LOG_PATH" ] && echo "  最近日志：$(tail -n 3 "$LOG_PATH" 2>/dev/null)"
    fi
}

# ---------------------------------------------------------------------------
# 开机自启（cron @reboot 优先，回退 ~/.profile）
# ---------------------------------------------------------------------------
register_autostart() {
    if have crontab; then
        local entry tmp
        entry="@reboot sh $SELF start"
        if crontab -l 2>/dev/null | grep -Fq -- "$SELF start"; then
            log_info "crontab @reboot 已存在。"
            return 0
        fi
        tmp="$(mktemp)"
        crontab -l 2>/dev/null > "$tmp"
        printf '%s\n' "$entry" >> "$tmp"
        crontab "$tmp"
        rm -f "$tmp"
        log_info "已写入 @reboot cron：$entry"
    else
        # 回退：登录时启动（user shell 兼容性最好）
        local profile="$HOME/.profile"
        if ! grep -Fq -- "$SELF start" "$profile" 2>/dev/null; then
            printf '\n# FreeApiGo autostart\n[ -x "%s" ] && sh %s start >/dev/null 2>&1 &\n' "$BIN_PATH" "$SELF" >> "$profile" 2>/dev/null && \
                log_info "无 crontab，已写入 ~/.profile 登录自启" || \
                log_warn "无法配置自启，请手动在开机后执行：sh $SELF start"
        else
            log_info "$profile 已包含自启。"
        fi
    fi
}

# ---------------------------------------------------------------------------
# 菜单操作
# ---------------------------------------------------------------------------
do_install() {
    local token port
    token="$(prompt_token)"
    port="$(prompt_port)"

    log_step "环境检查"
    mk_dirs

    fetch_and_build

    log_step "写入配置"
    write_conf "$token" "$port"

    start_service
    register_autostart
}

do_update() {
    if [ ! -f "$BIN_PATH" ]; then
        log_warn "尚未安装，请先执行 install。"
        return 1
    fi
    log_step "更新：拉最新源码并重编译"
    fetch_and_build
    log_step "重启服务生效"
    restart_service
}

show_menu() {
    echo
    echo "=========================================="
    echo "    FreeApiGo 管理脚本（免 root）"
    echo "=========================================="
    echo "  [1] 安装服务（装 Go + 编译 + 启动 + 自启）"
    echo "  [2] 暂停服务"
    echo "  [3] 重启服务"
    echo "  [4] 更新服务"
    echo "  [5] 查看状态"
    echo "  [q] 退出"
    echo "=========================================="
}

# ---------------------------------------------------------------------------
# 入口
# ---------------------------------------------------------------------------
case "$1" in
    install) do_install ;;
    start)   start_service ;;
    stop)    stop_service ;;
    restart) restart_service ;;
    status)  status_service ;;
    update)  do_update ;;
    *)
        while true; do
            show_menu
            printf '请选择 [1/2/3/4/5/q]: '
            read choice
            case "$choice" in
                1) do_install ;;
                2) stop_service ;;
                3) restart_service ;;
                4) do_update ;;
                5) status_service ;;
                q|Q) echo "退出。"; break ;;
                *) log_warn "无效选项。" ;;
            esac
            echo
        done
        ;;
esac