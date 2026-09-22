package com.cheese.assistant.recognize

import android.graphics.Bitmap
import com.cheese.assistant.board.Piece
import com.cheese.assistant.board.Position
import kotlin.math.sqrt

/**
 * 棋盘识别 v2：24x24 灰度窗口 + 中心圆掩码归一化 + NCC 平移搜索最近邻。
 * 判空：窗口灰度标准差（棋子方差大、空木纹方差小，PC 验证占用>=39.8 空位<=25.4）。
 * 红黑：暗像素 (R-G) 均值硬门（红 65-82 / 黑 7-30 / 空 25-27，>40 只配红方模板）。
 */
object BoardRecognizer {

    /** 窗口灰度标准差低于该值判空 */
    const val SD_MIN = 32f

    /** redness 超过该值视为红方查询 */
    const val REDNESS_GATE = 40f

    /** 形状距离超过该值视为不确定 */
    const val UNKNOWN_DIST = 0.75f

    class Result(
        val position: Position,
        val unknown: BooleanArray,
        val matrixText: String,
    ) {
        val fen: String get() = position.toFen()
        val hasUnknown: Boolean get() = unknown.any { it }
    }

    fun recognize(bmp: Bitmap, rawGrid: BoardGrid, templates: TemplateStore): Result {
        // 线吸附：先让 90 个交叉点贴合实际棋盘线再匹配
        val grid = GridRefiner.refine(bmp, rawGrid)
        val tmpl = templates.entries
            .filter { it.bitmap.width == Features.T && it.bitmap.height == Features.T }
            .map { it.piece to Features.of(it.bitmap) }

        val radius = grid.cellSize * 0.48f
        val board = arrayOfNulls<Piece>(90)
        val unknown = BooleanArray(90)
        val debug = StringBuilder()

        for (i in 0 until 90) {
            val row = i / 9; val col = i % 9
            val feat = Features.ofBmp(bmp, grid.x(row, col), grid.y(row, col), radius)
            if (feat.std < SD_MIN) continue
            if (tmpl.isEmpty()) { unknown[i] = true; continue }
            var bestPiece = "EMPTY"; var bestDist = Float.MAX_VALUE
            for ((name, tf) in tmpl) {
                // 硬门：红方查询只配红方模板，其余只配黑方+EMPTY
                if (feat.redness > REDNESS_GATE) {
                    if (!name.startsWith("R_")) continue
                } else if (name.startsWith("R_")) continue
                val d = Features.dist(feat, tf)
                if (d < bestDist) { bestDist = d; bestPiece = name }
            }
            if (bestDist > UNKNOWN_DIST) {
                unknown[i] = true
                debug.append("($row,$col) d=$bestDist  ")
                if (col == 2) debug.append('\n')
                continue
            }
            if (bestPiece != "EMPTY") board[i] = Piece.valueOf(bestPiece)
        }

        val sb = StringBuilder()
        for (r in 0 until 10) {
            val cells = (0 until 9).joinToString(" ") { i ->
                val idx = r * 9 + i
                if (unknown[idx]) "？" else board[idx]?.cnName ?: "・"
            }
            sb.append(r).append(' ').append(cells).append('\n')
        }
        if (debug.isNotEmpty()) sb.append("— 未定点 —\n").append(debug)
        return Result(Position(board, true), unknown, sb.toString())
    }
}

/**
 * 特征：T x T 灰度零均值单位方差归一化（中心圆掩码）+ 窗口标准差 + redness。
 * 与 PC 端 tools/proto_recognize.py v2 一致。
 */
object Features {

    const val T = 24
    private const val SHIFT = 3

    /** 惩罚权重与 Python 端一致：W_SIDE*(r1-r2)^2/100 */
    private const val W_SIDE = 0.05f

    private val CC = (T - 1) / 2f

    class F(val q: FloatArray, val std: Float, val redness: Float)

    fun of(bitmap: Bitmap): F = ofBmp(
        bitmap, bitmap.width / 2f, bitmap.height / 2f,
        minOf(bitmap.width, bitmap.height) / 2f)

    fun ofBmp(bmp: Bitmap, cx: Float, cy: Float, radius: Float): F {
        val x0 = (cx - radius).toInt().coerceIn(0, bmp.width - 2)
        val y0 = (cy - radius).toInt().coerceIn(0, bmp.height - 2)
        val x1 = (cx + radius).toInt().coerceIn(x0 + 2, bmp.width)
        val y1 = (cy + radius).toInt().coerceIn(y0 + 2, bmp.height)
        // 用 BOX 区域平均缩到 24x24：Android createScaledBitmap 的双线性在
        // ~4.7:1 缩小时每目标点只采 2x2 源像素，混叠严重导致 NCC 距离劣化
        // （实测 (9,6) d 0.55→0.98）。模板是 BOX 缩放的，查询窗口必须一致。
        val win = Bitmap.createBitmap(bmp, x0, y0, x1 - x0, y1 - y0)
        val wpx = IntArray(win.width * win.height)
        win.getPixels(wpx, 0, win.width, 0, 0, win.width, win.height)
        removeRedOverlay(wpx, win.width, win.height)

        val gray = FloatArray(T * T)
        val rCh = FloatArray(T * T)
        val gCh = FloatArray(T * T)
        for (j in 0 until T) {
            val sy0 = j * win.height / T
            val sy1 = maxOf(sy0 + 1, (j + 1) * win.height / T)
            for (i in 0 until T) {
                val sx0 = i * win.width / T
                val sx1 = maxOf(sx0 + 1, (i + 1) * win.width / T)
                var sg = 0f; var sr = 0f; var sgn = 0f; var n = 0
                for (y in sy0 until sy1) {
                    val base = y * win.width
                    for (x in sx0 until sx1) {
                        val c = wpx[base + x]
                        val r = (c shr 16 and 0xFF).toFloat()
                        val g = (c shr 8 and 0xFF).toFloat()
                        val b = (c and 0xFF).toFloat()
                        sg += 0.299f * r + 0.587f * g + 0.114f * b
                        sr += r; sgn += g; n++
                    }
                }
                val idx = j * T + i
                gray[idx] = sg / n
                rCh[idx] = sr / n
                gCh[idx] = sgn / n
            }
        }
        return analyze(gray, rCh, gCh)
    }

    private val MR2 = (0.72f * T / 2f) * (0.72f * T / 2f)

    /** 灰度图 → 归一化特征 + std + redness（输入 rCh/gCh 供 redness 用） */
    private fun analyze(gray: FloatArray, rCh: FloatArray, gCh: FloatArray): F {
        var sum = 0f
        for (v in gray) sum += v
        val mean = sum / gray.size
        var sq = 0f
        for (v in gray) { val d = v - mean; sq += d * d }
        val std = sqrt(sq / gray.size)
        val norm = maxOf(8f, std)
        val q = FloatArray(gray.size)
        for (i in q.indices) q[i] = (gray[i] - mean) / norm

        val med = median(gray)
        var rdSum = 0f; var n = 0
        for (i in gray.indices) {
            if (MASK[i] && gray[i] < med - 30f) { rdSum += rCh[i] - gCh[i]; n++ }
        }
        val redness = if (n == 0) 0f else rdSum / n
        return F(q, std, redness)
    }

    private fun median(a: FloatArray): Float {
        val v = a.copyOf(); v.sort()
        return v[v.size / 2]
    }

    /**
     * JJ 等棋盘 App 会在棋盘上画纯红落子/选中标记框，混入查询窗口会污染
     * NCC（实测仕/车距离差从 0.013 收窄到修复后 0.03 级余量）。把标记像素
     * （亮度高且 R 远超 G/B）膨胀 1px 吃掉抗锯齿晕，替换为窗口中值灰度。
     * 红棋字色 R≈137 低于标记 R≈201，不受影响。
     */
    private fun removeRedOverlay(wpx: IntArray, w: Int, h: Int) {
        val ov = BooleanArray(wpx.size)
        var any = false
        for (i in wpx.indices) {
            val c = wpx[i]
            val r = c shr 16 and 0xFF
            val g = c shr 8 and 0xFF
            val b = c and 0xFF
            if (r > 170 && r - g > 120 && r - b > 120) { ov[i] = true; any = true }
        }
        if (!any) return
        val gray = FloatArray(wpx.size)
        for (i in wpx.indices) {
            val c = wpx[i]
            gray[i] = 0.299f * (c shr 16 and 0xFF) +
                0.587f * (c shr 8 and 0xFF) + 0.114f * (c and 0xFF)
        }
        val keep = FloatArray(wpx.size)
        var n = 0
        for (i in gray.indices) if (!ov[i]) { keep[n++] = gray[i] }
        if (n == 0) return
        val med = median(keep.copyOf(n))
        val dil = BooleanArray(wpx.size)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (!ov[i]) continue
            dil[i] = true
            if (x > 0) dil[i - 1] = true
            if (x < w - 1) dil[i + 1] = true
            if (y > 0) dil[i - w] = true
            if (y < h - 1) dil[i + w] = true
        }
        val neutral = (med.toInt() and 0xFF) * 0x10101
        for (i in wpx.indices) if (dil[i]) wpx[i] = neutral
    }

    fun dist(a: F, b: F): Float {
        var best = Float.MAX_VALUE
        for (dy in -SHIFT..SHIFT) {
            val y0 = maxOf(0, dy); val y1 = minOf(T, T + dy)
            for (dx in -SHIFT..SHIFT) {
                val x0 = maxOf(0, dx); val x1 = minOf(T, T + dx)
                var sumAB = 0f; var sumAA = 0f; var sumBB = 0f; var n = 0
                for (y in y0 until y1) {
                    val qa = y * T; val qb = (y - dy) * T
                    for (x in x0 until x1) {
                        if (!MASK[y * T + x]) continue
                        val va = a.q[qa + x]; val vb = b.q[qb + (x - dx)]
                        sumAB += va * vb; sumAA += va * va; sumBB += vb * vb
                        n++
                    }
                }
                if (n < 100) continue
                val den = sqrt(sumAA * sumBB)
                if (den < 1e-6f) continue
                val d = 1f - sumAB / den +
                    W_SIDE * (a.redness - b.redness) * (a.redness - b.redness) / 100f
                if (d < best) best = d
            }
        }
        return if (best == Float.MAX_VALUE) 1f else best
    }

    private val MASK: BooleanArray by lazy {
        BooleanArray(T * T) { i ->
            val dy = i / T - CC; val dx = i % T - CC
            dy * dy + dx * dx <= MR2
        }
    }
}
