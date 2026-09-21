package com.cheese.assistant.engine

/**
 * 解析 Pikafish 的 UCI 输出行。
 *
 * info depth 10 seldepth 14 multipv 1 score cp 128 nodes ... pv h2e2 h9g8 ...
 * info depth 10 ... score mate 3 ... pv ...
 * bestmove h2e2 ponder ...
 */
data class Candidate(
    val rank: Int,          // multipv 序号，1 = 最佳
    val scoreCp: Int?,      // 厘兵评分，正数 = 红优；mate 时为 null
    val mateIn: Int?,       // N 步内必杀，非杀局面为 null
    val depth: Int,
    val pv: List<String>,   // 主变着法序列，ICCS 格式
)

sealed class UciLine {
    data class Info(val candidate: Candidate) : UciLine()
    data class BestMove(val move: String) : UciLine()
    data object ReadyOk : UciLine()
    data object UciOk : UciLine()
    data class Other(val raw: String) : UciLine()
}

object UciParser {

    fun parse(line: String): UciLine = when {
        line == "readyok" -> UciLine.ReadyOk
        line == "uciok" -> UciLine.UciOk
        line.startsWith("bestmove") ->
            UciLine.BestMove(line.split(" ").getOrNull(1)?.takeIf { it != "(none)" } ?: "")
        line.startsWith("info") && " pv " in line -> parseInfo(line) ?: UciLine.Other(line)
        else -> UciLine.Other(line)
    }

    private fun parseInfo(line: String): UciLine? {
        val tokens = line.split(" ")
        fun valueAfter(key: String): String? =
            tokens.indexOf(key).takeIf { it >= 0 }?.let { tokens.getOrNull(it + 1) }

        val multipv = valueAfter("multipv")?.toInt() ?: 1
        val depth = valueAfter("depth")?.toInt() ?: return null

        val scoreIdx = tokens.indexOf("score")
        var scoreCp: Int? = null
        var mateIn: Int? = null
        if (scoreIdx >= 0) {
            when (tokens.getOrNull(scoreIdx + 1)) {
                "cp" -> scoreCp = tokens.getOrNull(scoreIdx + 2)?.toInt()
                "mate" -> mateIn = tokens.getOrNull(scoreIdx + 2)?.toInt()
            }
        }

        val pvIdx = tokens.indexOf("pv")
        val pv = tokens.drop(pvIdx + 1).filter { it.isNotBlank() && it != "ponder" }
        if (pv.isEmpty()) return null

        return UciLine.Info(
            Candidate(
                rank = multipv,
                scoreCp = scoreCp,
                mateIn = mateIn,
                depth = depth,
                pv = pv,
            )
        )
    }
}
