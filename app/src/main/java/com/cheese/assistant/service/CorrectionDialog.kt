package com.cheese.assistant.service

import android.app.AlertDialog
import android.content.Context
import android.view.Gravity
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.cheese.assistant.board.Piece
import com.cheese.assistant.recognize.MoveInput

/** 纠错预览：确定按钮文字 + 该着法是否在当前/上一手局面成立 */
data class CorrectionPreview(val label: String, val valid: Boolean)

/**
 * 浮窗「纠错」四下拉弹窗（2026-10-22 用户方案）。
 *
 * 四个下拉：棋子（含红/黑）/ 起始纵线 / 进·退·平 / 目标纵线或步数。
 * 每次选择变化都回调 modeResolver 更新确定按钮文字（自动判断
 * "补上漏的一手"还是"改掉上一手"）；非法着法不关闭弹窗。
 */
class CorrectionDialog(
    private val context: Context,
    private val userIsRed: Boolean,
) {
    var modeResolver: ((MoveInput) -> CorrectionPreview)? = null
    var onConfirm: ((MoveInput) -> Unit)? = null
    var onUndo: (() -> Unit)? = null

    private val pieces: List<Pair<String, Piece>> = listOf(
        "红车" to Piece.R_ROOK, "红马" to Piece.R_KNIGHT, "红炮" to Piece.R_CANNON,
        "红相" to Piece.R_BISHOP, "红仕" to Piece.R_ADVISOR,
        "红帅" to Piece.R_KING, "红兵" to Piece.R_PAWN,
        "黑车" to Piece.B_ROOK, "黑马" to Piece.B_KNIGHT, "黑炮" to Piece.B_CANNON,
        "黑象" to Piece.B_BISHOP, "黑士" to Piece.B_ADVISOR,
        "黑将" to Piece.B_KING, "黑卒" to Piece.B_PAWN,
    )

    fun show() {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }

        val pieceSpinner = spinner(pieces.map { it.first }, if (userIsRed) 0 else 7)
        val fileSpinner = spinner((1..9).map { it.toString() }, 0)
        val actionSpinner = spinner(listOf("进", "退", "平"), 0)
        val targetSpinner = spinner((1..9).map { it.toString() }, 0)
        val targetLabel = TextView(context).apply {
            text = "目标纵线"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
        }

        root.addView(row("棋子", pieceSpinner))
        root.addView(row("起始纵线", fileSpinner))
        root.addView(row("走法", actionSpinner))
        val targetRow = row("目标", targetSpinner)
        targetRow.removeViewAt(0)
        targetLabel.width = dp(78)
        targetRow.addView(targetLabel, 0)
        root.addView(targetRow)

        val status = TextView(context).apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(8), 0, 0)
            setTextColor(0xFFB45309.toInt())
        }
        root.addView(status)

        val dialog = AlertDialog.Builder(context)
            .setTitle("纠错：输入实际走子")
            .setView(root)
            .setPositiveButton("确认", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)

        fun input(): MoveInput {
            val action = when (actionSpinner.selectedItemPosition) {
                1 -> MoveInput.Action.RETREAT
                2 -> MoveInput.Action.LEVEL
                else -> MoveInput.Action.ADVANCE
            }
            return MoveInput(
                piece = pieces[pieceSpinner.selectedItemPosition].second,
                fromFile = fileSpinner.selectedItemPosition + 1,
                action = action,
                target = targetSpinner.selectedItemPosition + 1,
            )
        }

        fun refresh() {
            val inp = input()
            val diagonal = inp.piece == Piece.R_KNIGHT || inp.piece == Piece.B_KNIGHT ||
                inp.piece == Piece.R_BISHOP || inp.piece == Piece.B_BISHOP ||
                inp.piece == Piece.R_ADVISOR || inp.piece == Piece.B_ADVISOR
            targetLabel.text =
                if (diagonal || inp.action == MoveInput.Action.LEVEL) "目标纵线" else "步数"
            val preview = modeResolver?.invoke(inp)
            status.text = if (preview != null) "$inp  ${preview.label}" else "$inp"
            val btn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            btn?.text = preview?.label ?: "确认"
            btn?.isEnabled = preview?.valid ?: true
        }

        val onSelect = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?, view: android.view.View?,
                position: Int, id: Long,
            ) = refresh()

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        pieceSpinner.onItemSelectedListener = onSelect
        fileSpinner.onItemSelectedListener = onSelect
        actionSpinner.onItemSelectedListener = onSelect
        targetSpinner.onItemSelectedListener = onSelect

        dialog.setOnShowListener {
            refresh()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                val preview = modeResolver?.invoke(input())
                if (preview != null && !preview.valid) {
                    Toast.makeText(context, preview.label, Toast.LENGTH_SHORT).show()
                } else {
                    onConfirm?.invoke(input())
                    dialog.dismiss()
                }
            }
            if (onUndo != null) {
                dialog.setButton(AlertDialog.BUTTON_NEUTRAL, "撤回上一手") { _, _ ->
                    onUndo?.invoke()
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun spinner(items: List<String>, selected: Int): Spinner =
        Spinner(context).apply {
            adapter = ArrayAdapter(
                context, android.R.layout.simple_spinner_item, items).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(selected.coerceIn(0, items.size - 1))
        }

    private fun row(label: String, view: Spinner): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
            addView(TextView(context).apply {
                text = label
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
                width = dp(78)
            })
            addView(view, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

    private fun dp(v: Int): Int =
        (v * context.resources.displayMetrics.density).toInt()
}
