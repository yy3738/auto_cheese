package com.cheese.assistant.review

import com.cheese.assistant.board.MoveFormatter
import com.cheese.assistant.board.Position
import com.cheese.assistant.board.Zobrist
import com.cheese.assistant.db.AppDb
import com.cheese.assistant.db.GameEntity
import com.cheese.assistant.db.PositionEntity
import com.cheese.assistant.engine.Candidate
import com.cheese.assistant.engine.SearchController
import org.json.JSONArray
import org.json.JSONObject

/** 复盘中一个用户行棋局面的分析结论 */
data class PositionReview(
    val fen: String,
    val playedIccs: String,
    val playedCn: String,
    val bestIccs: String,
    val bestCn: String,
    /** 最佳走法评分（走子方视角，厘兵）；杀棋时为 null */
    val bestScoreCp: Int?,
    val mateIn: Int?,
    /** 分差（厘兵，≥0）；用户着法=最佳时为 0 */
    val deltaCp: Int,
    val depth: Int,
) {
    /** ≥1.5 分失误，≥3 分大漏着（CONTEXT.md 坏棋标记阈值，单位:分 = 100cp） */
    val blunderLevel: Int
        get() = when {
            deltaCp >= 300 -> 2
            deltaCp >= 150 -> 1
            else -> 0
        }
}

/**
 * 复盘：对局封存后，对每个"轮到用户走"的局面以 3000ms + MultiPV=5 重搜，
 * 分差 = 最佳评分 − 用户实际选择评分，坏棋判定完全以复盘为准（CONTEXT.md）。
 * 用户着法不在 MultiPV=5 候选里时以 MultiPV=16 重搜一次（罕见，成本可忽略）。
 */
class GameReviewer(private val controller: SearchController) {

    companion object {
        private const val REVIEW_MOVETIME = 3000
        private const val REVIEW_MULTIPV = 5
        private const val WIDE_MULTIPV = 16
        /** 坏棋阈值（厘兵）：失误 150 / 大漏着 300 */
        const val MISTAKE_CP = 150
        const val BLUNDER_CP = 300
    }

    /**
     * 复盘一局。moves 为完整棋谱；userIsRed 决定分析哪些局面
     * （轮到用户走、且非开局初始局面——初始局面没有"用户选择"）。返回逐局面结论。
     *
     * @param onProgress 复盘进度回调（已完成/总局面数）。复盘在模拟器上
     *        每个 3000ms 搜索实际要 6s+，34 步的一局要几分钟；没有进度
     *        用户会以为悬浮窗卡死在「复盘中」。
     */
    suspend fun review(
        moves: List<MoveRecord>,
        userIsRed: Boolean,
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
        /** 每个局面开始前的让路钩子（异步复盘：实战优先，等实战空闲） */
        yieldGate: suspend () -> Unit = {},
        /** 单局面时长（共用引擎时降速，最坏只让实战等一次搜索） */
        movetimeFor: () -> Int = { REVIEW_MOVETIME },
    ): List<PositionReview> {
        val targets = moves.filter { m ->
            val redToMove = m.fenBefore.substringAfter(' ').startsWith("w")
            redToMove == userIsRed
        }
        val total = targets.size
        onProgress(0, total)
        val out = ArrayList<PositionReview>()
        for ((idx, m) in targets.withIndex()) {
            yieldGate()
            onProgress(idx, total)
            val r = analyzePosition(m.fenBefore, m.iccs, userIsRed, movetimeFor())
            if (r != null) out.add(r)
            onProgress(idx + 1, total)
        }
        return out
    }

    private suspend fun analyzePosition(
        fen: String,
        playedIccs: String,
        userIsRed: Boolean,
        movetime: Int,
    ): PositionReview? {
        val r1 = controller.search(fen, movetime, REVIEW_MULTIPV)
        val played = r1.candidates.firstOrNull { it.pv.firstOrNull() == playedIccs }
        val best = r1.best
        // 用户着法不在候选里：以更宽 MultiPV 重搜一次把用户着法包进来
        if (played == null) {
            val r2 = controller.search(fen, movetime, WIDE_MULTIPV)
            val wideBest = r2.best
            val widePlayed = r2.candidates.firstOrNull { it.pv.firstOrNull() == playedIccs }
            if (wideBest != null && widePlayed != null) {
                return build(fen, playedIccs, userIsRed, wideBest, widePlayed, r2.depth)
            }
            // 仍然包不进来（极罕见）：记"未按推荐"但无分差
            val b = r1.best ?: return null
            return PositionReview(
                fen, playedIccs, MoveFormatter.toChinese(fen, playedIccs),
                b.pv.firstOrNull() ?: "", cn(fen, b), b.scoreCp, b.mateIn,
                Int.MAX_VALUE, r1.depth,
            )
        }
        return build(fen, playedIccs, userIsRed, r1.best ?: return null, played, r1.depth)
    }

    private fun build(
        fen: String,
        playedIccs: String,
        @Suppress("UNUSED_PARAMETER") userIsRed: Boolean,
        best: Candidate,
        played: Candidate,
        depth: Int,
    ): PositionReview = PositionReview(
        fen = fen,
        playedIccs = playedIccs,
        playedCn = MoveFormatter.toChinese(fen, playedIccs),
        bestIccs = best.pv.firstOrNull() ?: "",
        bestCn = cn(fen, best),
        bestScoreCp = best.scoreCp,
        mateIn = best.mateIn,
        deltaCp = deltaOf(best, played),
        depth = depth,
    )

    private fun cn(fen: String, c: Candidate): String =
        c.pv.firstOrNull()?.let { MoveFormatter.toChinese(fen, it) } ?: ""

    private fun deltaOf(best: Candidate, played: Candidate): Int {
        // 杀棋局面：直接按 mate 差分级（被杀方看 mate 差也是巨大分差）
        val bm = best.mateIn
        val pm = played.mateIn
        if (bm != null && pm != null) {
            return if (bm == pm) 0 else MISTAKE_CP * (kotlin.math.abs(bm - pm) + 1)
        }
        if (bm != null) return BLUNDER_CP
        if (pm != null) return 0 // 用户直接杀还快于"最佳"线，不亏
        val b = best.scoreCp ?: return 0
        val p = played.scoreCp ?: return 0
        return (b - p).coerceAtLeast(0)
    }
}

/** 复盘结论 → review_json 字段 */
fun reviewsToJson(reviews: List<PositionReview>): String {
    val arr = JSONArray()
    for (r in reviews) {
        arr.put(
            JSONObject()
                .put("fen", r.fen)
                .put("played", r.playedIccs)
                .put("playedCn", r.playedCn)
                .put("best", r.bestIccs)
                .put("bestCn", r.bestCn)
                .put("scoreCp", r.bestScoreCp ?: JSONObject.NULL)
                .put("mateIn", r.mateIn ?: JSONObject.NULL)
                .put("deltaCp", r.deltaCp)
                .put("depth", r.depth)
        )
    }
    return arr.toString()
}

/** 复盘结论写入知识库（ADR-0003）：occurrences 累加；复盘深度更深时才更新最佳走法
 *  （兑现"同一局面第二次遇到的推荐不差于第一次"——浅结果不得覆盖深结果） */
suspend fun saveToKnowledge(db: AppDb, reviews: List<PositionReview>) {
    val now = System.currentTimeMillis()
    for (r in reviews) {
        if (r.bestIccs.isEmpty()) continue
        val pos = Position.fromFen(r.fen) ?: continue
        val hash = Zobrist.of(pos.board, pos.redToMove)
        val old = db.positionDao().get(hash)
        val entity = when (old) {
            null -> PositionEntity(
                zobrist = hash,
                fen = r.fen,
                bestMove = r.bestIccs,
                bestScoreCp = r.bestScoreCp ?: 0,
                mateIn = r.mateIn,
                depth = r.depth,
                occurrences = 1,
                lastPlayed = r.playedIccs,
                lastDeltaCp = if (r.deltaCp == Int.MAX_VALUE) null else r.deltaCp,
                updatedAt = now,
            )
            else -> {
                val deeper = r.depth > old.depth
                old.copy(
                    bestMove = if (deeper) r.bestIccs else old.bestMove,
                    bestScoreCp = if (deeper) r.bestScoreCp ?: old.bestScoreCp else old.bestScoreCp,
                    mateIn = if (deeper) r.mateIn else old.mateIn,
                    depth = maxOf(old.depth, r.depth),
                    occurrences = old.occurrences + 1,
                    lastPlayed = r.playedIccs,
                    lastDeltaCp = if (r.deltaCp == Int.MAX_VALUE) old.lastDeltaCp else r.deltaCp,
                    updatedAt = now,
                )
            }
        }
        db.positionDao().put(entity)
    }
}
