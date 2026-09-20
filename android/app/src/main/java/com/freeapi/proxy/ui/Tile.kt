package com.freeapi.proxy.ui

/**
 * Tab 页面的刷新契约。
 *
 * MainActivity 维护一个 1 秒的 ticker，每跳只刷新**当前可见**的那个页面（避免让 5 个页面
 * 每秒都做一遍无用功）。页面在 onViewCreated 里无需注册，MainActivity 在切换 Tab 时
 * 通过 `as? Tile` 直接调用即可。
 *
 * 不实现本接口的页面（如配置页）表示"不需要周期性刷新"——配置页只在进入时绑定一次，
 * 免得用户正在输入端口、切走看一眼状态再切回来，输入的内容被覆盖。
 */
interface Tile {
    fun refresh()
}
