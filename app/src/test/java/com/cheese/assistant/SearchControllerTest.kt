package com.cheese.assistant

import com.cheese.assistant.engine.Candidate
import com.cheese.assistant.engine.SearchResult
import com.cheese.assistant.engine.dedupCandidates
import com.cheese.assistant.engine.isDegenerateSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 退化首搜判定：引擎抢在 NNUE 就绪前搜索时返回深度1/单候选/评分0，
 * 必须触发一次重搜（曾导致首推兵九进一 +0.0）。
 */
class SearchControllerTest {

    private fun cand(score: Int?, mate: Int?, depth: Int,
                     rank: Int = 1, move: String = "a3a4") =
        Candidate(rank = rank, scoreCp = score, mateIn = mate, depth = depth,
            pv = listOf(move))

    @Test
    fun `深度1单候选评分0判退化`() {
        val r = SearchResult(listOf(cand(0, null, 1)), "a3a4", 1)
        assertTrue(isDegenerateSearch(r))
    }

    @Test
    fun `深度2不算退化`() {
        val r = SearchResult(listOf(cand(0, null, 2)), "a3a4", 2)
        assertFalse(isDegenerateSearch(r))
    }

    @Test
    fun `多候选不算退化`() {
        val r = SearchResult(
            listOf(cand(0, null, 1), cand(0, null, 1)), "a3a4", 1)
        assertFalse(isDegenerateSearch(r))
    }

    @Test
    fun `有评分不算退化`() {
        val r = SearchResult(listOf(cand(35, null, 1)), "a3a4", 1)
        assertFalse(isDegenerateSearch(r))
    }

    @Test
    fun `杀棋不算退化`() {
        val r = SearchResult(listOf(cand(null, 1, 1)), "a3a4", 1)
        assertFalse(isDegenerateSearch(r))
    }

    @Test
    fun `空结果但非失败算退化`() {
        val r = SearchResult(emptyList(), "", 0)
        assertTrue(isDegenerateSearch(r))
    }

    @Test
    fun `非法局面失败不重搜`() {
        val r = SearchResult(emptyList(), "", 0, failed = true)
        assertFalse(isDegenerateSearch(r))
    }

    @Test
    fun `同一着法不同深度只留更深的一条`() {
        val a = cand(10, null, 12, rank = 1, move = "f0e1")
        val b = cand(30, null, 14, rank = 2, move = "f0e1")
        val out = dedupCandidates(listOf(a, b))
        assertEquals(1, out.size)
        assertEquals(14, out[0].depth)
    }

    @Test
    fun `同一着法同深度保留排名靠前`() {
        val a = cand(10, null, 12, rank = 1, move = "f0e1")
        val b = cand(30, null, 12, rank = 2, move = "f0e1")
        val out = dedupCandidates(listOf(a, b))
        assertEquals(1, out.size)
        assertEquals(1, out[0].rank)
    }

    @Test
    fun `不同着法全部保留`() {
        val a = cand(10, null, 12, rank = 1, move = "f0e1")
        val b = cand(5, null, 12, rank = 2, move = "d0e1")
        assertEquals(2, dedupCandidates(listOf(a, b)).size)
    }
}
