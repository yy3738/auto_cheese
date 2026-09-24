package com.cheese.assistant.board

import kotlin.random.Random

/**
 * 局面的 64 位 Zobrist 哈希：局面内容（90 点棋子布局）+ 行棋方的函数。
 * 知识库主键。非增量——每次全量算 90 次查表，识别频率下成本可忽略。
 * 注意：不含回合数等历史信息，同构局面（如对称开局的不同顺序）会得到
 * 同一哈希，这正是知识库想要的语义（"同一局面第二次遇到"）。
 */
object Zobrist {

    // 固定种子——哈希是知识库主键，必须跨进程重启稳定。
    // 单一 Random 实例顺序生成全部 key（每张表各自 new 同种子 Random 会全键相同）。
    private val rng = Random(-6250849315081816574L)
    private val PIECE_KEYS = Array(Piece.entries.size) { LongArray(90) { rng.nextLong() } }
    private val SIDE_KEY = rng.nextLong()

    /** 长度 90 的棋盘 + 行棋方 → 64 位哈希。碰撞概率在个人使用量级下可忽略。 */
    fun of(board: Array<Piece?>, redToMove: Boolean): Long {
        var h = 0L
        for (i in board.indices) {
            val p = board[i] ?: continue
            h = h xor PIECE_KEYS[p.ordinal][i]
        }
        if (!redToMove) h = h xor SIDE_KEY
        return h
    }
}
