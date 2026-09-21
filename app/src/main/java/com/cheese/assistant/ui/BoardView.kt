package com.cheese.assistant.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.cheese.assistant.board.Piece
import com.cheese.assistant.board.Position

/**
 * 阶段1的手动摆盘棋盘视图：
 * - 点击空白交叉点：循环放置当前选中棋子（或清除）
 * - 点击已有棋子：选中，再点空点即移动
 * - 简化交互：每次点击在"选棋子类型"面板配合下完成
 */
class BoardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var position: Position = Position.START
        set(value) { field = value; invalidate() }

    var currentBrush: Piece? = null   // 待放置的棋子
    var selected: Int = -1            // 已选中格 index

    var onPositionChanged: (() -> Unit)? = null

    private val linePaint = Paint().apply { color = Color.DKGRAY; strokeWidth = 3f }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; textSize = 42f; isFakeBoldText = true
    }
    private val selPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 255, 140, 0); style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        val cw = w / 9f; val ch = h / 10f
        val cx = { col: Int -> cw * (col + 0.5f) }
        val cy = { row: Int -> ch * (row + 0.5f) }

        // 横线 10 条
        for (r in 0..9) canvas.drawLine(cx(0), cy(r), cx(8), cy(r), linePaint)
        // 纵线 9 条（分两段避开楚河汉界）
        for (c in 0..8) {
            canvas.drawLine(cx(c), cy(0), cx(c), cy(4), linePaint)
            canvas.drawLine(cx(c), cy(5), cx(c), cy(9), linePaint)
        }
        // 九宫斜线
        canvas.drawLine(cx(3), cy(0), cx(5), cy(2), linePaint)
        canvas.drawLine(cx(5), cy(0), cx(3), cy(2), linePaint)
        canvas.drawLine(cx(3), cy(9), cx(5), cy(7), linePaint)
        canvas.drawLine(cx(5), cy(9), cx(3), cy(7), linePaint)

        // 选中高亮
        if (selected >= 0) {
            canvas.drawRect(
                cx(selected % 9) - cw / 2, cy(selected / 9) - ch / 2,
                cx(selected % 9) + cw / 2, cy(selected / 9) + ch / 2, selPaint)
        }

        // 棋子
        for (idx in 0 until 90) {
            val p = position.board[idx] ?: continue
            val row = idx / 9; val col = idx % 9
            textPaint.color = if (p.isRed) Color.RED else Color.BLACK
            canvas.drawText(p.cnName, cx(col), cy(row) + 14f, textPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_DOWN) return true
        val cw = width / 9f; val ch = height / 10f
        val col = (event.x / cw).toInt().coerceIn(0, 8)
        val row = (event.y / ch).toInt().coerceIn(0, 9)
        val idx = row * 9 + col

        when {
            // 先点棋子 -> 选中
            selected == -1 && position.board[idx] != null -> selected = idx
            // 已有选中 -> 移动或放置到该点
            selected >= 0 -> {
                position.board[idx] = position.board[selected]
                if (selected != idx) position.board[selected] = null
                selected = -1
                invalidate(); onPositionChanged?.invoke()
            }
            // 空手点击 -> 用画笔放置
            currentBrush != null -> {
                position.board[idx] =
                    if (position.board[idx] == currentBrush) null else currentBrush
                invalidate(); onPositionChanged?.invoke()
            }
        }
        return true
    }
}
