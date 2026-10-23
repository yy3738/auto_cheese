package com.cheese.assistant

import com.cheese.assistant.board.Piece
import com.cheese.assistant.recognize.AnchorRecovery
import com.cheese.assistant.recognize.EngineResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * AnchorRecovery 单元测试：静态拒判回填 / 变化拒判作废 / 新鲜度记账。
 */
class AnchorRecoveryTest {

    private fun boardOf(vararg pieces: Pair<Int, Piece?>): Array<Piece?> =
        arrayOfNulls<Piece>(90).also { b -> pieces.forEach { (i, p) -> b[i] = p } }

    @Test
    fun `静态拒判格用锚点回填`() {
        AnchorRecovery.reset()
        val draftBoard = boardOf() // 全部拒判
        val confidence = Array(90) { EngineResult.Confidence.UNKNOWN }
        val anchor = com.cheese.assistant.board.Position(boardOf(0 to Piece.R_ROOK), true)
        val draft = EngineResult(draftBoard, confidence, "test")
        val changed = BooleanArray(90)

        val result = AnchorRecovery.resolve(draft, anchor, changed)

        assertNotNull(result)
        assertEquals(Piece.R_ROOK, result!!.board[0])
    }

    @Test
    fun `变化格上的拒判整帧作废`() {
        AnchorRecovery.reset()
        val draftBoard = boardOf(0 to null)
        val confidence = Array(90) { EngineResult.Confidence.UNKNOWN }
        val anchor = com.cheese.assistant.board.Position(boardOf(0 to Piece.R_ROOK), true)
        val draft = EngineResult(draftBoard, confidence, "test")
        val changed = BooleanArray(90).also { it[0] = true }

        val result = AnchorRecovery.resolve(draft, anchor, changed)

        assertNull(result)
    }

    @Test
    fun `同格连续回填超限强制全量重识别`() {
        AnchorRecovery.reset()
        val draftBoard = boardOf(0 to null)
        val confidence = Array(90) { EngineResult.Confidence.UNKNOWN }
        val anchor = com.cheese.assistant.board.Position(boardOf(0 to Piece.R_ROOK), true)
        val draft = EngineResult(draftBoard, confidence, "test")
        val changed = BooleanArray(90)

        // 前几帧正常回填
        repeat(AnchorRecovery.MAX_CONSECUTIVE_BACKFILL - 1) {
            val r = AnchorRecovery.resolve(draft, anchor, changed)
            assertNotNull("第${it + 1}次应正常回填", r)
        }
        // 达到上限帧
        val final = AnchorRecovery.resolve(draft, anchor, changed)
        assertNull("连续回填超限应返回null", final)
    }

    @Test
    fun `CONFIDENT格重置回填计数`() {
        AnchorRecovery.reset()
        val good = EngineResult(boardOf(0 to Piece.B_ROOK), Array(90) {
            EngineResult.Confidence.CONFIDENT
        }, "test")
        val bad = EngineResult(boardOf(0 to null), Array(90) {
            EngineResult.Confidence.UNKNOWN
        }, "test")
        val anchor = com.cheese.assistant.board.Position(boardOf(0 to Piece.R_ROOK), true)
        val changed = BooleanArray(90)

        // 连续拒判3帧
        repeat(3) { AnchorRecovery.resolve(bad, anchor, changed) }
        // 1帧 CONFIDENT（例如棋子重新可见）
        AnchorRecovery.resolve(good, anchor, changed)
        // 再拒判不应立即触发超限
        val r = AnchorRecovery.resolve(bad, anchor, changed)
        assertNotNull(r)
    }
}
