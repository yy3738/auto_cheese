package com.cheese.assistant

import com.cheese.assistant.board.Piece
import com.cheese.assistant.board.Position
import com.cheese.assistant.review.GameRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手动纠错记账：补上漏的一手 / 改掉上一手。
 */
class GameRecorderCorrectionTest {

    private fun move(board: Array<Piece?>, from: Int, to: Int): Position {
        val b = board.copyOf()
        b[to] = b[from]
        b[from] = null
        return Position(b, !(b[to]?.isRed ?: true))
    }

    @Test
    fun `补上漏的一手追加到最后`() {
        val r = GameRecorder()
        r.start(Position.START, true)
        val p1 = move(Position.START.board, 54, 45)       // 红兵九进一
        assertNotNull(r.record(Position.START, p1, moverIsRed = true))
        assertEquals(1, r.moveCount())

        val p2 = move(p1.board, 27, 36)                   // 黑卒九进一
        val iccs = r.recordManual(p1, p2, moverIsRed = false, replaceLast = false)

        assertNotNull(iccs)
        assertEquals(2, r.moveCount())
        assertEquals(p2.toFen(), r.lastFen())
    }

    @Test
    fun `改掉上一手替换末条`() {
        val r = GameRecorder()
        r.start(Position.START, true)
        val wrong = move(Position.START.board, 88, 69)    // 误识别的红马二进三
        assertNotNull(r.record(Position.START, wrong, moverIsRed = true))
        assertEquals(1, r.moveCount())

        val corrected = move(Position.START.board, 64, 67) // 实际是红炮八平五
        val iccs = r.recordManual(Position.START, corrected,
            moverIsRed = true, replaceLast = true)

        assertNotNull(iccs)
        assertEquals(1, r.moveCount())
        assertEquals(corrected.toFen(), r.lastFen())
        assertTrue(r.movesSnapshot().last().fenBefore == Position.START.toFen())
    }

    @Test
    fun `上一手之前的局面可还原`() {
        val r = GameRecorder()
        r.start(Position.START, true)
        val p1 = move(Position.START.board, 54, 45)
        r.record(Position.START, p1, moverIsRed = true)
        assertEquals(Position.START.toFen(), r.positionBeforeLast()?.toFen())
    }
}
