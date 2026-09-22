package com.cheese.assistant.recognize

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 棋子取样：从截图截取交叉点附近的正方形窗口，缩到 SIZE x SIZE，
 * RGB 三通道各自做零均值单位方差归一化（消除光照/对比度差异），
 * 匹配用平均绝对差。
 */
object Patch {
    const val SIZE = 24

    /** 以 (cx, cy) 为中心、边长 2*radius 的窗口 → 归一化特征向量 */
    fun extract(bmp: Bitmap, cx: Float, cy: Float, radius: Float): FloatArray {
        val x0 = (cx - radius).toInt().coerceIn(0, bmp.width - 1)
        val y0 = (cy - radius).toInt().coerceIn(0, bmp.height - 1)
        val x1 = (cx + radius).toInt().coerceIn(0, bmp.width - 1)
        val y1 = (cy + radius).toInt().coerceIn(0, bmp.height - 1)
        val w = maxOf(1, x1 - x0); val h = maxOf(1, y1 - y0)
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, x0, y0, w, h)

        val out = FloatArray(SIZE * SIZE * 3)
        for (oy in 0 until SIZE) {
            val sy = (oy * h / SIZE).coerceAtMost(h - 1)
            for (ox in 0 until SIZE) {
                val sx = (ox * w / SIZE).coerceAtMost(w - 1)
                val c = px[sy * w + sx]
                val i = (oy * SIZE + ox) * 3
                out[i] = (c shr 16 and 0xFF).toFloat()
                out[i + 1] = (c shr 8 and 0xFF).toFloat()
                out[i + 2] = (c and 0xFF).toFloat()
            }
        }
        return normalize(out)
    }

    /** 模板 Bitmap（SIZE x SIZE）→ 归一化特征向量 */
    fun fromBitmap(bmp: Bitmap): FloatArray {
        require(bmp.width == SIZE && bmp.height == SIZE) { "template must be ${SIZE}x$SIZE" }
        val px = IntArray(SIZE * SIZE)
        bmp.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
        val out = FloatArray(SIZE * SIZE * 3)
        for (i in px.indices) {
            val c = px[i]
            out[i * 3] = (c shr 16 and 0xFF).toFloat()
            out[i * 3 + 1] = (c shr 8 and 0xFF).toFloat()
            out[i * 3 + 2] = (c and 0xFF).toFloat()
        }
        return normalize(out)
    }

    fun normalize(v: FloatArray): FloatArray {
        val n = v.size / 3
        for (ch in 0..2) {
            var sum = 0f
            for (i in ch until v.size step 3) sum += v[i]
            val mean = sum / n
            var sq = 0f
            for (i in ch until v.size step 3) { val d = v[i] - mean; sq += d * d }
            val std = sqrt(sq / n).coerceAtLeast(0.5f)
            for (i in ch until v.size step 3) v[i] = (v[i] - mean) / std
        }
        return v
    }

    fun dist(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += abs(a[i] - b[i])
        return s / a.size
    }

    fun toBase64(bmp: Bitmap): String {
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
    }

    fun bitmapFromBase64(s: String): Bitmap {
        val bytes = android.util.Base64.decode(s, android.util.Base64.DEFAULT)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }
}
