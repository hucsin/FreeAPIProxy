pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "FreeAPIProxy"
include(":app")
// ZeroTier 用户态协议栈（libzt）封装：Java 绑定 + 预编译 libzt.so
include(":libzt")
