package com.cheese.assistant.service

import android.app.AlertDialog
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 多步识别歧义确认（S3，2026-10-26 用户方案）。
 *
 * 把候选序列直接列出来（如 `① 红 车四进一 → 黑 卒7进1`），用户点一条确认；
 * 不再用四下拉输入。超时由调用方按第一条兜底。
 */
class AmbiguityDialog(private val context: Context, private val options: List<String>) {

    var onPick: ((Int) -> Unit)? = null
    private var dialog: AlertDialog? = null

    fun show() {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(6), dp(18), dp(2))
        }
        val marks = listOf("①", "②", "③", "④", "⑤", "⑥")
        options.forEachIndexed { i, text ->
            root.addView(TextView(context).apply {
                this.text = "${marks.getOrElse(i) { "${i + 1}." }}  $text"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(dp(10), dp(10), dp(10), dp(10))
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(0xFFF1F5F9.toInt())
                    setStroke(dp(1), 0xFFCBD5E1.toInt())
                }
                setOnClickListener { choose(i) }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) })
        }
        root.addView(TextView(context).apply {
            text = "20 秒未选择将按第 1 条记录"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(0xFF64748B.toInt())
            setPadding(0, dp(4), 0, 0)
        })
        dialog = AlertDialog.Builder(context)
            .setTitle("识别到多步，请确认实际走法")
            .setView(root)
            .setNegativeButton("按第 1 条") { _, _ -> choose(0) }
            .create()
        dialog?.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog?.show()
    }

    private fun choose(i: Int) {
        dialog?.dismiss()
        onPick?.invoke(i)
    }

    fun dismiss() {
        dialog?.dismiss()
    }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
}
