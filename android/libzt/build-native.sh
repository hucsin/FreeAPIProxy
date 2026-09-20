#!/usr/bin/env bash
#
# libzt.so 交叉编译脚本（Android）
#
# 用途：从 libzt 源码树重新生成 android/libzt/src/main/jniLibs/<abi>/libzt.so
#
# 设计取向与仓库里其它脚本一致：实时探测工具链 + 菜单选择，不用 CLI 参数。
#
# --- 解释器守卫 -------------------------------------------------------------
# 本脚本含 bash 专有语法（数组、`+=`、`local -a`、`[[ ]]`），POSIX sh 无法解析。
# 注意：macOS 的 /bin/sh 本身就是 bash 3.2，以 sh 名启动时进入 POSIX 模式，
# 但 `BASH_VERSION` 仍有值——所以判据必须是「模式」而非「身份」。
#
if [ -z "${BASH_VERSION:-}" ] || shopt -qo posix 2>/dev/null; then
    if [ -f "$0" ] && command -v bash >/dev/null 2>&1; then
        unset POSIXLY_CORRECT
        exec bash "$0" "$@"
    fi
    echo "error: 本脚本需要 bash 运行（例：bash build-native.sh），但未找到 bash。" >&2
    exit 1
fi

set -uo pipefail

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly PROJ_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
readonly REPO_DIR="$(cd "${PROJ_DIR}/.." && pwd)"
readonly JNILIBS_DIR="${SCRIPT_DIR}/src/main/jniLibs"

# libzt 源码树候选位置
LIBSRC_CANDIDATES=(
    "${REPO_DIR}/libzt-main"
    "${REPO_DIR}/third_party/libzt"
    "${REPO_DIR}/third_party/libzt-main"
)

# ---------------------------------------------------------------------------
# 输出助手：渲染一律走 stderr，只有「选项号 / 路径」这类结果走 stdout，
# 这样调用方可以用 var=$(...) 捕获而不会被菜单文字污染。
# ---------------------------------------------------------------------------
c_reset=$'\033[0m'; c_bold=$'\033[1m'; c_dim=$'\033[2m'
c_red=$'\033[31m'; c_grn=$'\033[32m'; c_yel=$'\033[33m'; c_cyn=$'\033[36m'

hr()  { printf '%s\n' "────────────────────────────────────────────────────────────" >&2; }
say() { printf '%s\n' "$*" >&2; }
ok()  { printf '  %s✓%s %s\n' "${c_grn}" "${c_reset}" "$*" >&2; }
no()  { printf '  %s✗%s %s\n' "${c_red}" "${c_reset}" "$*" >&2; }
warn(){ printf '  %s!%s %s\n' "${c_yel}" "${c_reset}" "$*" >&2; }
info(){ printf '  %s·%s %s\n' "${c_dim}" "${c_reset}" "$*" >&2; }

banner() {
    say ""
    printf '%s%s%s\n' "${c_bold}${c_cyn}" "  libzt.so 交叉编译" "${c_reset}" >&2
    printf '%s\n' "  FreeAPIProxy · Android 端 ZeroTier 支持" >&2
    hr
}

# ---------------------------------------------------------------------------
# 探测
# ---------------------------------------------------------------------------
detect_sdk() {
    local candidates=(
        "${ANDROID_HOME:-}"
        "${ANDROID_SDK_ROOT:-}"
        "${HOME}/Library/Android/sdk"
        "${HOME}/Android/Sdk"
    )
    local d
    for d in "${candidates[@]}"; do
        [ -n "${d}" ] && [ -d "${d}/ndk" ] && { printf '%s' "${d}"; return 0; }
    done
    return 1
}

# 返回可用的 NDK 版本列表（每行一个）
list_ndks() {
    local sdk="$1"
    find "${sdk}/ndk" -maxdepth 1 -mindepth 1 -type d 2>/dev/null \
        | xargs -n1 basename 2>/dev/null | sort -V
}

# 返回可用的 CMake 版本列表
list_cmakes() {
    local sdk="$1"
    find "${sdk}/cmake" -maxdepth 1 -mindepth 1 -type d 2>/dev/null \
        | xargs -n1 basename 2>/dev/null | sort -V
}

# libzt 依赖的三个子模块是否就位
check_libzt_src() {
    local src="$1"
    [ -f "${src}/CMakeLists.txt" ] || return 1
    [ -n "$(ls -A "${src}/ext/ZeroTierOne" 2>/dev/null)" ] || return 2
    [ -n "$(ls -A "${src}/ext/lwip" 2>/dev/null)" ] || return 3
    [ -n "$(ls -A "${src}/ext/lwip-contrib" 2>/dev/null)" ] || return 4
    return 0
}

# ---------------------------------------------------------------------------
# 菜单：把漂亮的菜单渲染到 stderr，把选项号写到 stdout
#   $1 标题   $2.. 选项
# ---------------------------------------------------------------------------
choose() {
    local title="$1"; shift
    local -a opts=("$@")
    local i reply

    say "" >&2
    printf '%s%s%s\n' "${c_bold}" "${title}" "${c_reset}" >&2
    for i in "${!opts[@]}"; do
        printf '  %s%2d)%s %s\n' "${c_cyn}" "$((i + 1))" "${c_reset}" "${opts[$i]}" >&2
    done
    while true; do
        printf '  %s请选择 [1-%d]: %s' "${c_dim}" "${#opts[@]}" "${c_reset}" >&2
        read -r reply || return 1
        if [[ "${reply}" =~ ^[0-9]+$ ]] && (( reply >= 1 && reply <= ${#opts[@]} )); then
            printf '%d' "${reply}"
            return 0
        fi
        warn "无效输入：${reply}（请输入 1-${#opts[@]}）"
    done
}

# ---------------------------------------------------------------------------
# 编译单个 ABI
# ---------------------------------------------------------------------------
build_abi() {
    local sdk="$1" ndk_ver="$2" cmake_ver="$3" src="$4" abi="$5"
    local ndk="${sdk}/ndk/${ndk_ver}"
    local cmake="${sdk}/cmake/${cmake_ver}/bin/cmake"
    local ninja="${sdk}/cmake/${cmake_ver}/bin/ninja"
    local build_dir="/tmp/libzt-build/${abi}"
    local out="${build_dir}/lib/libzt.so"

    say "" >&2
    printf '%s[%s]%s 交叉编译中…\n' "${c_bold}" "${abi}" "${c_reset}" >&2

    rm -rf "${build_dir}"
    mkdir -p "${build_dir}"

    if ! "${cmake}" -S "${src}" -B "${build_dir}" -G Ninja \
        -DCMAKE_MAKE_PROGRAM="${ninja}" \
        -DCMAKE_TOOLCHAIN_FILE="${ndk}/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="${abi}" \
        -DANDROID_PLATFORM=android-26 \
        -DCMAKE_BUILD_TYPE=Release \
        -DZTS_ENABLE_JAVA=ON > "${build_dir}/configure.log" 2>&1; then
        no "${abi}：configure 失败，日志 → ${build_dir}/configure.log"
        tail -20 "${build_dir}/configure.log" >&2
        return 1
    fi

    if ! "${cmake}" --build "${build_dir}" -j > "${build_dir}/build.log" 2>&1; then
        no "${abi}：编译失败，日志 → ${build_dir}/build.log"
        grep -E 'error|Error' "${build_dir}/build.log" | head -20 >&2
        return 1
    fi

    [ -f "${out}" ] || { no "${abi}：未产出 libzt.so"; return 1; }

    # strip：17 MB → 约 2 MB
    local strip="${ndk}/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-strip"
    [ -x "${strip}" ] || strip="${ndk}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
    if [ -x "${strip}" ]; then
        "${strip}" --strip-unneeded "${out}" 2>/dev/null \
            && info "已 strip 调试符号" \
            || warn "strip 失败（不影响功能，只是包体偏大）"
    fi

    return 0
}

install_so() {
    local build_dir="$1" abi="$2" dst_file="${JNILIBS_DIR}/${abi}/libzt.so"
    mkdir -p "${JNILIBS_DIR}/${abi}"
    cp "${build_dir}/lib/libzt.so" "${dst_file}" || return 1
    local size
    size=$(du -h "${dst_file}" | awk '{print $1}')
    ok "${abi}/libzt.so  →  ${size}"
    return 0
}

# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------
banner

SDK="$(detect_sdk)" || {
    no "未找到 Android SDK"
    info "可设置 ANDROID_HOME 后重试，例如："
    info "  export ANDROID_HOME=\$HOME/Library/Android/sdk"
    exit 1
}
ok "Android SDK：${SDK}"

# 注意：macOS 的 /bin/bash 是 3.2.57，**没有 mapfile / readarray**（bash 4.0+ 才有），
# 且 bash 3.2 在 `set -u` 下展开空数组会报 unbound variable。
# 因此下面一律用「管道 + head -1」挑版本，不引入数组。
#
# 版本偏好：先 3.22.x（与上游 pkg/android 里硬编码的 3.22.1 对齐，也是本项目验证过的版本），
# 再退到任意 3.x。**4.x 一律排除**——libzt 的 cmake_minimum_required 声明的是 VERSION 3.0，
# CMake 4 已移除对 3.5 以下兼容版本的支持，会直接拒绝配置。
CMAKE_VER="$(list_cmakes "${SDK}" | grep -E '^3\.22\.' 2>/dev/null | sort -Vr 2>/dev/null | head -1 2>/dev/null)"
if [ -z "${CMAKE_VER}" ]; then
    CMAKE_VER="$(list_cmakes "${SDK}" | grep -E '^3\.' 2>/dev/null | sort -Vr 2>/dev/null | head -1 2>/dev/null)"
fi
if [ -z "${CMAKE_VER}" ]; then
    warn "未找到 3.x 的 CMake"
    warn "CMake 4.x 会拒绝 libzt（其 cmake_minimum_required 声明的是 VERSION 3.0）"
    info "已安装：$(list_cmakes "${SDK}" | tr '\n' ' ' 2>/dev/null)"
    info "用 sdkmanager 安装：sdkmanager 'cmake;3.22.1'"
    exit 1
fi
ok "CMake：${CMAKE_VER}"

# NDK：优先 25.x（与上游工程硬编码的 25.1 最接近），否则退到 26/27 里最高的
NDK_VER="$(list_ndks "${SDK}" | grep -E '^25\.' 2>/dev/null | sort -Vr 2>/dev/null | head -1 2>/dev/null)"
if [ -z "${NDK_VER}" ]; then
    NDK_VER="$(list_ndks "${SDK}" | grep -E '^(26|27)\.' 2>/dev/null | sort -Vr 2>/dev/null | head -1 2>/dev/null)"
fi
if [ -z "${NDK_VER}" ]; then
    no "未找到合适的 NDK（需要 25.x / 26.x / 27.x）"
    info "已安装：$(list_ndks "${SDK}" | tr '\n' ' ' 2>/dev/null)"
    exit 1
fi
ok "NDK：${NDK_VER}"

# libzt 源码树
LIBSRC=""
for d in "${LIBSRC_CANDIDATES[@]}"; do
    if check_libzt_src "${d}" >/dev/null 2>&1; then LIBSRC="${d}"; break; fi
done
if [ -z "${LIBSRC}" ]; then
    no "未找到可用的 libzt 源码树（需含 ext/ZeroTierOne、ext/lwip、ext/lwip-contrib）"
    for d in "${LIBSRC_CANDIDATES[@]}"; do
        info "查找过：${d}"
    done
    exit 1
fi
ok "libzt 源码：${LIBSRC}"

# JAVA_HOME（JNI 头文件；NDK sysroot 自带 jni.h，此处仅作兜底）
if [ -z "${JAVA_HOME:-}" ]; then
    JAVA_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
    export JAVA_HOME
fi
[ -n "${JAVA_HOME:-}" ] && info "JAVA_HOME：${JAVA_HOME}"

while true; do
    ACTION="$(choose "选择操作" \
        "编译并安装到模块 jniLibs" \
        "仅编译（输出留在 /tmp，不动模块）" \
        "查看当前 jniLibs 产物" \
        "退出")" || exit 0

    case "${ACTION}" in
        1|2)
            ABICHOICE="$(choose "选择目标 ABI" \
                "arm64-v8a（推荐 · 覆盖绝大多数真机，模拟器同为 arm64）" \
                "x86_64（模拟器）" \
                "armeabi-v7a（老 32 位设备）" \
                "全部三个 ABI")" || continue

            case "${ABICHOICE}" in
                1) ABIS=("arm64-v8a") ;;
                2) ABIS=("x86_64") ;;
                3) ABIS=("armeabi-v7a") ;;
                4) ABIS=("arm64-v8a" "x86_64" "armeabi-v7a") ;;
            esac

            FAILED=0
            for abi in "${ABIS[@]}"; do
                if ! build_abi "${SDK}" "${NDK_VER}" "${CMAKE_VER}" "${LIBSRC}" "${abi}"; then
                    FAILED=1
                    continue
                fi
                if [ "${ACTION}" = "1" ]; then
                    install_so "/tmp/libzt-build/${abi}" "${abi}" || FAILED=1
                else
                    ok "${abi}：/tmp/libzt-build/${abi}/lib/libzt.so ($(du -h "/tmp/libzt-build/${abi}/lib/libzt.so" | awk '{print $1}'))"
                fi
            done

            hr
            if [ "${FAILED}" -eq 0 ]; then
                ok "全部完成"
            else
                no "存在失败项，请查看上方日志"
            fi
            ;;

        3)
            hr
            if [ -d "${JNILIBS_DIR}" ]; then
                found=0
                for abi_dir in "${JNILIBS_DIR}"/*/; do
                    [ -d "${abi_dir}" ] || continue
                    so="${abi_dir}libzt.so"
                    if [ -f "${so}" ]; then
                        ok "$(basename "${abi_dir}")/libzt.so  $(du -h "${so}" | awk '{print $1}')"
                        found=1
                    fi
                done
                [ "${found}" -eq 1 ] || warn "jniLibs 下暂无 libzt.so"
            else
                warn "jniLibs 目录不存在：${JNILIBS_DIR}"
            fi
            ;;

        4) say "拜拜 👋" ; exit 0 ;;
    esac
done
