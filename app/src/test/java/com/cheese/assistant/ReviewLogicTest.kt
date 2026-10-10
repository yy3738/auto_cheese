package com.cheese.assistant

import com.cheese.assistant.board.Piece
import com.cheese.assistant.board.Position
import com.cheese.assistant.board.Zobrist
import com.cheese.assistant.recognize.MoveCheck
import com.cheese.assistant.review.GameRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
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

class OcclusionTest {

    private fun board(vararg placed: Pair<Int, Piece>): Array<Piece?> {
        val b = arrayOfNulls<Piece>(90)
        for ((i, p) in placed) b[i] = p
        return b
    }

    @Test
    fun `仅消失一处是遮蔽帧`() {
        val prev = Position(board(65 to Piece.R_KNIGHT), true)
        val cur = Position(board(), true)
        assertTrue(MoveCheck.isVanishOnly(prev, cur))
        assertFalse(MoveCheck.isAppearOnly(prev, cur))
    }

    @Test
    fun `仅出现一处是恢复帧`() {
        val prev = Position(board(), true)
        val cur = Position(board(65 to Piece.R_KNIGHT), true)
        assertTrue(MoveCheck.isAppearOnly(prev, cur))
        assertFalse(MoveCheck.isVanishOnly(prev, cur))
    }

    @Test
    fun `消失加出现是真实走子不是遮蔽`() {
        val prev = Position(board(65 to Piece.R_KNIGHT), true)
        val cur = Position(board(74 to Piece.R_KNIGHT), true)
        assertFalse(MoveCheck.isVanishOnly(prev, cur))
        assertFalse(MoveCheck.isAppearOnly(prev, cur))
        assertTrue(MoveCheck.isPlausibleStep(prev, cur))
    }

    @Test
    fun `吃子且落点索引小于出发点仍是合法步`() {
        // AI 居上吃下方子(normalize 后出发点 74 > 落点 65),
        // 固定"第一个差异=出发点"会把落点当出发点而误判
        val prev = Position(board(74 to Piece.B_KNIGHT, 65 to Piece.R_PAWN), true)
        val cur = Position(board(65 to Piece.B_KNIGHT), true)
        assertTrue(MoveCheck.isPlausibleStep(prev, cur))
    }

    @Test
    fun `吃子(变更型)不是遮蔽`() {
        val prev = Position(board(65 to Piece.R_KNIGHT, 74 to Piece.B_PAWN), true)
        val cur = Position(board(74 to Piece.R_KNIGHT), true)
        assertFalse(MoveCheck.isVanishOnly(prev, cur))
        assertFalse(MoveCheck.isAppearOnly(prev, cur))
        assertTrue(MoveCheck.isPlausibleStep(prev, cur))
    }

    @Test
    fun `三处消失超过遮蔽上限`() {
        val prev = Position(board(
            65 to Piece.R_KNIGHT, 66 to Piece.R_ROOK, 67 to Piece.R_CANNON), true)
        val cur = Position(board(), true)
        assertFalse(MoveCheck.isVanishOnly(prev, cur))
    }

    @Test
    fun `两处同时消失仍算遮蔽`() {
        val prev = Position(board(65 to Piece.R_KNIGHT, 20 to Piece.B_PAWN), true)
        val cur = Position(board(), true)
        assertTrue(MoveCheck.isVanishOnly(prev, cur))
    }

    @Test
    fun `单点原地换子是误配恢复`() {
        // 锚点帧把光圈下的炮误配成仕,恢复帧修正为炮
        val prev = Position(board(65 to Piece.R_ADVISOR, 20 to Piece.B_PAWN), true)
        val cur = Position(board(65 to Piece.R_CANNON, 20 to Piece.B_PAWN), true)
        assertTrue(MoveCheck.isSingleRetag(prev, cur))
        // 不能被当成走子/遮蔽
        assertFalse(MoveCheck.isPlausibleStep(prev, cur))
        assertFalse(MoveCheck.isVanishOnly(prev, cur))
        assertFalse(MoveCheck.isAppearOnly(prev, cur))
    }

    @Test
    fun `消失加出现不是单点换子`() {
        val prev = Position(board(65 to Piece.R_KNIGHT), true)
        val cur = Position(board(74 to Piece.R_KNIGHT), true)
        assertFalse(MoveCheck.isSingleRetag(prev, cur))
    }

    @Test
    fun `两处原地换子超过容忍上限`() {
        val prev = Position(board(65 to Piece.R_ADVISOR, 66 to Piece.R_ROOK), true)
        val cur = Position(board(65 to Piece.R_CANNON, 66 to Piece.R_KNIGHT), true)
        assertFalse(MoveCheck.isSingleRetag(prev, cur))
    }

    @Test
    fun `消失型单点不是换子`() {
        val prev = Position(board(65 to Piece.R_KNIGHT), true)
        val cur = Position(board(), true)
        assertFalse(MoveCheck.isSingleRetag(prev, cur))
    }

    @Test
    fun `出发点残留假子的走子是宽松步`() {
        // 实测:卒从 A 走到 B,出发点被行棋光圈罩住误配成士,
        // 2 处差异(卒→士 + 空→卒)不是严格步,需宽松判定接住
        val prev = Position(board(65 to Piece.B_PAWN), true)
        val cur = Position(board(65 to Piece.B_ADVISOR, 56 to Piece.B_PAWN), true)
        assertFalse(MoveCheck.isPlausibleStep(prev, cur))
        assertTrue(MoveCheck.isStepGhostOrigin(prev, cur))
    }

    @Test
    fun `宽松步容忍吃子后落点残影`() {
        val prev = Position(board(65 to Piece.R_KNIGHT, 20 to Piece.B_PAWN), true)
        // 马吃卒,出发点残留误配的车
        val cur = Position(board(65 to Piece.R_ROOK, 20 to Piece.R_KNIGHT), true)
        assertTrue(MoveCheck.isStepGhostOrigin(prev, cur))
    }

    @Test
    fun `宽松步拒绝目标点是己方子`() {
        val prev = Position(board(65 to Piece.R_KNIGHT, 20 to Piece.R_PAWN), true)
        val cur = Position(board(65 to Piece.R_ADVISOR, 20 to Piece.R_KNIGHT), true)
        assertFalse(MoveCheck.isStepGhostOrigin(prev, cur))
    }

    @Test
    fun `宽松步拒绝落点不是出走子`() {
        // 落点出现的子与出发点的子不同(不是同一枚棋子挪动)
        val prev = Position(board(65 to Piece.R_KNIGHT), true)
        val cur = Position(board(65 to Piece.R_ADVISOR, 20 to Piece.R_CANNON), true)
        assertFalse(MoveCheck.isStepGhostOrigin(prev, cur))
    }

    @Test
    fun `宽松步拒绝出发点原本无子`() {
        val prev = Position(board(56 to Piece.B_PAWN), true)
        val cur = Position(board(65 to Piece.B_ADVISOR, 56 to Piece.B_PAWN), true)
        assertFalse(MoveCheck.isStepGhostOrigin(prev, cur))
    }

    @Test
    fun `宽松步拒绝三处差异`() {
        val prev = Position(board(65 to Piece.R_KNIGHT, 10 to Piece.B_PAWN), true)
        val cur = Position(board(65 to Piece.R_ADVISOR, 20 to Piece.R_KNIGHT,
            1 to Piece.B_PAWN), true)
        assertFalse(MoveCheck.isStepGhostOrigin(prev, cur))
    }

    @Test
    fun `双步分解容忍 AI 秒回吃刚走的子`() {
        // 实测:我方(黑)卒走一步,AI 秒回红炮吃掉它,稳定 3 处差异。
        // 被吃卒位置也满足 mid≠cur 且 mid 有子,出发点选取须双向配对
        val prev = Position(board(33 to Piece.B_PAWN, 69 to Piece.R_CANNON), true)
        val cur = Position(board(42 to Piece.R_CANNON), true)
        val mid = MoveCheck.tryTwoSteps(prev, cur, firstIsRed = false)
        assertNotNull(mid)
        assertEquals(Piece.B_PAWN, mid?.board?.get(42))
        assertEquals(Piece.R_CANNON, mid?.board?.get(69))
    }

    @Test
    fun `双步分解非吃子叠加`() {
        val prev = Position(board(33 to Piece.B_PAWN, 69 to Piece.R_CANNON), true)
        val cur = Position(board(42 to Piece.B_PAWN, 60 to Piece.R_CANNON), true)
        val mid = MoveCheck.tryTwoSteps(prev, cur, firstIsRed = false)
        assertNotNull(mid)
        assertEquals(Piece.B_PAWN, mid?.board?.get(42))
    }

    @Test
    fun `双步分解拒绝第二步走己方子`() {
        // 分解出的"第二步"动的是黑子而非红子,说明第一步枚举错了,应拒绝
        val prev = Position(board(33 to Piece.B_PAWN, 69 to Piece.B_KNIGHT), true)
        val cur = Position(board(42 to Piece.B_KNIGHT), true)
        assertNull(MoveCheck.tryTwoSteps(prev, cur, firstIsRed = false))
    }

    @Test
    fun `stepMoverIsRed 返回实际移动者颜色`() {
        // 红马走一步 → true
        val prev = Position(board(65 to Piece.R_KNIGHT), true)
        val cur = Position(board(74 to Piece.R_KNIGHT), true)
        assertEquals(true, MoveCheck.stepMoverIsRed(prev, cur))
        // 黑车吃红兵(落点索引小于出发点) → false
        val prev2 = Position(board(74 to Piece.B_ROOK, 65 to Piece.R_PAWN), true)
        val cur2 = Position(board(65 to Piece.B_ROOK), true)
        assertEquals(false, MoveCheck.stepMoverIsRed(prev2, cur2))
    }

    @Test
    fun `stepMoverIsRed 非法步返回 null`() {
        // 两处差异但落点不是出发点的子(异子替换) → null
        val prev = Position(board(65 to Piece.R_KNIGHT), true)
        val cur = Position(board(74 to Piece.R_CANNON), true)
        assertNull(MoveCheck.stepMoverIsRed(prev, cur))
        // 无差异 → null
        val same = Position(board(65 to Piece.R_KNIGHT), true)
        assertNull(MoveCheck.stepMoverIsRed(prev, same))
    }

    @Test
    fun `ghostMoverIsRed 返回宽松步移动者颜色`() {
        // 黑卒走后出发点残留假子 → false
        val prev = Position(board(65 to Piece.B_PAWN), true)
        val cur = Position(board(65 to Piece.B_ADVISOR, 56 to Piece.B_PAWN), true)
        assertEquals(false, MoveCheck.ghostMoverIsRed(prev, cur))
        assertTrue(MoveCheck.isStepGhostOrigin(prev, cur))
        // 红马吃卒且出发点残留 → true
        val prev2 = Position(board(65 to Piece.R_KNIGHT, 20 to Piece.B_PAWN), true)
        val cur2 = Position(board(65 to Piece.R_ROOK, 20 to Piece.R_KNIGHT), true)
        assertEquals(true, MoveCheck.ghostMoverIsRed(prev2, cur2))
    }
}
