package com.freeapi.proxy.cable

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * 蓝牙相关运行时权限随系统版本变化，集中在这里判断，别处不要自己拼数组。
 *
 * ```
 * Android 12(API31) 起：BLUETOOTH_SCAN + BLUETOOTH_CONNECT
 *                        （清单里声明了 neverForLocation，所以不必再要定位权限）
 * Android 11 及以下：    扫描 BLE 必须有 ACCESS_FINE_LOCATION
 * ```
 * `BLUETOOTH` / `BLUETOOTH_ADMIN` 是安装即授予的普通权限，不需要运行时申请。
 *
 * 通知权限不在这里 —— 本 App 的通知权限属于"代理保活"那条线，已由权限页单独处理。
 */
object BlePermissions {

    fun required(): List<String> {
        val list = ArrayList<String>(2)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list += Manifest.permission.BLUETOOTH_SCAN
            list += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            list += Manifest.permission.ACCESS_FINE_LOCATION
        }
        return list
    }

    fun missing(context: Context): List<String> = required().filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }

    fun allGranted(context: Context): Boolean = missing(context).isEmpty()

    /** 权限的中文名，报错时给用户看。 */
    fun label(permission: String): String = when (permission) {
        Manifest.permission.BLUETOOTH_SCAN -> "扫描附近蓝牙设备"
        Manifest.permission.BLUETOOTH_CONNECT -> "连接蓝牙设备"
        Manifest.permission.ACCESS_FINE_LOCATION -> "定位（Android 11 及以下扫描蓝牙必需）"
        else -> permission
    }
}
