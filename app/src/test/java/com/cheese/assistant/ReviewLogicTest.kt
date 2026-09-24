package com.cheese.assistant

import com.cheese.assistant.board.Piece
import com.cheese.assistant.board.Position
import com.cheese.assistant.board.Zobrist
import com.cheese.assistant.review.GameRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewLogicTest {

    private fun initialBoard(): Array<Piece?> = Position.START.board.copyOf()

    @Test
    fun `zobrist 稳定且区分行棋方`() {
        val b = initialBoard()
        val h1 = Zobrist.of(b, redToMove = true)
        val h2 = Zobrist.of(b, redToMove = true)
        val h3 = Zobrist.of(b, redToMove = false)
        assertEquals(h1, h2)
        assertNotEquals(h1, h3)
    }

    @Test
    fun `zobrist 对不同局面不同`() {
        val b = initialBoard()
        val h1 = Zobrist.of(b, true)
        val b2 = initialBoard().also { it[3 * 9] = null } // 拿掉一个黑兵
        val h2 = Zobrist.of(b2, true)
        assertNotEquals(h1, h2)
    }

    @Test
    fun `recorder 记录一步并生成正确 ICCS`() {
        val r = GameRecorder()
        val before = Position(initialBoard(), redToMove = true)
        val after = Position(initialBoard().also {
            it[6 * 9 + 4] = null      // 红兵 (6,4) 离开
            it[5 * 9 + 4] = Piece.R_PAWN // 进一格
        }, redToMove = false)
        r.start(before, userIsRed = true)
        val iccs = r.record(before, after, moverIsRed = true)
        // index 6*9+4 → col4=e, row 9-6=3 → "e3";目标 row5 → "e4"
        assertEquals("e3e4", iccs)
        assertEquals(1, r.moveCount())
    }

    @Test
    fun `recorder 判定用户行棋时机`() {
        val r = GameRecorder()
        val pos = Position(initialBoard(), redToMove = true)
        r.start(pos, userIsRed = false) // 用户执黑
        assertFalse(r.isUserToMove())   // 红先,轮红=AI
    }

    @Test
    fun `recorder reset 清空`() {
        val r = GameRecorder()
        val before = Position(initialBoard(), true)
        val after = Position(initialBoard().also { it[6 * 9] = null; it[5 * 9] = Piece.R_PAWN }, false)
        r.start(before, true)
        r.record(before, after, true)
        assertFalse(r.isEmpty)
        r.reset()
        assertTrue(r.isEmpty)
        assertNull(r.lastFen())
    }
}
