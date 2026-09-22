package com.cheese.assistant.recognize

import android.content.Context
import org.json.JSONObject
import kotlin.math.hypot

/**
 * 棋盘网格：由校准 JSON（4 外角像素坐标）双线性插值出 90 个交叉点。
 * index = row * 9 + col，row 0 = 校准时"上外角"一侧（通常是黑方底线）。
 */
class BoardGrid private constructor(
    val xs: FloatArray,
    val ys: FloatArray,
) {

    fun x(row: Int, col: Int): Float = xs[row * 9 + col]
    fun y(row: Int, col: Int): Float = ys[row * 9 + col]

    /** 微调：仅接受 4 角（index 0=左上 8=右上 89=右下 81=左下），拖动后整体重算 */
    fun moveCorner(idx: Int, nx: Float, ny: Float) {
        when (idx) {
            0 -> tl = nx to ny
            8 -> tr = nx to ny
            89 -> br = nx to ny
            81 -> bl = nx to ny
            else -> return
        }
        recompute()
    }

    fun save(context: Context, targetApp: String, capturedW: Int, capturedH: Int) {
        fun pt(x: Float, y: Float) =
            JSONObject().put("x", x.toDouble()).put("y", y.toDouble())
        val o = JSONObject()
            .put("capturedWidth", capturedW)
            .put("capturedHeight", capturedH)
            .put("tl", pt(tl.first, tl.second)).put("tr", pt(tr.first, tr.second))
            .put("br", pt(br.first, br.second)).put("bl", pt(bl.first, bl.second))
        com.cheese.assistant.capture.CalibrateActivity
            .calibrationFile(context, targetApp).writeText(o.toString())
    }

    private var tl = 0f to 0f
    private var tr = 0f to 0f
    private var br = 0f to 0f
    private var bl = 0f to 0f

    private fun recompute() {
        val (tlx, tly) = tl; val (trx, try_) = tr
        val (brx, bry) = br; val (blx, bly) = bl
        for (row in 0 until 10) {
            val v = row / 9f
            for (col in 0 until 9) {
                val u = col / 8f
                val idx = row * 9 + col
                val topX = lerp(tlx, trx, u); val topY = lerp(tly, try_, u)
                val botX = lerp(blx, brx, u); val botY = lerp(bly, bry, u)
                xs[idx] = lerp(topX, botX, v)
                ys[idx] = lerp(topY, botY, v)
            }
        }
    }

    /** 平均格距（像素），用于确定取样窗口大小 */
    val cellSize: Float by lazy {
        var sum = 0f; var n = 0
        for (r in 0 until 10) for (c in 0 until 8) {
            val i = r * 9 + c
            sum += hypot(xs[i + 1] - xs[i], ys[i + 1] - ys[i]); n++
        }
        for (r in 0 until 9) for (c in 0 until 9) {
            val i = r * 9 + c
            sum += hypot(xs[i + 9] - xs[i], ys[i + 9] - ys[i]); n++
        }
        sum / n
    }

    companion object {
        fun load(context: Context, targetApp: String): BoardGrid? {
            val f = com.cheese.assistant.capture.CalibrateActivity.calibrationFile(context, targetApp)
            if (!f.exists()) return null
            return runCatching { fromJson(f.readText()) }.getOrNull()
        }

        fun fromJson(text: String): BoardGrid {
            val o = JSONObject(text)
            fun pt(name: String): Pair<Float, Float> {
                val p = o.getJSONObject(name)
                return p.getDouble("x").toFloat() to p.getDouble("y").toFloat()
            }
            val tl = pt("tl"); val tr = pt("tr")
            val br = pt("br"); val bl = pt("bl")
            val grid = BoardGrid(FloatArray(90), FloatArray(90))
            grid.tl = tl; grid.tr = tr; grid.br = br; grid.bl = bl
            grid.recompute()
            return grid
        }

        /** 由（线吸附后的）90 个显式交叉点构建，仅用于识别路径 */
        fun fromPoints(xs: FloatArray, ys: FloatArray): BoardGrid =
            BoardGrid(xs.copyOf(), ys.copyOf())

        private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    }
}
