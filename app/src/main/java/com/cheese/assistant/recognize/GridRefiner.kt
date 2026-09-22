package com.cheese.assistant.recognize

import android.graphics.Bitmap
import kotlin.math.abs

/**
 * 棋盘线吸附：校准的 4 角双线性插值有 2~8px 的系统性偏差（透视/镜头畸变），
 * 窗口错位会让相邻棋子（仕/车）的 NCC 距离竞争翻转。对每条棋盘线在预期
 * 位置附近扫描暗度峰（线比底色暗），再最小二乘拟合直线求交，得到贴合
 * 实际线的 90 个交叉点。与 PC 端 proto_recognize.refine_grid 一致。
 */
object GridRefiner {

    private const val SCAN = 12

    fun refine(bmp: Bitmap, raw: BoardGrid): BoardGrid {
        val w = bmp.width
        val h = bmp.height
        val gray = FloatArray(w * h)
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val c = px[i]
            gray[i] = 0.299f * (c shr 16 and 0xFF) +
                0.587f * (c shr 8 and 0xFF) + 0.114f * (c and 0xFF)
        }

        fun darkness(x: Int, y: Int): Float {
            if (x < 1 || x >= w - 1 || y < 1 || y >= h - 1) return 0f
            var s = 0f
            for (dy in -1..1) {
                val base = (y + dy) * w
                for (dx in -1..1) s += gray[base + x + dx]
            }
            return 255f - s / 9f
        }

        // 每条竖线：在 9 个中点高度附近横向扫描暗度峰，拟合 x = a + b*y
        val colFit = Array(9) { FloatArray(2) }
        val colOk = BooleanArray(9)
        for (k in 0 until 9) {
            val sy = ArrayList<Float>()
            val sx = ArrayList<Float>()
            for (i in 0 until 9) {
                val ym = (raw.y(i, k) + raw.y(i + 1, k)) / 2f
                val xm = (raw.x(i, k) + raw.x(i + 1, k)) / 2f
                val x0 = (xm.toInt() - SCAN).coerceAtLeast(1)
                val x1 = (xm.toInt() + SCAN).coerceAtMost(w - 2)
                if (x1 <= x0) continue
                var bx = x0; var bd = -1f
                for (x in x0..x1) {
                    val d = darkness(x, ym.toInt())
                    if (d > bd) { bd = d; bx = x }
                }
                sy.add(ym); sx.add(bx.toFloat())
            }
            if (sy.size >= 4) {
                colFit[k] = linfit(sy.toFloatArray(), sx.toFloatArray())
                colOk[k] = true
            }
        }

        // 每条横线：在 8 个中点横向位置附近纵向扫描暗度峰，拟合 y = c0 + d*x
        val rowFit = Array(10) { FloatArray(2) }
        val rowOk = BooleanArray(10)
        for (r in 0 until 10) {
            val sxv = ArrayList<Float>()
            val syv = ArrayList<Float>()
            for (c in 0 until 8) {
                val xm = (raw.x(r, c) + raw.x(r, c + 1)) / 2f
                val yq = raw.y(r, c).toInt()
                val y0 = (yq - SCAN).coerceAtLeast(1)
                val y1 = (yq + SCAN).coerceAtMost(h - 2)
                if (y1 <= y0) continue
                var by = y0; var bd = -1f
                for (y in y0..y1) {
                    val d = darkness(xm.toInt(), y)
                    if (d > bd) { bd = d; by = y }
                }
                sxv.add(xm); syv.add(by.toFloat())
            }
            if (sxv.size >= 5) {
                rowFit[r] = linfit(sxv.toFloatArray(), syv.toFloatArray())
                rowOk[r] = true
            }
        }

        val xs = FloatArray(90)
        val ys = FloatArray(90)
        for (r in 0 until 10) {
            for (c in 0 until 9) {
                if (colOk[c] && rowOk[r]) {
                    // 拟合式与 PC 一致：y 由行线在 raw.x 处取值，x 由列线在 y 处取值
                    val y = rowFit[r][1] + rowFit[r][0] * raw.x(r, c)
                    val x = colFit[c][1] + colFit[c][0] * y
                    xs[r * 9 + c] = x
                    ys[r * 9 + c] = y
                } else {
                    xs[r * 9 + c] = raw.x(r, c)
                    ys[r * 9 + c] = raw.y(r, c)
                }
            }
        }
        return BoardGrid.fromPoints(xs, ys)
    }

    /** 最小二乘 v = slope*t + intercept，返回 [slope, intercept] */
    private fun linfit(t: FloatArray, v: FloatArray): FloatArray {
        var st = 0f; var sv = 0f
        for (i in t.indices) { st += t[i]; sv += v[i] }
        val mt = st / t.size; val mv = sv / t.size
        var num = 0f; var den = 0f
        for (i in t.indices) {
            val dt = t[i] - mt; num += dt * (v[i] - mv); den += dt * dt
        }
        val slope = num / den
        return floatArrayOf(slope, mv - slope * mt)
    }
}
