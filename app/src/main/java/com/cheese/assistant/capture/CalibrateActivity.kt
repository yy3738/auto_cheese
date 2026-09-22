package com.cheese.assistant.capture

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.cheese.assistant.R
import org.json.JSONObject
import java.io.File

/**
 * 校准：显示目标棋盘截图，用户点棋盘 4 个外角（左上→右上→右下→左下），
 * 保存网格参数供识别阶段把像素坐标映射到 9x10 交叉点。
 *
 * 入口：showCalibrateButton() 在抓屏后弹一个悬浮按钮，点它进入本页
 * （悬浮窗回调的 startActivity 不受 Android 10 后台启动限制）。
 */
class CalibrateActivity : Activity() {

    private lateinit var calibView: CalibrateView
    private lateinit var statusView: TextView
    private lateinit var previewView: ImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        removeCalibrateButton(this)

        val frame = FrameLayout(this)
        calibView = CalibrateView(this)
        frame.addView(calibView)

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xCC000000.toInt())
        }
        statusView = TextView(this).apply {
            setPadding(24, 8, 24, 8)
            setTextColor(Color.WHITE)
            text = "点第 1 个点：棋盘左上外角"
        }
        previewView = ImageView(this).apply { visibility = View.GONE }
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnUndo = Button(this).apply {
            text = "撤销"
            setOnClickListener { calibView.undo() }
        }
        val btnSave = Button(this).apply {
            text = "保存校准"
            isEnabled = false
            setOnClickListener { saveCalibration(); finish() }
        }
        btnRow.addView(btnUndo)
        btnRow.addView(btnSave)
        bottom.addView(statusView)
        bottom.addView(previewView)
        bottom.addView(btnRow)
        frame.addView(bottom, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM))

        setContentView(frame)

        calibView.onPointsChanged = { count ->
            statusView.text = when (count) {
                0 -> "点第 1 个点：棋盘左上外角"
                1 -> "点第 2 个点：棋盘右上外角"
                2 -> "点第 3 个点：棋盘右下外角"
                3 -> "点第 4 个点：棋盘左下外角"
                else -> "网格贴合吗？贴合就保存（下方为裁出的棋盘区域）"
            }
            btnSave.isEnabled = count == 4
            if (count == 4) showBoardPreview() else previewView.visibility = View.GONE
        }
    }

    /** 4 点齐后：把网格内区域裁出来显示，让用户肉眼确认范围正确 */
    private fun showBoardPreview() {
        val bmp = calibView.bitmap
        val (tl, tr, br, bl) = calibView.points
        val left = minOf(tl.first, bl.first).toInt().coerceAtLeast(0)
        val top = minOf(tl.second, tr.second).toInt().coerceAtLeast(0)
        val right = maxOf(tr.first, br.first).toInt().coerceAtMost(bmp.width)
        val bottom = maxOf(bl.second, br.second).toInt().coerceAtMost(bmp.height)
        if (right <= left || bottom <= top) return
        val cropped = Bitmap.createBitmap(bmp, left, top, right - left, bottom - top)
        previewView.setImageBitmap(cropped)
        previewView.visibility = View.VISIBLE
    }

    private fun saveCalibration() {
        val target = intent.getStringExtra(EXTRA_TARGET_APP) ?: "default"
        val (tl, tr, br, bl) = calibView.points
        val json = JSONObject().apply {
            put("targetApp", target)
            put("capturedWidth", calibView.bitmap.width)
            put("capturedHeight", calibView.bitmap.height)
            put("tl", JSONObject().put("x", tl.first).put("y", tl.second))
            put("tr", JSONObject().put("x", tr.first).put("y", tr.second))
            put("br", JSONObject().put("x", br.first).put("y", br.second))
            put("bl", JSONObject().put("x", bl.first).put("y", bl.second))
        }
        calibrationFile(this, target).writeText(json.toString())
    }

    companion object {
        const val EXTRA_TARGET_APP = "targetApp"
        private var entryButton: Button? = null

        fun calibrationFile(context: Context, targetApp: String): File =
            File(context.filesDir, "calib_$targetApp.json")

        /** 抓屏完成后调用：屏幕中央弹「校准」悬浮按钮 */
        @SuppressLint("ClickableViewAccessibility")
        fun showCalibrateButton(context: Context) {
            removeCalibrateButton(context)
            val appContext = context.applicationContext
            val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val btn = Button(appContext).apply { text = "棋盘校准 →" }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.CENTER }
            btn.setOnClickListener {
                removeCalibrateButton(appContext)
                appContext.startActivity(
                    Intent(appContext, CalibrateActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            entryButton = btn
            wm.addView(btn, lp)
        }

        fun removeCalibrateButton(context: Context) {
            val btn = entryButton ?: return
            entryButton = null
            runCatching {
                val wm = context.applicationContext
                    .getSystemService(Context.WINDOW_SERVICE) as WindowManager
                wm.removeView(btn)
            }
        }
    }
}

/**
 * 校准视图：显示截图，按点击收集 4 角，4 点齐后画出推算的 9x10 网格预览。
 */
@SuppressLint("ClickableViewAccessibility")
class CalibrateView(context: android.content.Context) :
    android.view.View(context) {

    val bitmap: Bitmap = ScreenCapture.lastFrame
        ?: ScreenCapture.capture()
        ?: android.graphics.BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)

    var onPointsChanged: ((Int) -> Unit)? = null
    val points = mutableListOf<Pair<Float, Float>>()

    private val dotPaint = Paint().apply {
        color = Color.RED; style = Paint.Style.FILL
    }
    private val linePaint = Paint().apply {
        color = Color.GREEN; strokeWidth = 2f; style = Paint.Style.STROKE
    }

    override fun onDraw(canvas: Canvas) {
        canvas.save()
        val scale = width.toFloat() / bitmap.width
        canvas.scale(scale, scale)
        canvas.drawBitmap(bitmap, 0f, 0f, null)

        points.forEach { (x, y) ->
            canvas.drawCircle(x, y, 8f / scale, dotPaint)
        }
        if (points.size == 4) {
            drawGrid(canvas)
        }
        canvas.restore()
    }

    private fun drawGrid(canvas: Canvas) {
        val (tl, tr, br, bl) = points
        for (col in 0..8) {
            val t = col / 8f
            val top = lerp(tl.first, tr.first, t) to lerp(tl.second, tr.second, t)
            val bottom = lerp(bl.first, br.first, t) to lerp(bl.second, br.second, t)
            canvas.drawLine(top.first, top.second, bottom.first, bottom.second, linePaint)
        }
        for (row in 0..9) {
            val t = row / 9f
            val left = lerp(tl.first, bl.first, t) to lerp(tl.second, bl.second, t)
            val right = lerp(tr.first, br.first, t) to lerp(tr.second, br.second, t)
            canvas.drawLine(left.first, left.second, right.first, right.second, linePaint)
        }
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_DOWN) return true
        if (points.size >= 4) return true
        // 屏幕坐标 → 截图坐标
        val scale = bitmap.width.toFloat() / width
        points.add(event.x * scale to event.y * scale)
        onPointsChanged?.invoke(points.size)
        invalidate()
        return true
    }

    fun undo() {
        if (points.isNotEmpty()) {
            points.removeAt(points.size - 1)
            onPointsChanged?.invoke(points.size)
            invalidate()
        }
    }
}
