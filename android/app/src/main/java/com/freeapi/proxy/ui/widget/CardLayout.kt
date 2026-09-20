package com.freeapi.proxy.ui.widget

import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.freeapi.proxy.R

/**
 * 统一卡片容器：**头部的图标 / 标题 / 折叠箭头由代码生成，XML 里只放内容**。
 *
 * ## 用法
 *
 * ```xml
 * <com.freeapi.proxy.ui.widget.CardLayout
 *     style="@style/Card"
 *     app:cardTitle="运行状态"
 *     app:cardIcon="@drawable/ic_tab_dashboard">
 *
 *     <TextView … />          <!-- 下面这些都算卡片内容 -->
 *     <Switch  … />
 * </com.freeapi.proxy.ui.widget.CardLayout>
 * ```
 *
 * 布局里**不要再写 SectionTitle 那一行** —— 标题现在归头部管，写了会出现两个标题。
 *
 * ## 实现要点（改之前先读）
 *
 * 1. **内容包裹发生在 [onFinishInflate]**：先把 XML 里已 inflated 的子视图整体摘下来，
 *    再按「头部 → 内容容器」的顺序重新挂回去。这样做的好处是布局文件不用改结构，
 *    也不用额外套一层 `<LinearLayout>` —— 但代价是**内容容器的 layoutParams 由我们接管**，
 *    所以 `layout_weight` 之类依赖"直接父容器"的属性会失效（卡片内部本来也用不到）。
 *
 * 2. **[fillBody] 必须用「0dp + weight=1」而不是 MATCH_PARENT**：卡片自身在父容器里
 *    往往是 `height=0dp, weight=1`（固定高度），此时内容区若写 MATCH_PARENT，
 *    它会按**父容器的**高度去填，把头顶的头部挤出可视区。日志页就踩在这个点上。
 *
 * 3. **折叠状态持久化**：按 [stateKey]（默认标题）存在单独的一个 SharedPreferences 里。
 *    只持久化"已折叠"，默认值是"展开" —— 这样新加的卡片永远是展开的，
 *    不会因为键名对不上而莫名其妙地藏起来。
 */
class CardLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private val title: String
    private val iconRes: Int
    private val collapsible: Boolean
    private val fillBody: Boolean
    private val stateKey: String

    private var body: LinearLayout? = null
    private var chevron: ImageView? = null

    /**
     * [fillBody] 卡片的「原始占位参数」，折叠时用它们换算出收起来的形态。
     * 在 [onAttachedToWindow] 里补记（inflate 期还拿不到 layoutParams，别提前读）。
     */
    private var ownHeight = 0
    private var ownWeight = 0f

    /** 当前是否处于折叠态。外部只读，改动走 [setCollapsed]。 */
    var collapsed: Boolean = false
        private set

    init {
        orientation = VERTICAL

        val a = context.obtainStyledAttributes(attrs, R.styleable.CardLayout)
        title = a.getString(R.styleable.CardLayout_cardTitle).orEmpty()
        iconRes = a.getResourceId(R.styleable.CardLayout_cardIcon, 0)
        collapsible = a.getBoolean(R.styleable.CardLayout_cardCollapsible, true)
        fillBody = a.getBoolean(R.styleable.CardLayout_cardFillBody, false)
        stateKey = a.getString(R.styleable.CardLayout_cardKey)?.takeIf { it.isNotEmpty() }
            ?: title
        a.recycle()
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        // addView 会再次触发本回调，加个哨兵避免重复包裹
        if (body != null) return
        wrapAndBuild()
    }

    // ------------------------------------------------------------------ 组装

    private fun wrapAndBuild() {
        val n = childCount
        val existing = ArrayList<View>(n)
        for (i in 0 until n) existing.add(getChildAt(i))
        removeAllViews()

        addView(buildHeader())

        val content = LinearLayout(context).apply { orientation = VERTICAL }
        val lp = if (fillBody) {
            LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        } else {
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        // 间距挂在内容区而不是头部：折叠后内容区 GONE，这点空白会一起消失，
        // 卡片就能收成紧凑的一条，不留尾巴。
        lp.topMargin = dp(10)
        content.layoutParams = lp
        existing.forEach { content.addView(it) }
        addView(content)
        body = content

        applyCollapsed(restoreCollapsed(), animate = false)
    }

    /**
     * 记录自身原始占位参数，并补做一次收缩。
     *
     * **为什么不能放在 [onFinishInflate]**：那时 `layoutParams` 还是 null ——
     * LayoutInflater 的顺序是「createViewFromTag → generateLayoutParams → rInflateChildren
     * （触发 onFinishInflate）→ addView」，`addView` 才真正把 LayoutParams 设到子视图上。
     * 早读一步会拿到 null，于是 `ownWeight` 永远停在 0，「折叠后收起来」静默失效
     * （现象是折叠后仍留一张只有标题的大白卡）。
     */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (fillBody && ownWeight <= 0f) {
            (layoutParams as? LayoutParams)?.let {
                ownHeight = it.height
                ownWeight = it.weight
            }
        }
        collapseOwnSizing(collapsed)
    }

    private fun buildHeader(): View {
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            minimumHeight = dp(24)
        }

        if (iconRes != 0) {
            header.addView(
                ImageView(context).apply {
                    setImageResource(iconRes)
                    // 图标是单色 path，必须显式 tint，否则渲染成黑色
                    imageTintList = ColorStateList.valueOf(color(R.color.brand_dark))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    layoutParams = LayoutParams(dp(16), dp(16))
                }
            )
        }

        header.addView(
            TextView(context).apply {
                text = title
                setTextColor(color(R.color.brand_dark))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (iconRes != 0) marginStart = dp(6)
                }
            }
        )

        val arrow = ImageView(context).apply {
            setImageResource(R.drawable.ic_chevron)
            imageTintList = ColorStateList.valueOf(color(R.color.muted))
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LayoutParams(dp(15), dp(15))
        }
        chevron = arrow
        header.addView(arrow)

        if (collapsible) {
            // 整条头部都是点击区：只让 15dp 的小箭头可点，手指根本戳不中
            header.isClickable = true
            header.isFocusable = true
            header.setOnClickListener { toggle() }
        } else {
            arrow.visibility = View.GONE
        }
        return header
    }

    // ------------------------------------------------------------------ 折叠

    fun toggle() = setCollapsed(!collapsed)

    /** 供外部（如"一键展开全部"）调用；[animate] 为 false 时立即生效。 */
    fun setCollapsed(value: Boolean, animate: Boolean = true) {
        applyCollapsed(value, animate)
        persist(value)
    }

    private fun applyCollapsed(value: Boolean, animate: Boolean) {
        collapsed = value
        val content = body ?: return

        if (animate) {
            // AutoTransition 是框架自带的（API 19+），不需要 androidx.transition 依赖。
            // 万一动画在某个 ROM 上抛异常也不能让"折叠"这个功能本身失效，所以兜一层。
            runCatching { TransitionManager.beginDelayedTransition(this, AutoTransition()) }
        }
        content.visibility = if (value) View.GONE else View.VISIBLE
        collapseOwnSizing(value)

        val arrow = chevron ?: return
        val target = if (value) 0f else 180f
        if (animate) {
            arrow.animate().rotation(target).setDuration(160L).start()
        } else {
            arrow.animate().cancel()
            arrow.rotation = target
        }
    }

    /**
     * [fillBody] 卡片的自我收缩。
     *
     * 这类卡片在父容器里通常是 `0dp + weight=1`（占满剩余空间）。只把内容区设成 GONE
     * 是**不够**的 —— 卡片自身仍然占着整屏，用户会看到一张只有标题、下面一大片空白的大白卡。
     * 所以折叠时把自己的 weight 也收掉、退回 wrap_content，展开时再还回去。
     *
     * 只在父容器确实是 LinearLayout（有 weight 概念）时才做，否则保持原样。
     */
    private fun collapseOwnSizing(collapsedNow: Boolean) {
        if (!fillBody || ownWeight <= 0f) return
        val lp = layoutParams as? LayoutParams ?: return
        lp.height = if (collapsedNow) LayoutParams.WRAP_CONTENT else ownHeight
        lp.weight = if (collapsedNow) 0f else ownWeight
        requestLayout()
    }

    // ------------------------------------------------------------------ 持久化

    private fun persist(value: Boolean) {
        if (!collapsible || stateKey.isEmpty()) return
        prefs().edit { putBoolean(KEY_PREFIX + stateKey, value) }
    }

    private fun restoreCollapsed(): Boolean {
        if (!collapsible || stateKey.isEmpty()) return false
        return prefs().getBoolean(KEY_PREFIX + stateKey, false)
    }

    private fun prefs(): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ 工具

    private fun color(id: Int): Int = ContextCompat.getColor(context, id)

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics)
            .toInt()

    private companion object {
        const val PREF = "freeapi_ui"
        const val KEY_PREFIX = "card_collapsed_"
    }
}
