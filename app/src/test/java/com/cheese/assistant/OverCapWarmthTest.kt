package com.cheese.assistant

import com.cheese.assistant.board.Piece
import com.cheese.assistant.board.Position
import com.cheese.assistant.recognize.MoveCheck
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 木色证据优先于过期期望/过期像素快照（2026-10-23）。
 *
 * 两个实测事故：
 * - 17:51 超限核对借期望「空」删掉真黑马；
 * - 17:51 红马真走，出发点像素与旧快照一致，被当幻步空等 10 帧。
 */
class OverCapWarmthTest {

    @Test
    fun `期望说空但木色棋子仍在时不允许删除`() {
        assertFalse(MoveCheck.canDropOverCap(expected = null, warm = true))
    }

    @Test
    fun `冷色空格允许按期望清掉幻影`() {
        assertTrue(MoveCheck.canDropOverCap(expected = null, warm = false))
    }

    @Test
    fun `期望是另一枚子时允许改写判型`() {
        assertTrue(MoveCheck.canDropOverCap(expected = Piece.R_ROOK, warm = true))
    }

    @Test
    fun `出发点仍为木色棋子时判为残影幻步`() {
        assertTrue(MoveCheck.rejectStaticOrigin(originWarm = true))
    }

    @Test
    fun `出发点已冷时首帧放行真实单步`() {
        assertFalse(MoveCheck.rejectStaticOrigin(originWarm = false))
    }

    @Test
    fun `没有木色证据时沿用多帧保护`() {
        assertTrue(MoveCheck.rejectStaticOrigin(originWarm = null))
    }

    @Test
    fun `某方凭空多子时物料超量`() {
        // 参照：黑方 1 车（已被吃一个）；本帧 2 车 = 误配
        val ref = Position(arrayOfNulls<Piece?>(90).also {
            it[4] = Piece.B_KING; it[85] = Piece.R_KING; it[0] = Piece.B_ROOK
        }, true)
        val frame = Position(arrayOfNulls<Piece?>(90).also {
            it[4] = Piece.B_KING; it[85] = Piece.R_KING
            it[0] = Piece.B_ROOK; it[5] = Piece.B_ROOK
        }, true)
        assertTrue(MoveCheck.exceedsMaterialCount(frame, ref))
    }

    @Test
    fun `子数不增则不算超量`() {
        val ref = Position(arrayOfNulls<Piece?>(90).also {
            it[4] = Piece.B_KING; it[85] = Piece.R_KING
            it[0] = Piece.B_ROOK; it[5] = Piece.B_ROOK
        }, true)
        val frame = Position(arrayOfNulls<Piece?>(90).also {
            it[4] = Piece.B_KING; it[85] = Piece.R_KING; it[0] = Piece.B_ROOK
        }, true)
        assertFalse(MoveCheck.exceedsMaterialCount(frame, ref))
    }
}
