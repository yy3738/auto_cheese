package com.cheese.assistant.recognize

import com.cheese.assistant.board.Piece
import com.cheese.assistant.board.Position

/**
 * 浮窗「纠错」四下拉的着法输入（2026-10-22 用户方案）：
 * 棋子 / 起始纵线 / 进·退·平 / 目标纵线或步数。
 *
 * 纵线约定与 MoveFormatter 一致：双方统一从屏幕右侧数 1-9。
 * - 马/相/仕：第四项是目标纵线（进/退决定往前还是往后）
 * - 车/炮/帅/兵：第四项是步数（进/退）+ 目标纵线仅在「平」时使用
 *
 * 解析结果用 XiangqiRules.canMove 校验，非法返回 null。
 */
data class MoveInput(
    val piece: Piece,
    /** 起始纵线 1..9（屏幕右侧=1） */
    val fromFile: Int,
    val action: Action,
    /** 1..9：马相仕=目标纵线；车炮兵帅将=步数；平=目标纵线 */
    val target: Int,
) {
    enum class Action { ADVANCE, RETREAT, LEVEL }

    /** 在 board 上解析成 (from,to)；不合法返回 null */
    fun resolve(board: Array<Piece?>): Pair<Int, Int>? {
        if (fromFile !in 1..9 || target !in 1..9) return null
        val col = 9 - fromFile                       // 纵线 1 = 屏幕最右列
        val sources = (0 until 90).filter { it % 9 == col && board[it] == piece }
        if (sources.isEmpty()) return null
        val forward = forwardIsRowMinus(board)
        for (from in sources) {
            for (to in destinations(from, forward)) {
                if (to in 0..89 && XiangqiRules.canMove(board, from, to)) return from to to
            }
        }
        return null
    }

    private fun destinations(from: Int, forward: Boolean): List<Int> {
        val fr = from / 9
        val tc = 9 - target
        return when {
            action == Action.LEVEL -> listOf(fr * 9 + tc)
            piece == Piece.R_KNIGHT || piece == Piece.B_KNIGHT ||
                piece == Piece.R_BISHOP || piece == Piece.B_BISHOP ||
                piece == Piece.R_ADVISOR || piece == Piece.B_ADVISOR -> {
                // 目标纵线上的所有格；按进/退筛掉反方向的落点
                (0 until 90).filter { it % 9 == tc }.filter { to ->
                    val tr = to / 9
                    if (action == Action.ADVANCE) {
                        if (forward) tr < fr else tr > fr
                    } else {
                        if (forward) tr > fr else tr < fr
                    }
                }
            }
            else -> {
                val sign = when (action) {
                    Action.ADVANCE -> if (forward) -1 else 1
                    Action.RETREAT -> if (forward) 1 else -1
                    Action.LEVEL -> 0
                }
                val tr = fr + sign * target
                if (tr !in 0..9) emptyList() else listOf(tr * 9 + (from % 9))
            }
        }
    }

    /** 该方"进"是否 row 减小（红在屏幕下方时红进 = row-1） */
    private fun forwardIsRowMinus(board: Array<Piece?>): Boolean {
        val redRow = board.indexOfFirst { it == Piece.R_KING } / 9
        val blackRow = board.indexOfFirst { it == Piece.B_KING } / 9
        return if (piece.isRed) redRow > blackRow else redRow < 0 || redRow < blackRow
    }

    val pieceLabel: String get() = (if (piece.isRed) "红" else "黑") + piece.cnName

    val actionLabel: String get() = when (action) {
        Action.ADVANCE -> "进"
        Action.RETREAT -> "退"
        Action.LEVEL -> "平"
    }

    override fun toString(): String = "$pieceLabel$fromFile$actionLabel$target"
}
