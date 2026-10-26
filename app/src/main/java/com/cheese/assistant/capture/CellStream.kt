package com.cheese.assistant.capture

import android.media.Image
import com.cheese.assistant.recognize.BoardGrid

/**
 * 单帧 90 格细胞状态（raw 屏幕方向，row0 = 屏幕最上排）。
 *
 * 只存采样结果，不存整帧位图：3 秒窗口 ≈ 60 帧 × ~7KB，内存可忽略。
 * 指纹用于"这一格像素变没变"，木色占比用于"这格还有没有棋子"，
 * 白/红标记只做歧义佐证，不参与落定判定（用户明确：别过度依赖光圈）。
 */
class CellFrame(
    val ts: Long,
    /** 每格木色占比：格心 4×4 采样中 `R-B>40` 的比例（棋子 0.56+ / 空位 0.02-） */
    val warmth: FloatArray,
    /** 每格中心 4×4 灰度量化指纹（90*16），格心采样以避开外围光圈 */
    val finger: IntArray,
    /** 每格边缘白圈占比（JJ 最后一步起点标记，仅佐证） */
    val white: FloatArray,
    /** 每格边缘红标记占比（落点/威胁，仅佐证） */
    val red: FloatArray,
) {
    companion object {
        const val CELLS = 90
        const val FP_GRID = 4
        const val FP_PER_CELL = FP_GRID * FP_GRID
    }
}

/**
 * 从 MediaProjection 的 Image 平面直接采样 90 格细胞状态（S1b）。
 *
 * 不转 Bitmap、不跑 CNN：60fps 的原始帧里只有"90 格变没变"需要知道，
 * 分类留给落定帧的懒推理。格心 4×4 指纹取 ±0.3 格距，避开 0.45 格半径
 * 的白圈/红圈——光圈脉动是装饰，不能让它把落定无限推迟。
 *
 * 采样节流到 [sampleGapMs]（默认 50ms≈20fps）：3 秒 60 帧足够覆盖
 * 对方 AI 0.5-1s / 人类 1-3s 的窗口，又不会把 CPU 打满。
 */
class CellSampler(
    private val grid: BoardGrid,
    private val frameWidth: Int,
    private val frameHeight: Int,
    private val sampleGapMs: Long = 50,
    /** 悬浮窗矩形：窗内格不采样（窗文字每帧变化，会把落定永久推迟） */
    private val excludeBounds: (() -> android.graphics.Rect?)? = null,
) {
    private var lastTs = 0L

    val frameSize: String get() = "${frameWidth}x$frameHeight"
    val sampleGap: Long get() = sampleGapMs

    private val fpStep = (grid.cellSize * 0.2f).toInt().coerceAtLeast(1)
    private val ringStep = grid.cellSize * 0.225f

    fun sample(image: Image, ts: Long): CellFrame? {
        if (ts - lastTs < sampleGapMs) return null
        val plane = image.planes.firstOrNull() ?: return null
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        if (pixelStride < 4) return null
        val w = minOf(frameWidth, image.width)
        val h = minOf(frameHeight, image.height)
        if (w <= 0 || h <= 0) return null
        val limit = buf.limit()

        fun rgb(x: Int, y: Int): Int {
            val idx = y.coerceIn(0, h - 1) * rowStride + x.coerceIn(0, w - 1) * pixelStride
            if (idx + 2 >= limit) return 0
            val r = buf.get(idx).toInt() and 0xFF
            val g = buf.get(idx + 1).toInt() and 0xFF
            val b = buf.get(idx + 2).toInt() and 0xFF
            return (r shl 16) or (g shl 8) or b
        }

        val warmth = FloatArray(CellFrame.CELLS)
        val fp = IntArray(CellFrame.CELLS * CellFrame.FP_PER_CELL)
        val white = FloatArray(CellFrame.CELLS)
        val red = FloatArray(CellFrame.CELLS)
        val n = CellFrame.FP_GRID
        val half = (n - 1) / 2f
        val blocked = excludeBounds?.invoke()

        for (i in 0 until CellFrame.CELLS) {
            val cx = grid.xs[i].toInt()
            val cy = grid.ys[i].toInt()
            // 悬浮窗覆盖的格保持恒零：窗口自身文字动画不能算棋子变化
            if (blocked != null && blocked.contains(cx, cy)) continue
            val o = i * CellFrame.FP_PER_CELL
            var warm = 0
            for (dy in 0 until n) for (dx in 0 until n) {
                val x = (cx + (dx - half) * fpStep).toInt()
                val y = (cy + (dy - half) * fpStep).toInt()
                val c = rgb(x, y)
                val r = c shr 16 and 0xFF
                val g = c shr 8 and 0xFF
                val bl = c and 0xFF
                fp[o + dy * n + dx] = ((0.299f * r + 0.587f * g + 0.114f * bl) / 32f).toInt()
                if (r - bl > 40) warm++
            }
            var whitish = 0
            var reddish = 0
            for (dy in -1..1) for (dx in -1..1) {
                val cr = rgb((cx + dx * ringStep).toInt(), (cy + dy * ringStep).toInt())
                val rr = cr shr 16 and 0xFF
                val gg = cr shr 8 and 0xFF
                val bb = cr and 0xFF
                val hi = maxOf(rr, gg, bb)
                val lo = minOf(rr, gg, bb)
                if (lo > 190 && hi - lo < 45) whitish++          // 白圈：亮、低饱和
                if (rr > 170 && rr - gg > 110) reddish++        // 红圈/红点
            }
            warmth[i] = warm / n.toFloat() / n
            white[i] = whitish / 9f
            red[i] = reddish / 9f
        }
        lastTs = ts
        return CellFrame(ts, warmth, fp, white, red)
    }
}

/**
 * 动画状态机（S1b）：把逐帧细胞状态切成 STABLE / MOVING / PLATEAU / SETTLE。
 *
 * - 非装饰格（格心指纹）连续 [settleMs] 不变 → 落定，产出一个窗口；
 * - MOVING 中安静满 [plateauMs] 记一个中间平台（AI 落子后、我方落子前的
 *   "想棋"时间就是天然平台），供 1-4 步还原消歧；
 * - 白圈/红圈脉动落在 0.45 格半径，格心指纹不采，天然不计入变化。
 *
 * 纯 Kotlin、无 Android 依赖，便于 JVM 单测。
 */
class MotionTracker(
    private val plateauMs: Long = 80,
    private val settleMs: Long = 160,
    private val cellDiffLimit: Int = 3,
    private val warmthDelta: Float = 0.2f,
    private val ringCapacity: Int = 64,
) {
    /** 中间平台：安静起点时刻 + 相对稳定基线的变化格 */
    class Plateau(val ts: Long, val changed: BooleanArray)

    /** 一段动画的落定结果 */
    class Window(
        val seq: Long,
        val startTs: Long,
        val settleTs: Long,
        /** 相对稳定基线的变化格（raw 方向） */
        val changed: BooleanArray,
        val plateaus: List<Plateau>,
        /** 落定帧的白圈/红圈占比（仅佐证） */
        val white: FloatArray,
        val red: FloatArray,
    ) {
        val movingMs: Long get() = settleTs - startTs
    }

    private var baseline: CellFrame? = null
    private var lastSeen: CellFrame? = null
    private var moving = false
    private var startTs = 0L
    private var lastChangeTs = 0L
    private var plateauEmitted = false
    private var seq = 0L
    private val plateaus = ArrayList<Plateau>()

    /** 最近约 3 秒的细胞状态（标定/落盘用） */
    private val ring = ArrayDeque<CellFrame>(ringCapacity)

    val isMoving: Boolean get() = moving

    @Synchronized
    fun onFrame(frame: CellFrame): Window? {
        ring.addLast(frame)
        while (ring.size > ringCapacity) ring.removeFirst()

        val base = baseline
        if (base == null) {
            baseline = frame
            lastSeen = frame
            return null
        }
        val changedSinceSeen = changedCells(lastSeen ?: base, frame)
        if (!moving) {
            if (changedSinceSeen.none { it }) {
                baseline = frame
                lastSeen = frame
                return null
            }
            // 单格变化且前后都是木色棋子 = 点选抬起/落点光圈等装饰：
            // 棋子没走、语义没变，不进入 MOVING（否则按住棋子 1s 会落定出假盘）。
            // 只推进 lastSeen、不推进 baseline——否则真实走子若被拆成两帧，
            // "起点变空"可能被当装饰吃掉，落定窗口的 changed 会漏掉起点格。
            if (isDecorationOnly(base, frame, changedSinceSeen)) {
                lastSeen = frame
                return null
            }
            moving = true
            startTs = frame.ts
            lastChangeTs = frame.ts
            plateauEmitted = false
            plateaus.clear()
            lastSeen = frame
            return null
        }

        lastSeen = frame
        if (changedSinceSeen.any { it }) {
            lastChangeTs = frame.ts
            plateauEmitted = false
            return null
        }
        val quietMs = frame.ts - lastChangeTs
        if (!plateauEmitted && quietMs >= plateauMs) {
            plateauEmitted = true
            plateaus.add(Plateau(frame.ts, changedCells(base, frame)))
        }
        if (quietMs >= settleMs) {
            val window = Window(
                seq = ++seq,
                startTs = startTs,
                settleTs = frame.ts,
                changed = changedCells(base, frame),
                plateaus = ArrayList(plateaus),
                white = frame.white.copyOf(),
                red = frame.red.copyOf(),
            )
            baseline = frame
            moving = false
            plateauEmitted = false
            plateaus.clear()
            return window
        }
        return null
    }

    @Synchronized
    fun recent(ms: Long): List<CellFrame> {
        val now = ring.lastOrNull()?.ts ?: return emptyList()
        return ring.filter { now - it.ts <= ms }
    }

    /** 距最后一次变化已安静的毫秒数（未在 MOVING 时返回 0） */
    @Synchronized
    fun quietMs(now: Long): Long =
        if (moving) (now - lastChangeTs).coerceAtLeast(0) else 0

    /**
     * 无新帧心跳：静止画面下 ImageReader 不再推帧，安静计时只能靠时间推进。
     * 落定帧由采集侧预转的 Bitmap 提供；返回窗口时回到 STABLE。
     */
    @Synchronized
    fun tick(now: Long): Window? {
        val base = baseline ?: return null
        val last = lastSeen ?: return null
        if (!moving) return null
        val quiet = now - lastChangeTs
        if (!plateauEmitted && quiet >= plateauMs) {
            plateauEmitted = true
            plateaus.add(Plateau(now, changedCells(base, last)))
        }
        if (quiet < settleMs) return null
        val window = Window(
            seq = ++seq,
            startTs = startTs,
            settleTs = now,
            changed = changedCells(base, last),
            plateaus = ArrayList(plateaus),
            white = last.white.copyOf(),
            red = last.red.copyOf(),
        )
        baseline = last
        moving = false
        plateauEmitted = false
        plateaus.clear()
        return window
    }

    @Synchronized
    fun reset() {
        baseline = null
        lastSeen = null
        moving = false
        plateauEmitted = false
        plateaus.clear()
        ring.clear()
    }

    /** 两帧之间的变化格：格心指纹差异 > 阈值，或木色占比突变量达阈值 */
    fun changedCells(a: CellFrame, b: CellFrame): BooleanArray {
        val out = BooleanArray(CellFrame.CELLS)
        for (i in 0 until CellFrame.CELLS) {
            val o = i * CellFrame.FP_PER_CELL
            var d = 0
            for (k in 0 until CellFrame.FP_PER_CELL) {
                if (a.finger[o + k] != b.finger[o + k]) d++
            }
            out[i] = d > cellDiffLimit ||
                kotlin.math.abs(a.warmth[i] - b.warmth[i]) >= warmthDelta
        }
        return out
    }

    /**
     * 装饰性单格变化：恰好一格变、且这一格前后都还是木色棋子。
     * 点选抬起（棋子上移几像素）与光圈套子在像素上就是这个形状；
     * 真实走子/吃子必然有起点+落点两格（或木色有无翻转），不会命中。
     */
    private fun isDecorationOnly(a: CellFrame, b: CellFrame, changed: BooleanArray): Boolean {
        var count = 0
        var idx = -1
        for (i in changed.indices) {
            if (changed[i]) { count++; idx = i }
            if (count > 1) return false
        }
        if (count != 1) return false
        return a.warmth[idx] >= DECOR_WARM_MIN && b.warmth[idx] >= DECOR_WARM_MIN
    }

    private companion object {
        /** 木色占比下限：与 BoardWatcher.WARMTH_PIECE_MIN 同一口径 */
        const val DECOR_WARM_MIN = 0.25f
    }
}
