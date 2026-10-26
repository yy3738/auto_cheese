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
 * 版式：藏蓝实底标题栏(拖动+执方+局前「开局」/局中「纠/实」+结/下一局+
 * 关闭,白字) → 上一手行 → 推荐列表(序号徽章;打吃=藏蓝「吃」圆牌+着法
 * 藏蓝加粗,将军=朱红「将」圆牌+着法朱红加粗) → 降级状态行(琥珀提示+重试)。
 * 支持收起为小圆点：轮到你=藏蓝实心,对手回合=灰白描边,点击切换。
 */
@SuppressLint("ViewConstructor")
class OverlayView(context: Context) : LinearLayout(context) {

    // ---- 颜色常量(蓝白主题) ----
    private val colorTextPrimary = 0xFF1A1A1A.toInt()
    private val colorTextSecondary = 0xFF64748B.toInt()
    private val colorNavy = 0xFF234B94.toInt()
    private val colorNavyLight = 0xFF3A63B5.toInt()
    private val colorNavySoft = 0xFFB9C6DE.toInt()   // 淡蓝:打吃徽章
    private val colorVermillion = 0xFFD64545.toInt()
    private val colorVermillionSoft = 0xFFF0B3AE.toInt() // 淡朱红:蹲杀徽章
    private val colorLine = 0xFFCBD5E1.toInt()
    private val colorDisabled = 0xFF94A3B8.toInt()
    private val colorAmber = 0xFFB45309.toInt()

    /** 收起态:是否轮到用户(决定圆点样式) */
    private var userTurn = true

    // ---- 展开态控件 ----

    private val titleView = TextView(context).apply {
        text = "弈枰"
        setTextColor(0xFFFFFFFF.toInt())
        setTypeface(Typeface.SERIF, Typeface.BOLD)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        letterSpacing = 0.15f
        gravity = Gravity.CENTER_VERTICAL
    }

    /** 执方切换按钮:显示当前执方,点一下翻转(手动覆盖) */
    private val sideButton = TextView(context).apply {
        text = "红"
        setTextColor(0xFFFFFFFF.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(1), dp(8), dp(1))
        background = pill(0x33FFFFFF)
    }

    /** 封存按钮。对局中="结"(封存并复盘);封存后="下一局"(清谱+状态机归位) */
    private val sealButton = TextView(context).apply {
        text = "结"
        setTextColor(colorNavy)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        gravity = Gravity.CENTER
        setPadding(dp(9), dp(1), dp(9), dp(1))
        background = pill(0xFFFFFFFF)
    }

    private val closeButton = TextView(context).apply {
        text = "✕"
        setTextColor(0xB3FFFFFF.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        gravity = Gravity.CENTER
        setPadding(dp(9), dp(1), dp(9), dp(1))
    }

    /** 实况页入口（局中显示）：单字「实」，与「结」同排 */
    private val liveButton = TextView(context).apply {
        text = "实"
        setTextColor(0xFFFFFFFF.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        gravity = Gravity.CENTER
        setPadding(dp(9), dp(1), dp(9), dp(1))
        background = pill(0x33FFFFFF)
        visibility = GONE
    }

    /** 纠错入口（局中显示）：单字「纠」，与「结」同排 */
    private val correctButton = TextView(context).apply {
        text = "纠"
        setTextColor(0xFFFFFFFF.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        gravity = Gravity.CENTER
        setPadding(dp(9), dp(1), dp(9), dp(1))
        background = pill(0x33FFFFFF)
        visibility = GONE
    }

    /** 开局入口（局前显示）：与「结」并排；点后立即消失，换成「纠/实」 */
    private val newGameButton = TextView(context).apply {
        text = "开局"
        setTextColor(0xFFFFFFFF.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(1), dp(8), dp(1))
        background = pill(0x44FFFFFF)
    }

    /** 上一手:推荐区顶部常驻,降级时保持可见作为局面锚点 */
    private val lastMoveView = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        setTextColor(colorTextSecondary)
        setPadding(dp(10), 0, dp(10), 0)
        visibility = View.GONE
    }

    /** 推荐列表:每行"徽章+着法" */
    private val suggestionContainer = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(dp(6), dp(2), dp(6), dp(4))
    }

    /** 降级状态行:识别不可信时显示(琥珀提示+重触发按钮),正常时隐藏 */
    private val degradedRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(2), dp(10), dp(4))
        visibility = View.GONE
        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(0x14D97706)
        }
    }

    private val degradedText = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setTextColor(colorAmber)
        setPadding(0, 0, dp(8), 0)
    }

    private val retriggerButton = TextView(context).apply {
        text = "重试识别"
        setTextColor(colorAmber)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(3), dp(8), dp(3))
        background = outline(colorAmber)
        setOnClickListener { onRetrigger?.invoke() }
    }

    /** 拖动把手 = 标题栏整行 */
    val handleBar: LinearLayout

    /** 收起态:单个小圆点 */
    private val collapsedDot: TextView = TextView(context).apply {
        text = "枰"
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
            setPadding(dp(10), dp(4), dp(6), dp(4))
            background = headerShape()
            fun btnLp() = LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                .apply { setMargins(dp(4), 0, dp(4), 0) }
            addView(titleView, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(sideButton, btnLp())
            addView(newGameButton, btnLp())
            addView(correctButton, btnLp())
            addView(liveButton, btnLp())
            addView(sealButton, btnLp())
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
        setGameStarted(false)
    }

    /**
     * 标题栏状态切换（2026-10-23 四态闭环）：
     * - 局前/复盘完成：只有「开局」（「结」「纠」「实」全部隐藏）
     * - 局中：`纠 | 实 | 结`（「开局」隐藏）
     */
    fun setGameStarted(started: Boolean) {
        newGameButton.visibility = if (started) GONE else VISIBLE
        correctButton.visibility = if (started) VISIBLE else GONE
        liveButton.visibility = if (started) VISIBLE else GONE
        sealButton.visibility = if (started) VISIBLE else GONE
        if (started) resetSealButton()
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
        // 排障:"推荐区变空"曾无法归因——UI 更新全打点
        com.cheese.assistant.util.AppLog.d("Overlay", "setText len=${text.length}")
        showingSuggestions = false
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
        com.cheese.assistant.util.AppLog.d("Overlay", "setSuggestions n=${items.size}")
        showingSuggestions = true
        suggestionContainer.removeAllViews()
        for ((index, text, threat) in items) {
            suggestionContainer.addView(suggestionRow(index, text, threat))
        }
    }

    /** 推荐区当前行数(0=空,用于空推荐自愈检测) */
    fun suggestionCount(): Int = suggestionContainer.childCount

    /** 推荐区是否正显示候选(区分"候选挂着"与"文本态",自愈/覆盖判断用) */
    var showingSuggestions = false
        private set

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

    /** 复盘进行中:按钮转灰蓝"复盘中",明确反馈已受理(完成时回到「开局」态) */
    fun showReviewingMode() {
        newGameButton.visibility = GONE
        correctButton.visibility = GONE
        liveButton.visibility = GONE
        sealButton.visibility = VISIBLE
        sealButton.text = "复盘中"
        sealButton.setTextColor(0xFFFFFFFF.toInt())
        sealButton.setPadding(dp(10), dp(2), dp(10), dp(2))
        sealButton.background = pill(colorNavySoft.toLong())
    }

    /** 复盘进度:按钮直接显示「复盘X/Y」——短局 1/1 也能看出已受理并完成 */
    fun setReviewProgress(done: Int, total: Int) {
        newGameButton.visibility = GONE
        correctButton.visibility = GONE
        liveButton.visibility = GONE
        sealButton.visibility = VISIBLE
        sealButton.text = "复盘$done/$total"
        sealButton.setTextColor(0xFFFFFFFF.toInt())
        sealButton.setPadding(dp(8), dp(2), dp(8), dp(2))
        sealButton.background = pill(colorNavySoft.toLong())
    }

    /** 新一局开始:从"下一局"态复位为对局中的"结" */
    fun resetSealButton() {
        sealButton.text = "结"
        sealButton.setTextColor(colorNavy)
        sealButton.setPadding(dp(9), dp(1), dp(9), dp(1))
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

    /** ♟ 按钮 → 跳转对局实况页 */
    fun setOnLiveListener(listener: () -> Unit) {
        liveButton.setOnClickListener { listener() }
    }

    /** 纠错按钮 → 四下拉输入一手棋 */
    fun setOnCorrectListener(listener: () -> Unit) {
        correctButton.setOnClickListener { listener() }
    }

    /** 开局按钮 → 以当前盘面为新局起点 */
    fun setOnNewGameListener(listener: () -> Unit) {
        newGameButton.setOnClickListener { listener() }
    }

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
     * 推荐行:**威胁用字面标签+深浅两级,直读不用猜**。
     * 当前将=朱红实心「将」加粗;蹲杀(下一手可叫将)=淡朱红「将」不加粗;
     * 当前吃=藏蓝实心「吃」加粗;打吃(下一步能吃)=淡蓝「吃」不加粗;
     * 无威胁=空心圆序号+普通着法。
     */
    private fun suggestionRow(index: Int, moveText: String, threat: ThreatLevel): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(2), dp(4), dp(2))
        }
        val isCheck = threat == ThreatLevel.CHECK
        val isCapture = threat == ThreatLevel.CAPTURE
        val isThreatCheck = threat == ThreatLevel.THREAT_CHECK
        val isThreatCapture = threat == ThreatLevel.THREAT_CAPTURE
        val badge = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTypeface(Typeface.DEFAULT_BOLD)
            gravity = Gravity.CENTER
        }
        val size = dp(20)
        badge.layoutParams = LinearLayout.LayoutParams(size, size).apply {
            marginEnd = dp(6)
        }
        badge.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            when {
                isCheck -> {
                    setColor(colorVermillion)
                    setStroke(dp(2), colorVermillion)
                }
                isThreatCheck -> setColor(colorVermillionSoft)
                isCapture -> {
                    setColor(colorNavy)
                    setStroke(dp(2), colorNavyLight)
                }
                isThreatCapture -> setColor(colorNavySoft)
                else -> {
                    setColor(0x00000000)
                    setStroke(dp(1), colorLine)
                }
            }
        }
        // 深色徽章配白字;淡色徽章配同色系深字,一眼分出"当前/威胁"
        badge.text = when {
            isCheck || isThreatCheck -> "将"
            isCapture || isThreatCapture -> "吃"
            else -> "$index"
        }
        badge.setTextColor(when {
            isCheck -> 0xFFFFFFFF.toInt()
            isThreatCheck -> colorVermillion
            isCapture -> 0xFFFFFFFF.toInt()
            isThreatCapture -> colorNavy
            else -> colorTextSecondary
        })
        row.addView(badge)
        row.addView(TextView(context).apply {
            text = moveText
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            when {
                isCheck -> {
                    setTextColor(colorVermillion)
                    setTypeface(Typeface.DEFAULT_BOLD)
                }
                isThreatCheck -> setTextColor(colorVermillion)
                isCapture -> {
                    setTextColor(colorNavy)
                    setTypeface(Typeface.DEFAULT_BOLD)
                }
                isThreatCapture -> setTextColor(colorNavy)
                else -> setTextColor(colorTextPrimary)
            }
        })
        return row
    }

    /** 悬浮窗当前屏幕矩形；未挂载时返回 null（边条指纹采样跳过用） */
    fun boundsOnScreen(): android.graphics.Rect? {
        if (!isAttachedToWindow) return null
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        if (width <= 0 || height <= 0) return null
        return android.graphics.Rect(loc[0], loc[1], loc[0] + width, loc[1] + height)
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(),
            resources.displayMetrics).toInt()
}

/** 推荐着法的威胁级别(与 ThreatMarker.Level 对应)。
 *  CAPTURE/THREAT_CAPTURE 分别=当前吃(深蓝)/打吃(淡蓝);
 *  CHECK/THREAT_CHECK 分别=当前将(朱红)/蹲杀(淡朱红) */
enum class ThreatLevel { NONE, THREAT_CAPTURE, CAPTURE, THREAT_CHECK, CHECK }
