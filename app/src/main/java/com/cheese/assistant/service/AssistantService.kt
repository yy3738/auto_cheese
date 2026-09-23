package com.cheese.assistant.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.cheese.assistant.R
import com.cheese.assistant.board.MoveFormatter
import com.cheese.assistant.board.Position
import com.cheese.assistant.capture.ScreenCapture
import com.cheese.assistant.engine.SearchController
import com.cheese.assistant.recognize.BoardGrid
import com.cheese.assistant.recognize.BoardWatcher
import com.cheese.assistant.recognize.MoveCheck
import com.cheese.assistant.recognize.TemplateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 前台服务：持有引擎与悬浮窗信息条。
 * 自动模式：低频抓帧等画面稳定 → 识别 → 引擎分析 → 信息条显示候选走法。
 */
class AssistantService : Service() {

    companion object {
        const val CHANNEL_ID = "assistant"
        const val NOTIFICATION_ID = 1
        const val TARGET_APP = "default"

        var isRunning = false
            private set
    }

    private lateinit var windowManager: WindowManager
    private var overlayView: OverlayView? = null
    private var controller: SearchController? = null
    private var autoJob: Job? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForeground(NOTIFICATION_ID, buildNotification())
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            Action.STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            Action.ANALYZE -> {
                val fen = intent.getStringExtra(Extra.FEN) ?: return START_STICKY
                analyze(fen)
            }
            Action.START_AUTO -> startAutoLoop()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        autoJob?.cancel()
        autoJob = null
        removeOverlay()
        serviceScope.cancel()
        controller?.shutdown()
        controller = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun analyze(fen: String) {
        serviceScope.launch {
            val text = analyzeText(fen)
            withContext(Dispatchers.Main) { showOverlay(text) }
        }
    }

    private suspend fun ensureController(): SearchController? {
        controller?.let { return it }
        withContext(Dispatchers.Main) { showOverlay("引擎启动中...") }
        val c = SearchController()
        val ok = c.start(applicationContext)
        controller = c
        return if (ok) c else null
    }

    private suspend fun analyzeText(fen: String): String {
        val ctl = ensureController() ?: return "引擎初始化失败"
        return try {
            val result = ctl.search(fen)
            result.candidates.joinToString("\n") { c ->
                val score = c.mateIn?.let { m -> if (m > 0) "杀 $m" else "被杀 $m" }
                    ?: String.format("%+.1f", c.scoreCp!! / 100.0)
                "$score  ${MoveFormatter.toChinese(fen, c.pv.first())}"
            }.ifEmpty { "无结果" }
        } catch (t: Throwable) {
            "搜索失败: ${t.message}"
        }
    }

    /**
     * 自动识别循环：抓帧等画面稳定 → 识别 → 与上一局面比对（恰好一步或
     * 连续 3 次异常后重置）→ 引擎分析 → 信息条更新。空闲时只做低频哈希
     * 比对，几乎不耗电。
     */
    private fun startAutoLoop() {
        if (autoJob?.isActive == true) return
        if (!ScreenCapture.isRunning) {
            serviceScope.launch {
                withContext(Dispatchers.Main) { showOverlay("请先回主界面完成截屏授权") }
            }
            return
        }
        autoJob = serviceScope.launch(Dispatchers.IO) {
            val grid = BoardGrid.load(this@AssistantService, TARGET_APP)
            if (grid == null) {
                withContext(Dispatchers.Main) { showOverlay("未找到校准,请先在主界面校准棋盘") }
                return@launch
            }
            // 排障时临时传入 File(filesDir, "dump") 可把识别失败帧落盘供 PC 端分析
            val watcher = BoardWatcher(
                grid,
                TemplateStore.load(this@AssistantService, TARGET_APP),
            )
            if (ensureController() == null) {
                withContext(Dispatchers.Main) { showOverlay("引擎初始化失败") }
                return@launch
            }

            var last: Position? = null
            var redToMove = true
            var rejects = 0
            withContext(Dispatchers.Main) { showOverlay("自动识别：等待棋盘画面...") }
            while (isActive) {
                val pos = runCatching { watcher.waitForPosition() }
                    .onFailure { android.util.Log.e("AutoLoop", "waitForPosition threw", it) }
                    .getOrNull()
                if (pos == null) {
                    withContext(Dispatchers.Main) { showOverlay("等待稳定棋盘画面...") }
                    continue
                }
                val prev = last
                when {
                    prev == null -> {
                        last = pos
                        postAnalysis(pos, redToMove)
                    }
                    pos.board.contentEquals(prev.board) -> delay(500)
                    MoveCheck.isPlausibleStep(prev, pos) -> {
                        last = pos
                        redToMove = !redToMove
                        postAnalysis(pos, redToMove)
                    }
                    else -> {
                        // 动画中间帧或识别抖动；连续 3 次视为新对局/大变化，重置局面
                        rejects++
                        android.util.Log.d("AutoLoop", "implausible step rejects=$rejects")
                        if (rejects >= 3) {
                            rejects = 0
                            last = pos
                            postAnalysis(pos, redToMove)
                        } else delay(400)
                    }
                }
            }
        }
    }

    private suspend fun postAnalysis(pos: Position, redToMove: Boolean) {
        val side = if (redToMove) "红方" else "黑方"
        withContext(Dispatchers.Main) { showOverlay("${side}回合 分析中...") }
        val text = analyzeText(Position(pos.board, redToMove).toFen())
        withContext(Dispatchers.Main) { showOverlay("${side}回合\n$text") }
    }

    @SuppressLint("InflateParams", "ClickableViewAccessibility")
    private fun showOverlay(initialText: String) {
        if (overlayView != null) {
            overlayView?.setText(initialText)
            return
        }
        val view = OverlayView(this)
        view.setText(initialText)
        view.setOnCloseListener { stopSelf() }

        // 拖动：按住标题栏移动整个窗口
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        view.handleBar.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    val lp = view.layoutParams as WindowManager.LayoutParams
                    startX = lp.x; startY = lp.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val lp = view.layoutParams as WindowManager.LayoutParams
                    lp.x = startX + (event.rawX - downX).toInt()
                    lp.y = startY + (event.rawY - downY).toInt()
                    windowManager.updateViewLayout(view, lp)
                    true
                }
                else -> false
            }
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // 默认放棋盘下缘之外：JJ 棋盘识别区底约 y=1530（row9 1455 + 余量），
            // 信息条 3 行文字高约 350，顶部/中部任何位置都可能压进棋盘 row0
            // （曾致 (0,0)~(0,2) 持续 unknown）。底部中央避开整个识别区，
            // 用户仍可拖动调整。
            x = 270
            y = 1560
        }
        windowManager.addView(view, lp)
        overlayView = view
    }

    private fun removeOverlay() {
        overlayView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        overlayView = null
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "象棋助手", NotificationManager.IMPORTANCE_LOW))
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("象棋助手运行中")
            .build()
    }

    object Action {
        const val ANALYZE = "com.cheese.assistant.ANALYZE"
        const val START_AUTO = "com.cheese.assistant.START_AUTO"
        const val STOP = "com.cheese.assistant.STOP"
    }

    object Extra {
        const val FEN = "fen"
    }
}
