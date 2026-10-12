package com.cheese.assistant

import com.cheese.assistant.board.MoveFormatter
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
    fun `车换卒是价值跳变不是合法换子`() {
        // 实测:点击棋子后JJ把棋子抬起,轮廓变化使车被识别成卒,
        // 采纳后毒FEN进引擎 set_position failed
        val prev = Position(board(83 to Piece.B_ROOK), true)
        val cur = Position(board(83 to Piece.B_PAWN), true)
        assertTrue(MoveCheck.isSingleRetag(prev, cur))
        assertTrue(MoveCheck.isValueJumpRetag(prev, cur))
    }

    @Test
    fun `异色单点换子不是价值跳变`() {
        val prev = Position(board(83 to Piece.B_ROOK), true)
        val cur = Position(board(83 to Piece.R_PAWN), true)
        assertFalse(MoveCheck.isValueJumpRetag(prev, cur))
    }

    @Test
    fun `同类子换标签不是价值跳变`() {
        // 光圈误配恢复:炮↔仕 同为大子侧互换? 炮是大子,仕不是——
        // 但实测这类"新走的炮被配成仕"需要被采纳修正锚点,
        // 价值跳变只拦大子→小子的跨级互换
        val prev = Position(board(65 to Piece.R_CANNON, 83 to Piece.B_PAWN), true)
        val cur = Position(board(65 to Piece.R_ADVISOR, 83 to Piece.B_PAWN), true)
        assertTrue(MoveCheck.isSingleRetag(prev, cur))
        assertFalse(MoveCheck.isValueJumpRetag(prev, cur))
    }

    @Test
    fun `小子换大子是恢复帧不是价值跳变`() {
        // 抬起结束后的恢复帧(卒→车)必须放行,毒锚点才能自愈;
        // 双向拦截会让毒永远洗不掉
        val prev = Position(board(83 to Piece.B_PAWN), true)
        val cur = Position(board(83 to Piece.B_ROOK), true)
        assertTrue(MoveCheck.isSingleRetag(prev, cur))
        assertFalse(MoveCheck.isValueJumpRetag(prev, cur))
    }

    @Test
    fun `黑卒在行0至2是不可能的局面`() {
        // 实测:点击选中抬起黑车,被误配成黑卒出现在底行(norm 行0)
        val pos = Position(board(7 to Piece.B_PAWN), true)
        assertTrue(MoveCheck.hasImpossiblePawn(pos))
    }

    @Test
    fun `黑卒在行3是正常的局面`() {
        val pos = Position(board(27 to Piece.B_PAWN), true)
        assertFalse(MoveCheck.hasImpossiblePawn(pos))
    }

    @Test
    fun `红兵在行7至9是不可能的局面`() {
        val pos = Position(board(63 to Piece.R_PAWN), true)
        assertTrue(MoveCheck.hasImpossiblePawn(pos))
    }

    @Test
    fun `无不可能兵位返回假`() {
        val pos = Position(board(65 to Piece.R_KNIGHT, 33 to Piece.B_PAWN), true)
        assertFalse(MoveCheck.hasImpossiblePawn(pos))
    }

    @Test
    fun `用锚点修复抬起棋子的误读兵卒`() {
        // 锚点:黑车在 7(行0);抬起后同格被误读成黑卒(不可能位)
        val anchor = Position(board(7 to Piece.B_ROOK, 65 to Piece.R_KNIGHT), true)
        val bad = Position(board(7 to Piece.B_PAWN, 65 to Piece.R_KNIGHT), true)
        val fixed = MoveCheck.repairImpossiblePawns(anchor, bad)
        assertEquals(Piece.B_ROOK, fixed?.board?.get(7))
    }

    @Test
    fun `锚点同格是兵卒无法修复`() {
        // 锚点同格也是兵(合法的兵推进到此处),不可能是误读,拒绝修复
        val anchor = Position(board(27 to Piece.B_PAWN), true)
        val bad = Position(board(27 to Piece.B_PAWN), true)
        // 该帧本身没有不可能位,构造一个:7格卒 + 锚点同格无子
        val anchor2 = Position(board(), true)
        val bad2 = Position(board(7 to Piece.B_PAWN), true)
        assertNull(MoveCheck.repairImpossiblePawns(anchor2, bad2))
        @Suppress("UNUSED_VARIABLE") val unused = anchor to bad
    }

    @Test
    fun `修复结果与锚点相同时可判断未动`() {
        val anchor = Position(board(7 to Piece.B_ROOK, 30 to Piece.B_PAWN), true)
        val bad = Position(board(7 to Piece.B_PAWN, 30 to Piece.B_PAWN), true)
        val fixed = MoveCheck.repairImpossiblePawns(anchor, bad)
        assertTrue(fixed?.board?.contentEquals(anchor.board) == true)
    }

    @Test
    fun `缺红王时用锚点王格修复`() {
        // 锚点红王在 4;当前帧被将军特效罩住判空
        val anchor = Position(board(4 to Piece.R_KING, 85 to Piece.B_KING, 30 to Piece.B_PAWN), true)
        val bad = Position(board(85 to Piece.B_KING, 30 to Piece.B_PAWN), true)
        val fixed = MoveCheck.restoreMissingKings(anchor, bad)
        assertEquals(Piece.R_KING, fixed?.board?.get(4))
    }

    @Test
    fun `缺红王但王格被同色子占据也修复`() {
        // 实测:帅被白光圈罩住被误读成炮(同色其他子),也要放回王
        val anchor = Position(board(4 to Piece.R_KING, 85 to Piece.B_KING), true)
        val bad = Position(board(4 to Piece.R_CANNON, 85 to Piece.B_KING), true)
        val fixed = MoveCheck.restoreMissingKings(anchor, bad)
        assertEquals(Piece.R_KING, fixed?.board?.get(4))
    }

    @Test
    fun `双王俱在无需修复`() {
        val anchor = Position(board(4 to Piece.R_KING, 85 to Piece.B_KING), true)
        val ok = Position(board(4 to Piece.R_KING, 85 to Piece.B_KING), true)
        assertNull(MoveCheck.restoreMissingKings(anchor, ok))
    }

    @Test
    fun `王格被敌子占据时拒绝修复`() {
        // 当前帧王格出现敌方棋子 → 王真被吃了(对局结束),修复无意义
        val anchor = Position(board(4 to Piece.R_KING, 85 to Piece.B_KING), true)
        val bad = Position(board(4 to Piece.B_KNIGHT, 85 to Piece.B_KING), true)
        assertNull(MoveCheck.restoreMissingKings(anchor, bad))
    }

    @Test
    fun `锚点无王时拒绝修复`() {
        val anchor = Position(board(85 to Piece.B_KING), true)
        val bad = Position(board(85 to Piece.B_KING), true)
        assertNull(MoveCheck.restoreMissingKings(anchor, bad))
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

    @Test
    fun `执方判定红上黑下用户执黑`() {
        // 常规:红王 row0(屏幕上),黑王 row9(屏幕下) → 用户执黑
        val pos = Position(board(4 to Piece.R_KING, 85 to Piece.B_KNIGHT), true)
        val pos2 = Position(board(4 to Piece.R_KING, 85 to Piece.B_KING), true)
        assertEquals(false, MoveCheck.userIsRed(pos2))
        @Suppress("UNUSED_VARIABLE") val unused = pos
    }

    @Test
    fun `执方判定红下黑上用户执红`() {
        // JJ 让用户执红坐屏幕下方:红王 row9,黑王 row0
        val pos = Position(board(4 to Piece.R_KING, 85 to Piece.B_KING), true)
        val flipped = Position(Array(90) { i ->
            val r = i / 9; val c = i % 9
            pos.board[(9 - r) * 9 + c]
        }, true)
        assertTrue(MoveCheck.userIsRed(flipped))
    }

    @Test
    fun `执方判定王在九宫顶线row7仍正确`() {
        // 实测 bug 现场:黑将走到九宫顶线 row7(将士士缩宫顶),旧扫描窗
        // (row9-8)落空后静默 fallback 成红方。新逻辑按双王行比较不受影响
        val pos = Position(board(4 to Piece.R_KING, 76 to Piece.B_KING,
            67 to Piece.B_ADVISOR, 66 to Piece.B_ADVISOR), true)
        assertEquals(false, MoveCheck.userIsRed(pos))
    }

    @Test
    fun `执方判定用户王也缩到宫顶线时红王仍在底线`() {
        // 红王 row9 黑王 row7:红在屏幕更下方 → 用户执红
        val pos = Position(board(85 to Piece.R_KING, 76 to Piece.B_KING), true)
        assertTrue(MoveCheck.userIsRed(pos))
    }
}

class MoveFormatterTest {

    // 黑车在 rank6 col7(黑方视角右起第2路)，卒在 col0/2/4/8
    private val blackRookFen =
        "2bak1b2/9/2n1b1n2/p1p1p1prp/9/9/P1P1P3P/1C2C4/9/RNBAKABNR b"
    private val startFen =
        "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w"

    @Test
    fun `黑车直线进1步不显示目标纵线号`() {
        // 旧bug: "进N"显示目标rank而非步数(如"卒3进5"实为进1步)
        assertEquals("车2进1", MoveFormatter.toChinese(blackRookFen, "h6h5"))
    }

    @Test
    fun `黑车同列后退显示退`() {
        // 旧bug: 同列直线无条件写"进"
        assertEquals("车2退1", MoveFormatter.toChinese(blackRookFen, "h6h7"))
    }

    @Test
    fun `红马斜走进用汉字`() {
        // 马八进七: b0 -> c2
        assertEquals("马八进七", MoveFormatter.toChinese(startFen, "b0c2"))
    }

    @Test
    fun `红炮横移显示平`() {
        // 炮二平五: h2 -> e2
        assertEquals("炮二平五", MoveFormatter.toChinese(startFen, "h2e2"))
    }
}
