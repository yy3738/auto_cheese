package com.cheese.assistant

import com.cheese.assistant.board.ThreatMarker
import org.junit.Assert.assertEquals
import org.junit.Test

class ThreatMarkerTest {

    // 标准开局
    private val fen = "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w"

    @Test
    fun `空头炮开局打中兵是打吃`() {
        // 炮二平五: h2 -> e2 (row7,col4)。炮到 e2 后直上: row6 col4 红兵为山,
        // 山后 row3 col4 = 中卒(敌) → 该炮下一步可吃卒 → 打打(非当前吃)
        assertEquals(ThreatMarker.Level.THREAT_CAPTURE, ThreatMarker.of(fen, "h2e2"))
    }

    @Test
    fun `开局平车无威胁`() {
        // 车九进一: a0 -> a1 (row8,col0)。该车向上 row7 空、row6 col0 红兵(己方)挡,
        // 横向 row8 col1 红马(己方)。该子无下一步吃敌子 → NONE
        assertEquals(ThreatMarker.Level.NONE, ThreatMarker.of(fen, "a0a1"))
    }

    @Test
    fun `平车吃敌子本身也算打吃`() {
        // 红车 h0(row9,col7), 黑卒 h5(row4,col7)。h0h5 直接吃卒 → CAPTURE
        val f = "4k4/9/9/9/7p1/9/9/9/9/7R1 w"
        assertEquals(ThreatMarker.Level.CAPTURE, ThreatMarker.of(f, "h0h5"))
    }

    @Test
    fun `将军优先于打吃`() {
        // 黑王 e9(row0,col4), 红车 h2(row7,col7) 平到 e2(row7,col4) →
        // 直攻 row6..1 全空, row0 col4 = 黑王 → CHECK
        val f = "4k4/9/9/9/9/9/9/7R1/9/4K4 w"
        assertEquals(ThreatMarker.Level.CHECK, ThreatMarker.of(f, "h2e2"))
    }

    @Test
    fun `吃王即将军`() {
        // 红车 h2(row7,col7), 黑王 h0(row9,col7)。h2h0 直接吃王 → CHECK
        val f = "9/9/9/9/9/9/9/7R1/9/7k1 w"
        assertEquals(ThreatMarker.Level.CHECK, ThreatMarker.of(f, "h2h0"))
    }

    @Test
    fun `马踏中卒是打吃`() {
        // 红马 c5(row4,col2) 跳 (-1,+2) → e6(row3,col4) = 中卒, 直接吃 → CAPTURE
        // 马腿 (row4,col3) 空
        val f = "4k4/9/9/4p4/2N6/9/9/9/9/4K4 w"
        assertEquals(ThreatMarker.Level.CAPTURE, ThreatMarker.of(f, "c5e6"))
    }

    @Test
    fun `马腿被蹩不能吃`() {
        // 同上局面, 但马腿 (row4,col3) 放黑卒 → 蹩腿。马其他跳点均无子可吃 → NONE
        val f = "4k4/9/9/4p4/2Np5/9/9/9/9/4K4 w"
        assertEquals(ThreatMarker.Level.NONE, ThreatMarker.of(f, "c5e6"))
    }

    @Test
    fun `炮需隔山才能打吃`() {
        // 炮 d4(row5,col3) 平到 e4(row5,col4), 山 = 红马 e5(row4,col4),
        // 山后 e6(row3,col4) 黑卒 → 该炮隔山吃卒 → 打吃(非当前吃)
        val f = "4k4/9/9/4p4/4N4/3C5/9/9/9/4K4 w"
        assertEquals(ThreatMarker.Level.THREAT_CAPTURE, ThreatMarker.of(f, "d4e4"))
    }

    @Test
    fun `炮无山不能隔空吃`() {
        // 炮 e4(row5,col4) 前进到 e5(row4,col4), 黑卒在 d6(row3,col3) 不在 e 线,
        // 炮 e5 各方向无山可隔 → 无吃点 → NONE
        val f = "4k4/9/9/3p5/9/4C4/9/9/9/4K4 w"
        assertEquals(ThreatMarker.Level.NONE, ThreatMarker.of(f, "e4e5"))
    }

    @Test
    fun `非法局面或非法move返回NONE不崩溃`() {
        assertEquals(ThreatMarker.Level.NONE, ThreatMarker.of("bad fen", "h2e2"))
        assertEquals(ThreatMarker.Level.NONE, ThreatMarker.of(fen, "zz99"))
        assertEquals(ThreatMarker.Level.NONE, ThreatMarker.of(fen, ""))
        assertEquals(ThreatMarker.Level.NONE, ThreatMarker.of(fen, "h2"))
        assertEquals(ThreatMarker.Level.NONE, ThreatMarker.of(fen, "aa10"))
        // 空格上无子
        assertEquals(ThreatMarker.Level.NONE, ThreatMarker.of(fen, "e5e6"))
        // 黑方棋子不能由红方走(轮次不符)
        assertEquals(ThreatMarker.Level.NONE, ThreatMarker.of(fen, "b9c9"))
    }
}
