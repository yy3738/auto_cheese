package com.cheese.assistant.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.Animation
import android.view.animation.ScaleAnimation
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 悬浮窗信息条：蓝白主题圆角卡片。
 * 布局：标题栏(拖动+执方+结/下一局+关闭) → 上次走子行 → 推荐列表(带打吃徽章)
 * → 降级状态行(识别不可信时显示,附重触发按钮)。
 * 支持收起为小圆点：轮到你=藏蓝实心,对手回合=灰白描边,点击切换。
 */
@SuppressLint("ViewConstructor")
class OverlayView(context: Context) : LinearLayout(context) {

    // ---- 颜色常量(蓝白主题) ----
    private val colorTextPrimary = 0xFF1A1A1A.toInt()
    private val colorTextSecondary = 0xFF64748B.toInt()
    private val colorNavy = 0xFF234B94.toInt()
    private val colorNavyLight = 0xFF3A63B5.toInt()
    private val colorLine = 0xFFCBD5E1.toInt()
    private val colorDisabled = 0xFF94A3B8.toInt()

    /** 收起态:是否轮到用户(决定圆点样式) */
    private var userTurn = true

    // ---- 展开态控件 ----
    private val titleView = TextView(context).apply {
        text = "♟ 棋室"
        setTextColor(colorNavy)
        setTypeface(Typeface.SERIF, Typeface.BOLD)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(6), dp(12), dp(6))
    }

    /** 执方切换按钮:显示当前执方,点一下翻转(手动覆盖) */
    private val sideButton = TextView(context).apply {
        text = "红"
        setTextColor(colorNavy)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        gravity = Gravity.CENTER
        setPadding(dp(11), dp(3), dp(11), dp(3))
        background = pill(0x14234B94)
    }

    /** 封存按钮。对局中="结"(封存并复盘);封存后="下一局"(清谱+状态机归位) */
    private val sealButton = TextView(context).apply {
        text = "结"
        setTextColor(colorNavy)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        gravity = Gravity.CENTER
        setPadding(dp(11), dp(3), dp(11), dp(3))
        background = outline(colorNavy)
    }

    private val closeButton = TextView(context).apply {
        text = "✕"
        setTextColor(colorTextSecondary)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(3), dp(12), dp(3))
    }

    /** 上次走子:推荐区顶部常驻,降级时保持可见作为局面锚点 */
    private val lastMoveView = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setTextColor(colorTextSecondary)
        setPadding(dp(14), 0, dp(14), 0)
        visibility = View.GONE
    }

    /** 推荐列表:每行"徽章+着法+评分" */
    private val suggestionContainer = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(dp(8), dp(2), dp(8), dp(6))
    }

    /** 降级状态行:识别不可信时显示(灰字+重触发按钮),正常时隐藏 */
    private val degradedRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(2), dp(14), dp(8))
        visibility = View.GONE
    }

    private val degradedText = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(colorDisabled)
        setPadding(0, 0, dp(10), 0)
    }

    private val retriggerButton = TextView(context).apply {
        text = "重试识别"
        setTextColor(colorNavy)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(4), dp(10), dp(4))
        background = outline(colorNavy)
        setOnClickListener { onRetrigger?.invoke() }
    }

    /** 拖动把手 = 标题栏整行 */
    val handleBar: LinearLayout

    /** 收起态:单个小圆点 */
    private val collapsedDot: TextView = TextView(context).apply {
        text = "棋"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        gravity = Gravity.CENTER
        setPadding(dp(6), dp(6), dp(6), dp(6))
        visibility = View.GONE
        setOnClickListener {
            setExpanded(true)
            onExpand?.invoke()
        }
    }

    var onExpand: (() -> Unit)? = null

    private var degraded = false

    init {
        orientation = VERTICAL
        background = GradientDrawable().apply {
            setColor(0xD9F8FAFD.toInt()) // rgba(248,250,253,0.85)
            cornerRadius = dp(14).toFloat()
            setStroke(dp(1), colorLine)
        }

        handleBar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(titleView, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(sideButton)
            addView(sealButton)
            addView(closeButton)
        }

        degradedRow.addView(degradedText,
            LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        degradedRow.addView(retriggerButton)

        addView(handleBar)
        addView(lastMoveView)
        addView(suggestionContainer)
        addView(degradedRow)

        collapsedDot.background = dotFilled()
        addView(collapsedDot, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        })
    }

    // ---- 收起/展开 ----

    private var expanded = true

    fun setExpanded(expanded: Boolean) {
        if (expanded == this.expanded) return
        this.expanded = expanded
        handleBar.visibility = if (expanded) VISIBLE else GONE
        lastMoveView.visibility =
            if (expanded && lastMoveView.text.isNotEmpty()) VISIBLE else GONE
        suggestionContainer.visibility = if (expanded) VISIBLE else GONE
        degradedRow.visibility = if (expanded && degraded) VISIBLE else GONE
        collapsedDot.visibility = if (expanded) GONE else VISIBLE
        applyDotStyle()
    }

    /** 收起时的窗口尺寸由服务层读取(切换 layout 尺寸) */
    val collapsed: Boolean get() = !expanded

    fun setUserTurn(turn: Boolean) {
        if (userTurn == turn) return
        userTurn = turn
        if (!expanded) applyDotStyle()
    }

    private fun applyDotStyle() {
        if (userTurn) {
            collapsedDot.background = dotFilled()
            collapsedDot.setTextColor(0xFFFFFFFF.toInt())
            playPulse()
        } else {
            collapsedDot.background = dotOutline()
            collapsedDot.setTextColor(colorTextSecondary)
        }
    }

    /** "轮到你"脉冲:一次缩放弹跳,唯一的主动性动效 */
    private fun playPulse() {
        if (expanded) return
        val anim = ScaleAnimation(
            1f, 1.25f, 1f, 1.25f,
            Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f).apply {
            duration = 250
            repeatMode = Animation.REVERSE
            repeatCount = 1
        }
        collapsedDot.startAnimation(anim)
    }

    // ---- 数据更新 ----

    fun setText(text: String) {
        // 兼容旧接口:整段文本塞推荐区(带徽章的推荐走 setSuggestions)
        suggestionContainer.removeAllViews()
        suggestionContainer.addView(TextView(context).apply {
            setTextColor(colorTextPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(6), 0, dp(6), 0)
            setLineSpacing(dp(3).toFloat(), 1f)
            text
        })
    }

    /** 推荐列表:每条 = (序号, 文本, 威胁级别) */
    fun setSuggestions(items: List<Triple<Int, String, ThreatLevel>>) {
        suggestionContainer.removeAllViews()
        for ((index, text, threat) in items) {
            suggestionContainer.addView(suggestionRow(index, text, threat))
        }
    }

    /** 上次走子提示,如 "对手：炮8平7"。空串隐藏 */
    fun setLastMove(text: String) {
        lastMoveView.text = text
        lastMoveView.visibility = if (expanded && text.isNotEmpty()) VISIBLE else GONE
    }

    /** 识别降级状态:显示灰字+重试按钮;null/空 = 正常,隐藏该行 */
    fun setDegraded(reason: String?) {
        degraded = !reason.isNullOrEmpty()
        degradedText.text = reason ?: ""
        degradedRow.visibility = if (degraded && expanded) VISIBLE else GONE
        // 降级时推荐区整体变灰
        suggestionContainer.alpha = if (degraded) 0.4f else 1f
    }

    /** 封存完成后切"下一局"状态 */
    fun showNextGameMode() {
        sealButton.text = "下一局"
    }

    fun setOnCloseListener(listener: () -> Unit) {
        closeButton.setOnClickListener { listener() }
    }

    fun setOnSealListener(listener: () -> Unit) {
        sealButton.setOnClickListener { listener() }
    }

    fun setOnRetriggerListener(listener: () -> Unit) {
        onRetrigger = listener
    }

    private var onRetrigger: (() -> Unit)? = null

    fun setOnSideToggleListener(listener: (Boolean) -> Unit) {
        var isRed = recorderSide
        sideButton.setOnClickListener {
            isRed = !isRed
            recorderSide = isRed
            setUserSide(isRed)
            listener(isRed)
        }
    }

    /** 记录最后一次设置的执方,让内部翻转保持连续 */
    var recorderSide: Boolean = true
        private set

    /** 执方按钮显示同步(藏蓝系,红/黑区分靠文字) */
    fun setUserSide(isRed: Boolean) {
        recorderSide = isRed
        sideButton.text = if (isRed) "红" else "黑"
    }

    // ---- 样式 ----

    private fun pill(color: Long): GradientDrawable = GradientDrawable().apply {
        setColor(color.toInt())
        cornerRadius = dp(20).toFloat()
    }

    private fun outline(color: Int): GradientDrawable = GradientDrawable().apply {
        setColor(0x00000000)
        cornerRadius = dp(20).toFloat()
        setStroke(dp(1), color)
    }

    /** 收起圆点-轮到你:藏蓝实心 */
    private fun dotFilled(): GradientDrawable = GradientDrawable().apply {
        setColor(colorNavy)
        shape = GradientDrawable.OVAL
    }

    /** 收起圆点-对手回合:灰白描边空心 */
    private fun dotOutline(): GradientDrawable = GradientDrawable().apply {
        setColor(0x66FFFFFF)
        shape = GradientDrawable.OVAL
        setStroke(dp(1), colorLine)
    }

    /**
     * 推荐行:序号即徽章。
     * 有威胁=藏蓝实心圆白字;将军=加粗外圈;无威胁=灰蓝细描边空心圆。
     */
    private fun suggestionRow(index: Int, moveText: String, threat: ThreatLevel): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(3), dp(6), dp(3))
        }
        val badge = TextView(context).apply {
            text = "$index"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
        }
        val size = dp(22)
        badge.layoutParams = LinearLayout.LayoutParams(size, size).apply {
            marginEnd = dp(8)
        }
        when (threat) {
            ThreatLevel.CHECK -> {
                badge.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(colorNavy)
                    setStroke(dp(2), colorNavyLight)
                }
                badge.setTextColor(0xFFFFFFFF.toInt())
            }
            ThreatLevel.CAPTURE -> {
                badge.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(colorNavy)
                }
                badge.setTextColor(0xFFFFFFFF.toInt())
            }
            ThreatLevel.NONE -> {
                badge.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0x00000000)
                    setStroke(dp(1), colorLine)
                }
                badge.setTextColor(colorTextSecondary)
            }
        }
        row.addView(badge)
        row.addView(TextView(context).apply {
            text = moveText
            setTextColor(colorTextPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        })
        return row
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(),
            resources.displayMetrics).toInt()
}

/** 推荐着法的威胁级别(打吃/将军),与 ThreatMarker.Level 对应 */
enum class ThreatLevel { NONE, CAPTURE, CHECK }
