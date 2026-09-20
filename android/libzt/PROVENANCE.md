# libzt 模块来源与版本

本目录是 **预编译封装模块**，不含 libzt 的 C/C++ 源码。以下记录它到底从哪来、怎么来的，
以便日后重建或审计。

## 上游

| 项 | 值 |
|---|---|
| 仓库 | https://github.com/zerotier/libzt |
| 分支 | `main` |
| commit | `a707ea6ae0910efdc1125d04758c411e2e9ea4f9` |
| 提交时间 | 2024-11-07T18:24:35Z |
| 许可 | Business Source License 1.1（正文见 `LICENSE.txt`） |

> 该仓库自 2024-11 起无新提交，且**没有任何 Release**（无预编译 AAR 可用），
> 因此 `libzt.so` 必须自行交叉编译。重建方法见 `build-native.sh`。

## 子模块（编译必需，且版本必须对齐）

libzt 的 `main` 分支把三个依赖放在 git submodule 里，**用 GitHub 的 zip 下载会得到空目录**，
必须按下列 commit 单独取：

| 路径 | 仓库 | commit |
|---|---|---|
| `ext/ZeroTierOne` | `zerotier/ZeroTierOne` | `c53c6bd9c320ea839c525972d8afba448a58606e` |
| `ext/lwip` | `joseph-henry/lwip` | `32708c0a8b140efb545cc35101ee5fdeca6d6489` |
| `ext/lwip-contrib` | `joseph-henry/lwip-contrib` | `4fd612c9c72dfcd1db6618bd59c1a17d9f5b55f8` |

> `ext/concurrentqueue` 不是 submodule，zip 里自带，无需单独取。

## 本模块的文件构成

| 路径 | 来源 |
|---|---|
| `src/main/java/com/zerotier/sockets/*.java` | 复制自上游 `src/bindings/java/com/zerotier/sockets/`（11 个文件，纯 Java，无 Android 依赖） |
| `src/main/jniLibs/arm64-v8a/libzt.so` | 由 `build-native.sh` 交叉编译产出（**含下方补丁**），已 `llvm-strip --strip-unneeded` |
| `LICENSE.txt` | 复制自上游根目录 |

`libzt.so` 导出的 JNI 符号形如 `Java_com_zerotier_sockets_ZeroTierNative_*`（共 98 个），
与 `ZeroTierNative.java` 声明的 native 方法一一对应。

## 编译参数（已在 build-native.sh 中固化）

```
CMAKE_TOOLCHAIN_FILE = <NDK>/build/cmake/android.toolchain.cmake
ANDROID_ABI          = arm64-v8a
ANDROID_PLATFORM     = android-26
CMAKE_BUILD_TYPE     = Release
ZTS_ENABLE_JAVA      = ON
```

工具链：NDK `25.2.9519653` + CMake `3.22.1`（**不能用 CMake 4.x**）。

## ⚠️ 对上游源码的补丁（重编必读）

本模块的 `libzt.so` **不是原样编译**的，打了一个补丁：

| 文件 | 函数 | 上游行为 | 补丁后 |
|---|---|---|---|
| `src/bindings/java/JavaSockets.cxx` | `java_detach_from_thread()` | 无条件 `jvm->DetachCurrentThread()` | 空实现（不再 detach） |

**为什么必须打**：该函数的两个调用点 `Java_..._zts_1node_1stop()` / `_zts_1node_1free()`
都是 JNI 入口，从 **Java 线程**进入。Java 线程本来就附着在 JVM 上，把它 detach 掉会
让 ART 立刻 fatal：

```
Thread[1,tid=<pid>,...,"main"] attempting to detach while still running code
Runtime aborting...
```

用户可见现象：**在 App 里点「停止节点」→ 整个进程闪退**（前台代理服务一起没）。
之所以看不到 libzt 的函数帧，是因为这不是 native 崩溃，而是 ART 检测到「正在执行
Java 代码的线程被 detach」后主动 abort。

libzt 自己 `AttachCurrentThread()` 起的回调线程由 `Events.cpp` 的 `sendToUser()` 自行
detach，不经过该函数，所以改成空实现是安全的。

**重建时务必带上这个补丁**，否则闪退会复现。补丁以注释形式留在源码里，
搜索 `【FreeAPIProxy 补丁】` 即可定位。

## 三个必须记住的坑

1. **CMake 版本上限**。libzt 的 `CMakeLists.txt` 首行是
   `cmake_minimum_required(VERSION 3.0)`，CMake 4.x 已移除对 3.5 以下兼容版本的支持，
   会直接报错拒绝配置。必须用 3.22.x 这类 3.x 版本。

2. **`find_package(JNI)` 会失败，但不影响构建**。在 macOS 上 CMake 报
   `Could NOT find JNI (missing: JAVA_INCLUDE_PATH2)`，于是
   `JavaSockets.cxx` 里的 `#include <jni.h>` 靠的是 **NDK sysroot 自带的 `jni.h`**
   （`<NDK>/toolchains/llvm/prebuilt/<host>/sysroot/usr/include/jni.h`）解析成功。
   所以 JNI 那行报错可以忽略——**不要**据此以为绑定没编进去，
   用 `llvm-nm -D` 数 `Java_com_zerotier_*` 符号才作准。

3. **`zts_node_stop()` / `zts_node_free()` 必须带补丁**。见上节——不带补丁重编，
   「点停止节点 App 闪退」会立刻复现。

## 依赖情况

`libzt.so` 只依赖 Android 系统库，**不需要额外打包 `libc++_shared.so`**：

```
NEEDED  libandroid.so  liblog.so  libm.so  libdl.so  libc.so
```

strip 前 17 MB，strip 后约 2.0 MB。
