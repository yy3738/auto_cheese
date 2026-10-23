package com.cheese.assistant.recognize

import android.graphics.Bitmap
import com.cheese.assistant.board.Piece
import com.cheese.assistant.util.AppLog

/**
 * CNN 识别引擎（MobileNetV3-Small ONNX + 懒推理）。
 *
 * 懒推理（2026-10-20, 方案A 的算力前提）: 大模型 90 格整帧推理代价高
 * （非常量约 0.5-1s), 而相邻帧棋盘 90 格几乎全部像素不变。每格保存
 * 12 点灰度指纹: 指纹未变的格直接沿用上帧分类结果, 只对新指纹格做
 * 批量推理; 变化格过多(>40, 如整帧重建/镜头晃动)时退回全量推理。
 * 分类结果缓存与指纹同帧求值, 保证一致。
 *
 * 分层阈值(与用户确认的设计, 历史沿用):
 * - margin ≥0.9 CONFIDENT 直接采信
 * - 0.75-0.9 WEAK 静态可采信, 变化格交 AnchorRecovery 裁决
 * - <0.75 UNKNOWN 拒判
 */
class CnnEngine(private val net: OnnxCnn) : RecognizerEngine {

    override val name: String = "cnn"

    private val img = net.img

    // ---- 懒推理缓存（与 cachedFp 同帧求值）----
    private var cachedFp: IntArray? = null
    private var cachedCls: Array<String?> = arrayOfNulls(90)
    private var cachedMargin = FloatArray(90)
    /** 每格笔画红度（0-255 裁图中心最暗 18% 的 mean(R-G)） */
    private var cachedRedness = FloatArray(90) { Float.NaN }

    /**
     * 每格指纹：5×5 灰度点（覆盖到格子边缘，红/绿光圈套在棋子外圈也能察觉）
     * + 中心区平均 R-G 量化（颜色变化单独成项）。
     *
     * 旧版只采 3×3、±0.35 格距的灰度点：JJ 红色落点标记套在黑子上时，
     * 灰度采样点几乎不变 → 懒推理不重跑，错误分类被永久缓存。
     */
    private fun cellFp(bmp: Bitmap, grid: BoardGrid): IntArray? {
        val out = IntArray(90)
        val pts = intArrayOf(-2, -1, 0, 1, 2)
        val step = grid.cellSize * 0.225f
        // 颜色项：中心 70% 区域的 5×5 采样平均 R-G（16 级量化）
        val colorStep = grid.cellSize * 0.175f
        for (i in 0 until 90) {
            val r0 = i / 9; val c0 = i % 9
            val cx = grid.x(r0, c0)
            val cy = grid.y(r0, c0)
            var h = 17
            for (dy in pts) for (dx in pts) {
                val x = (cx + dx * step).toInt().coerceIn(0, bmp.width - 1)
                val y = (cy + dy * step).toInt().coerceIn(0, bmp.height - 1)
                val c = bmp.getPixel(x, y)
                val g = (0.299f * (c shr 16 and 0xFF) +
                    0.587f * (c shr 8 and 0xFF) + 0.114f * (c and 0xFF)) / 24f
                h = h * 31 + g.toInt()
            }
            var rg = 0f
            var n = 0
            for (dy in pts) for (dx in pts) {
                val x = (cx + dx * colorStep).toInt().coerceIn(0, bmp.width - 1)
                val y = (cy + dy * colorStep).toInt().coerceIn(0, bmp.height - 1)
                val c = bmp.getPixel(x, y)
                rg += ((c shr 16 and 0xFF) - (c shr 8 and 0xFF)).toFloat()
                n++
            }
            h = h * 31 + ((rg / n) / 16f).toInt()
            out[i] = h
        }
        return out
    }

    override fun recognize(bmp: Bitmap, grid: BoardGrid): EngineResult {
        val start = System.currentTimeMillis()
        val fp = cellFp(bmp, grid) ?: return emptyResult(grid)
        val prev = cachedFp
        // 待推理格: 无缓存 → 全量; 否则指纹变化格
        val dirty = ArrayList<Int>(16)
        for (i in 0 until 90) {
            if (prev == null || prev[i] != fp[i]) dirty.add(i)
        }
        val full = prev == null || dirty.size > 40
        val targets = if (full) (0 until 90).toList() else dirty
        if (targets.isNotEmpty()) {
            val half = (grid.cellSize * 0.45f).toInt().coerceAtLeast(24)
            val size = half * 2
            // 待采样格的外扩包围盒（限位在帧内）
            var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE
            var maxX = Int.MIN_VALUE; var maxY = Int.MIN_VALUE
            for (i0 in targets) {
                val r0 = i0 / 9; val c0 = i0 % 9
                minX = minOf(minX, (grid.x(r0, c0) - half).toInt())
                minY = minOf(minY, (grid.y(r0, c0) - half).toInt())
                maxX = maxOf(maxX, (grid.x(r0, c0) + half).toInt())
                maxY = maxOf(maxY, (grid.y(r0, c0) + half).toInt())
            }
            minX = minX.coerceIn(0, bmp.width - 1)
            minY = minY.coerceIn(0, bmp.height - 1)
            val bw = (maxX + 1).coerceAtMost(bmp.width) - minX
            val bh = (maxY + 1).coerceAtMost(bmp.height) - minY
            val px = IntArray(maxOf(1, bw * bh))
            if (bw > 0 && bh > 0) {
                bmp.getPixels(px, 0, bw, minX, minY, bw, bh)
            }
            if (full) {
                cachedCls = arrayOfNulls(90)
                cachedMargin = FloatArray(90)
                cachedFp = IntArray(90)
                cachedRedness = FloatArray(90) { Float.NaN }
            }
            // 采样 96×96 → 单次批量推理
            val crops = ArrayList<FloatArray>(targets.size)
            val okIdx = ArrayList<Int>(targets.size)
            for (i in targets) {
                val r0 = i / 9; val c0 = i % 9
                val x0 = grid.x(r0, c0).toInt() - half - minX
                val y0 = grid.y(r0, c0).toInt() - half - minY
                if (x0 < 0 || y0 < 0 || x0 + size > bw || y0 + size > bh) {
                    cachedCls[i] = "OOB"; cachedMargin[i] = -1f
                    continue
                }
                crops.add(FrameSampling.toRgb(px, bw, bh, x0, y0, size, img))
                okIdx.add(i)
            }
            val out = net.classifyBatch(crops)
            for ((k, i) in okIdx.withIndex()) {
                cachedCls[i] = net.classes[out[k].first]
                cachedMargin[i] = out[k].second
                cachedFp!![i] = fp[i]
                cachedRedness[i] = glyphRedness(crops[k])
            }
        }
        AppLog.d("engine", "cnn lazy: dirty=${dirty.size}/90 full=$full " +
            "${System.currentTimeMillis() - start}ms")
        return assemble(grid, System.currentTimeMillis() - start)
    }

    /**
     * 笔画红度：裁图中心 18%-82% 内最暗 18% 像素的平均 R-G。
     * 黑子≈1-3，红子≈76-113（实测数据集），白圈/绿圈/红圈都不会改变
     * 这个量——红圈套黑子时最暗像素仍是黑色笔画。
     */
    private fun glyphRedness(rgb: FloatArray): Float {
        val out = img
        val plane = out * out
        val lo = (out * 0.18f).toInt()
        val hi = (out * 0.82f).toInt()
        val hist = IntArray(256)
        var n = 0
        for (y in lo until hi) for (x in lo until hi) {
            val idx = y * out + x
            val lum = 0.299f * rgb[idx] + 0.587f * rgb[plane + idx] +
                0.114f * rgb[2 * plane + idx]
            hist[lum.toInt().coerceIn(0, 255)]++
            n++
        }
        if (n == 0) return Float.NaN
        val target = (n * 0.18f).toInt().coerceAtLeast(1)
        var acc = 0
        var th = 0
        for (v in 0 until 256) {
            acc += hist[v]
            if (acc >= target) { th = v; break }
        }
        var sr = 0f; var sg = 0f; var cnt = 0
        for (y in lo until hi) for (x in lo until hi) {
            val idx = y * out + x
            val r = rgb[idx]
            val g = rgb[plane + idx]
            val lum = 0.299f * r + 0.587f * g + 0.114f * rgb[2 * plane + idx]
            if (lum <= th + 2) { sr += r; sg += g; cnt++ }
        }
        return if (cnt == 0) Float.NaN else (sr - sg) / cnt
    }

    /** 颜色门：模型颜色与笔画颜色矛盾时按笔画颜色改回同形棋子。 */
    private fun colorGuard(i: Int): String? {
        val cls0 = cachedCls[i] ?: return null
        if (cls0 == "EMPTY" || cls0 == "OOB" || cls0 == "UNKNOWN") return null
        val rg = cachedRedness[i]
        if (rg.isNaN()) return null
        val predRed = cls0.startsWith("R_")
        val name = cls0.substringAfter('_')
        if (predRed && rg < BLACK_RG_MAX) {
            val fixed = "B_$name"
            cachedCls[i] = fixed
            AppLog.w("engine", "color gate: $cls0→$fixed at (${i / 9},${i % 9}) R-G=$rg")
            return fixed
        }
        if (!predRed && rg > RED_RG_MIN) {
            val fixed = "R_$name"
            cachedCls[i] = fixed
            AppLog.w("engine", "color gate: $cls0→$fixed at (${i / 9},${i % 9}) R-G=$rg")
            return fixed
        }
        return null
    }

    override fun invalidate(cells: Collection<Int>) {
        val fp = cachedFp ?: return
        for (i in cells) if (i in 0 until 90) fp[i] = -1
        AppLog.d("engine", "invalidate cells=${cells.joinToString()}")
    }

    override fun invalidateAll() {
        cachedFp = null
        AppLog.d("engine", "invalidate all (next frame full inference)")
    }

    companion object {
        /** 黑子笔画 R-G 上限（数据集 p95≈2.8，留足余量） */
        private const val BLACK_RG_MAX = 35f
        /** 红子笔画 R-G 下限（数据集 p5≈76.5） */
        private const val RED_RG_MIN = 60f
    }

    /** 组装 board/confidence（含棋规先验过滤） */
    private fun assemble(grid: BoardGrid, costMs: Long): EngineResult {
        val board = arrayOfNulls<Piece>(90)
        val confidence = Array(90) { EngineResult.Confidence.CONFIDENT }
        var weak = 0; var unknown = 0
        for (i in 0 until 90) {
            val r0 = i / 9; val c0 = i % 9
            colorGuard(i)
            var cls = cachedCls[i]
            val margin = cachedMargin[i]
            if (cls == "OOB" || cls == null) {
                confidence[i] = EngineResult.Confidence.UNKNOWN
                unknown++
                continue
            }
            if (cls != "EMPTY" && !isLegalPlacement(r0, c0, cls)) {
                cls = "UNKNOWN" // 棋规不可能位: 幻觉, 拒判
            }
            when {
                cls == "UNKNOWN" || margin < 0.75f -> {
                    confidence[i] = EngineResult.Confidence.UNKNOWN; unknown++
                }
                margin < 0.9f -> {
                    confidence[i] = EngineResult.Confidence.WEAK; weak++
                    if (cls != "EMPTY") board[i] = Piece.valueOf(cls)
                    // EMPTY WEAK: board 保持 null
                }
                else -> {
                    if (cls != "EMPTY") board[i] = Piece.valueOf(cls)
                }
            }
        }
        val sb = StringBuilder()
        for (r in 0 until 10) {
            val cells = (0 until 9).joinToString(" ") { c ->
                val idx = r * 9 + c
                when (confidence[idx]) {
                    EngineResult.Confidence.UNKNOWN -> "？"
                    else -> board[idx]?.cnName ?: "・"
                }
            }
            sb.append(r).append(' ').append(cells).append('\n')
        }
        sb.append("weak=$weak unknown=$unknown ${costMs}ms")
        AppLog.d("engine", "cnn frame: weak=$weak unknown=$unknown ${costMs}ms")
        return EngineResult(board, confidence, sb.toString(), cachedMargin.copyOf())
    }

    private fun emptyResult(grid: BoardGrid): EngineResult =
        EngineResult(arrayOfNulls(90),
            Array(90) { EngineResult.Confidence.UNKNOWN }, "fp null")

    /**
     * 棋类-位置合法性（识别矩阵坐标）。双向许可: 屏幕红黑方向随执方翻转。
     */
    private fun isLegalPlacement(row: Int, col: Int, cls: String): Boolean = when (cls) {
        "B_ADVISOR", "R_ADVISOR" -> inPalace(row, col) && inAdvisorSlot(row, col)
        "B_KING", "R_KING" -> inPalace(row, col)
        "B_BISHOP", "R_BISHOP" -> inBishopRange(row, col)
        else -> true
    }

    private fun inPalace(row: Int, col: Int): Boolean =
        col in 3..5 && (row <= 2 || row >= 7)

    private fun inAdvisorSlot(row: Int, col: Int): Boolean {
        val top = row == 0 && (col == 3 || col == 5) || row == 1 && col == 4 ||
            row == 2 && (col == 3 || col == 5)
        val bottom = row == 9 && (col == 3 || col == 5) || row == 8 && col == 4 ||
            row == 7 && (col == 3 || col == 5)
        return top || bottom
    }

    private fun inBishopRange(row: Int, col: Int): Boolean {
        if (row <= 4) {
            return (row == 0 && (col == 2 || col == 6)) ||
                (row == 2 && (col == 0 || col == 4 || col == 8)) ||
                (row == 4 && (col == 2 || col == 6))
        }
        return (row == 5 && (col == 2 || col == 6)) ||
            (row == 7 && (col == 0 || col == 4 || col == 8)) ||
            (row == 9 && (col == 2 || col == 6))
    }
}
