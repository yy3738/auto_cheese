package com.cheese.assistant

import com.cheese.assistant.board.Piece
import com.cheese.assistant.board.Position
import com.cheese.assistant.recognize.MoveInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 浮窗纠错四下拉 → ICCS 解析（纵线统一从屏幕右侧数 1-9）。
 */
class MoveInputTest {

    private val start = Position.START.board

    @Test
    fun `红炮8平5 解析到 row7 col1 到 col4`() {
        val input = MoveInput(Piece.R_CANNON, fromFile = 8,
            action = MoveInput.Action.LEVEL, target = 5)
        assertEquals(64 to 67, input.resolve(start))
    }

    @Test
    fun `红马2进3 解析到 row9 col7 到 row7 col6`() {
        val input = MoveInput(Piece.R_KNIGHT, fromFile = 2,
            action = MoveInput.Action.ADVANCE, target = 3)
        assertEquals(88 to 69, input.resolve(start))
    }

    @Test
    fun `黑车9进1 解析到 row0 col0 到 row1 col0`() {
        val input = MoveInput(Piece.B_ROOK, fromFile = 9,
            action = MoveInput.Action.ADVANCE, target = 1)
        assertEquals(0 to 9, input.resolve(start))
    }

    @Test
    fun `红兵9进1 解析到 row6 col0 到 row5 col0`() {
        val input = MoveInput(Piece.R_PAWN, fromFile = 9,
            action = MoveInput.Action.ADVANCE, target = 1)
        assertEquals(54 to 45, input.resolve(start))
    }

    @Test
    fun `被挡住的横车不合法`() {
        val input = MoveInput(Piece.R_ROOK, fromFile = 1,
            action = MoveInput.Action.LEVEL, target = 9)
        assertNull(input.resolve(start))
    }

    @Test
    fun `该纵线没有该棋子返回 null`() {
        // 黑车在 file 9(左)与 file 1(右)，file 5 没有黑车
        val input = MoveInput(Piece.B_ROOK, fromFile = 5,
            action = MoveInput.Action.ADVANCE, target = 1)
        assertNull(input.resolve(start))
    }
}
