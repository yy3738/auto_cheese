package com.cheese.assistant.recognize

import android.graphics.Bitmap
import com.cheese.assistant.board.Piece
import com.cheese.assistant.board.Position
import com.cheese.assistant.capture.ScreenCapture
import java.io.File

/**
 * 自动识别循环核心：等待画面稳定（连续两帧棋盘区域哈希一致，即对局动画
 * 结束的信号）再做完整识别。识别结果需无未定点、双王俱在、棋子数合理
 * 才可信，否则调用方稍后重试。哈希只覆盖校准网格外扩区域，状态栏时钟、
 * 悬浮信息条等棋盘外的动静不触发识别。
 */
class BoardWatcher(
    private val grid: BoardGrid,
    private val templates: TemplateStore,
    /** 非空时识别失败的原始 MediaProjection 帧落盘此目录（限 DUMP_LIMIT 张），供 PC 端对比诊断 */
    private val dumpDir: File? = null,
) {

    companion object {
        private const val DUMP_LIMIT = 5
        private const val QUIET_MS = 350L
        private const val POLL_TIMEOUT_MS = 250L
        private const val STABLE_TIMEOUT_MS = 8000L
        // FNV 已弃用，改用差分比较；采样点总数约 48x48=2304，
        // 允许差异点覆盖少量装饰动画像素
        private const val SAMPLE_STEPS = 48
        private const val MAX_DIFF_PIXELS = SAMPLE_STEPS * 2
        private const val MIN_PIECES = 10
        private const val BBOX_MARGIN = 20f
    }

    // 网格外扩包围盒（原始校准坐标即可，吸附偏差 ~8px 被余量覆盖）
    private val bbox: IntArray by lazy {
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (i in 0 until 90) {
            minX = minOf(minX, grid.xs[i]); maxX = maxOf(maxX, grid.xs[i])
            minY = minOf(minY, grid.ys[i]); maxY = maxOf(maxY, grid.ys[i])
        }
        arrayOf(minX - BBOX_MARGIN, minY - BBOX_MARGIN,
            maxX + BBOX_MARGIN, maxY + BBOX_MARGIN)
            .map { it.toInt().coerceAtLeast(0) }.toIntArray()
    }

    /**
     * 阻塞等待一个可信局面。返回 null 表示本次没有可信结果
     * （动画太久 / 识别有未定点 / 当前画面不是棋盘）。
     * 可信标准：无未定点 + 双方各恰好一个王 + 棋子总数合理。
     * 非棋盘画面（大厅/弹窗）可能凑齐棋子数但没有双王，必须挡住，
     * 否则缺王 FEN 喂给 Pikafish 会触发断言 abort 整个进程。
     */
    fun waitForPosition(timeoutMs: Long = STABLE_TIMEOUT_MS): Position? {
        val bmp = ScreenCapture.captureStable(QUIET_MS, timeoutMs, ::sameRegion)
            ?: return null.also {
                android.util.Log.e("BoardWatcher", "waitForPosition: captureStable null")
            }
        val result = BoardRecognizer.recognize(bmp, grid, templates)
        if (result.hasUnknown) {
            android.util.Log.e("BoardWatcher", "waitForPosition: unknown ${result.unknown.count { it }}")
            android.util.Log.d("BoardWatcher", result.matrixText.replace("\n", " | "))
            dumpFrame(bmp)
            return null
        }
        val pos = normalizeOrientation(result.position)
        var redKings = 0
        var blackKings = 0
        var pieces = 0
        for (p in pos.board) {
            if (p != null) {
                pieces++
                if (p == Piece.R_KING) redKings++
                if (p == Piece.B_KING) blackKings++
            }
        }
        if (redKings != 1 || blackKings != 1 || pieces < MIN_PIECES) {
            android.util.Log.e("BoardWatcher",
                "waitForPosition: credibility fail redK=$redKings blackK=$blackKings pieces=$pieces")
            return null
        }
        android.util.Log.d("BoardWatcher", "position OK pieces=$pieces")
        return pos
    }

    /** 识别失败帧落盘（app 内部存储），adb pull 出来给 PC 端 diag 对比 */
    private fun dumpFrame(bmp: Bitmap) {
        val dir = dumpDir ?: return
        runCatching {
            if ((dir.listFiles()?.size ?: 0) >= DUMP_LIMIT) return
            val f = File(dir, "mp_${System.currentTimeMillis()}.png")
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            android.util.Log.e("BoardWatcher", "dumpFrame: ${f.name}")
        }.onFailure { android.util.Log.e("BoardWatcher", "dumpFrame failed", it) }
    }

    /**
     * 方向归一化：识别矩阵 row0 = 屏幕最上排，而 Position/FEN 约定 row0 =
     * 黑方底线。第三方棋盘（如 JJ）可能让红方坐在屏幕上方，此时直接转
     * FEN 会把红仕放进黑方底线，被引擎以"非法局面"拒绝。按双王行位判断
     * 屏幕方向，红在上时上下翻转（列不变——物理棋盘 a 线总在屏幕左；
     * 若对方 App 的"翻转棋盘"改变网格布局，需重新校准）。
     */
    private fun normalizeOrientation(pos: Position): Position {
        val redRow = pos.board.indexOfFirst { it == Piece.R_KING } / 9
        val blackRow = pos.board.indexOfFirst { it == Piece.B_KING } / 9
        if (redRow > blackRow) return pos
        val flipped = Array(90) { i ->
            val r = i / 9
            val c = i % 9
            pos.board[(9 - r) * 9 + c]
        }
        return Position(flipped, pos.redToMove)
    }

    /**
     * 棋盘区域稳定性判定：降采样逐点比较，差异点比例低于阈值即视为稳定。
     * 对局画面常有持续微动画（选中光圈脉动、云影飘动），严格相等永远
     * 等不到；这些装饰动画只影响少数采样点，棋子挪动则影响一大片。
     */
    private fun sameRegion(a: Bitmap, b: Bitmap): Boolean {
        val ha = sampleRegion(a)
        val hb = sampleRegion(b)
        if (ha.size != hb.size) return false
        var diff = 0
        for (i in ha.indices) if (ha[i] != hb[i] && ++diff > MAX_DIFF_PIXELS) return false
        return true
    }

    /** 棋盘区域降采样像素灰度（量化到 8 级，抵消压缩噪声） */
    private fun sampleRegion(bmp: Bitmap): IntArray {
        val x0 = bbox[0]; val y0 = bbox[1]
        val w = (bbox[2] - bbox[0]).coerceAtMost(bmp.width - x0)
        val h = (bbox[3] - bbox[1]).coerceAtMost(bmp.height - y0)
        if (w <= 0 || h <= 0) return IntArray(0)
        val stepX = maxOf(1, w / SAMPLE_STEPS)
        val stepY = maxOf(1, h / SAMPLE_STEPS)
        val out = ArrayList<Int>(SAMPLE_STEPS * SAMPLE_STEPS)
        var y = y0
        while (y < y0 + h) {
            var x = x0
            while (x < x0 + w) {
                val c = bmp.getPixel(x, y)
                val r = c shr 16 and 0xFF
                val g = c shr 8 and 0xFF
                val bl = c and 0xFF
                out.add(((0.299f * r + 0.587f * g + 0.114f * bl) / 32f).toInt())
                x += stepX
            }
            y += stepY
        }
        return out.toIntArray()
    }
}

/**
 * 识别自动循环的走子校验：新局面与上一局面必须恰好是"一枚棋子从 A 到 B"
 * （可吃子），其余 88 点不变。用于过滤动画中间帧与识别抖动。
 */
object MoveCheck {

    fun isPlausibleStep(prev: Position, cur: Position): Boolean {
        var from = -1
        var to = -1
        for (i in 0 until 90) {
            if (prev.board[i] == cur.board[i]) continue
            if (from < 0) from = i
            else if (to < 0) to = i
            else return false
        }
        if (from < 0 || to < 0) return false
        val mover = prev.board[from] ?: return false
        if (cur.board[from] != null) return false
        if (cur.board[to] != mover) return false
        val captured = prev.board[to]
        if (captured != null && captured.side == mover.side) return false
        return true
    }
}
