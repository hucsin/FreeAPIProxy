package com.freeapi.proxy

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.freeapi.proxy.databinding.ActivityMainBinding
import com.freeapi.proxy.cable.BlePermissions
import com.freeapi.proxy.cable.CablePrefs
import com.freeapi.proxy.service.CableService
import com.freeapi.proxy.service.ProxyService
import com.freeapi.proxy.service.VendorSettings
import com.freeapi.proxy.ui.CableFragment
import com.freeapi.proxy.ui.CableHost
import com.freeapi.proxy.ui.DashboardFragment
import com.freeapi.proxy.ui.LogFragment
import com.freeapi.proxy.ui.PermissionHost
import com.freeapi.proxy.ui.SettingsFragment
import com.freeapi.proxy.ui.Tile
import com.freeapi.proxy.ui.ZeroTierFragment
import com.freeapi.proxy.zerotier.ZeroTierRuntime

/**
 * 单 Activity + 多 Tab 容器。
 *
 * Tab 条是**自绘**的，没有引入 Material 库 —— 当前主题是 AppCompat，
 * Material 组件（TabLayout 等）会要求 `Theme.MaterialComponents.*`，改主题又会连带
 * 改变现有所有 Button / Switch 的观感。自绘零依赖、零主题风险，且 5 个页签用不着
 * 滑动切换。
 *
 * Fragment 用 add + show/hide 管理：全部一次性 add，切换只改可见性，因此来回切页
 * 不会重建视图、不会丢失滚动位置。
 */
class MainActivity : AppCompatActivity(), PermissionHost, CableHost {

    private lateinit var b: ActivityMainBinding
    private val handler = Handler(Looper.getMainLooper())

    private data class Tab(val title: String, val icon: Int, val factory: () -> Fragment)

    /**
     * 五个页签，ZeroTier 放**正中间**（第 3 个）。
     *
     * 排布逻辑：两边各是"入口"和"结果" —— 左边是「仪表盘 / 设置」（把代理跑起来并配好），
     * 右边是「充电线 / 日志」（辅助与排查），ZeroTier 作为最常用的对外入口居中。
     * 「配置」与「权限」已合并为「设置」。
     */
    private val tabs = listOf(
        Tab("仪表盘", R.drawable.ic_tab_dashboard) { DashboardFragment() },
        Tab("设置", R.drawable.ic_tab_config) { SettingsFragment() },
        Tab("ZeroTier", R.drawable.ic_tab_zerotier) { ZeroTierFragment() },
        Tab("充电线", R.drawable.ic_tab_cable) { CableFragment() },
        Tab("日志", R.drawable.ic_tab_log) { LogFragment() },
    )

    private val fragments = ArrayList<Fragment>()
    private val icons = ArrayList<ImageView>()
    private val labels = ArrayList<TextView>()
    private var current = 0

    /** 每秒只刷新**当前可见**的页面，其余页面在被切到时才刷 */
    private val ticker = object : Runnable {
        override fun run() {
            refreshCurrent()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        ProxyRuntime.loadConfig(this)
        // 载入已持久化的 ZeroTier 网络列表/开关，让 ZeroTier 页首帧就有正确数据
        // （原生库加载失败的结论也会在这里被一次性确定）
        ZeroTierRuntime.prepare(this)
        // 按上次的运行意图把服务拉回来（详见方法注释）
        restoreRunningIntent()
        current = savedInstanceState?.getInt(KEY_TAB) ?: 0

        buildTabs()
        ensureFragments()
        selectTab(current.coerceIn(0, tabs.size - 1))
    }

    override fun onResume() {
        super.onResume()
        refreshCurrent()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB, current)
    }

    /**
     * 按上次的运行意图恢复代理服务。
     *
     * 为什么非得在这里做：MIUI / HyperOS 上「划掉任务」「一键清理」等价于 `force-stop` ——
     * 杀进程 + 撤销**全部** AlarmManager 闹钟 + 清通知 + 标记 stopped。
     * 此后 App 收不到任何广播或闹钟，守护巡检、`START_STICKY`、开机自启统统失效，
     * **系统不会再给任何自愈机会**。唯一可靠的时机，就是用户自己再次打开 App 的这一刻。
     *
     * 只恢复**用户上次明确开着**的情况：主动点过「停止代理」的，意图已是 false，
     * 不会被自作主张拉起来。
     */
    private fun restoreRunningIntent() {
        restoreProxyIfDesired()
        restoreCableIfDesired()
    }

    private fun restoreProxyIfDesired() {
        if (!RunState.proxyDesired(this)) return
        // 服务还活着（例如 AOSP 上划掉任务并不杀服务）就别重复拉起
        if (ProxyService.isAlive) return

        runCatching { ProxyService.start(this) }
            .onSuccess { ProxyRuntime.log.info("检测到上次处于运行状态，已自动恢复代理服务") }
            .onFailure {
                ProxyRuntime.log.warn(
                    "自动恢复代理服务失败，请手动点「启动代理」：" +
                        "${it.javaClass.simpleName}: ${it.message}"
                )
            }
    }

    /**
     * 充电线服务同理恢复。
     *
     * 这里**不需要**单独的"运行意图"存储 —— `CablePrefs.autoEnabled` 本身就是意图：
     * 自动控制开着，服务就该在跑；关掉自动控制时服务本来就会退出。
     */
    private fun restoreCableIfDesired() {
        val ctx = applicationContext
        if (!CablePrefs(ctx).autoEnabled) return
        if (CableService.isAlive) return
        CableService.checkNow(ctx)
        ProxyRuntime.log.info("检测到自动充电控制处于开启状态，已自动恢复充电线服务")
    }

    // ------------------------------------------------------------------ Tab 条

    private fun buildTabs() {
        b.tabBar.removeAllViews()
        icons.clear()
        labels.clear()

        tabs.forEachIndexed { index, tab ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
                setPadding(0, dp(6), 0, dp(6))
                isClickable = true
            }

            val icon = ImageView(this).apply {
                setImageResource(tab.icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
            }

            val label = TextView(this).apply {
                text = tab.title
                textSize = 10f
                gravity = Gravity.CENTER
                maxLines = 1
                setPadding(0, dp(3), 0, 0)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

            item.addView(icon)
            item.addView(label)
            item.setOnClickListener { selectTab(index) }

            b.tabBar.addView(item)
            icons.add(icon)
            labels.add(label)
        }
    }

    private fun ensureFragments() {
        if (fragments.isNotEmpty()) return
        val fm = supportFragmentManager
        val tx = fm.beginTransaction()
        tabs.forEachIndexed { index, tab ->
            val tag = "tab_$index"
            val existing = fm.findFragmentByTag(tag)
            val f = existing ?: tab.factory().also { tx.add(R.id.tabContent, it, tag) }
            fragments.add(f)
        }
        if (tx.isEmpty) tx.commit() else tx.commitNow()
    }

    private fun selectTab(index: Int) {
        if (index !in tabs.indices) return
        current = index

        val tx = supportFragmentManager.beginTransaction()
        fragments.forEachIndexed { i, f ->
            if (i == index) tx.show(f) else tx.hide(f)
        }
        tx.commit()

        updateTabStyles()
        refreshCurrent()
    }

    private fun updateTabStyles() {
        val active = color(R.color.brand)
        val idle = color(R.color.nav_unselected)
        labels.forEachIndexed { i, tv ->
            val selected = i == current
            tv.setTextColor(if (selected) active else idle)
            icons[i].imageTintList = ColorStateList.valueOf(if (selected) active else idle)
        }
    }

    private fun refreshCurrent() {
        (fragments.getOrNull(current) as? Tile)?.refresh()
    }

    // ------------------------------------------------------------------ 权限宿主

    override fun requestNotifPermission() {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF
            )
        } else {
            openAppNotificationSettings()
        }
    }

    @SuppressLint("BatteryLife")
    override fun requestBatteryWhitelist() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            toast("已在电池优化白名单中")
            return
        }
        // 厂商私有的省电策略不吃 AOSP 那套 Intent：MIUI 12+ 上
        // ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 既跳不过去、也不抛异常，
        // 于是下面那串 fallback 一个都不会触发 —— 用户点了像没反应。
        // 所以先按厂商直达，AOSP 这套降级成最后的兜底。
        if (openFirst(VendorSettings.candidates(this, VendorSettings.Page.POWER_SAVE))) return

        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:$packageName"))
        if (tryStart(direct)) return
        if (tryStart(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) return
        if (tryStart(appDetailsIntent())) return
        toast(VendorSettings.manualHint(VendorSettings.current(), VendorSettings.Page.POWER_SAVE))
    }

    override fun openPowerSaveSettings() {
        if (openFirst(VendorSettings.candidates(this, VendorSettings.Page.POWER_SAVE))) return
        if (tryStart(appDetailsIntent())) return
        toast(VendorSettings.manualHint(VendorSettings.current(), VendorSettings.Page.POWER_SAVE))
    }

    override fun openAutoStartSettings() {
        if (openFirst(VendorSettings.candidates(this, VendorSettings.Page.AUTO_START))) return
        if (tryStart(appDetailsIntent())) return
        toast(VendorSettings.manualHint(VendorSettings.current(), VendorSettings.Page.AUTO_START))
    }

    // ------------------------------------------------------------------ 充电线宿主

    /**
     * 申请蓝牙权限。
     *
     * 分版本的那套判断在 [BlePermissions] 里，这里只管"要"。
     * 与通知权限的区别：这个是**一组**权限（12+ 是 SCAN + CONNECT 两个），
     * 系统会把它们合成一个对话框，用户点一次要么全给要么全拒。
     */
    override fun requestBlePermissions() {
        val missing = BlePermissions.missing(this)
        if (missing.isEmpty()) {
            toast("蓝牙权限已授予")
            refreshCurrent()
            return
        }
        ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ_BLE)
    }

    /** 切到「日志」页签（充电线页的「看完整日志」用）。 */
    override fun openLogTab() {
        val index = tabs.indexOfFirst { it.title == "日志" }
        if (index >= 0) selectTab(index)
    }

    // ------------------------------------------------------------------ 设置页跳转

    /** 依次尝试候选 Intent，命中即返回 true。 */
    private fun openFirst(candidates: List<Intent>): Boolean {
        for (i in candidates) if (tryStart(i)) return true
        return false
    }

    /**
     * 「到底跳过去了没有」的可靠判据。
     *
     * 只判 `startActivity` 抛不抛异常是不够的 —— 国产 ROM 上「打不开但也不抛」
     * 恰恰是最常见的情况。所以对显式组件再做一次可解析性预检：包名不存在的候选
     * （别的厂商的设置页）在这里就会被筛掉，不会白跳一次。
     */
    private fun tryStart(i: Intent): Boolean {
        if (i.component != null && packageManager.resolveActivity(i, 0) == null) return false
        return runCatching {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
    }

    private fun openAppNotificationSettings() {
        val i = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        } else {
            appDetailsIntent()
        }
        safeStart(i) { toast("无法打开系统通知设置页") }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQ_NOTIF -> {
                val ok = grantResults.isNotEmpty() &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED
                toast(if (ok) "通知权限已授予" else "通知权限被拒绝，可稍后在设置里开启")
                refreshCurrent()
            }

            REQ_BLE -> {
                // 逐个权限看：12+ 上是 SCAN + CONNECT 两个，可能只给一个
                val denied = permissions.filterIndexed { i, _ ->
                    grantResults.getOrNull(i) != PackageManager.PERMISSION_GRANTED
                }
                if (denied.isEmpty()) {
                    toast("蓝牙权限已授予")
                } else {
                    val names = denied.joinToString("、") { BlePermissions.label(it) }
                    toast("未授予「$names」，充电线页无法连接设备")
                }
                refreshCurrent()
            }
        }
    }

    // ------------------------------------------------------------------ 工具

    private fun appDetailsIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:$packageName"))

    private fun safeStart(i: Intent, onFail: () -> Unit) {
        if (runCatching { startActivity(i) }.isFailure) onFail()
    }

    private fun color(id: Int): Int = ContextCompat.getColor(this, id)

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics)
            .toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        private const val REQ_NOTIF = 1001
        private const val REQ_BLE = 1002
        private const val KEY_TAB = "selected_tab"

        // 厂商设置页的候选表已迁到 service/VendorSettings.kt —— 那里同时维护
        // 「自启动」与「省电策略」两张表，并按当前厂商排序后逐个尝试。
    }
}
