# ZeroTier (libzt) 集成 + 多 Tab 改造 · 可行性分析与方案

> 调研日期：2026-09-19　目标仓库：https://github.com/zerotier/libzt
> 结论基于 libzt `main` 分支源码（最后提交 2024-11-07）实际通读，非印象判断。
>
> **更新（21:49）**：用户澄清实际需求为「App 加入 ZeroTier 网络后，**其它已加入同一网络的设备能访问本 App 监听的端口**」。
> 这是**入站（inbound）**场景，**不是**本机流量接管。结论随之变化：**libzt 完全适用，且不涉及 TLS 难题**。
> 详见下方 **第九章**，该章为最终实施方案，第二章至第七章保留作为背景与风险依据。
>
> **更新（22:00）**：**P1 已完成** —— 界面重构为微信式**底部导航**、5 个 Tab（仪表盘 / 配置 / 权限 / ZeroTier / 日志），**零新增依赖**。
> 代理核心逻辑一行未动，端到端回归通过（无/错 Token→401、OPTIONS→204+CORS、正确 Token→200）。
> ZeroTier 页当前是占位（按钮禁用），等 P2 把 AAR 接进来。

---

## 【最终结论 · 一句话】

你的场景 = **入站**。libzt 提供一个 `ZeroTierServerSocket`，让 App 直接监听在 ZeroTier 虚拟 IP 的端口上，其它 ZeroTier 设备访问该 IP:端口即可打到 App。**不需要 VpnService、不需要 TUN、不需要处理 TLS、不需要接管本机流量。**
上一轮提到的「`ZeroTierSocket` 不是 `java.net.Socket`」只在**出站走后端 TLS** 时才是问题——而你的出站仍然走系统网络（普通 `SSLSocket`），**完全不受影响**。

---


## 一、结论先行

1. **libzt 官方支持 Android**（`./build.sh android-aar`），但**没有任何预编译产物**，必须自己从源码编译 AAR。
2. **libzt 是"应用内用户态 socket 库"，不是 VPN**。它**无法接管整机流量**——JNI 层不存在 TUN fd 注入，源码里没有一处 `VpnService` / `ParcelFileDescriptor` 引用。想要"手机整体进 ZeroTier 内网"，libzt 这条路走不通。
3. **一个关键的拦路虎**：`ZeroTierSocket` **不是 `java.net.Socket` 的子类**，所以 JSSE 的 `SSLSocketFactory.createSocket(Socket, ...)` 和 OkHttp 都**无法**复用它。想让它承载 HTTPS，必须自己用 `SSLEngine` 手写 TLS 状态机。

**一句话**：如果目标是「**管理** ZeroTier 网络」（看节点 ID、加入/离开网络、看状态和对端），libzt 完全够用且干净；如果目标是「**让手机流量走 ZeroTier**」，得换技术栈，libzt 帮不上忙。

---

## 二、能力边界：libzt 能做什么、不能做什么

| 问题 | 结论 | 证据 |
|---|---|---|
| 官方支持 Android？ | ✅ 是 | `build.sh` 有 `android-aar()` 目标，走 `pkg/android` Gradle 工程，CMake 开关 `-DZTS_ENABLE_JAVA=ON` |
| 有现成 AAR 可下载？ | ❌ 没有 | `api.github.com/repos/zerotier/libzt/releases` 返回**空数组**，必须自建 |
| 需要 VPN 权限吗？ | ❌ 不需要 | 用户态 lwIP 协议栈，不走系统网络栈 |
| 能接管整机流量？ | ❌ **不能** | `JavaSockets.cxx` 全部 98 个 JNI 函数中，无任何 TUN fd 注入；无 `VpnService` 引用；文档明确"该绑定旨在提供应用层网络 API，而非实现传统 VPN 隧道接口" |
| 其他 App 能走 ZeroTier？ | ❌ 不能 | 同上。libzt 只对本进程内调用 `ZeroTierSocket` 的代码生效 |
| App 自己能访问内网？ | ✅ 能 | `ZeroTierSocket` / `ZeroTierServerSocket` / `ZeroTierDatagramSocket` 覆盖 TCP/UDP |
| 能显示节点/网络状态？ | ✅ 能 | `ZeroTierNode` 提供 `getId()` / `isOnline()` / `getIPv4Address()` / `isNetworkTransportReady()` 等 |

### 为什么会这样？

zerotier-one（官方客户端）和 libzt 是两个不同的东西：

- **zerotier-one**：跑一个系统服务，创建 TUN 虚拟网卡，改路由表 → 整机流量进虚拟网络。
- **libzt**：把 ZeroTier 协议 + lwIP 用户态协议栈链进你的进程，你调 `zts_bsd_socket()` 拿到的 fd 是 lwIP 的 fd，不是内核 fd → **只有你的进程能受益**。

官方 Android 客户端之所以能"整机 VPN"，是因为它用 `VpnService.establish()` 拿到 TUN fd 交给 **zerotier-one 的核心库**，跟 libzt 无关。

---

## 三、关键技术发现

### 3.1 Java API 面（`com.zerotier.sockets`）

从 `src/bindings/java/com/zerotier/sockets/` 读到的完整类清单：

| 类 | 对应标准库 | 用途 |
|---|---|---|
| `ZeroTierNative` | — | 98 个 JNI native 方法声明（内部用） |
| `ZeroTierNode` | — | **节点/网络生命周期，UI 主要靠它** |
| `ZeroTierSocket` | `java.net.Socket` | TCP 客户端 |
| `ZeroTierServerSocket` | `java.net.ServerSocket` | TCP 服务端 |
| `ZeroTierDatagramSocket` | `java.net.DatagramSocket` | UDP |
| `ZeroTierInputStream` / `ZeroTierOutputStream` | 同名标准类 | 流读写 |
| `ZeroTierSocketAddress` | `java.net.SocketAddress` | 地址 |
| `ZeroTierEventListener` | — | 事件回调接口 |
| `ZeroTierPeerDetails` | — | 对端详情 |
| `ZeroTierFileDescriptorSet` | — | `select()` 多路复用 |

`ZeroTierNode` 的公开方法（这基本就是 ZeroTier 管理 Tab 能做的全部）：

```java
int  start();                        int  stop();
int  initFromStorage(String path);   // 必须在 start() 之前调用
int  initSetEventHandler(ZeroTierEventListener handler);
int  initSetPort(short port);
int  join(long networkId);           int  leave(long networkId);
boolean isOnline();                  // 能否触达 root
boolean isNetworkTransportReady(long networkId);
long getId();                        // 10 位十六进制节点 ID（数字形式）
InetAddress getIPv4Address(long networkId);
String getMACAddress(long networkId);
```

### 3.2 ⚠️ 拦路虎：`ZeroTierSocket` 不是 `java.net.Socket`

源码类声明就一行（`src/bindings/java/com/zerotier/sockets/ZeroTierSocket.java`）：

```java
public class ZeroTierSocket {   // 没有 extends，没有 implements
```

它只是"Socket-like"（注释原话：*Implements Socket-like behavior over ZeroTier*），**不继承 `java.net.Socket`**。

**这直接卡死两条常规路径**：

| 想做的事 | 为什么不行 |
|---|---|
| `SSLSocketFactory.createSocket(Socket, host, port, autoClose)` 包一层 TLS | 该方法签名要求 `java.net.Socket` 参数，编译期就不通过 |
| 让 OkHttp 走 ZeroTier | OkHttp 的 transport 依赖 `SocketFactory` / `java.net.Socket` |
| Spring/Retrofit/标准 HTTP 客户端 | 同上，全部基于 `java.net` 体系 |

**唯一可行解：JSSE `SSLEngine` 手写 TLS。**
`SSLEngine` 的设计恰恰是与传输层解耦的——它只吃/吐 `ByteBuffer`，所以可以架在 `ZeroTierSocket` 的流之上。但代价是要自己实现：

- `wrap()` / `unwrap()` 状态机，处理 `BUFFER_OVERFLOW` / `BUFFER_UNDERFLOW`
- 握手阶段双向驱动、`NEED_TASK` / `NEED_WRAP` / `NEED_UNWRAP` 循环
- 应用数据的分包与重组成帧（TLS record 边界 ≠ 应用消息边界）
- `close_notify` 收尾、异常路径的清理

估算 **300–500 行**，且属于"写对了很稳、写错了偶发必现"的那种代码，需要专门构造测试。

**如果只访问内网明文 HTTP 服务**（NAS 管理页、ESP32 的 HTTP API 等），则完全不需要 TLS，成本为零。

### 3.3 官方 Android 构建工程有多老

`pkg/android/app/build.gradle` 的实际配置：

```gradle
apply plugin: 'com.android.library'
android {
    compileSdkVersion 33            // 旧式 DSL
    minSdkVersion 21
    targetSdkVersion 33
    ndkVersion '25.1.8937393'       // 硬编码 NDK 版本
    defaultConfig {
        externalNativeBuild { cmake { version '3.22.1' } }   // 硬编码 CMake 版本
        ndk { abiFilters "armeabi-v7a", "arm64-v8a", "x86", "x86_64" }  // 4 个 ABI 全编
    }
    namespace 'com.example.zerotier'
}
// 根 build.gradle: classpath 'com.android.tools.build:gradle:7.3.1'
dependencies {
    implementation 'com.android.support.constraint:constraint-layout:2.0.4'  // 旧 support 库
    androidTestImplementation 'com.android.support.test:runner:1.0.2'
}
```

要点：

- **AGP 7.3.1**，与你当前工程的 **AGP 8.2.2 不是一套**，需要独立环境编译。
- 目录里有 `.project` / `.settings/org.eclipse.buildship.core.prefs` —— Eclipse 时代的残留，说明这个工程很久没人动。
- `com.android.support` 依赖是示例残留，对库功能无用，集成时可 `exclude group: 'com.android.support'` 排掉。
- **4 个 ABI 全量交叉编译 ZeroTier + lwIP，耗时会很长**（首次可能几十分钟量级）。
- 需要 `git clone --recursive` 拉 submodule（`ext/ZeroTierOne`、`ext/lwip`、`ext/concurrentqueue`），否则 CMake 直接失败。

### 3.4 维护状态（重要风险）

| 项 | 值 |
|---|---|
| 最后提交 | **2024-11-07**（Joseph Henry，merge PR #283 rust 依赖更新） |
| 距今 | 约 **22 个月**无更新 |
| GitHub Releases | 无 |

对一个已经两年没动的 C/C++ 跨平台工程，用当前 NDK/CMake 编译**大概率需要打补丁**（编译告警升 error 是头号杀手，常见于 `-Werror` 和新版 clang 的组合）。

### 3.5 许可

- 仓库 README 声明：**BSL 1.1**（Business Source License）。
- README 原文警示：*"Certain types of commercial use such as building closed-source apps and devices based on ZeroTier ... require a commercial license."*
- 但源码文件头写的是：

  ```
  Use of this software is governed by the Business Source License ...
  Change Date: 2026-01-01
  On the date above, ... governed by version 2.0 of the Apache License.
  ```

  **Change Date 是 2026-01-01，而今天是 2026-09-19——按 BSL 规则，该日之后对应代码已转为 Apache 2.0。**
  ⚠️ 但 README 的文字没跟着更新。**这条需要你自行向 ZeroTier 确认，或至少以 `LICENSE.txt` 正文为准**，别只信 README。

  - 自用 / 内部使用 / 非商业用途：BSL 明确允许，无顾虑。
  - 要闭源分发给第三方：务必先确认许可状态。

---

## 四、三条集成路线

| | 路线 A：只做管理面板 | 路线 B：管理 + App 内直连内网 | 路线 C：管理 + 整机 VPN |
|---|---|---|---|
| **技术基础** | libzt（`ZeroTierNode`） | libzt（`ZeroTierNode` + `ZeroTierSocket`） | **VpnService + zerotier-one core（与 libzt 无关）** |
| **能看节点ID/加入网络/状态** | ✅ | ✅ | ✅ |
| **App 自己访问内网** | ❌ | ✅（明文 HTTP 零成本；HTTPS 需自研 `SSLEngine` 300–500 行） | ✅ |
| **其他 App / 整机走 ZeroTier** | ❌ | ❌ | ✅ |
| **需要 VPN 权限** | 否 | 否 | 是 |
| **依赖 libzt AAR** | 是 | 是 | **否**——需要 zerotier-one 的 Android 核心库，另一套构建体系 |
| **工作量** | 小 | 中～大（取决于要不要 HTTPS） | 大（几乎等于重做官方客户端） |
| **风险** | 低 | 中 | 高 |

### 路线 A 明细（**推荐起点**）

用 `ZeroTierNode` 驱动一个管理 Tab：

- 节点 ID（`getId()`，格式化成 10 位十六进制）
- 在线状态（`isOnline()`，配合 `ZeroTierEventListener` 做实时刷新）
- 加入 / 离开网络（`join(networkId)` / `leave(networkId)`），`networkId` 是 16 位十六进制，UI 侧要转 `long`
- 显示分配到的虚拟 IP（`getIPv4Address(networkId)`）与 MAC
- 传输就绪状态（`isNetworkTransportReady(networkId)`）
- 持久化：`initFromStorage(filesDir)` + 缓存开关，让节点身份跨重启保持
- 生命周期：吃现有 `ProxyService` 的前台服务与唤醒锁体系，**不用新开 Service**

这条路**完全不碰** VPN 权限、不碰路由、不碰 TLS，风险可控，且当天就能看到东西。

### 路线 C 的现实提醒

如果你最终想要的是"装了这个 App，手机就进了 ZeroTier 内网"，**最省事的方案是用官方 ZeroTier 客户端**，而不是自己造。自研等于把官方客户端的活儿重做一遍：TUN 读写、路由表管理、DNS、分应用代理、开机自启、电量优化……这是月级工作量，不是小时级。

---

## 五、编译 libzt AAR 的步骤与风险

```bash
# 1) 拉源码（必须 --recursive，否则 submodule 缺失导致 CMake 失败）
git clone --recursive https://github.com/zerotier/libzt.git
cd libzt

# 2) 环境准备
#    NDK   25.1.8937393   （工程内硬编码）
#    CMake 3.22.1         （工程内硬编码）
#    ANDROID_HOME         （build.sh 里 macOS 写死 ~/Library/Android/sdk）
#    JDK   17

# 3) 构建
./build.sh android-aar

# 4) 产物落点
#    pkg/android/app/build/outputs/aar/libzt-release.aar
#    同时拷贝到 dist/android-any-android-release/libzt-release.aar
```

**风险清单**：

| 风险 | 说明 | 应对 |
|---|---|---|
| 编译报错 | 工程 22 个月未更新，新版 clang 的告警可能升级为错误 | 定位后局部打补丁；必要时降 NDK 版本 |
| CMake 版本不匹配 | 硬编码 3.22.1，本机 SDK 里可能没有 | `sdkmanager` 单独装该版本 |
| submodule 拉取慢/失败 | `ext/ZeroTierOne`、`ext/lwip` 都不小 | 配代理或分步 `git submodule update --init` |
| 编译耗时长 | 4 ABI × （ZeroTier 协议栈 + lwIP） | 先只编 `arm64-v8a` 验证通过再补全，可大幅省时 |
| AAR 与 AndroidX 工程冲突 | 官方库依赖旧 `com.android.support` | 集成时 `exclude group: 'com.android.support'` |
| 体积增长 | AAR 含原生库，4 ABI 会让 APK 明显变大 | 只带 `arm64-v8a`（现代设备主流），`abiFilters` 控制 |

**建议**：先只编 `arm64-v8a` 跑通端到端（能 `join` 网络、能拿到虚拟 IP），再决定是否补全 ABI。

---

## 六、多 Tab 改造设计

### 6.1 现状

当前是**单页纵向滚动**（`activity_main.xml` 498 行），`strings.xml` 里已有 4 个分区标题：

| 现有分区 | 内容 |
|---|---|
| `sec_status` 运行状态 | 运行时长、连接数、流量、启停按钮 |
| `sec_config` 配置 | 端口、token、模式、并发、超时 |
| `sec_permission` 权限与保活 | 通知权限、电池白名单、自启动 |
| `sec_log` 运行日志 | 实时日志 |

### 6.2 Tab 划分建议（5 个）

| # | Tab | 内容 | 来源 |
|---|---|---|---|
| 1 | **仪表盘** | 运行状态摘要 + 启停/重启主控 + 关键计数（连接数、流量、运行时长） | 现 `sec_status` |
| 2 | **配置** | 端口、token、模式、并发、超时、UA 覆盖 | 现 `sec_config` |
| 3 | **权限** | 通知/电池/自启动 + 保活自检 | 现 `sec_permission` |
| 4 | **ZeroTier** | 节点 ID、在线状态、加入/离开网络、虚拟 IP、对端列表 | 新增 |
| 5 | **日志** | 完整日志 + 清空/复制 | 现 `sec_log` |

把「状态」和「控制」合并进「仪表盘」是有意的——手机上一眼看到"在跑 / 没在跑"并立刻能切换，是最常用的路径，不该埋进二级页面。

### 6.3 实现方式（已定：零依赖自绘 + 底部导航）✅ 已实现

用户后续要求改为**微信式底部导航**，最终落地如下：

| | 方式一：Material `TabLayout` + `ViewPager2` | 方式二：零依赖自绘 ← **已采用** |
|---|---|---|
| 依赖 | 新增 `material` + `viewpager2` | **不新增任何依赖** |
| 包体影响 | 约 +400～600 KB | 0 |
| 主题要求 | 需 `Theme.MaterialComponents.*` | 沿用现有 AppCompat 主题，视觉零扰动 |
| 图标 | 需另备资源 | 自绘 5 个矢量图（房子 / 滑块 / 盾牌 / 地球仪 / 文档） |
| 位置 | 顶部 | **底部**（微信式） |

**为什么不用 Material**：当前主题是 `Theme.AppCompat.DayNight.NoActionBar`，而 Material 组件（`TabLayout`、`MaterialButton` 等）要求 `Theme.MaterialComponents.*` 父主题。换主题会让现有所有 `<Button>` 被自动替换成 MaterialButton、`<Switch>` 换样式——为一个 Tab 条让全 App 视觉改版，不划算。

**实际实现要点**：

- Tab 条：`LinearLayout + ImageView + TextView` 在代码内生成，置于底部（上分隔线、白底、6dp 内边距）；选中态图标与文字同为 `@color/brand`，未选中为 `@color/nav_unselected`（#7F7F7F，对齐微信底部 tab 的灰）。
- 页面容器：`FrameLayout`，页面用 `add + show/hide` 而**非** `replace` —— 切页只改可见性，不重建视图，滚动位置与输入内容都保留。
- 刷新：`MainActivity` 每秒只刷新当前可见页（`ui/Tile` 接口）；配置页故意不实现 `Tile`。
- 落盘文件：`activity_main.xml`（Tab 容器）+ 5 个 `fragment_*.xml` + `ui/` 下 5 个 Fragment + `ui/Tile.kt`；`MainActivity` 同时充当 `PermissionHost`，代 Fragment 发起 `startActivity` 与权限请求。


---

## 七、建议执行顺序

| 阶段 | 内容 | 依赖 | 风险 |
|---|---|---|---|
| **P1** | 多 Tab 框架重构（纯 UI，不碰核心逻辑），ZeroTier Tab 先放占位页 | 无 | 低 |
| **P2** | 编译 libzt AAR（先 arm64-v8a），集成进工程 | 需独立编译环境 | 中 |
| **P3** | ZeroTier Tab 接 `ZeroTierNode`：节点 ID / 在线状态 / 加入离开 / 虚拟 IP | P1+P2 | 低 |
| **P4**（可选） | 打通流量：明文 HTTP 直连内网（零成本）；HTTPS 需自研 `SSLEngine` | P3 | 中～高 |
| **P5**（可选） | 整机 VPN：换技术栈为 VpnService + zerotier-one core | — | 高 |

**P1 可以立刻做，且不浪费**——不管 ZeroTier 最终走哪条路，Tab 容器都要。

---

## 八、待确认

1. **ZeroTier 要做到哪一步**——只管理（P1–P3）／管理+App 能访问内网（+P4）／整机 VPN（P5）？
2. **Tab 划分**是否按上面 5 个来？
3. **Tab 实现方式**——Material 标准件，还是零依赖自绘？
4. **AAR 只带 arm64-v8a 够吗**（你的目标设备是什么）？

---

# 九、入站实施方案（最终方案）

> 用户澄清的实际需求：**App 启动后加入 ZeroTier 网络，其它已加入同一网络的设备，能访问本 App 监听的端口。**

## 9.1 为什么 libzt 恰好适用

这是**入站**场景，三个 API 事实让它成立：

| # | 事实 | 源码证据 | 意义 |
|---|---|---|---|
| 1 | **有 `ZeroTierServerSocket`** | `src/bindings/java/com/zerotier/sockets/ZeroTierServerSocket.java` | 可以监听在 ZeroTier 虚拟网络上，接受远端连接 |
| 2 | **`ZeroTierInputStream extends InputStream`** | 类声明：`public class ZeroTierInputStream extends InputStream` | **现有代理的 IO 代码零改造复用** |
| 3 | **出站仍走系统网络** | — | 上游 HTTPS 用普通 `SSLSocket`，**完全不受「`ZeroTierSocket` 不是 `java.net.Socket`」影响** |

第 3 条是关键：上一轮发现的「拦路虎」只在**出站需要 TLS 且想走 ZeroTier** 时才成立。而你的架构里，**入站走 ZeroTier、出站走系统网络**，天然避开。

**结论：不需要 VpnService、不需要 TUN、不需要 SSLEngine、不需要接管本机流量。**

## 9.2 架构

```
其他电脑 / 手机（各自加入同一 ZeroTier 网络）
        │
        │  HTTP → http://10.147.20.5:8788
        ▼
  ┌─────────────────────────┐
  │   ZeroTier 虚拟网络      │   （P2P，加密）
  └─────────────────────────┘
        │
        ▼
╔══════════════════════════════════════════════════════╗
║ 我们的 App（ZeroTier 网络里的一个成员）                ║
║                                                      ║
║   ZeroTierNode.join(networkId)                       ║
║            ↓                                         ║
║   ZeroTierServerSocket(8788)     ← 入站（ZeroTier）   ║
║            ↓ accept()                                ║
║   ZeroTierSocket → InputStream ✅ 标准 InputStream    ║
║            ↓                                         ║
║   ┌──────────────────────────────┐                   ║
║   │ ProxyEngine.handleOne()      │  ← 现有逻辑复用    ║
║   │ （鉴权 / 头过滤 / 分帧 / 中继）│                   ║
║   └──────────────────────────────┘                   ║
║            ↓                                         ║
║   出站：java.net.Socket / SSLSocket  ← 系统网络       ║
╚══════════════════════════════════════════════════════╝
        ↓
    上游 API（HTTPS，走运营商/WiFi）
```

**同端口双监听**：`jave.net.ServerSocket(0.0.0.0:8788)` 覆盖 WiFi/局域网/USB 访问，`ZeroTierServerSocket(10.147.20.5:8788)` 覆盖 ZeroTier 网络访问。两者绑定的是不同协议栈（内核 vs lwIP），**同端口不冲突**。

## 9.3 需要改的代码

改动集中在「接受连接」这一层，**代理核心逻辑（`Http.kt` / `Relay.kt` / `Body.kt` / `BufReader.kt` / `Tunnel.kt` / `RawForwarder.kt` / `FetchForwarder.kt`）全部不动**。

**第一步：抽出统一抽象**（新增 `core/Inbound.kt`）

```kotlin
/** 入站连接抽象：屏蔽 java.net.Socket 与 ZeroTierSocket 的差异。 */
class InboundConn(
    val input: InputStream,
    val output: OutputStream,
    val remoteLabel: String,
    private val closer: () -> Unit,
) {
    fun close() { runCatching { closer() } }
}

/** 监听器抽象。 */
interface InboundListener {
    val label: String            // "socket" | "zerotier"
    fun accept(): InboundConn    // 阻塞
    fun close()
}
```

**第二步：两个实现**

- `SysListener`：包 `java.net.ServerSocket`，行为与现在完全一致
- `ZtListener`：包 `ZeroTierServerSocket`

**第三步：改 `ProxyEngine`**

- `start()`：持有 `List<InboundListener>` 而非单个 `ServerSocket`
- `serve(listener)`：每个 listener 起一个 accept 线程
- `handleOne()` / `Tunnel.handle()`：入参由 `Socket` 改为 `InboundConn`

**第四步：新增 `zerotier/` 模块**

| 文件 | 职责 |
|---|---|
| `ZeroTierRuntime.kt` | 进程内单例：节点生命周期、网络列表、状态快照 |
| `ZeroTierNodeHolder.kt` | 封装 `initFromStorage` / `start` / `join` / `leave` / 状态查询 |
| `ZtListener.kt` | `ZeroTierServerSocket` 的 `InboundListener` 实现 |

**第五步：UI**（见第六章的 Tab 设计，ZeroTier Tab）

## 9.4 ⚠️ 六个必须遵守的约束（libzt 的源码坑）

### 坑 1：绝对不能对 `ZeroTierSocket` 调 `setSoTimeout()`

`ZeroTierInputStream.read(byte[], int, int)` 的实际实现：

```java
int retval = ZeroTierNative.zts_bsd_read_offset(zfd, destBuffer, offset, numBytes);
if ((retval == 0) | (retval == -104) /* EINTR, from SO_RCVTIMEO */) {
    return -1;      // ← 返回 -1
}
```

`InputStream` 契约里 **`-1` 表示 EOF（对端关闭）**。而 `-104` 是 `EINTR`——**由 `SO_RCVTIMEO` 超时产生**。

**后果**：一旦设了 `setSoTimeout(60000)`，60 秒没数据 → 返回 `-1` → `Relay.pump()` 判定"对端断开" → **把一个健康的 keep-alive / SSE 长连接当成正常结束而关掉**。

**对策**：`ZtListener` 对接受的 socket **必须保持 `soTimeout = 0`（无限等待）**，超时控制只依赖应用层自己的读循环。这也是必须走 `InboundConn` 抽象的原因之一——`SysListener` 可以有 socket 超时，`ZtListener` 不能，差异在抽象层内部消化。

### 坑 2：不要用单字节 `read()`

```java
public int read() throws IOException {
    byte[] buf = new byte[1];
    int retval = ZeroTierNative.zts_bsd_read(zfd, buf);
    ...
    return buf[0];      // ← byte 是有符号的！
}
```

`byte` 在 Java 里是**有符号**的，读到 `0x80`–`0xFF`（UTF-8 中文、二进制体里到处都是）会返回**负数**，违反 `InputStream.read()` 必须返回 0–255 或 -1 的契约。

**对策**：读取一律走 `read(byte[], int, int)`（该重载返回的是**长度**，无此问题）。现有 `BufReader` 用的正是带 offset 的重载，**天然安全**——这也是「复用现有 IO 代码」的另一个好处。

### 坑 3（❗️ 本节最重要）：`ZeroTierServerSocket` 不可用；且早于 `_node` 构造的 `join()` 会 SIGSEGV

> **本节初稿的建议是错的，以下是实测修正后的结论。**

#### 3a. `ZeroTierServerSocket` 的所有构造函数都绑不上 IPv4

初稿建议「用四参构造显式绑定到虚拟 IP」——**实测必然抛 IOException**。

原因：`ZeroTierServerSocket` 的**所有**构造函数都用 `ZTS_AF_INET6` 建 socket，而 `bind(InetAddress)` 里在 `Inet4Address && _family != ZTS_AF_INET` 时直接抛异常。两者自相矛盾，任何显式绑 IPv4 的用法都不可用。

**对策**：绕开封装，用裸 socket 自己走完，并绑通配地址：

```kotlin
val fd = ZeroTierSocket(ZTS_AF_INET, ZTS_SOCK_STREAM, 0)
fd.setReuseAddress(true)
fd.bind(port)          // 0.0.0.0，不依赖"网络已就绪"
fd.listen(backlog)
```

绑 `0.0.0.0` 比绑具体虚拟 IP 更好：网络后加入、虚拟 IP 重新分配都会自动覆盖，监听器不用重建。（实测内核侧与 lwIP 侧同端口共存，互不冲突。）

#### 3b. `zts_node_start()` 之后立刻 `zts_net_join()` 会 SIGSEGV

`NodeService::run()` 内部的顺序是致命的：

```cpp
NodeService::NodeService() : _node((Node*)0), _run(false) { }

NodeService::ReasonForTermination NodeService::run() {
    _run = true;                  // 第 215 行：先置"运行中"
    ...                           // 建目录、读身份文件（磁盘 IO）
    _node = new Node(...);        // 第 253 行：节点对象才被构造
}
```

而 `ACQUIRE_SERVICE` 的守卫**只看 `isRunning()`（就是读 `_run`）**。于是在这两行之间调用 `zts_net_join()`：守卫**放行**，`NodeService::join()` 里 `_node->join(...)` 解引用尚未构造的对象 → `pthread_mutex_lock` 拿到野地址 → **SIGSEGV**。

实测崩溃栈（`ndk-stack` 符号化）：

```
#00 pthread_mutex_lock+16
#01 ZeroTier::Mutex::lock() const               Mutex.hpp:43
#02 ZeroTier::NodeService::join(unsigned long)  NodeService.cpp:1217
#03 zts_net_join                                Controls.cpp:451
```

**同一个 API 两种表现，全看时机**：

| 调用时机 | `_run` | 守卫 | 结果 |
|---|---|---|---|
| `start()` 后立刻 | false | 挡住 | 干净返回 `-2`（ZTS_ERR_SERVICE） |
| `start()` 后数秒内 | true，但 `_node` 仍为 null | **放行** | **SIGSEGV** |

**对策**：调 `join()` / `leave()` 前先过安全闸，用 `zts_node_get_id()` 当探针：

```kotlin
private fun nodeObjectReady(n: ZeroTierNode): Boolean {
    val id = runCatching { n.getId() }.getOrDefault(0L)
    return id > 0L && id < (1L shl 40)     // 40 位节点地址区间
}
```

可靠的原因是 `NodeService::getNodeId()` 空指针安全：

```cpp
uint64_t NodeService::getNodeId() {
    Mutex::Lock _lr(_run_m);
    if (! _run) { return 0x0; }
    return _node ? _node->address() : 0x0;   // ← 有 null 检查
}
```

判据不能简单写"非零"：服务未运行时该接口返回 `ZTS_ERR_SERVICE(-2)`，被提升成 `uint64` 是个巨大值。

**危险面只有 `join` / `leave`**。其余查询接口（`networkIsReady` / `addrIsAssigned` / `getMACAddress` / `getFirstAssignedAddr`）只锁 `_nets_m` 读 `_nets`，是 `NodeService` 自己的成员，**不碰 `_node`**。因此状态刷新路径无需设防。

#### 3c. 推论：持久化网络的自动重加入必须"排队 + 重试"

因为 3b，**不能**在 `startNode()` 里对持久化网络一次性 join——那正是稳定踩中崩溃窗口的写法（早期版本表现为日志里两条固定的 `加入网络 xxx 失败：返回 -2`，且永不补做）。

正确做法是排队，由监督线程每 4 秒重试，每次动手前先过安全闸。详见 `README.md` 的「ZeroTier 入站」章节。

### 坑 4（❗️ 会闪退）：`ZeroTierNode.stop()` 会 detach 调用它的 Java 线程

**现象**：在 App 里点「停止节点」→ **整个进程立刻闪退**，连前台代理服务一起没。

**根因在上游 JNI 包装**（`src/bindings/java/JavaSockets.cxx`）：

```cpp
JNIEXPORT jint JNICALL ..._zts_1node_1stop(JNIEnv* jenv, jclass clazz)
{
    int res = zts_node_stop();
    java_detach_from_thread();     // ← 无条件 jvm->DetachCurrentThread()
    return res;
}
```

`java_detach_from_thread()` 的实现就是裸的 `jvm->DetachCurrentThread()`。而它的两个调用点
（`zts_node_stop` / `zts_node_free`）都是 **JNI 入口 —— 从 Java 线程进来的**。Java 线程本来
就附着在 JVM 上，把它 detach 掉会让 ART 立刻 fatal：

```
Thread[1,tid=<pid>,...,"main"] attempting to detach while still running code
Runtime aborting...
```

**注意迷惑性**：这不是 native 崩溃，而是 ART 主动 abort，所以崩溃栈里**看不到任何 libzt
的函数帧**，只有 `ZeroTierFragment$$ExternalSyntheticLambda0.onClick` —— 很容易误判成 UI 层问题。

**修法**：把 `java_detach_from_thread()` 改成空实现（libzt 自己 `AttachCurrentThread()` 起的
回调线程由 `Events.cpp` 的 `sendToUser()` 自行 detach，不经过该函数，所以是安全的），
然后重新编译 `libzt.so`。补丁以注释形式留在源码里，搜索 `【FreeAPIProxy 补丁】` 可定位；
细节见 `libzt/PROVENANCE.md`。

**连带影响**：崩溃会连带杀掉前台服务。`START_STICKY` 能在 1 秒后把服务拉起来，但
ZeroTier 节点不会自动恢复（除非开了「代理启动时一并启动节点」），所以用户会觉得
「重启 App 之后代理访问不了了」。

### 坑 5：监听挂载也会撞初始化窗口，别把它算成「绑定失败」

`zts_node_start()` 之后的那一两秒里 lwIP 还没就绪，此时 `bind(0.0.0.0:8788)` 必然失败，
报 `Error while connecting to remote host (-2)` —— 和坑 3b 的 join 是**同一个窗口**。

所以 `syncListener()` 里必须先过和 `join` 同一道安全闸（`nodeObjectReady`），**没就绪就直接
返回、不消耗退避窗口**。否则节点起来后监听要白等整整 60 秒（实测 23:18:07 失败 →
23:19:08 才挂上，正好 61 秒），期间从 ZeroTier 进来的流量全丢，日志里还留一条误导性的
「监听器创建失败」。加上安全闸后实测 **4 秒**就挂上了。

### 坑 6（❗️ 用户体感最差）：节点 UDP 端口默认随机，重启后对端要 4 分钟才重新找到你

这条是**真机**才暴露出来的，模拟器上永远看不到——因为没有第二个设备去访问它。

`libzt` 的主 UDP 端口默认 `0`，而 0 的语义是"随机挑"（`NodeService::run()` 里在
`[20000, 45500]` 之间取随机值，最多试 256 次）。于是每次 App / 节点重启，本节点的
**源端口都会变**，真机实测漂移链：

```
40208  →  27159  →  29267
```

后果不是"丢几个包"，而是**对端必须自己重新发现路径**：电脑侧 `zerotier-cli peers` 里
手机节点会先掉成 `RELAY`（`lat=-1`，无路径），必须等重新协商完才回到 `DIRECT`。
实测这段重建耗时 **244 秒**；而**同一时刻** WiFi 地址（`192.168.31.69:8788`）**3 秒**
就恢复了。4 分钟远超任何人愿意等的时长，所以用户结论必然是"一直不通"。

| 观测点 | 实测值 |
|---|---|
| 电脑侧该 peer 的 `lastRX`（force-stop 重开后） | 停在**上一个进程时代**（229 秒前），而 `lastTX` 持续刷新 → 新节点从未回过包 |
| 手机 `/proc/net/udp` 中 FreeAPI 的 socket | 绑在 `29267`，**对端仍在往 `27159` 发** |
| 修前 ZeroTier 路径恢复 | **244 秒** |
| 同期 WiFi 路径恢复 | **3 秒** |

> 取证细节：`/proc/net/udp` 的 **uid 列是十进制、端口列是十六进制**，搞反会得出
> "App 根本没有 UDP socket" 的错误结论（本项目的排查里真踩过这个坑）。

**修法**——启动前固定，一行：

```kotlin
// ZeroTierRuntime.startNode() 内，initFromStorage 之后、start() 之前
val nodePort = pickNodeUdpPort()          // 9993 → 9994 → 9995，全占用则返回 0
requestedNodePort = nodePort
n.initSetPort(nodePort.toShort())
```

`zts_init_set_port()` 属 `ACQUIRE_SERVICE_OFFLINE()` 系列，**必须在 `zts_node_start()` 之前**
调用（服务一旦运行就返回 `ZTS_ERR_SERVICE`）。

⚠️ **必须配 fallback**：端口被占用时 libzt 不会退让，而是把节点判成不可恢复错误
（`_fatalErrorMessage = "cannot bind to local control interface port"`）——**连节点都起不来**。
所以先探测（Java 侧 `DatagramSocket` 试绑，与 libzt 共用同一个内核 UDP 端口空间），
候选全被占才退回 0。

**UI 要如实显示这个区别**：`9993（固定）` 用绿色，`xxxxx（随机）` 用橙色并附一句说明 ——
后者就是"重启后要等一两分钟"的预警。同时新增「ZeroTier 入站」计数与最近一次时刻，
把「路径没通」和「鉴权拒了」彻底分开：前者要从网络层查，后者只需去配置页填 Token。

## 9.5 前置条件（ZeroTier Central 侧，必须在 UI 里讲清）

App 之外还要做的配置，缺一个就连不通：

1. 在 [my.zerotier.com](https://my.zerotier.com) 创建网络，记下 **16 位 Network ID**（如 `8056c2e21c000001`）。
2. 网络需要有 Managed Route（通常默认给 `10.147.20.0/24`）——这是各成员拿到虚拟 IP 的来源。
3. **授权成员**：默认是 Private 网络，新加入的节点需要在 Central 里勾选 `Auth`，否则拿不到虚拟 IP。
4. **其它设备**（电脑 / 手机）也要加入同一网络并被授权——电脑装 `zerotier-one`，手机装官方 App，或用同样的 zerotier-one。
5. 访问地址 = `http://<本 App 的虚拟 IP>:<代理端口>`。UI 里要把这个地址**直接显示出来并可复制**，省得用户自己算。

> 首次接入的常见卡点：① 忘了授权 → 一直 `isNetworkTransportReady() == false`；② 双方不在同一网络；③ 手机侧被 ROM 冻结导致节点掉线（复用现有的保活体系即可）。

## 9.6 实施步骤

| 阶段 | 内容 | 依赖 | 风险 | 状态 |
|---|---|---|---|---|
| **P1** | **多 Tab 框架重构**（底部导航 5 页，核心逻辑不动） | 无 | 低 | ✅ 已完成 |
| **P2** | 编译 libzt AAR（只编 `arm64-v8a`），以预编译模块 `android/libzt/` 并入 | NDK 25.2.9519653 / CMake 3.22.1 | 中 | ✅ 已完成（CMake 必须 3.x，4.x 拒编） |
| **P3** | `InboundConn` / `InboundListener` 抽象改造，`ProxyEngine` 支持多 listener | P1 | 中 | ✅ 已完成 |
| **P4** | `ZeroTierRuntime` + `ZeroTierNode` 接入：join / 状态 / 虚拟 IP | P2+P3 | 低 | ✅ 已完成（含 3b 安全闸 + 排队重试） |
| **P5** | ZeroTier Tab UI：节点 ID、在线状态、网络列表、**可复制的访问地址** | P4 | 低 | ✅ 已完成 |
| **P6** | 端到端验证：真机 + 另一台 ZeroTier 设备访问代理端口 | P5 | — | ✅ **已打通**（真机 MI 9 SE → 电脑 macOS，返回 401 = TCP 建连成功、请求到达 App）。修复过程暴露坑 6（端口漂移，路径重建 244 秒），已修 |
| **P7** | 节点 UDP 端口固定（坑 6）+ 状态如实化 + logcat 双通道日志 | P6 | 低 | ✅ 已完成（`pickNodeUdpPort` + UI 端口/入站显示 + `ProxyLog` 镜像 logcat） |

## 9.7 兜底方案（已不需要）

原计划在 libzt 编译失败时退回到「装官方 ZeroTier 客户端 + 普通 `ServerSocket` 监听 `0.0.0.0`」。**libzt 已成功编译并跑通**（节点在线、入站监听挂载、持久化重加入均验证通过），故该兜底路径不再需要。

保留其结论备查：因为 `0.0.0.0` 会监听包括 TUN 网卡在内的所有接口，若手机装官方客户端并加入网络，从 ZeroTier 网络来的包经内核正常投递，**现有代码零改动也能通**——代价是要多装一个 App，且虚拟 IP 由官方客户端持有。
**收益**：零 native 开发、零编译风险、立刻可用。

这也意味着：**P3 的 listener 抽象不是必需项**——如果接受"装两个 App"，现有代码原样就能满足你的需求，只是多一个管理入口。所以 P2 能不能成功，其实不阻塞你的最终目标。

