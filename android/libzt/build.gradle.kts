// ---------------------------------------------------------------------------
// libzt（ZeroTier + lwIP 用户态协议栈）· Android 预编译封装模块
//
// 本模块本身**不编译 native 代码**，只做两件事：
//   1. 提供 com.zerotier.sockets 的 Java 绑定（11 个 .java，来自 libzt 源码树）
//   2. 把预编译好的 libzt.so 通过 jniLibs 打进 APK
//
// 之所以不用 AGP 的 externalNativeBuild 现场编译：
//   libzt 的 CMakeLists 写着 cmake_minimum_required(VERSION 3.0)，
//   CMake 4.x 会直接拒绝；且全量编译 ZeroTierOne core + lwIP 每次构建要数分钟。
//   因此走「离线编一次 → 预编译产物入库 → 快速日常构建」的路线。
//
// 重新生成 libzt.so：见同目录 build-native.sh
// 版本与来源：见同目录 PROVENANCE.md
// ---------------------------------------------------------------------------

plugins {
    id("com.android.library")
}

android {
    namespace = "com.zerotier.sockets"

    compileSdk = 34

    defaultConfig {
        // 与 app 模块对齐。libzt 官方工程写的是 21，此处跟随本项目。
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // jniLibs 默认路径就是 src/main/jniLibs，无需显式声明。
    // 保持 lib 不压缩对齐，让 System.loadLibrary("zt") 直接 mmap APK 内的 .so。
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}
