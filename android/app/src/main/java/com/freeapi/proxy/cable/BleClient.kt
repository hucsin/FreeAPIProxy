package com.freeapi.proxy.cable

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 跟 USB-Switch 固件通信的 BLE 客户端（移植自 autoLine）。
 *
 * 协议（见固件 src/main.cpp）：
 * ```
 * 服务    0000ff01-...
 * ff02    Write        写文本指令 ON / OFF
 * ff03    Read,Notify  读状态，返回 "ON" / "OFF"
 * ```
 *
 * 对外是 suspend 函数，内部把回调式 BLE API 包成协程。一次调用走完
 * 「扫描 → 连接 → 写指令 → 回读确认 → 断开」，调用方不用管连接生命周期。
 *
 * **设备同时只允许一个客户端连接**，所以所有操作必须串行 —— 见 [CableService] 里的 Mutex。
 */
class BleClient(
    private val context: Context,
    /** 目标设备名。做成 lambda 是因为它可以在设置里改，不能构造时定死。 */
    private val targetName: () -> String,
) {

    class BleException(message: String) : Exception(message)

    private val adapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    fun isBluetoothOn(): Boolean = adapter?.isEnabled == true

    /**
     * 下发指令，并回读确认设备真的照做了。
     *
     * @param command  要写的文本，"ON" 或 "OFF"
     * @param expected 期望设备回报的状态；null 表示不校验
     * @return 设备实际回报的状态（"ON" / "OFF"）
     * @throws BleException 蓝牙没开 / 找不到设备 / 连接超时 / 回报与期望不符
     */
    @SuppressLint("MissingPermission")
    suspend fun sendCommand(
        command: String,
        expected: String? = null,
        timeoutMs: Long = TOTAL_TIMEOUT_MS,
    ): String {
        requireBleOn()
        val device = try {
            withTimeout(timeoutMs) { findDevice() }
        } catch (e: TimeoutCancellationException) {
            throw BleException("扫描超时，没找到设备")
        } ?: throw notFound()

        val state = try {
            withTimeout(timeoutMs) { connectSendAndRead(device, command) }
        } catch (e: TimeoutCancellationException) {
            throw BleException(
                "连接或通信超时（${timeoutMs / 1000} 秒）。设备可能已被别的客户端占用，或距离太远。"
            )
        }

        if (expected != null && state != expected) {
            throw BleException("指令 $command 已发出，但设备回报 $state（期望 $expected）")
        }
        return state
    }

    /** 只读取当前状态，不改变它。 */
    @SuppressLint("MissingPermission")
    suspend fun readState(timeoutMs: Long = TOTAL_TIMEOUT_MS): String {
        requireBleOn()
        val device = try {
            withTimeout(timeoutMs) { findDevice() }
        } catch (e: TimeoutCancellationException) {
            throw BleException("扫描超时，没找到设备")
        } ?: throw notFound()

        return try {
            withTimeout(timeoutMs) { connectSendAndRead(device, null) }
        } catch (e: TimeoutCancellationException) {
            throw BleException("连接或通信超时（${timeoutMs / 1000} 秒）")
        }
    }

    private fun requireBleOn() {
        if (!isBluetoothOn()) throw BleException("蓝牙未开启，请先在系统设置里打开蓝牙")
    }

    private fun notFound(): BleException {
        val name = targetName()
        return BleException(
            "没找到名为 \"$name\" 的设备。\n" +
                "1) 确认充电线控制器已上电\n" +
                "2) 确认没有别的手机/电脑正连着它（同时只能连一个客户端）\n" +
                "3) 确认手机在它的信号范围内\n" +
                "4) 名字对不上时，到「连接设置」改成固件实际广播的名字"
        )
    }

    // ── 扫描 ────────────────────────────────────────────────────────────

    /**
     * 按设备名找设备：先查系统里已绑定的（快，不用扫），找不到再扫。
     * 用名字而不是 MAC 地址，因为固件在不同手机上暴露的地址不一样。
     */
    @SuppressLint("MissingPermission")
    private suspend fun findDevice(): BluetoothDevice? {
        val wanted = targetName()
        adapter?.bondedDevices?.firstOrNull { it.name == wanted }?.let { return it }

        val scanner = adapter?.bluetoothLeScanner
            ?: throw BleException("这台手机不支持 BLE 扫描")

        return suspendCancellableCoroutine { cont ->
            val opHandler = Handler(Looper.getMainLooper())
            val done = AtomicBoolean(false)
            lateinit var callback: ScanCallback

            fun finish(result: Result<BluetoothDevice?>) {
                if (!done.compareAndSet(false, true)) return
                opHandler.removeCallbacksAndMessages(null)
                runCatching { scanner.stopScan(callback) }
                cont.resumeWith(result)
            }

            callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult?) {
                    val r = result ?: return
                    // 有些设备广播包里不带名字，名字要等系统解析完才有，两处都查
                    val name = r.device.name ?: r.scanRecord?.deviceName
                    if (name == wanted) finish(Result.success(r.device))
                }

                override fun onScanFailed(errorCode: Int) {
                    finish(
                        Result.failure(
                            BleException("蓝牙扫描失败（错误码 $errorCode），请确认蓝牙权限已授予")
                        )
                    )
                }
            }

            // 不设 ScanFilter：广播里没带名字的设备会被过滤掉，宁可多收几条回调自己筛
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            val started = runCatching { scanner.startScan(null, settings, callback) }
            if (started.isFailure) {
                finish(Result.failure(BleException("无法开始扫描：${started.exceptionOrNull()?.message}")))
                return@suspendCancellableCoroutine
            }

            opHandler.postDelayed({ finish(Result.success(null)) }, SCAN_MS)
            cont.invokeOnCancellation { finish(Result.success(null)) }
        }
    }

    // ── 连接 / 写入 / 回读 ──────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private suspend fun connectSendAndRead(
        device: BluetoothDevice,
        command: String?,
    ): String = suspendCancellableCoroutine { cont ->
        val opHandler = Handler(Looper.getMainLooper())
        val done = AtomicBoolean(false)
        var gatt: BluetoothGatt? = null
        var lastState: String? = null
        var wrote = false

        fun finish(result: Result<String>) {
            if (!done.compareAndSet(false, true)) return
            opHandler.removeCallbacksAndMessages(null)
            runCatching { gatt?.disconnect() }
            runCatching { gatt?.close() }
            cont.resumeWith(result)
        }

        fun fail(message: String) = finish(Result.failure(BleException(message)))

        /** 回读状态；固件改完引脚要几十毫秒才更新特征值，等一等再读。 */
        fun readStateBack(delayMs: Long) {
            opHandler.postDelayed({
                val charState = gatt?.getService(UUID_SERVICE)?.getCharacteristic(UUID_CHAR_STATE)
                if (charState == null) {
                    fail("回读时找不到状态特征值 ff03")
                } else if (!gatt!!.readCharacteristic(charState)) {
                    fail("发起状态读取失败")
                }
            }, delayMs)
        }

        fun handleStateText(text: String) {
            val trimmed = text.trim()
            lastState = trimmed
            if (trimmed.isEmpty()) fail("设备返回了空状态") else finish(Result.success(trimmed))
        }

        val callback = object : BluetoothGattCallback() {

            @SuppressLint("MissingPermission")
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED ->
                        if (!g.discoverServices()) fail("无法读取设备服务列表")

                    BluetoothProfile.STATE_DISCONNECTED -> {
                        // 已 done 说明是我们自己断的，忽略；否则是意外掉线
                        if (!done.get()) {
                            val s = lastState
                            if (wrote && s != null) {
                                finish(Result.success(s))
                            } else {
                                fail("与设备断开，且没读到状态（连接状态码 $status）")
                            }
                        }
                    }
                }
            }

            @SuppressLint("MissingPermission")
            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    fail("读取设备服务失败（$status）")
                    return
                }

                val service = g.getService(UUID_SERVICE)
                if (service == null) {
                    fail("设备里没有服务 $UUID_SERVICE，固件版本可能不匹配")
                    return
                }
                val charState = service.getCharacteristic(UUID_CHAR_STATE)
                if (charState == null) {
                    fail("设备里没有状态特征值 ff03")
                    return
                }

                if (command == null) {
                    if (!g.readCharacteristic(charState)) fail("发起状态读取失败")
                    return
                }

                val charCommand = service.getCharacteristic(UUID_CHAR_COMMAND)
                if (charCommand == null) {
                    fail("设备里没有指令特征值 ff02")
                    return
                }

                val payload = command.toByteArray(Charsets.UTF_8)
                val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeCharacteristic(
                        charCommand, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    ) == BluetoothGatt.GATT_SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    charCommand.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    @Suppress("DEPRECATION")
                    charCommand.value = payload
                    @Suppress("DEPRECATION")
                    g.writeCharacteristic(charCommand)
                }
                if (!ok) fail("指令写入请求发送失败")
            }

            override fun onCharacteristicWrite(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    fail("指令写入失败（$status）")
                    return
                }
                wrote = true
                readStateBack(READ_BACK_DELAY_MS)
            }

            // Android 13 以下走这个三参数重载
            @Deprecated("Deprecated in Java")
            @Suppress("DEPRECATION")
            override fun onCharacteristicRead(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                if (characteristic.uuid != UUID_CHAR_STATE) return
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    fail("读取状态失败（$status）")
                    return
                }
                handleStateText(characteristic.value?.toString(Charsets.UTF_8).orEmpty())
            }

            // Android 13 及以上走这个四参数重载
            override fun onCharacteristicRead(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int,
            ) {
                if (characteristic.uuid != UUID_CHAR_STATE) return
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    fail("读取状态失败（$status）")
                    return
                }
                handleStateText(value.toString(Charsets.UTF_8))
            }
        }

        // TRANSPORT_LE：只走 BLE。固件是 BLE 外设，不做经典蓝牙，
        // 指定传输层省掉一次无谓的 BR/EDR 尝试，连接更快。
        val opened = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        if (opened == null) {
            finish(Result.failure(BleException("发起连接失败")))
            return@suspendCancellableCoroutine
        }
        gatt = opened

        cont.invokeOnCancellation { finish(Result.failure(BleException("已取消"))) }
    }

    private companion object {
        val UUID_SERVICE: UUID = UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb")
        val UUID_CHAR_COMMAND: UUID = UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb")
        val UUID_CHAR_STATE: UUID = UUID.fromString("0000ff03-0000-1000-8000-00805f9b34fb")

        /** 单次扫描上限 */
        const val SCAN_MS = 8_000L

        /** 写完指令到回读之间的等待 */
        const val READ_BACK_DELAY_MS = 400L

        /** 单个阶段的超时（扫描、连接各算一次） */
        const val TOTAL_TIMEOUT_MS = 25_000L
    }
}
