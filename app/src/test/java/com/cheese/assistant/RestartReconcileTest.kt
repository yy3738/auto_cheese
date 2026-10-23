package com.cheese.assistant

import com.cheese.assistant.board.Piece
import com.cheese.assistant.board.Position
import com.cheese.assistant.recognize.MoveCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * 循环重启时的棋谱对齐：单步 / 双步（我方一步 + 对方一步）必须能被还原。
 * 2026-10-22 实测重试重启把「进黑车 + 平红车」两步吞掉，回放断裂。
 */
class RestartReconcileTest {

    private fun boardOf(vararg pieces: Pair<Int, Piece?>): Array<Piece?> =
        arrayOfNulls<Piece>(90).also { b -> pieces.forEach { (i, p) -> b[i] = p } }

    /** 黑车 (0,0)->(2,0)，红车 (9,0)->(9,1)；黑先 */
    private fun pairBoards(): Pair<Position, Position> {
        val prev = Position(boardOf(
            4 to Piece.B_KING, 9 * 9 + 4 to Piece.R_KING,
            0 to Piece.B_ROOK, 9 * 9 to Piece.R_ROOK,
        ), false)
        val mid = Position(boardOf(
            4 to Piece.B_KING, 9 * 9 + 4 to Piece.R_KING,
            2 * 9 to Piece.B_ROOK, 9 * 9 to Piece.R_ROOK,
        ), true)
        val cur = Position(boardOf(
            4 to Piece.B_KING, 9 * 9 + 4 to Piece.R_KING,
            2 * 9 to Piece.B_ROOK, 9 * 9 + 1 to Piece.R_ROOK,
        ), false)
        return prev to cur
    }

    @Test
    fun `双步分解返回中间局面`() {
        val (prev, cur) = pairBoards()
        val mid = MoveCheck.tryTwoSteps(prev, cur, firstIsRed = false)
        assertNotNull(mid)
        assertEquals(Piece.B_ROOK, mid!!.board[2 * 9])
        assertEquals(Piece.R_ROOK, mid.board[9 * 9])
    }

    @Test
    fun `双步按红先分解得到红车先走的中间局面`() {
        val (prev, cur) = pairBoards()
        val mid = MoveCheck.tryTwoSteps(prev, cur, firstIsRed = true)
        assertNotNull(mid)
        // 红车先走：红车已到 col1，黑车仍未动
        assertEquals(Piece.R_ROOK, mid!!.board[9 * 9 + 1])
        assertEquals(Piece.B_ROOK, mid.board[0])
    }

    @Test
    fun `单步能识别移动者颜色`() {
        val prev = Position(boardOf(
            4 to Piece.B_KING, 9 * 9 + 4 to Piece.R_KING,
            0 to Piece.B_ROOK,
        ), false)
        val cur = Position(boardOf(
            4 to Piece.B_KING, 9 * 9 + 4 to Piece.R_KING,
            2 * 9 to Piece.B_ROOK,
        ), true)
        assertEquals(false, MoveCheck.stepMoverIsRed(prev, cur))
        assertNotNull(MoveCheck.legalStepPair(prev, cur))
    }
}
