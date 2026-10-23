package com.cheese.assistant.recognize

import android.graphics.Bitmap
import com.cheese.assistant.board.Piece

/**
 * NCC 识别引擎（策略实现一）：原 BoardRecognizer 主体逻辑整体迁移，
 * 代码语义不变（判空门/红黑门/光圈处理/调试文本全部保留）。
 * 手动识别工作台（RecognizeActivity）与 NCC 回归测试仍走此引擎。
 */
class NccEngine(private val templates: TemplateStore) : RecognizerEngine {

    override val name: String = "ncc"

    override fun recognize(bmp: Bitmap, grid: BoardGrid): EngineResult {
        val tmpl = templates.entries
            .filter { it.bitmap.width == Features.T && it.bitmap.height == Features.T }
            .map { it.piece to Features.of(it.bitmap) }

        val radius = grid.cellSize * 0.48f
        val board = arrayOfNulls<Piece>(90)
        val confidence = Array(90) { EngineResult.Confidence.CONFIDENT }
        val debug = StringBuilder()

        for (i in 0 until 90) {
            val row = i / 9; val col = i % 9
            val px = grid.x(row, col); val py = grid.y(row, col)
            val feat = Features.ofBmp(bmp, px, py, radius)
            val glow = feat.whiteRatio > BoardRecognizer.GLOW_RATIO_MIN
            val sdGate = if (glow) BoardRecognizer.GLOW_SD_MIN else BoardRecognizer.SD_MIN
            if (feat.std < sdGate) continue // 判空
            if (tmpl.isEmpty()) { confidence[i] = EngineResult.Confidence.UNKNOWN; continue }
            var bestPiece = "EMPTY"; var bestDist = Float.MAX_VALUE
            for ((name, tf) in tmpl) {
                if (feat.redness > BoardRecognizer.REDNESS_GATE) {
                    if (!name.startsWith("R_")) continue
                } else if (name.startsWith("R_")) continue
                val d = Features.dist(feat, tf)
                if (d < bestDist) { bestDist = d; bestPiece = name }
            }
            if (bestDist > (if (glow) BoardRecognizer.GLOW_UNKNOWN_DIST
                    else BoardRecognizer.UNKNOWN_DIST)) {
                confidence[i] = EngineResult.Confidence.UNKNOWN
                debug.append("($row,$col) d=$bestDist  ")
                if (col == 2) debug.append('\n')
                continue
            }
            if (bestPiece != "EMPTY") board[i] = Piece.valueOf(bestPiece)
        }

        val sb = StringBuilder()
        for (r in 0 until 10) {
            val cells = (0 until 9).joinToString(" ") { c ->
                val idx = r * 9 + c
                if (confidence[idx] == EngineResult.Confidence.UNKNOWN) "？"
                else board[idx]?.cnName ?: "・"
            }
            sb.append(r).append(' ').append(cells).append('\n')
        }
        if (debug.isNotEmpty()) sb.append("— 未定点 —\n").append(debug)
        return EngineResult(board, confidence, sb.toString())
    }
}
