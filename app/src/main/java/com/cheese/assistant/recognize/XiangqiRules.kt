package com.cheese.assistant.recognize

import com.cheese.assistant.board.Piece

/**
 * 象棋走法合法性（识别矩阵坐标，row0=屏幕最上排，与屏幕方向无关）。
 *
 * 供 AnchorRecovery 的"消失子推测"使用：对方走子后，出发点（CONFIDENT 判空）
 * 与落点（CNN 拒判+像素有变化）构成 1 缺 1 疑，用走法合法性验证
 * "anchor 中的消失子恰好能走到变化拒判格"，唯一合法才采纳。
 *
 * 朝向：红方在屏幕哪一侧由双王行位推断（红王 row > 黑王 row = 红在下），
 * 兵/卒前进方向随之确定；其余子力规则与朝向无关。
 */
object XiangqiRules {

    /** board[from] 走到 to（to 允许有敌子=吃子）是否是一步合法走法 */
    fun canMove(board: Array<Piece?>, from: Int, to: Int): Boolean {
        if (from == to) return false
        val mover = board[from] ?: return false
        val target = board[to]
        if (target != null && target.side == mover.side) return false
        val fr = from / 9; val fc = from % 9
        val tr = to / 9; val tc = to % 9
        val dr = tr - fr; val dc = tc - fc
        var redRow = -1; var blackRow = -1
        for (i in 0 until 90) {
            when (board[i]) {
                Piece.R_KING -> if (redRow < 0) redRow = i / 9
                Piece.B_KING -> if (blackRow < 0) blackRow = i / 9
                else -> Unit
            }
        }
        // 红在屏幕下方 → 红子前进 row-1；王不在（残局识别异常）默认红在下
        val forward = if ((mover.isRed && redRow > blackRow) ||
            (!mover.isRed && (redRow < 0 || redRow < blackRow))) -1 else 1
        return when (mover) {
            Piece.R_ROOK, Piece.B_ROOK ->
                (dr == 0 || dc == 0) && countBetween(board, fr, fc, tr, tc) == 0
            Piece.R_CANNON, Piece.B_CANNON -> {
                if (dr != 0 && dc != 0) return false
                if (target == null) countBetween(board, fr, fc, tr, tc) == 0
                else countBetween(board, fr, fc, tr, tc) == 1
            }
            Piece.R_KNIGHT, Piece.B_KNIGHT -> {
                if (kotlin.math.abs(dr) + kotlin.math.abs(dc) != 3 ||
                    kotlin.math.abs(dr) * kotlin.math.abs(dc) != 2) return false
                // 马腿：横移两格时腿在横向中点，纵移两格时腿在纵向中点
                val legR = if (kotlin.math.abs(dc) == 2) fr else fr + dr / 2
                val legC = if (kotlin.math.abs(dc) == 2) fc + dc / 2 else fc
                board[legR * 9 + legC] == null
            }
            Piece.R_BISHOP, Piece.B_BISHOP -> {
                if (kotlin.math.abs(dr) != 2 || kotlin.math.abs(dc) != 2) return false
                // 象不过河：出发/落点须在同一半场
                if (fr / 5 != tr / 5) return false
                board[(fr + dr / 2) * 9 + fc + dc / 2] == null // 象眼
            }
            Piece.R_ADVISOR, Piece.B_ADVISOR -> inPalace(tr, tc) &&
                    kotlin.math.abs(dr) == 1 && kotlin.math.abs(dc) == 1
            Piece.R_KING, Piece.B_KING ->
                inPalace(tr, tc) && kotlin.math.abs(dr) + kotlin.math.abs(dc) == 1
            Piece.R_PAWN, Piece.B_PAWN -> {
                // 未过河：只能向前；过河后：向前或横走
                val crossed = if (forward == -1) fr <= 4 else fr >= 5
                if (!crossed) dr == forward && dc == 0
                else (dr == forward && dc == 0) || (dr == 0 && kotlin.math.abs(dc) == 1)
            }
        }
    }

    /** to 是否在任一方九宫内 */
    private fun inPalace(row: Int, col: Int): Boolean =
        col in 3..5 && (row <= 2 || row >= 7)

    /** 同行/同列两点（含）之间棋子数。调用方已保证同行或同列。 */
    private fun countBetween(board: Array<Piece?>, fr: Int, fc: Int, tr: Int, tc: Int): Int {
        var n = 0
        val dr = Integer.compare(tr, fr)
        val dc = Integer.compare(tc, fc)
        var r = fr + dr
        var c = fc + dc
        while (r != tr || c != tc) {
            if (board[r * 9 + c] != null) n++
            r += dr
            c += dc
        }
        return n
    }
}
