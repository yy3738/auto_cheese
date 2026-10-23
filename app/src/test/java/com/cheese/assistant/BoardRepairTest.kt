package com.cheese.assistant

import com.cheese.assistant.board.Piece
import com.cheese.assistant.board.Position
import com.cheese.assistant.recognize.BoardRepair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 少子修复：期望局面有、本帧空 → 补回；新增/异子/缺失过多 → 不修。
 */
class BoardRepairTest {

    private fun without(pos: Position, vararg cells: Int): Position {
        val b = pos.board.copyOf()
        cells.forEach { b[it] = null }
        return Position(b, pos.redToMove)
    }

    @Test
    fun `开局漏读左上黑车被补回`() {
        // 实测 bug：piece cap 把 (0,0) 真黑车当多余红车剔掉
        val observed = without(Position.START, 0)
        val repaired = BoardRepair.restoreMissing(Position.START, observed)
        assertNotNull(repaired)
        assertEquals(Piece.B_ROOK, repaired!!.board[0])
    }

    @Test
    fun `同时漏两子也补回`() {
        val observed = without(Position.START, 0, 1)
        val repaired = BoardRepair.restoreMissing(Position.START, observed)
        assertNotNull(repaired)
        assertEquals(Piece.B_ROOK, repaired!!.board[0])
        assertEquals(Piece.B_KNIGHT, repaired.board[1])
    }

    @Test
    fun `本帧多出期望没有的子不修`() {
        val b = Position.START.board.copyOf()
        b[44] = Piece.R_ROOK      // 期望空格出现了子(可能是真实落点)
        val observed = Position(b, true)
        assertNull(BoardRepair.restoreMissing(Position.START, observed))
    }

    @Test
    fun `同格异子不修`() {
        val b = Position.START.board.copyOf()
        b[0] = Piece.R_ROOK      // 颜色翻转：交给颜色门/超限核对
        val observed = Position(b, true)
        assertNull(BoardRepair.restoreMissing(Position.START, observed))
    }

    @Test
    fun `缺失超过3格不修`() {
        val observed = without(Position.START, 0, 1, 2, 3)
        assertNull(BoardRepair.restoreMissing(Position.START, observed))
    }

    @Test
    fun `没有缺失不修`() {
        assertNull(BoardRepair.restoreMissing(Position.START, Position.START))
    }
}
