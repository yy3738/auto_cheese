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
 * 悬浮窗信息条：半透明黑底圆角卡片，标题栏（拖动区+关闭）+ 内容文本。
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
            addView(closeButton)
        }
        addView(handleBar)
        addView(contentView)
    }

    fun setText(text: String) {
        contentView.text = text
    }

    fun setOnCloseListener(listener: () -> Unit) {
        closeButton.setOnClickListener { listener() }
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(),
            resources.displayMetrics).toInt()
}
