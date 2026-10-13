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
 * 悬浮窗信息条：蓝白主题卡片。
 * 版式：藏蓝实底标题栏(拖动+执方+结/下一局+关闭,白字) → 上一手行
 * → 推荐列表(序号徽章;打吃=藏蓝「吃」圆牌+着法藏蓝加粗,将军=朱红「将」圆牌
 * +着法朱红加粗) → 降级状态行(琥珀提示+重试)。
 * 支持收起为小圆点：轮到你=藏蓝实心,对手回合=灰白描边,点击切换。
 */
@SuppressLint("ViewConstructor")
class OverlayView(context: Context) : LinearLayout(context) {

    // ---- 颜色常量(蓝白主题) ----
    private val colorTextPrimary = 0xFF1A1A1A.toInt()
    private val colorTextSecondary = 0xFF64748B.toInt()
    private val colorNavy = 0xFF234B94.toInt()
    private val colorNavyLight = 0xFF3A63B5.toInt()
    private val colorVermillion = 0xFFD64545.toInt()
    private val colorLine = 0xFFCBD5E1.toInt()
    private val colorDisabled = 0xFF94A3B8.toInt()
    private val colorAmber = 0xFFB45309.toInt()

    /** 收起态:是否轮到用户(决定圆点样式) */
    private var userTurn = true

    // ---- 展开态控件 ----

    private val titleView = TextView(context).apply {
        text = "棋室"
        setTextColor(0xFFFFFFFF.toInt())
        setTypeface(Typeface.SERIF, Typeface.BOLD)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        letterSpacing = 0.15f
        gravity = Gravity.CENTER_VERTICAL
    }

    /** 执方切换按钮:显示当前执方,点一下翻转(手动覆盖) */
    private val sideButton = TextView(context).apply {
        text = "红"
        setTextColor(0xFFFFFFFF.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(2), dp(10), dp(2))
        background = pill(0x33FFFFFF)
    }

    /** 封存按钮。对局中="结"(封存并复盘);封存后="下一局"(清谱+状态机归位) */
    private val sealButton = TextView(context).apply {
        text = "结"
        setTextColor(colorNavy)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        gravity = Gravity.CENTER
        setPadding(dp(11), dp(2), dp(11), dp(2))
        background = pill(0xFFFFFFFF)
    }

    private val closeButton = TextView(context).apply {
        text = "✕"
        setTextColor(0xB3FFFFFF.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        gravity = Gravity.CENTER
        setPadding(dp(11), dp(2), dp(11), dp(2))
    }

    /** 上一手:推荐区顶部常驻,降级时保持可见作为局面锚点 */
    private val lastMoveView = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setTextColor(colorTextSecondary)
        setPadding(dp(14), 0, dp(14), 0)
        visibility = View.GONE
    }

    /** 推荐列表:每行"徽章+着法" */
    private val suggestionContainer = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(dp(8), dp(4), dp(8), dp(8))
    }

    /** 降级状态行:识别不可信时显示(琥珀提示+重触发按钮),正常时隐藏 */
    private val degradedRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(4), dp(14), dp(8))
        visibility = View.GONE
        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(0x14D97706)
        }
    }

    private val degradedText = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(colorAmber)
        setPadding(0, 0, dp(10), 0)
    }

    private val retriggerButton = TextView(context).apply {
        text = "重试识别"
        setTextColor(colorAmber)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(4), dp(10), dp(4))
        background = outline(colorAmber)
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
        clipToOutline = true
        background = cardShape()

        handleBar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(6), dp(8), dp(6))
            background = headerShape()
            addView(titleView, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(sideButton)
            addView(space(dp(5)))
            addView(sealButton)
            addView(space(dp(5)))
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

    /** 标题栏藏蓝底要盖住卡片圆角外的直角:圆角只有顶部两角 */
    private fun headerShape(): GradientDrawable = GradientDrawable().apply {
        orientation = GradientDrawable.Orientation.LEFT_RIGHT
        colors = intArrayOf(colorNavy, colorNavyLight)
        cornerRadii = floatArrayOf(
            dp(15).toFloat(), dp(15).toFloat(),
            dp(15).toFloat(), dp(15).toFloat(),
            0f, 0f, 0f, 0f)
    }

    private fun space(px: Int): View = View(context).also {
        it.layoutParams = LinearLayout.LayoutParams(px, 1)
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
        // 收起时根卡片背景必须隐藏:窗口缩到 40dp,近白底+描边会在圆点
        // 周围露出一圈白框;展开时恢复卡片
        background = if (expanded) cardShape() else null
        applyDotStyle()
    }

    private fun cardShape(): GradientDrawable = GradientDrawable().apply {
        setColor(0xF2F8FAFD.toInt())
        cornerRadius = dp(16).toFloat()
        setStroke(dp(1), colorLine)
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

    /** 上一手提示,如 "上一手 · 炮8平7"。空串隐藏 */
    fun setLastMove(text: String) {
        lastMoveView.text = text
        lastMoveView.visibility = if (expanded && text.isNotEmpty()) VISIBLE else GONE
    }

    /** 识别降级状态:显示琥珀提示+重试按钮;null/空 = 正常,隐藏该行 */
    fun setDegraded(reason: String?) {
        degraded = !reason.isNullOrEmpty()
        degradedText.text = reason ?: ""
        degradedRow.visibility = if (degraded && expanded) VISIBLE else GONE
        // 降级时推荐区整体变灰
        suggestionContainer.alpha = if (degraded) 0.4f else 1f
    }

    /** 封存完成后切"下一局"状态:朱红实底强调,引导进入下一局 */
    fun showNextGameMode() {
        sealButton.text = "下一局"
        sealButton.setTextColor(0xFFFFFFFF.toInt())
        sealButton.setPadding(dp(12), dp(3), dp(12), dp(3))
        sealButton.background = pill(colorVermillion.toLong())
    }

    /** 新一局开始:从"下一局"态复位为对局中的"结" */
    fun resetSealButton() {
        sealButton.text = "结"
        sealButton.setTextColor(colorNavy)
        sealButton.setPadding(dp(11), dp(2), dp(11), dp(2))
        sealButton.background = pill(0xFFFFFFFF)
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

    /** 执方按钮显示同步(藏蓝栏内白字) */
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
     * 推荐行:**威胁用字面标签,直读不用猜**。
     * 将军=朱红实心圆「将」+着法朱红加粗;打吃=藏蓝实心圆「吃」+着法藏蓝;
     * 无威胁=空心圆序号+普通着法。
     */
    private fun suggestionRow(index: Int, moveText: String, threat: ThreatLevel): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(3), dp(6), dp(3))
        }
        val isCheck = threat == ThreatLevel.CHECK
        val isCapture = threat == ThreatLevel.CAPTURE
        val badge = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(Typeface.DEFAULT_BOLD)
            gravity = Gravity.CENTER
        }
        val size = dp(22)
        badge.layoutParams = LinearLayout.LayoutParams(size, size).apply {
            marginEnd = dp(8)
        }
        badge.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            when {
                isCheck -> {
                    setColor(colorVermillion)
                    setStroke(dp(2), colorVermillion)
                }
                isCapture -> {
                    setColor(colorNavy)
                    setStroke(dp(2), colorNavyLight)
                }
                else -> {
                    setColor(0x00000000)
                    setStroke(dp(1), colorLine)
                }
            }
        }
        badge.text = when {
            isCheck -> "将"
            isCapture -> "吃"
            else -> "$index"
        }
        badge.setTextColor(if (threat == ThreatLevel.NONE) colorTextSecondary
        else 0xFFFFFFFF.toInt())
        row.addView(badge)
        row.addView(TextView(context).apply {
            text = moveText
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            when {
                isCheck -> {
                    setTextColor(colorVermillion)
                    setTypeface(Typeface.DEFAULT_BOLD)
                }
                isCapture -> {
                    setTextColor(colorNavy)
                    setTypeface(Typeface.DEFAULT_BOLD)
                }
                else -> setTextColor(colorTextPrimary)
            }
        })
        return row
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(),
            resources.displayMetrics).toInt()
}

/** 推荐着法的威胁级别(打吃/将军),与 ThreatMarker.Level 对应 */
enum class ThreatLevel { NONE, CAPTURE, CHECK }
