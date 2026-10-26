package com.cheese.assistant

import com.cheese.assistant.capture.CellFrame
import com.cheese.assistant.capture.MotionTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 动画状态机（S1b）：快棋窗口切分。
 * 时序来自实机：AI 落子 0.5-1s、人类 1-3s、我方思考 2-3s；
 * 落定判定 = 非装饰格安静 ≥180ms，中间平台 = 安静 ≥90ms。
 */
class MotionTrackerTest {

    private fun frame(
        ts: Long,
        changed: Set<Int> = emptySet(),
        warm: Map<Int, Float> = emptyMap(),
        white: Set<Int> = emptySet(),
    ): CellFrame {
        val warmth = FloatArray(90)
        val fp = IntArray(90 * 16)
        warm.forEach { (i, v) -> warmth[i] = v }
        changed.forEach { i ->
            for (k in 0 until 16) fp[i * 16 + k] = 1
        }
        val w = FloatArray(90)
        white.forEach { i -> w[i] = 0.8f }
        return CellFrame(ts, warmth, fp, w, FloatArray(90))
    }

    @Test
    fun `静止画面不产生落定窗口`() {
        val tracker = MotionTracker()
        assertNull(tracker.onFrame(frame(0)))
        assertNull(tracker.onFrame(frame(60)))
        assertNull(tracker.onFrame(frame(120)))
    }

    @Test
    fun `一步走子安静后产出落定窗口与平台`() {
        val tracker = MotionTracker()
        tracker.onFrame(frame(0))
        assertNull(tracker.onFrame(frame(60, changed = setOf(10))))
        // 安静 90ms → 平台
        assertNull(tracker.onFrame(frame(150, changed = setOf(10))))
        // 安静 180ms → 落定
        val win = tracker.onFrame(frame(240, changed = setOf(10)))
        assertNotNull(win)
        assertEquals(1, win!!.changed.count { it })
        assertTrue(win.changed[10])
        assertEquals(1, win.plateaus.size)
        assertEquals(180, win.movingMs)
    }

    @Test
    fun `连续动画帧不落定 动画结束后才落定`() {
        val tracker = MotionTracker()
        tracker.onFrame(frame(0))
        // 滑行/飞行：每帧都有格子变化
        for (t in 50L..300L step 50) {
            val cell = 20 + ((t / 50).toInt() % 5)
            assertNull(tracker.onFrame(frame(t, changed = setOf(cell))))
        }
        assertTrue(tracker.isMoving)
        assertNull(tracker.onFrame(frame(350, changed = setOf(21))))
        val win = tracker.onFrame(frame(480, changed = setOf(21)))
        assertNotNull(win)
        assertEquals(480, win!!.settleTs)
    }

    @Test
    fun `两连手留下两个平台且变化格取并集`() {
        val tracker = MotionTracker()
        tracker.onFrame(frame(0))
        tracker.onFrame(frame(50, changed = setOf(1)))
        // 第一手落定前的平台
        tracker.onFrame(frame(140, changed = setOf(1)))
        // 第二手立刻开始（中间平台没等到落定）
        tracker.onFrame(frame(190, changed = setOf(1, 2)))
        tracker.onFrame(frame(280, changed = setOf(1, 2)))
        val win = tracker.onFrame(frame(370, changed = setOf(1, 2)))
        assertNotNull(win)
        assertEquals(2, win!!.plateaus.size)
        assertTrue(win.changed[1])
        assertTrue(win.changed[2])
    }

    @Test
    fun `白圈红圈脉动不算棋子变化`() {
        val tracker = MotionTracker()
        tracker.onFrame(frame(0, white = setOf(5)))
        // 只有白圈/木色不动，指纹不变 → 不进入 MOVING
        assertNull(tracker.onFrame(frame(60, white = emptySet())))
        assertNull(tracker.onFrame(frame(120, white = setOf(5))))
        assertTrue(!tracker.isMoving)
    }

    @Test
    fun `木色突变量达到阈值也算变化`() {
        val tracker = MotionTracker()
        tracker.onFrame(frame(0))
        assertNull(tracker.onFrame(frame(50, warm = mapOf(7 to 0.8f))))
        val win = tracker.onFrame(frame(240, warm = mapOf(7 to 0.8f)))
        assertNotNull(win)
        assertTrue(win!!.changed[7])
    }

    @Test
    fun `单格且前后都暖是点选抬起 不算走子`() {
        val tracker = MotionTracker()
        tracker.onFrame(frame(0, warm = mapOf(3 to 0.8f)))
        // 棋子被抬起：只有这一格像素变，木色仍在
        assertNull(tracker.onFrame(frame(60, changed = setOf(3), warm = mapOf(3 to 0.8f))))
        assertNull(tracker.onFrame(frame(300, changed = setOf(3), warm = mapOf(3 to 0.8f))))
        assertTrue(!tracker.isMoving)
    }

    @Test
    fun `单格由暖变冷是真消失 要进入 MOVING`() {
        val tracker = MotionTracker()
        tracker.onFrame(frame(0, warm = mapOf(4 to 0.8f)))
        assertNull(tracker.onFrame(frame(60, changed = setOf(4))))
        assertTrue(tracker.isMoving)
    }

    @Test
    fun `静态画面不再推帧时 tick 也能落定`() {
        val tracker = MotionTracker()
        tracker.onFrame(frame(0))
        tracker.onFrame(frame(50, changed = setOf(10)))
        // 后再无新帧：安静计时只能靠 tick 推进
        assertNull(tracker.tick(120))
        assertNull(tracker.tick(200))
        val win = tracker.tick(220)
        assertNotNull(win)
        assertTrue(win!!.changed[10])
        assertEquals(1, win.plateaus.size)
    }

    @Test
    fun `落定后不会重复产出窗口`() {
        val tracker = MotionTracker()
        tracker.onFrame(frame(0))
        tracker.onFrame(frame(50, changed = setOf(10)))
        assertNotNull(tracker.onFrame(frame(240, changed = setOf(10))))
        assertNull(tracker.onFrame(frame(300, changed = setOf(10))))
        assertNull(tracker.tick(600))
        assertTrue(!tracker.isMoving)
    }

    @Test
    fun `reset 后重新建立基线`() {
        val tracker = MotionTracker()
        tracker.onFrame(frame(0))
        tracker.onFrame(frame(50, changed = setOf(10)))
        tracker.reset()
        assertTrue(!tracker.isMoving)
        // reset 后的第一帧是新基线，不产出窗口
        assertNull(tracker.onFrame(frame(400, changed = setOf(10))))
        assertNull(tracker.tick(2000))
    }
}
