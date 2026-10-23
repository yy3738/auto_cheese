package com.cheese.assistant.recognize

import android.graphics.Bitmap

/**
 * 帧采样公共工具：从 Bitmap 提取引擎输入。CnnEngine 与影子对照共用，
 * 保证两处输入管线一致（面积均值缩放，不做 overlay 清理——训练样本
 * 本来就含光圈污染）。
 *
 * RGB 3 通道（2026-10-19）：灰度下红/黑子与石纹挤在同一通道，石纹空格
 * 的置信度常年卡在 0.5-0.9（实测同皮肤训过的格子仍拒判）；颜色域里
 * 红子/黑子/棋盘底色是三种颜色，EMPTY 与棋子几乎不可能混淆。
 * 输出行优先、通道平面排列 [ch][y][x]（与 PyTorch (N,C,H,W) 一致）。
 */
object FrameSampling {

    /**
     * 从 bmp 的 [x0, x0+size) × [y0, y0+size) 区域面积均值缩到 3×32×32，
     * 通道平面排列。调用方保证区域不越界。
     */
    fun toRgb32(bmp: Bitmap, x0: Int, y0: Int, size: Int): FloatArray {
        val px = IntArray(size * size)
        bmp.getPixels(px, 0, size, x0, y0, size, size)
        return toRgb32(px, size, size, 0, 0, size)
    }

    /** 像素数组版本，输出 3×32×32 通道平面排列。 */
    fun toRgb32(px: IntArray, boxW: Int, boxH: Int, x0: Int, y0: Int, size: Int): FloatArray =
        toRgb(px, boxW, boxH, x0, y0, size, 32)

    /**
     * 双线性重采样到 3×out×out 通道平面(2026-10-21): 输入 96 时代降采样
     * 面积均值可用,Plan A 升 128 后成为上采样——面积均值的块复制马赛克
     * 与训练管线(PIL BILINEAR)分布偏离, 置信全场塌进 0.6-0.9 拒判带。
     * 这里改为双线性, 与训练/PC 对拍管线一致。
     * boxW/boxH 为像素数组尺寸, [x0,y0,x0+size,y0+size) 为采样窗。
     */
    fun toRgb(px: IntArray, boxW: Int, boxH: Int, x0: Int, y0: Int,
              size: Int, out: Int): FloatArray {
        val buf = FloatArray(3 * out * out)
        val plane = out * out
        fun fetch(x: Int, y: Int, ch: Int): Float {
            val xx = x.coerceIn(0, boxW - 1)
            val yy = y.coerceIn(0, boxH - 1)
            val c = px[yy * boxW + xx]
            return when (ch) {
                0 -> c shr 16 and 0xFF
                1 -> c shr 8 and 0xFF
                else -> c and 0xFF
            }.toFloat()
        }
        for (oy in 0 until out) {
            val fy = (oy + 0.5f) * size / out - 0.5f
            val iy = fy.toInt().coerceAtLeast(0)
            val wy = (fy - iy).coerceIn(0f, 1f)
            for (ox in 0 until out) {
                val fx = (ox + 0.5f) * size / out - 0.5f
                val ix = fx.toInt().coerceAtLeast(0)
                val wx = (fx - ix).coerceIn(0f, 1f)
                val o = oy * out + ox
                for (ch in 0 until 3) {
                    val c00 = fetch(x0 + ix, y0 + iy, ch)
                    val c10 = fetch(x0 + ix + 1, y0 + iy, ch)
                    val c01 = fetch(x0 + ix, y0 + iy + 1, ch)
                    val c11 = fetch(x0 + ix + 1, y0 + iy + 1, ch)
                    val top = c00 + (c10 - c00) * wx
                    val bot = c01 + (c11 - c01) * wx
                    buf[ch * plane + o] = top + (bot - top) * wy
                }
            }
        }
        return buf
    }
}