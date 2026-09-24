package com.cheese.assistant.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 悬浮窗信息条：墨色圆角卡片,标题栏(拖动+执方+封存+关闭)+ 内容文本。
 * 「结」= 封存当前对局并触发复盘;「红/黑」= 手动覆盖本局执方。
 */
@SuppressLint("ViewConstructor")
class OverlayView(context: Context) : LinearLayout(context) {

    private val titleView = TextView(context).apply {
        text = "♟ 棋室"
        setTextColor(0xFFE8C88F.toInt()) // wood_light
        setTypeface(Typeface.SERIF, Typeface.BOLD)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(5), dp(12), dp(5))
    }

    /** 执方切换按钮:显示当前执方,点一下翻转(手动覆盖) */
    private val sideButton = TextView(context).apply {
        text = "红"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        gravity = Gravity.CENTER
        setPadding(dp(11), dp(3), dp(11), dp(3))
        background = pill(0x33EF9A9A)
    }

    /** 封存复盘按钮(对局进行中点它:封存棋谱 → 自动复盘) */
    private val sealButton = TextView(context).apply {
        text = "结"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        gravity = Gravity.CENTER
        setPadding(dp(11), dp(3), dp(11), dp(3))
        background = pill(0x33FFD54F)
    }

    private val closeButton = TextView(context).apply {
        text = "✕"
        setTextColor(0xFF8A8074.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(3), dp(12), dp(3))
    }

    private val contentView = TextView(context).apply {
        setTextColor(0xFFF0E9DD.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding(dp(14), dp(3), dp(14), dp(9))
        setLineSpacing(dp(3).toFloat(), 1f)
        typeface = Typeface.MONOSPACE
    }

    /** 拖动把手 = 标题栏整行 */
    val handleBar: LinearLayout

    init {
        orientation = VERTICAL
        background = GradientDrawable().apply {
            setColor(0xF2141210.toInt())
            cornerRadius = dp(14).toFloat()
            setStroke(dp(1), 0xFF4A4238.toInt())
        }

        handleBar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(titleView, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(sideButton)
            addView(sealButton)
            addView(closeButton)
        }
        addView(handleBar)
        addView(contentView)
    }

    private fun pill(color: Long): GradientDrawable = GradientDrawable().apply {
        setColor(color.toInt())
        cornerRadius = dp(20).toFloat()
    }

    fun setText(text: String) {
        contentView.text = text
    }

    /** 执方按钮显示同步(红=暖红底,黑=墨青底) */
    /** 执方按钮显示同步(红=暖红底,黑=墨青底) */
    fun setUserSide(isRed: Boolean) {
        recorderSide = isRed
        sideButton.text = if (isRed) "红" else "黑"
        sideButton.setTextColor(if (isRed) 0xFFEF9A9A.toInt() else 0xFF9FC8B0.toInt())
        sideButton.background = pill(if (isRed) 0x40E05A47 else 0x407FA88C)
    }

    fun setOnCloseListener(listener: () -> Unit) {
        closeButton.setOnClickListener { listener() }
    }

    fun setOnSealListener(listener: () -> Unit) {
        sealButton.setOnClickListener { listener() }
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

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(),
            resources.displayMetrics).toInt()
}
