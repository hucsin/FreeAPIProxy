#!/usr/bin/env bash
#
# FreeAPIProxy · Android 端 构建 / 安装 交互式脚本
#
#   bash build.sh          # 打开菜单
#
# 设计取向与仓库里其它脚本一致：实时探测设备 + 菜单选择，不用 CLI 参数。
#
# 三种启动方式都可以：
#   bash build.sh   /   ./build.sh   /   sh build.sh
#
# --- 解释器守卫 ---------------------------------------------------------------
# 本脚本使用了数组、数组追加、进程替换 < <(...)、here-string <<< 等 bash 专有
# 语法，POSIX sh / dash 无法解析（会报 "syntax error near unexpected token `<'"）。
# 因此这里做一次自举：一旦发现当前解释器跑不了这些语法，就用 bash 重新执行自己。
#
# 判据有两个，缺一不可：
#   1) BASH_VERSION 为空           —— 覆盖 dash / zsh / 真正的 POSIX sh
#   2) bash 但处于 POSIX 模式      —— 覆盖 macOS 的 /bin/sh
#      macOS 的 /bin/sh 就是 bash 3.2，它 **仍然会设置 BASH_VERSION**，
#      只是开着 `posix` 选项，此时 bash 专有语法照样解析失败。仅凭第 1 条会漏掉它。
#
# 注意：判断与 exec 本身必须保持 POSIX 兼容，才能被 sh 正确解析到这里。
if [ -z "${BASH_VERSION:-}" ] || shopt -qo posix 2>/dev/null; then
  if [ -f "$0" ] && command -v bash >/dev/null 2>&1; then
    unset POSIXLY_CORRECT
    exec bash "$0" "$@"
  fi
  echo "error: 本脚本需要 bash 运行（例：bash build.sh），但未找到 bash。" >&2
  exit 1
fi

set -uo pipefail

BOLD=$'\033[1m'
DIM=$'\033[2m'
RED=$'\033[31m'
GREEN=$'\033[32m'
YELLOW=$'\033[33m'
CYAN=$'\033[36m'
NC=$'\033[0m'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

APP_ID="com.freeapi.proxy"
APK_DEBUG="app/build/outputs/apk/debug/app-debug.apk"

ok()   { echo "${GREEN}[ OK ]${NC} $*" >&2; }
info() { echo "${CYAN}[ .. ]${NC} $*" >&2; }
warn() { echo "${YELLOW}[WARN]${NC} $*" >&2; }
err()  { echo "${RED}[FAIL]${NC} $*" >&2; }
step() { echo "" >&2; echo "${BOLD}$*${NC}" >&2; }

# ------------------------------------------------------------------ 环境探测

locate_adb() {
  local cand
  for cand in "${ANDROID_HOME:-}/platform-tools/adb" \
              "$HOME/Library/Android/sdk/platform-tools/adb" \
              "$(command -v adb 2>/dev/null || true)"; do
    if [ -n "${cand:-}" ] && [ -x "${cand}" ]; then
      printf '%s' "${cand}"
      return 0
    fi
  done
  return 1
}

locate_java_home() {
  local v h
  for v in 17 21; do
    h=$(/usr/libexec/java_home -v "${v}" 2>/dev/null) || true
    if [ -n "${h:-}" ] && [ -x "${h}/bin/java" ]; then
      printf '%s' "${h}"
      return 0
    fi
  done
  return 1
}

ensure_env() {
  if [ -z "${ANDROID_HOME:-}" ]; then
    for cand in "$HOME/Library/Android/sdk" "/usr/local/share/android-sdk"; do
      if [ -d "${cand}" ]; then export ANDROID_HOME="${cand}"; break; fi
    done
  fi
  if [ -n "${ANDROID_HOME:-}" ] && [ -d "${ANDROID_HOME}" ]; then
    info "ANDROID_HOME = ${ANDROID_HOME}"
  else
    warn "未找到 Android SDK，构建可能失败"
  fi

  if [ -z "${JAVA_HOME:-}" ]; then
    local jh
    jh=$(locate_java_home) || true
    if [ -n "${jh:-}" ]; then
      export JAVA_HOME="${jh}"
      info "JAVA_HOME    = ${JAVA_HOME}"
    else
      warn "未找到 JDK 17/21，构建可能失败"
    fi
  else
    info "JAVA_HOME    = ${JAVA_HOME}"
  fi

  if [ ! -f "local.properties" ] && [ -n "${ANDROID_HOME:-}" ]; then
    printf 'sdk.dir=%s\n' "${ANDROID_HOME}" > local.properties
    info "已生成 local.properties"
  fi
}

# ------------------------------------------------------------------ 菜单组件

choose_option() {
  local title="$1"; shift
  local default_idx="$1"; shift

  if ! [[ "${default_idx}" =~ ^[0-9]+$ ]]; then
    default_idx="1"
  fi

  echo "" >&2
  echo "${BOLD}${title}${NC}" >&2
  echo "${DIM}────────────────────────────${NC}" >&2

  local i=1 total=0 marker
  for opt in "$@"; do
    marker=""
    [ "${i}" -eq "${default_idx}" ] && marker="  ${GREEN}<- 默认${NC}"
    printf "  ${BOLD}%d)${NC} %s%s\n" "${i}" "${opt}" "${marker}" >&2
    i=$((i + 1))
    total=$((total + 1))
  done
  echo "" >&2

  local choice
  while true; do
    read -rp "请选择 [${default_idx}]: " choice
    choice="${choice:-${default_idx}}"
    if [[ "${choice}" =~ ^[0-9]+$ ]] && [ "${choice}" -ge 1 ] && [ "${choice}" -le "${total}" ]; then
      printf '%s' "${choice}"
      return 0
    fi
    echo "${YELLOW}无效选择，请输入 1-${total}${NC}" >&2
  done
}

confirm() {
  local prompt="$1" def="${2:-Y}" answer
  read -rp "${prompt} [${def}/$( [ "${def}" = "Y" ] && printf 'n' || printf 'y' )]: " answer
  answer="${answer:-${def}}"
  case "${answer}" in
    y|Y|yes|YES) return 0 ;;
    *) return 1 ;;
  esac
}

pause() {
  read -rp "按回车继续…" _ 2>/dev/null || true
}

# ------------------------------------------------------------------ 设备

# stdout = 设备序列号列表（每行一个）；渲染信息全部走 stderr
list_devices() {
  local adb="$1" line serial state count=0
  while IFS= read -r line; do
    case "${line}" in
      ""|"List of devices attached"|"* daemon"*) continue ;;
    esac
    serial="$(printf '%s' "${line}" | awk '{print $1}')"
    state="$(printf '%s' "${line}" | awk '{print $2}')"
    [ -z "${serial}" ] && continue
    if [ "${state}" = "device" ]; then
      count=$((count + 1))
      printf '%s\n' "${serial}"
      echo "  ${GREEN}●${NC} ${serial} ${DIM}(在线)${NC}" >&2
    else
      echo "  ${YELLOW}○${NC} ${serial} ${DIM}(${state})${NC}" >&2
    fi
  done < <("${adb}" devices 2>/dev/null)
  return 0
}

# stdout = 选中的设备序列号
select_device() {
  local adb="$1"
  echo "" >&2
  echo "${BOLD}已连接设备${NC}" >&2

  local devices
  devices="$(list_devices "${adb}")"

  if [ -z "${devices}" ]; then
    warn "没有可用的在线设备"
    return 1
  fi

  local count
  count="$(printf '%s\n' "${devices}" | wc -l | tr -d ' ')"

  if [ "${count}" -eq 1 ]; then
    local only
    only="$(printf '%s\n' "${devices}" | head -n1)"
    if confirm "使用设备 ${only}？" "Y" >&2; then
      printf '%s' "${only}"
      return 0
    fi
    return 1
  fi

  local -a arr=()
  while IFS= read -r d; do arr+=("${d}"); done <<< "${devices}"

  local idx
  idx=$(choose_option "选择目标设备" 1 "${arr[@]/#/设备 }")
  printf '%s' "${arr[$((idx - 1))]}"
  return 0
}

# ------------------------------------------------------------------ 动作

do_build() {
  step "构建 Debug APK"
  info "执行 ./gradlew assembleDebug"
  ./gradlew assembleDebug
  local rc=$?
  if [ "${rc}" -eq 0 ] && [ -f "${APK_DEBUG}" ]; then
    local size
    size=$(du -h "${APK_DEBUG}" | awk '{print $1}')
    ok "构建成功 · ${APK_DEBUG} (${size})"
    return 0
  fi
  err "构建失败（退出码 ${rc}）"
  return 1
}

do_install() {
  local adb
  adb=$(locate_adb) || { err "未找到 adb"; return 1; }

  if [ ! -f "${APK_DEBUG}" ]; then
    warn "还没有构建产物，先构建一次"
    do_build || { pause; return 1; }
  fi

  local dev
  dev=$(select_device "${adb}" 2>/dev/null) || { warn "未选择设备"; pause; return 1; }
  [ -z "${dev:-}" ] && { warn "未选择设备"; pause; return 1; }

  step "安装到 ${dev}"
  "${adb}" -s "${dev}" install -r "${APK_DEBUG}"
  if [ $? -ne 0 ]; then
    err "安装失败"
    pause
    return 1
  fi
  ok "安装成功"

  if confirm "立即启动 App？" "Y"; then
    # 用 monkey 拉起 LAUNCHER，避免 am start 打非 exported Activity 被拒
    "${adb}" -s "${dev}" shell monkey -p "${APP_ID}" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
    ok "已拉起 ${APP_ID}"
  fi

  echo "" >&2
  echo "${DIM}端口默认 8788。设备内可直接用 adb 验证（先在 App 里启动代理）：${NC}" >&2
  echo "  ${CYAN}adb -s ${dev} shell curl -s -x http://127.0.0.1:8788 -H 'X-Proxy-Token: TOKEN' https://example.com -o /dev/null -w '%{http_code}\\n'${NC}" >&2
  echo "${DIM}或把设备端口转发到本机再用本机 curl：${NC}" >&2
  echo "  ${CYAN}adb -s ${dev} forward tcp:18788 tcp:8788${NC}" >&2
  echo "  ${CYAN}curl -s -H 'X-Forward-Target: https://example.com' -H 'X-Proxy-Token: TOKEN' http://127.0.0.1:18788/${NC}" >&2
  echo "" >&2
  pause
  return 0
}

do_logcat() {
  local adb
  adb=$(locate_adb) || { err "未找到 adb"; return 1; }
  local dev
  dev=$(select_device "${adb}" 2>/dev/null) || { warn "未选择设备"; pause; return 1; }
  [ -z "${dev:-}" ] && { warn "未选择设备"; pause; return 1; }

  step "实时日志（Ctrl-C 退出）"
  echo "${DIM}筛选项：${APP_ID} / AndroidRuntime / ActivityManager${NC}" >&2
  "${adb}" -s "${dev}" logcat -v color \
    "${APP_ID}":V AndroidRuntime:E ActivityManager:I "*:S" 2>/dev/null \
    | grep --line-buffered -Ei "${APP_ID}|freeapi|proxy|foreground" || true
  return 0
}

do_foreground_check() {
  local adb
  adb=$(locate_adb) || { err "未找到 adb"; return 1; }
  local dev
  dev=$(select_device "${adb}" 2>/dev/null) || { warn "未选择设备"; pause; return 1; }
  [ -z "${dev:-}" ] && { warn "未选择设备"; pause; return 1; }

  step "前台服务 / 存活状态自检（${dev}）"

  echo "${DIM}—— 进程 ——${NC}" >&2
  "${adb}" -s "${dev}" shell pidof "${APP_ID}" || echo "  (无进程)" >&2

  echo "${DIM}—— 前台服务类型（0x40000000 = SPECIAL_USE）——${NC}" >&2
  "${adb}" -s "${dev}" shell dumpsys activity services "${APP_ID}" 2>/dev/null \
    | grep -E "isForeground|foregroundServiceType|ServiceRecord" | head -20 >&2

  echo "${DIM}—— 唤醒锁 ——${NC}" >&2
  "${adb}" -s "${dev}" shell dumpsys power 2>/dev/null \
    | grep -i "FreeAPIProxy" | head -10 >&2

  echo "${DIM}—— 电池优化白名单 ——${NC}" >&2
  "${adb}" -s "${dev}" shell dumpsys deviceidle whitelist 2>/dev/null \
    | grep -i "${APP_ID}" >&2 || echo "  (未在白名单)" >&2

  echo "${DIM}—— 守护闹钟 ——${NC}" >&2
  "${adb}" -s "${dev}" shell dumpsys alarm 2>/dev/null \
    | grep -i "${APP_ID}" | head -10 >&2 || true

  echo "" >&2
  pause
  return 0
}

do_clean() {
  step "清理构建产物"
  ./gradlew clean
  if [ $? -eq 0 ]; then ok "已清理"; else err "清理失败"; fi
  pause
  return 0
}

# ------------------------------------------------------------------ 主流程

main_menu() {
  ensure_env

  while true; do
    echo "" >&2
    echo "${BOLD}FreeAPIProxy · Android 出口代理${NC}" >&2
    echo "${DIM}交互式构建 / 安装 / 诊断${NC}" >&2

    local choice
    choice=$(choose_option "请选择操作" 1 \
      "构建并安装到设备" \
      "仅构建 Debug APK" \
      "安装已构建的 APK（含 curl 验证示例）" \
      "实时查看 App 日志" \
      "前台服务 / 存活状态自检" \
      "清理构建产物" \
      "退出")

    case "${choice}" in
      1) do_build && do_install || true ;;
      2) do_build || true; pause ;;
      3) do_install || true ;;
      4) do_logcat || true ;;
      5) do_foreground_check || true ;;
      6) do_clean || true ;;
      7) echo "拜拜 👋" >&2; exit 0 ;;
    esac
  done
}

main_menu
