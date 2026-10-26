package com.cheese.assistant.capture

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display
import com.cheese.assistant.util.AppLog

/**
 * MediaProjection 截屏：授权后 start()，capture() 同步抓一帧返回 Bitmap。
 * 整个进程只需一个实例。
 *
 * 2026-10-23（S1b）新增细胞状态流：监听式逐帧采样 90 格，动画落定后
 * 把窗口 + 落定帧 Bitmap 入队。识别循环不再靠 350ms×2 盲等，也不会在
 * 引擎分析期间漏掉中间平台。
 */
object ScreenCapture {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    @Volatile private var width = 0
    @Volatile private var height = 0
    private var handlerThread: HandlerThread? = null

    /** 落定结果：状态机窗口 + 落定帧（取不到位图时为 null） */
    class SettleFrame(val window: MotionTracker.Window, val bitmap: Bitmap?)

    /** 最近一帧缓存：倒计时抓屏后由校准界面读取 */
    var lastFrame: Bitmap? = null
        private set

    // ---- 细胞状态流（S1b） ----
    private const val SETTLE_KEEP = 3
    /** 静态画面不再推帧：轮询期间用这个间隔推进"安静"计时 */
    private const val HEARTBEAT_MS = 40L
    /** 安静这么久就先预转一张落定帧（防"动画结束即静止、不再推帧"） */
    private const val PENDING_QUIET_MS = 90L
    private val frameLock = Any()
    private val settleLock = Object()
    private val settleQueue = ArrayDeque<SettleFrame>()

    /** 预转的落定帧：静态画面下 tick 落定时用它 */
    private class Pending(val frameTs: Long, val bitmap: Bitmap)
    private var pendingSettle: Pending? = null

    @Volatile private var cellSampler: CellSampler? = null
    @Volatile private var motionTracker: MotionTracker? = null

    val frameWidth: Int get() = width
    val frameHeight: Int get() = height
    val isCellStreamOn: Boolean get() = cellSampler != null

    /** 动画进行中（有格子变化、还没落定）：识别循环此时不应抓帧识别 */
    val isMoving: Boolean get() = motionTracker?.isMoving == true

    /** 结果码 + 数据由授权 Activity 转交过来 */
    fun start(context: Context, resultCode: Int, data: android.content.Intent) {
        stop()
        val metrics = context.resources.displayMetrics
        width = metrics.widthPixels
        height = metrics.heightPixels

        val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
            as MediaProjectionManager
        projection = mgr.getMediaProjection(resultCode, data)

        handlerThread = HandlerThread("ScreenCapture").also { it.start() }
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection!!.createVirtualDisplay(
            "cheese_capture", width, height, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null,
            Handler(handlerThread!!.looper))
    }

    // ---- 细胞状态流 ----

    /**
     * 开启动画感知采集。采样在投屏线程完成（不转 Bitmap、不跑 CNN），
     * 落定的窗口通过 [pollSettle] 交给识别循环。
     */
    fun startCellStream(sampler: CellSampler): Boolean {
        val reader = imageReader ?: return false
        val thread = handlerThread ?: return false
        synchronized(frameLock) {
            cellSampler = sampler
            motionTracker = MotionTracker()
            reader.setOnImageAvailableListener({ r -> onImage(r) }, Handler(thread.looper))
        }
        synchronized(settleLock) {
            settleQueue.clear()
            pendingSettle = null
        }
        AppLog.i("ScreenCapture", "cell stream on: ${sampler.frameSize} " +
            "gap=${sampler.sampleGap}ms")
        return true
    }

    /**
     * 停流。[owner] 非空时只停自己的流：重试/纠错会用 `cancel()` 重建
     * watcher，旧协程的 finally 可能晚于新 watcher 的启动执行——没有归属
     * 校验就会把新流关掉且新 watcher 不再重试（2026-10-23 代码审查 HIGH）。
     */
    fun stopCellStream(owner: CellSampler? = null) {
        var stopped = false
        var mismatched = false
        synchronized(frameLock) {
            val cur = cellSampler
            when {
                cur == null -> Unit
                owner != null && owner !== cur -> mismatched = true
                else -> {
                    cellSampler = null
                    motionTracker = null
                    runCatching { imageReader?.setOnImageAvailableListener(null, null) }
                    stopped = true
                }
            }
        }
        if (mismatched) {
            AppLog.w("ScreenCapture", "stopCellStream skipped: stream owned by newer watcher")
            return
        }
        if (!stopped) return
        synchronized(settleLock) {
            settleQueue.clear()
            pendingSettle = null
            settleLock.notifyAll()
        }
        AppLog.i("ScreenCapture", "cell stream off")
    }

    /** 识别暂停（非对局前台/实况页）时清空落定队列，恢复后不吃过期窗口 */
    fun clearSettles() {
        synchronized(settleLock) {
            settleQueue.clear()
            pendingSettle = null
            settleLock.notifyAll()
        }
    }

    /** 阻塞等一个落定窗口（识别循环用）：有就立即返回，没有最多等 timeoutMs */
    fun pollSettle(timeoutMs: Long = 0): SettleFrame? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            synchronized(settleLock) {
                settleQueue.removeFirstOrNull()?.let { return it }
            }
            // 静态画面没有新帧时，状态机的安静计时不会前进——用 tick 推进，
            // 落定帧取预转的那张（动画结束时画面已静止，内容仍然有效）
            motionTracker?.tick(SystemClock.elapsedRealtime())?.let { win ->
                val bmp = synchronized(settleLock) {
                    pendingSettle?.bitmap.also { pendingSettle = null }
                }
                AppLog.d("ScreenCapture",
                    "settle by heartbeat seq=${win.seq} moving=${win.movingMs}ms" +
                        (if (bmp == null) " (no frame)" else ""))
                return SettleFrame(win, bmp)
            }
            val left = deadline - SystemClock.elapsedRealtime()
            if (left <= 0) return null
            synchronized(settleLock) {
                if (settleQueue.isEmpty()) {
                    try {
                        settleLock.wait(left.coerceAtMost(HEARTBEAT_MS))
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return null
                    }
                }
            }
        }
    }

    private fun onImage(reader: ImageReader) {
        val image = try {
            synchronized(frameLock) { reader.acquireLatestImage() }
        } catch (_: Exception) {
            null
        } ?: return
        try {
            synchronized(frameLock) {
                val sampler = cellSampler ?: return
                val tracker = motionTracker ?: return
                val now = SystemClock.elapsedRealtime()
                val frame = sampler.sample(image, now) ?: return
                val window = tracker.onFrame(frame)
                if (window != null) {
                    // 落定：转换落定帧并入队（一次落定一次转换）
                    val bmp = runCatching { imageToBitmap(image) }.getOrNull()
                    enqueue(SettleFrame(window, bmp))
                    return
                }
                if (tracker.isMoving) {
                    val quiet = tracker.quietMs(now)
                    if (quiet >= PENDING_QUIET_MS) {
                        // 一段安静：预转一张落定帧，防"画面静止后不再推帧"
                        synchronized(settleLock) {
                            if (pendingSettle == null) {
                                pendingSettle = runCatching { imageToBitmap(image) }
                                    .getOrNull()?.let { Pending(frame.ts, it) }
                            }
                        }
                    } else {
                        synchronized(settleLock) { pendingSettle = null }
                    }
                }
            }
        } catch (t: Throwable) {
            AppLog.e("ScreenCapture", "cell stream frame failed", t)
        } finally {
            image.close()
        }
    }

    private fun enqueue(settle: SettleFrame) {
        var dropped: Long? = null
        synchronized(settleLock) {
            if (settleQueue.size >= SETTLE_KEEP) {
                dropped = settleQueue.removeFirst().window.seq
            }
            settleQueue.addLast(settle)
            pendingSettle = null
            settleLock.notifyAll()
        }
        dropped?.let {
            AppLog.w("ScreenCapture", "settle queue full, drop seq=$it")
        }
        val w = settle.window
        AppLog.d("ScreenCapture",
            "settle seq=${w.seq} moving=${w.movingMs}ms " +
                "changed=${w.changed.count { it }} plateaus=${w.plateaus.size} " +
                "white=${w.white.count { it > 0.2f }} " +
                "red=${w.red.count { it > 0.2f }}" +
                if (w.changed.count { it } <= 12)
                    " cells=" + w.changed.indices.filter { w.changed[it] }
                        .joinToString { "(${it / 9},${it % 9})" }
                else "")
    }

    /**
     * 等待画面稳定：连续两帧 isSame 判定一致才返回（对局动画结束的信号）。
     * 静止画面下 ImageReader 不再推送新帧，captureFresh 超时即视为稳定，
     * 直接返回已持有的帧。全程只在内存中比对，不落盘。
     */
    fun captureStable(
        quietMs: Long = 350,
        timeoutMs: Long = 8000,
        isSame: (Bitmap, Bitmap) -> Boolean,
    ): Bitmap? {
        var prev = captureFresh() ?: return null
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(quietMs)
            // 静止画面 ImageReader 不推新帧(fresh=null)属正常: 返回已持有帧。
            // 投屏真死时 isRunning/上层 credibility 检测兜底, 这里不能
            // 用 "null 代替 prev"——否则静止界面永远识别失败(2026-10-20
            // 回归教训: 此处改动让主界面停留即"识别失败")
            val next = captureFresh(timeoutMs = 250) ?: return prev
            if (isSame(prev, next)) return next
            prev = next
        }
        return null
    }

    /** 抓一帧并缓存到 lastFrame，返回是否成功 */
    fun captureToCache(): Boolean {
        val bmp = captureFresh() ?: return false
        lastFrame = bmp
        return true
    }

    /**
     * 抓一帧新画面：先丢掉缓冲里的陈旧帧（VirtualDisplay 在后台不持续推流，
     * acquireLatestImage 会返回几秒前的旧帧），等新帧渲染进来再取。
     */
    fun captureFresh(timeoutMs: Long = 2000): Bitmap? {
        val reader = imageReader ?: return null
        val stale = try { synchronized(frameLock) { reader.acquireLatestImage() } }
            catch (_: Exception) { null }
        stale?.close()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val image = try { synchronized(frameLock) { reader.acquireLatestImage() } }
                catch (_: Exception) { null }
            if (image != null) {
                try { return imageToBitmap(image) } finally { image.close() }
            }
            Thread.sleep(50)
        }
        return null
    }

    /** 抓一帧(校准/识别工作台在 lastFrame 缺失时兜底取当前帧) */
    fun capture(): Bitmap? {
        val reader = imageReader ?: return null
        val image: Image = try {
            synchronized(frameLock) { reader.acquireLatestImage() } ?: return null
        } catch (_: Exception) {
            return null
        }
        try {
            return imageToBitmap(image)
        } finally {
            image.close()
        }
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val rowPadding = plane.rowStride - plane.pixelStride * width
        val bitmap = Bitmap.createBitmap(
            width + rowPadding / plane.pixelStride, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(plane.buffer)
        // 裁掉 rowPadding 区域
        return if (rowPadding == 0) bitmap
        else Bitmap.createBitmap(bitmap, 0, 0, width, height)
    }

    fun stop() {
        stopCellStream()
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        projection?.stop()
        projection = null
        // 释放约 8MB 的帧缓存;校准/识别工作台每次都会重新 captureToCache
        lastFrame = null
        handlerThread?.quitSafely()
        handlerThread = null
    }

    val isRunning: Boolean get() = projection != null
}
