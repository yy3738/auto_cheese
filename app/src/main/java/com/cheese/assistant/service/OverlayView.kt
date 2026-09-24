package com.cheese.assistant.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 悬浮窗信息条：半透明黑底圆角卡片，标题栏（拖动区+结束+关闭）+ 内容文本。
 * 「结」= 封存当前对局并触发复盘；「红/黑」= 手动覆盖本局执方（默认按
 * "屏幕下方 = 用户"自动判定）。
 */
@SuppressLint("ViewConstructor")
class OverlayView(context: Context) : LinearLayout(context) {

    private val titleView = TextView(context).apply {
        text = "♟ 象棋助手"
        setTextColor(0xFFCCCCCC.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(4), dp(10), dp(4))
    }

    /** 封存复盘按钮（对局进行中点它：封存棋谱 → 自动复盘） */
    private val sealButton = TextView(context).apply {
        text = "结"
        setTextColor(0xFFFFD54F.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(4), dp(10), dp(4))
    }

    /** 执方切换按钮：显示当前执方，点一下翻转（手动覆盖） */
    private val sideButton = TextView(context).apply {
        text = "红"
        setTextColor(0xFFEF9A9A.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(4), dp(10), dp(4))
    }

    private val closeButton = TextView(context).apply {
        text = "✕"
        setTextColor(0xFF999999.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(4), dp(10), dp(4))
    }

    private val contentView = TextView(context).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding(dp(12), dp(2), dp(12), dp(8))
        setLineSpacing(dp(3).toFloat(), 1f)
        typeface = android.graphics.Typeface.MONOSPACE
    }

    /** 拖动把手 = 标题栏整行 */
    val handleBar: LinearLayout

    init {
        orientation = VERTICAL
        background = GradientDrawable().apply {
            setColor(0xE6101010.toInt())
            cornerRadius = dp(10).toFloat()
            setStroke(dp(1), 0xFF444444.toInt())
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

    fun setText(text: String) {
        contentView.text = text
    }

    /** 执方按钮显示同步（用户点击切换后调用方回填） */
    fun setUserSide(isRed: Boolean) {
        sideButton.text = if (isRed) "红" else "黑"
        sideButton.setTextColor(if (isRed) 0xFFEF9A9A.toInt() else 0xFF90CAF9.toInt())
    }

    fun setOnCloseListener(listener: () -> Unit) {
        closeButton.setOnClickListener { listener() }
    }

    fun setOnSealListener(listener: () -> Unit) {
        sealButton.setOnClickListener { listener() }
    }

    fun setOnSideToggleListener(listener: (Boolean) -> Unit) {
        var isRed = true
        sideButton.setOnClickListener {
            isRed = !isRed
            setUserSide(isRed)
            listener(isRed)
        }
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(),
            resources.displayMetrics).toInt()
}
