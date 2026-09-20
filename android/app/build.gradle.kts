plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.freeapi.proxy"
    // 注意：compileSdk 35 需要 AGP >= 8.6；当前用 8.2.2，故锁 34。
    compileSdk = 34

    defaultConfig {
        applicationId = "com.freeapi.proxy"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        // 充电线控制器的 BLE 广播名，必须与固件（autoLine 的 src/main.cpp）一致。
        // 只是**出厂默认值**：用户可能在「充电线 → 连接设置」里改（固件是自烧的，名字不保证）。
        buildConfigField("String", "BLE_NAME", "\"USB-Switch\"")
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    // fetch 兜底路径：需要精确控制 Accept-Encoding 以避免透明解压
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // 充电线控制：BLE 的回调式 API 被包成 suspend 函数，需要协程运行时
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    // ZeroTier 用户态协议栈：入站监听 ZeroTier 虚拟网络用（com.zerotier.sockets）
    implementation(project(":libzt"))

    // CablePolicy 是刻意写成纯 Kotlin 的（不碰任何 Android API），
    // 好让「跨午夜时段」这类边界能被单测钉住 —— 加了测试依赖才算真兑现这句注释。
    testImplementation("junit:junit:4.13.2")
}
