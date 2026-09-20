package com.freeapi.proxy.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 厂商定制的「自启动」与「省电策略」设置页跳转。
 *
 * ### 为什么不能只用 AOSP 那套
 *
 * 保活页原本走的是 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` +
 * `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` + `ACTION_APPLICATION_DETAILS_SETTINGS`
 * 三级 fallback。问题在于 fallback 的判据是「`startActivity` 有没有抛异常」，
 * 而 **MIUI 12+ 上第一个 Intent 既跳不过去、也不抛异常** —— 于是三级 fallback
 * 一个都不会触发，用户点了按钮像没反应。
 *
 * 更根本的是：国产 ROM 的「省电策略」（MIUI 叫应用省电策略，EMUI 叫应用启动管理，
 * ColorOS/OriginOS 叫后台运行管理）是**厂商私有**的东西，AOSP 的 Doze 白名单
 * 跟它根本不是一回事。只申请 AOSP 白名单，在 MIUI 上照样会被「智能限制后台」
 * 在锁屏后掐掉网络 —— 这正是「划掉任务后服务就停」的根因。
 *
 * 所以这里按厂商直达具体 Activity，把 AOSP 那套降级成最后的兜底。
 *
 * ### 顺序策略
 *
 * **当前厂商的候选排最前**，其余依次垫后。同一台设备上只有本厂商的包名存在，
 * 别的候选会因为解析不到被跳过，所以顺序只影响命中速度、不影响正确性。
 */
object VendorSettings {

    enum class Vendor(val display: String) {
        XIAOMI("小米 / 红米"),
        HUAWEI("华为 / 荣耀"),
        OPPO("OPPO / 一加 / realme"),
        VIVO("vivo / iQOO"),
        OTHER("通用"),
    }

    /** 两类设置页：自启动管理、省电策略（后台运行限制）。 */
    enum class Page { AUTO_START, POWER_SAVE }

    /** 按 `Build.MANUFACTURER` / `Build.BRAND` 识别厂商。 */
    fun current(): Vendor {
        val s = "${Build.MANUFACTURER} ${Build.BRAND}".lowercase()
        return when {
            s.contains("xiaomi") || s.contains("redmi") || s.contains("poco") -> Vendor.XIAOMI
            s.contains("huawei") || s.contains("honor") -> Vendor.HUAWEI
            s.contains("oppo") || s.contains("oneplus") || s.contains("realme") -> Vendor.OPPO
            s.contains("vivo") || s.contains("iqoo") -> Vendor.VIVO
            else -> Vendor.OTHER
        }
    }

    // ------------------------------------------------------------------ 页面表

    private val AUTO_START: Map<Vendor, List<ComponentName>> = mapOf(
        Vendor.XIAOMI to listOf(
            cn("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        ),
        Vendor.HUAWEI to listOf(
            cn("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            cn("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
            cn("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
        ),
        Vendor.OPPO to listOf(
            cn("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            cn("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            cn("com.oplus.safecenter", "com.oplus.safecenter.startupapp.StartupAppListActivity"),
            cn("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
        ),
        Vendor.VIVO to listOf(
            cn("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            cn("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
        ),
    )

    /**
     * 省电策略页。注意各家叫法不同：
     *  - MIUI/HyperOS：「应用省电策略」，三档（无限制 / 智能限制后台 / 禁止后台运行）
     *  - EMUI/MagicOS：「应用启动管理」，需关掉「自动管理」再手开三项
     *  - ColorOS / OriginOS：后台运行管理 / 自启动
     *
     * 小米的页面**必须带 `miui.intent.action.HIDDEN_APPS_CONFIG_ACTIVITY` 与
     * `package_name` / `package_label`**，否则打开的是空白页或列表首页，
     * 不会定位到本应用 —— 见 [build]。
     */
    private val POWER_SAVE: Map<Vendor, List<ComponentName>> = mapOf(
        Vendor.XIAOMI to listOf(
            cn("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"),
            cn("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsContainerManagementActivity"),
        ),
        Vendor.HUAWEI to listOf(
            cn("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
            cn("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
        ),
        Vendor.OPPO to listOf(
            cn("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            cn("com.oplus.safecenter", "com.oplus.safecenter.startupapp.StartupAppListActivity"),
        ),
        Vendor.VIVO to listOf(
            cn("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
        ),
    )

    // ------------------------------------------------------------------ 构造

    /** 当前厂商优先 + 其它厂商垫后的候选 Intent 列表。逐个尝试，命中即止。 */
    fun candidates(ctx: Context, page: Page): List<Intent> {
        val table = if (page == Page.AUTO_START) AUTO_START else POWER_SAVE
        val me = current()

        val order = ArrayList<Vendor>(table.size)
        if (table.containsKey(me)) order += me
        for (v in table.keys) if (v != me) order += v

        val out = ArrayList<Intent>()
        for (v in order) {
            for (c in table[v].orEmpty()) {
                val i = Intent().setComponent(c)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (v == Vendor.XIAOMI && page == Page.POWER_SAVE) {
                    // MIUI 12+ 的约定：这个 action + 这两个 extra 才能定位到本应用
                    i.action = ACTION_MIUI_HIDDEN_APPS
                    i.putExtra("package_name", ctx.packageName)
                    i.putExtra("package_label", appLabel(ctx))
                }
                out += i
            }
        }
        return out
    }

    /**
     * 该厂商在「省电策略」上是否需要用户手动介入。
     * 返回 null 表示没有可靠结论（**不编造**，界面据此显示中性文案）。
     */
    fun powerSaveAdvice(v: Vendor): String? = when (v) {
        Vendor.XIAOMI -> "MIUI/HyperOS：须手动设为「无限制」"
        Vendor.HUAWEI -> "EMUI/MagicOS：须关掉「自动管理」"
        Vendor.OPPO -> "ColorOS：须允许后台运行"
        Vendor.VIVO -> "OriginOS：须允许后台运行"
        Vendor.OTHER -> null
    }

    /**
     * 界面上的兜底提示：跳不过去时告诉用户手动怎么走。
     */
    fun manualHint(v: Vendor, page: Page): String = when {
        v == Vendor.XIAOMI && page == Page.POWER_SAVE ->
            "请手动前往：设置 → 省电与电池 → 右上角齿轮 → 应用省电策略 → 选「无限制」"
        v == Vendor.XIAOMI ->
            "请手动前往：设置 → 应用 → 权限 → 自启动 → 允许本应用"
        page == Page.POWER_SAVE ->
            "请手动前往：系统设置 → 电池 → 应用省电策略 / 后台运行管理"
        else ->
            "请手动前往：系统设置 → 应用管理 → 自启动 / 后台运行 中放行"
    }

    // ------------------------------------------------------------------ 内部

    private const val ACTION_MIUI_HIDDEN_APPS = "miui.intent.action.HIDDEN_APPS_CONFIG_ACTIVITY"

    private fun cn(pkg: String, cls: String) = ComponentName(pkg, cls)

    private fun appLabel(ctx: Context): String = runCatching {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(ctx.packageName, 0)).toString()
    }.getOrDefault(ctx.packageName)
}
