package com.cheese.assistant

import com.cheese.assistant.board.Piece
import com.cheese.assistant.board.Position
import com.cheese.assistant.recognize.MoveCheck
import com.cheese.assistant.recognize.SequenceSolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 3-4 步序列还原（S2 后半）：循环重启/识别暂停期间的连走必须能还原，
 * 还原不出来才允许留缺口。每一步都要过棋规，终局必须完全一致。
 */
class SequenceSolverTest {

    private fun boardOf(vararg pieces: Pair<Int, Piece?>): Array<Piece?> =
        arrayOfNulls<Piece>(90).also { b -> pieces.forEach { (i, p) -> b[i] = p } }

    private val kingR = 9 * 9 + 4
    private val kingB = 4

    @Test
    fun `单步也能还原`() {
        val prev = Position(boardOf(kingB to Piece.B_KING, kingR to Piece.R_KING,
            0 to Piece.B_ROOK), false)
        val cur = Position(boardOf(kingB to Piece.B_KING, kingR to Piece.R_KING,
            2 * 9 to Piece.B_ROOK), true)
        val chain = SequenceSolver.solve(prev, cur)
        assertNotNull(chain)
        assertEquals(2, chain!!.size)
        assertTrue(chain.last().board.contentEquals(cur.board))
    }

    @Test
    fun `三步含同一子连走也能还原`() {
        // 马必须走两跳才能到目标格，中间夹对手一步 → 只有 3 步解
        // 红马 (9,0)→(7,1)→(5,2)；黑卒 (3,0)→(4,0)
        val prev = Position(boardOf(kingB to Piece.B_KING, kingR to Piece.R_KING,
            9 * 9 to Piece.R_KNIGHT, 3 * 9 to Piece.B_PAWN), true)
        val cur = Position(boardOf(kingB to Piece.B_KING, kingR to Piece.R_KING,
            5 * 9 + 2 to Piece.R_KNIGHT, 4 * 9 to Piece.B_PAWN), false)
        val chain = SequenceSolver.solve(prev, cur, minPlies = 3, maxPlies = 4)
        assertNotNull(chain)
        assertEquals(4, chain!!.size)
        assertTrue(chain.last().board.contentEquals(cur.board))
        // 每相邻两步都必须是合法单步
        for (k in 0 until chain.size - 1) {
            assertNotNull(MoveCheck.legalStepPair(chain[k], chain[k + 1]))
        }
    }

    @Test
    fun `四步还原`() {
        // 红马两跳 + 两个黑卒各进一步 = 4 步（红黑交替，无更短解）
        val prev = Position(boardOf(kingB to Piece.B_KING, kingR to Piece.R_KING,
            9 * 9 to Piece.R_KNIGHT, 3 * 9 to Piece.B_PAWN, 3 * 9 + 1 to Piece.B_PAWN), true)
        val cur = Position(boardOf(kingB to Piece.B_KING, kingR to Piece.R_KING,
            5 * 9 + 2 to Piece.R_KNIGHT, 4 * 9 to Piece.B_PAWN, 4 * 9 + 1 to Piece.B_PAWN), true)
        val chain = SequenceSolver.solve(prev, cur, minPlies = 3, maxPlies = 4)
        assertNotNull(chain)
        assertEquals(5, chain!!.size)
        assertTrue(chain.last().board.contentEquals(cur.board))
        for (k in 0 until chain.size - 1) {
            assertNotNull(MoveCheck.legalStepPair(chain[k], chain[k + 1]))
        }
    }

    @Test
    fun `两步吃子序列`() {
        // 红车 (9,0)→(0,0) 吃黑车；黑车没了 → 其实是 1 步，这里造 2 步：
        // 黑卒 (3,0)→(4,0)，红车 (9,0)→(9,1)
        val prev = Position(boardOf(kingB to Piece.B_KING, kingR to Piece.R_KING,
            9 * 9 to Piece.R_ROOK, 3 * 9 to Piece.B_PAWN), false)
        val cur = Position(boardOf(kingB to Piece.B_KING, kingR to Piece.R_KING,
            9 * 9 + 1 to Piece.R_ROOK, 4 * 9 to Piece.B_PAWN), false)
        val chain = SequenceSolver.solve(prev, cur)
        assertNotNull(chain)
        assertTrue(chain!!.last().board.contentEquals(cur.board))
    }

    @Test
    fun `走不到的盘面返回 null 不猜`() {
        // 红车瞬移到对角线 → 任何步数都走不到
        val prev = Position(boardOf(kingB to Piece.B_KING, kingR to Piece.R_KING,
            9 * 9 to Piece.R_ROOK), true)
        val cur = Position(boardOf(kingB to Piece.B_KING, kingR to Piece.R_KING,
            5 * 9 + 3 to Piece.R_ROOK), true)
        assertNull(SequenceSolver.solve(prev, cur, minPlies = 1, maxPlies = 4))
    }

    @Test
    fun `轮走方不符时无解`() {
        val prev = Position(boardOf(kingB to Piece.B_KING, kingR to Piece.R_KING,
            9 * 9 to Piece.R_ROOK), true)
        // 红车走一步后应轮到黑，这里目标仍写红先 → 无解
        val cur = Position(boardOf(kingB to Piece.B_KING, kingR to Piece.R_KING,
            9 * 9 + 1 to Piece.R_ROOK), true)
        assertNull(SequenceSolver.solve(prev, cur, minPlies = 1, maxPlies = 1))
    }
}
