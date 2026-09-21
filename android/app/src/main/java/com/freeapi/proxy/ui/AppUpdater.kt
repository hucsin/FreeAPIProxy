package com.freeapi.proxy.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.FileProvider
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 设置页「自动更新」的下载 + 安装逻辑（纯工具，不做任何视图）。
 *
 * ## 流程
 * 0. 入口先检查「安装未知应用」权限 —— [canInstall]（内部走
 *    `canRequestPackageInstalls`，Android 8(API26) 及以上都有）。
 *    没有就去设备「设置 → 允许安装未知应用」页，等用户放行 *返回后* 再继续下载。
 * 1. [downloadAsync] 用 OkHttp 从 `https://dl.izao.cc/proxy.apk?<时间戳>` 拉最新包，
 *    逐块写盘并回调进度（每 64KB 一次）。
 * 2. [install] 用 FileProvider 把私有目录里的 APK 交给系统安装器（ACTION_VIEW）。
 *
 * 为什么 URL 要带时间戳：服务端同一个路径上热替换安装包，加一个每次都在变的查询串，
 * 既是缓存破坏（避免返回旧的、已失效的缓存包），也方便服务端统计下载次数。
 */
object AppUpdater {

    private const val TAG = "AppUpdater"

    /** 更新包来源。真正的下载地址会把当前毫秒时间戳拼在 `?` 后面。 */
    const val BASE_URL = "https://dl.izao.cc/proxy.apk"

    /** 每次下载都重新取一次时间戳 → 一个全新的、各次不同的 URL。 */
    fun urlWithTimestamp(): String =
        "$BASE_URL?${System.currentTimeMillis()}"

    /**
     * 是否具备「安装未知应用」权限。
     * minSdk=26，所以 [PackageManager.canRequestPackageInstalls] 一定可用。
     */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            context.packageManager.canRequestPackageInstalls()

    /**
     * 下载目标文件。优先放外部私有下载目录（用户可在文件管理器里看到），
     * 兜底放缓存目录；两个路径都已在 file_paths.xml 里对 FileProvider 放开。
     */
    fun targetFile(context: Context): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: context.cacheDir
        return File(dir, "freeapi-update.apk")
    }

    // ------------------------------------------------------------------ 下载

    /** 正在进行的 OkHttp Call，供「取消」按钮用。 */
    private var inflight: Call? = null

    fun cancel() {
        inflight?.cancel()
        inflight = null
    }

    // ------------------------------------------------------------------ 权限返回续传

    /**
     * 「权限缺失 → 跳到系统设置」这段的恢复标记。
     * 入口处若发现没装 App 权限，先置位，再交给宿主跳设置；等用户从设置页回来后，
     * 设置页的 onResume 会调 [shouldResumeAfterPermission] 决定是否续传。
     */
    private var resumeAfterPermission = false

    fun requestResumeAfterPermission() {
        resumeAfterPermission = true
    }

    fun shouldResumeAfterPermission(): Boolean = resumeAfterPermission

    fun markResumeHandled() {
        resumeAfterPermission = false
    }

    /**
     * 异步下载，回调都在**主线程**触发。
     * [onProgress] 参数：`-1` 表示总长度未知（转圈），`0..100` 为真实百分比。
     */
    fun downloadAsync(
        context: Context,
        onProgress: (Int) -> Unit,
        onSuccess: (File) -> Unit,
        onError: (String) -> Unit,
    ) {
        inflight?.cancel()
        val main = Handler(Looper.getMainLooper())

        // readTimeout=0：无读超时。APK 可能几十 MB，慢速移动网上长空档会被超时误杀。
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        val request = Request.Builder().url(urlWithTimestamp()).build()
        val call = client.newCall(request)
        inflight = call

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (call.isCanceled()) return
                main.post { onError("网络错误：${e.message ?: "连接失败"}") }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body
                if (body == null) {
                    response.close()
                    main.post { onError("服务器无响应内容") }
                    return
                }
                if (!response.isSuccessful) {
                    val code = response.code
                    body.close()
                    main.post { onError("下载失败（HTTP $code）") }
                    return
                }

                val total = body.contentLength()
                var written = 0L
                val file = targetFile(context)
                try {
                    file.parentFile?.mkdirs()
                    // 总长未知时先切到不确定进度（转圈）
                    if (total <= 0) main.post { onProgress(-1) }
                    body.byteStream().use { input ->
                        FileOutputStream(file).use { out ->
                            val buf = ByteArray(64 * 1024)
                            var n: Int
                            while (input.read(buf).also { n = it } != -1) {
                                out.write(buf, 0, n)
                                written += n
                                if (total > 0) {
                                    val pct = (written * 100 / total).toInt().coerceIn(0, 100)
                                    main.post { onProgress(pct) }
                                }
                            }
                        }
                    }
                    main.post { onSuccess(file) }
                } catch (e: Exception) {
                    if (call.isCanceled()) return
                    Log.e(TAG, "download write failed", e)
                    main.post { onError("写入失败：${e.message ?: "I/O 错误"}") }
                } finally {
                    body.close()
                }
            }
        })
    }

    // ------------------------------------------------------------------ 安装

    /**
     * 用系统安装器安装下载好的 APK。
     * 需要 `canRequestPackageInstalls` 已授权（入口处已检查）；
     * 这里通过 FileProvider 临时暴露文件并授予读权限，交给系统包安装界面处理。
     */
    fun install(context: Context, file: File) {
        if (!file.exists() || file.length() == 0L) return
        val uri: Uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
        }
        runCatching { context.startActivity(intent) }
            .onFailure { Log.e(TAG, "startActivity(install) failed", it) }
    }
}