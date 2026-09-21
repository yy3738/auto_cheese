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
import android.util.DisplayMetrics
import android.view.Display

/**
 * MediaProjection 截屏：授权后 start()，capture() 同步抓一帧返回 Bitmap。
 * 整个进程只需一个实例。
 */
object ScreenCapture {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var width = 0
    private var height = 0
    private var handlerThread: HandlerThread? = null

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

    /** 抓一帧；virtualDisplay.resize 首帧渲染需要一点时间 */
    fun capture(staleOkMs: Long = 500): Bitmap? {
        val reader = imageReader ?: return null
        val image: Image = try {
            reader.acquireLatestImage() ?: return null
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
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        projection?.stop()
        projection = null
        handlerThread?.quitSafely()
        handlerThread = null
    }

    val isRunning: Boolean get() = projection != null
}
