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
import com.cheese.assistant.board.Zobrist
import com.cheese.assistant.capture.ScreenCapture
import com.cheese.assistant.db.AppDb
import com.cheese.assistant.engine.SearchController
import com.cheese.assistant.recognize.BoardGrid
import com.cheese.assistant.recognize.BoardWatcher
import com.cheese.assistant.recognize.MoveCheck
import com.cheese.assistant.recognize.TemplateStore
import com.cheese.assistant.review.saveToKnowledge
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

        private const val PREFS = "assistant_state"
        private const val PREF_AUTO_ON = "auto_on"
        /** 知识库命中门槛：库内深度低于此值照常搜索（3000ms 复盘通常 ≥14） */
        private const val KB_MIN_DEPTH = 12

        var isRunning = false
            private set
    }

    private lateinit var windowManager: WindowManager
    private var overlayView: OverlayView? = null
    private var controller: SearchController? = null
    private var autoJob: Job? = null
    private var reviewJob: Job? = null
    private val recorder = com.cheese.assistant.review.GameRecorder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForeground(NOTIFICATION_ID, buildNotification())
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            // START_STICKY 重启（进程曾被杀）：MediaProjection 授权已随进程
            // 失效，autoLoop 无法自动恢复；悬浮窗权限是全局设置仍有效，
            // 必须重建悬浮窗——否则进程崩溃后悬浮窗永久消失（文档 B.1）
            val wasAuto = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(PREF_AUTO_ON, false)
            val hint = if (wasAuto) "服务已恢复；截屏授权已失效，请回主界面重新点悬浮窗按钮开启"
            else "服务已恢复"
            serviceScope.launch {
                runCatching {
                    withContext(Dispatchers.Main) { showOverlay(hint) }
                }.onFailure { android.util.Log.e("AssistantService", "restore overlay failed", it) }
            }
            return START_STICKY
        }
        when (intent.action) {
            Action.STOP -> {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean(PREF_AUTO_ON, false).apply()
                stopSelf()
                return START_NOT_STICKY
            }
            Action.ANALYZE -> {
                val fen = intent.getStringExtra(Extra.FEN) ?: return START_STICKY
                analyze(fen)
            }
            Action.START_AUTO -> {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean(PREF_AUTO_ON, true).apply()
                startAutoLoop()
            }
            Action.SEAL -> sealAndReview()
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
     * 连续 3 次异常后重置）→ 记棋谱 → 查知识库（命中秒回）→ 引擎分析 →
     * 信息条更新。
     */
    private fun startAutoLoop() {
        if (reviewJob?.isActive == true) {
            serviceScope.launch {
                withContext(Dispatchers.Main) { showOverlay("复盘中,请稍候...") }
            }
            return
        }
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
            var consecutiveFails = 0
            withContext(Dispatchers.Main) {
                showOverlay("自动识别：等待棋盘画面...")
                overlayView?.setUserSide(recorder.userIsRed)
            }
            while (isActive) {
                val observed = runCatching { watcher.waitForPosition() }
                    .onFailure { android.util.Log.e("AutoLoop", "waitForPosition threw", it) }
                    .getOrNull()
                if (observed == null) {
                    // 连续 3 次失败才提示等待——单帧识别失败不该把候选从悬浮窗上刷掉
                    if (++consecutiveFails >= 3) {
                        withContext(Dispatchers.Main) { showOverlay("等待稳定棋盘画面...") }
                    }
                    continue
                }
                consecutiveFails = 0
                val pos = observed.pos
                val prev = last
                when {
                    prev == null -> {
                        last = pos
                        recorder.start(pos, observed.userIsRed)
                        android.util.Log.d("AutoLoop", "recorder start userIsRed=${observed.userIsRed}")
                        // 轮走方:初始局面红先;中途开启(局面已变)时 AI 多半已走,
                        // 假定轮到用户——走一步后按交替自动校正
                        redToMove = if (pos.board.contentEquals(Position.START.board))
                            true else recorder.userIsRed
                        syncSideButton()
                        postAnalysis(pos, redToMove)
                    }
                    pos.board.contentEquals(prev.board) -> delay(500)
                    else -> {
                        when {
                            // 光圈遮蔽帧(被罩子判空):忽略,锚点保持完整版
                            MoveCheck.isVanishOnly(prev, pos) -> delay(500)
                            // 遮蔽恢复帧(子重新可见):新局面才是完整版,修正锚点并重析;
                            // 无真实走子,不记谱、不翻转轮走方
                            MoveCheck.isAppearOnly(prev, pos) -> {
                                android.util.Log.d("AutoLoop", "occlusion recovered")
                                last = pos
                                postAnalysis(pos, redToMove)
                            }
                            MoveCheck.isPlausibleStep(prev, pos) -> {
                                recorder.record(prev, pos, moverIsRed = redToMove)
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
                                    recorder.reset()
                                    last = pos
                                    recorder.start(pos, observed.userIsRed)
                                    // 跳变原因不明,假定轮用户(最常见:用户走完等建议)
                                    redToMove = recorder.userIsRed
                                    syncSideButton()
                                    postAnalysis(pos, redToMove)
                                } else delay(400)
                            }
                        }
                    }
                }
            }
        }
    }

    /** 识别到执方变化后同步悬浮窗按钮显示 */
    private suspend fun syncSideButton() {
        val isRed = recorder.userIsRed
        withContext(Dispatchers.Main) { overlayView?.setUserSide(isRed) }
    }

    /**
     * 单局面分析：用户行棋局面优先查知识库（命中且深度达标秒回，ADR-0003），
     * 未命中照常引擎搜索。
     */
    private suspend fun analyzeWithKnowledge(fen: String): String {
        val ctl = ensureController() ?: return "引擎初始化失败"
        val pos = Position.fromFen(fen)
        if (pos != null && recorder.isUserToMove()) {
            val hash = Zobrist.of(pos.board, pos.redToMove)
            val hit = runCatching { AppDb.get(this@AssistantService).positionDao().get(hash) }
                .onFailure { android.util.Log.e("AutoLoop", "kb query failed", it) }
                .getOrNull()
            if (hit != null && hit.depth >= KB_MIN_DEPTH) {
                android.util.Log.d("AutoLoop", "kb hit depth=${hit.depth}")
                val score = hit.mateIn?.let { m -> "杀 $m" }
                    ?: String.format("%+.1f", hit.bestScoreCp / 100.0)
                return "库 ${score}  ${MoveFormatter.toChinese(hit.fen, hit.bestMove)}"
            }
        }
        return analyzeText(fen)
    }

    // 最近已分析的局面(fen|轮到用户)集合——识别抖动在两个棋盘版本间往返时,
    // 单槽去重会被交替冲穿(上次只记一个 FEN),保留最近 8 个彻底消除重复计算
    private val analyzedKeys = ArrayDeque<String>()

    private fun markAnalyzed(key: String) {
        if (analyzedKeys.contains(key)) return
        if (analyzedKeys.size >= 8) analyzedKeys.removeFirst()
        analyzedKeys.addLast(key)
    }

    private suspend fun postAnalysis(pos: Position, redToMove: Boolean) {
        val fen = Position(pos.board, redToMove).toFen()
        val userTurn = redToMove == recorder.userIsRed
        val key = "$fen|$userTurn"
        if (key in analyzedKeys) return
        markAnalyzed(key)
        if (!userTurn) {
            // 实战辅助只给用户自己的走法;对手回合不占引擎
            withContext(Dispatchers.Main) { showOverlay("对手回合 · 等你落子") }
            return
        }
        val side = if (redToMove) "红方" else "黑方"
        withContext(Dispatchers.Main) { showOverlay("${side}回合 分析中...") }
        val text = analyzeWithKnowledge(Position(pos.board, redToMove).toFen())
        withContext(Dispatchers.Main) { showOverlay("${side}回合\n$text") }
    }

    /**
     * 封存当前对局并触发复盘（CONTEXT.md"对局边界=手动封存"）。
     * 停掉自动循环（引擎独占给复盘），棋谱入库，逐局面 3000ms 深搜，
     * 结论写 games.review_json 与知识库。复盘完成后 recorder 重置。
     */
    private fun sealAndReview() {
        if (reviewJob?.isActive == true) return
        if (recorder.isEmpty) {
            serviceScope.launch {
                withContext(Dispatchers.Main) { showOverlay("尚无对局记录") }
            }
            return
        }
        autoJob?.cancel()
        autoJob = null
        val game = com.cheese.assistant.db.GameEntity(
            startedAt = recorder.startedAt,
            endedAt = System.currentTimeMillis(),
            userIsRed = recorder.userIsRed,
            moveListJson = recorder.toJson(),
        )
        val moves = recorder.movesSnapshot()
        val userIsRed = recorder.userIsRed
        reviewJob = serviceScope.launch(Dispatchers.IO) {
            val db = AppDb.get(this@AssistantService)
            val gameId = runCatching { db.gameDao().insert(game) }
                .onFailure { android.util.Log.e("SealReview", "insert game failed", it) }
                .getOrNull()
            val ctl = ensureController()
            if (ctl == null || gameId == null) {
                withContext(Dispatchers.Main) { showOverlay("复盘失败:引擎不可用") }
                return@launch
            }
            val reviewer = com.cheese.assistant.review.GameReviewer(ctl)
            withContext(Dispatchers.Main) { showOverlay("复盘中:${moves.size}步...") }
            val reviews = runCatching { reviewer.review(moves, userIsRed) }
                .onFailure { android.util.Log.e("SealReview", "review failed", it) }
                .getOrDefault(emptyList())
            runCatching { saveToKnowledge(db, reviews) }
            val mistakes = reviews.count { it.blunderLevel == 1 }
            val majors = reviews.count { it.blunderLevel == 2 }
            db.gameDao().finishReview(
                gameId, com.cheese.assistant.db.GameEntity.STATUS_DONE,
                com.cheese.assistant.review.reviewsToJson(reviews), mistakes, majors)
            recorder.reset()
            withContext(Dispatchers.Main) {
                showOverlay("复盘完成\n失误$mistakes 大漏着$majors\n(主界面「对局复盘」查看)")
            }
        }
    }

    @SuppressLint("InflateParams", "ClickableViewAccessibility")
    private fun showOverlay(initialText: String) {
        if (overlayView != null) {
            overlayView?.setText(initialText)
            return
        }
        val view = OverlayView(this)
        view.setText(initialText)
        view.setUserSide(recorder.userIsRed)
        view.setOnCloseListener { stopSelf() }
        view.setOnSealListener {
            startService(Intent(this, AssistantService::class.java)
                .setAction(Action.SEAL))
        }
        view.setOnSideToggleListener { isRed ->
            recorder.setUserSide(isRed)
        }

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
        const val SEAL = "com.cheese.assistant.SEAL"
    }

    object Extra {
        const val FEN = "fen"
    }
}
