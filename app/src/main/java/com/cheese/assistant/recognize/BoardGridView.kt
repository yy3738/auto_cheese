package com.cheese.assistant.recognize

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View

/**
 * 截图 + 90 交叉点视图：完整 letterbox 显示截图（不裁剪），
 * 点选最近交叉点；微调模式下可拖动 4 角实时重算网格。
 */
class BoardGridView(
    context: Context,
    private val bitmap: Bitmap,
    val grid: BoardGrid,
) : View(context) {

    var selectedIndex = -1
        private set
    var onSelected: ((Int) -> Unit)? = null

    /** 微调模式：拖动选中的角点 */
    var adjustMode = false
    var onGridChanged: (() -> Unit)? = null

    private var lastUnknown: BooleanArray? = null

    private var fit = 1f
    private var offX = 0f
    private var offY = 0f

    private val dotPaint = Paint().apply { color = Color.GREEN; style = Paint.Style.FILL }
    private val selPaint = Paint().apply {
        color = Color.YELLOW; style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val unknownPaint = Paint().apply {
        color = Color.RED; style = Paint.Style.STROKE; strokeWidth = 3f
    }

    fun showResult(unknown: BooleanArray) {
        lastUnknown = unknown.copyOf()
    }

    private fun updateFit() {
        if (width == 0 || height == 0) return
        fit = minOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        offX = (width - bitmap.width * fit) / 2f
        offY = (height - bitmap.height * fit) / 2f
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        updateFit()
    }

    override fun onDraw(canvas: Canvas) {
        updateFit()
        canvas.save()
        canvas.translate(offX, offY)
        canvas.scale(fit, fit)
        canvas.drawBitmap(bitmap, 0f, 0f, null)

        for (i in 0 until 90) {
            if (lastUnknown?.get(i) == true) {
                canvas.drawCircle(grid.xs[i], grid.ys[i], 14f, unknownPaint)
            }
            canvas.drawCircle(grid.xs[i], grid.ys[i], 4f, dotPaint)
        }
        if (selectedIndex >= 0) {
            canvas.drawCircle(grid.xs[selectedIndex], grid.ys[selectedIndex], 16f, selPaint)
        }
        canvas.restore()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val bx = (event.x - offX) / fit
        val by = (event.y - offY) / fit
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                selectNearest(bx, by)
                if (adjustMode && selectedIndex >= 0) moveSelected(bx, by)
            }
            MotionEvent.ACTION_MOVE -> if (adjustMode && selectedIndex >= 0) moveSelected(bx, by)
        }
        invalidate()
        return true
    }

    private fun selectNearest(bx: Float, by: Float) {
        var bestIdx = -1
        var bestD = Float.MAX_VALUE
        for (i in 0 until 90) {
            val dx = grid.xs[i] - bx
            val dy = grid.ys[i] - by
            val d = dx * dx + dy * dy
            if (d < bestD) { bestD = d; bestIdx = i }
        }
        selectedIndex = bestIdx
        onSelected?.invoke(bestIdx)
    }

    private fun moveSelected(bx: Float, by: Float) {
        grid.moveCorner(selectedIndex, bx, by)
        onGridChanged?.invoke()
    }
}
