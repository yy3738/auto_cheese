package com.cheese.assistant.service

import com.cheese.assistant.util.AppLog
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.widget.Toast
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.cheese.assistant.R
import com.cheese.assistant.board.MoveFormatter
import com.cheese.assistant.board.Position
import com.cheese.assistant.board.ThreatMarker
import com.cheese.assistant.board.Zobrist
import com.cheese.assistant.capture.ScreenCapture
import com.cheese.assistant.db.AppDb
import com.cheese.assistant.engine.SearchController
import com.cheese.assistant.recognize.BoardGrid
import com.cheese.assistant.recognize.BoardWatcher
import com.cheese.assistant.recognize.MoveCheck
import com.cheese.assistant.recognize.MoveInput
import com.cheese.assistant.recognize.PreferenceAccess
import com.cheese.assistant.recognize.RecognizerEngineFactory
import com.cheese.assistant.recognize.SequenceSolver
import com.cheese.assistant.recognize.TemplateStore
import com.cheese.assistant.review.saveToKnowledge
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    @Volatile private var controller: SearchController? = null
    /** 引擎初始化互斥：onCreate 预热与首帧分析可能并发 */
    private val controllerMutex = Mutex()
    private var autoJob: Job? = null
    private var reviewJob: Job? = null

    /** 库着法秒出后的后台补齐（引擎就绪再补两条；新分析会取消上一轮） */
    private var bookFillJob: Job? = null
    private val recorder: com.cheese.assistant.review.GameRecorder by lazy {
        com.cheese.assistant.review.GameRecorder().also { r ->
            val stateDir = java.io.File(filesDir, "state")
            // 版本清盘(2026-10-20): 版本变更后旧棋谱/锚点可能已被上一版
            // 识别缺陷毒化,恢复record等于把旧毒带进新逻辑(_escape salvage
            // 反复被毒谱拉回)。新版本首次启动强制清空持久化与孤儿备份——
            // 用户确认"重建即弃谱,留日志定位"
            val prefs = getSharedPreferences("state", Context.MODE_PRIVATE)
            val pkgInfo = packageManager.getPackageInfo(packageName, 0)
            val verCode = pkgInfo.longVersionCode
            val lastUpdate = pkgInfo.lastUpdateTime
            val seenVer = prefs.getInt("cleared_version", -1)
            val seenInstall = prefs.getLong("cleared_last_update", -1)
            // 版本变化 OR 重装(安装时间变化) → 清盘。重装后旧棋谱/锚点
            // 属于上一个进程的识别状态,用户每次都要手点「结&下一局」
            // 才能复位(2026-10-21 反馈)——现在重装即自动清盘
            if (seenVer != verCode.toInt() || seenInstall != lastUpdate) {
                AppLog.e("AssistantService",
                    "安装变化(ver $seenVer→${verCode.toInt()}, ts $seenInstall→$lastUpdate): 清空对局记录与状态目录")
                stateDir.listFiles()?.forEach { it.delete() }
                if (stateDir.isDirectory) prefs.edit()
                    .putInt("cleared_version", verCode.toInt())
                    .putLong("cleared_last_update", lastUpdate).apply()
            }
            r.attachPersistence(stateDir)
            if (r.restoreFromDisk()) {
                // 进程曾被系统整杀,恢复未封存的棋谱,「结」仍可复盘
                AppLog.i("AssistantService",
                    "recorder restored ${r.moveCount()} moves from disk")
            }
        }
    }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        AppLog.start(this)
        AppLog.i("AssistantService", "service created")
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForeground(NOTIFICATION_ID, buildNotification())
        isRunning = true
        // 引擎/NNUE 预热：用户授权投屏、切回 JJ 的这段时间里先把 64MiB
        // 权重加载完，避免「点开局后首推等十几秒」（2026-10-22 实测 12s）
        serviceScope.launch(Dispatchers.IO) {
            runCatching { ensureController(announce = false) }
                .onFailure { AppLog.e("AssistantService", "engine prewarm failed", it) }
        }
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
                }.onFailure { AppLog.e("AssistantService", "restore overlay failed", it) }
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
                // 2026-10-23 用户要求：没点「开局」不识别、不提示识别不稳定。
                // 授权后只亮悬浮窗等开局；识别循环在「开局」/重试时才启动。
                // 进程中途恢复且棋谱已有走子 → 仍要自动续上。
                if (recorder.moveCount() > 0) {
                    startAutoLoop()
                } else {
                    serviceScope.launch(Dispatchers.Main) {
                        showOverlay("点「开局」开始记账\n不点不会识别棋盘")
                        overlayView?.setGameStarted(false)
                    }
                }
            }
            Action.SEAL -> onSealButton()
            Action.REANALYZE -> {
                // 实况页手动纠正局面后的重算:只更新实况页数据,不动悬浮窗
                val fen = intent.getStringExtra(Extra.FEN) ?: return START_STICKY
                serviceScope.launch {
                    val list = analyzeCandidates(fen)
                    GameLiveState.setCandidates(taggedCandidateLines(list))
                    AppLog.i("Live", "re-analyzed overridden fen, ${list.size} candidates")
                }
            }
            Action.UNDO_STEP -> {
                // 实况页「撤回一步」:识别错的棋谱步由用户一键撤销,锚点重同步
                serviceScope.launch(Dispatchers.IO) {
                    if (recorder.undoLast()) {
                        undoStepRequested = true
                        AppLog.i("Live", "undo step, ${recorder.moveCount()} moves left")
                        withContext(Dispatchers.Main) {
                            overlayView?.setDegraded("已撤回最后一步识别,重新同步中…")
                        }
                    }
                }
            }
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

    private suspend fun ensureController(announce: Boolean = true): SearchController? =
        controllerMutex.withLock {
        controller?.let { return@withLock it }
        // 后台预热/库补齐不要改悬浮窗正文：局前只有「开局」，识别未启动时
        // 任何"引擎启动中"都是噪音（2026-10-23 用户要求）
        if (announce) withContext(Dispatchers.Main) { showOverlay("引擎启动中...") }
        val c = SearchController()
        val ok = c.start(applicationContext)
        if (!ok) {
            runCatching { c.shutdown() }
            return@withLock null
        }
        controller = c
        c
    }

    /** 单条候选:着法文本/评分/威胁级别/原始move */
    data class Candidate(val line: String, val iccs: String, val threat: ThreatLevel)

    /** ThreatMarker 级别 → 悬浮窗威胁徽章级别(一一对应) */
    private fun threatLevelOf(level: ThreatMarker.Level): ThreatLevel = when (level) {
        ThreatMarker.Level.NONE -> ThreatLevel.NONE
        ThreatMarker.Level.THREAT_CAPTURE -> ThreatLevel.THREAT_CAPTURE
        ThreatMarker.Level.CAPTURE -> ThreatLevel.CAPTURE
        ThreatMarker.Level.THREAT_CHECK -> ThreatLevel.THREAT_CHECK
        ThreatMarker.Level.CHECK -> ThreatLevel.CHECK
    }

    private suspend fun analyzeCandidates(fen: String): List<Candidate> {
        // 知识库优先（2026-10-23）：命中就秒出，不再等引擎。实测点「开局」
        // 到首推 18s = NNUE 加载 14s + warm-up 3.1s + 首搜 3.7s，而库着法
        // 早已可用（14:46:59 已命中 START，却仍同步等了 3.7s 搜索）。
        // 库着法先上屏，引擎就绪后由 scheduleBookFill 后台补齐另外两条。
        val pos = Position.fromFen(fen)
        if (pos != null && recorder.isUserToMove()) {
            val hash = Zobrist.of(pos.board, pos.redToMove)
            val hit = runCatching { AppDb.get(this@AssistantService).positionDao().get(hash) }
                .onFailure { AppLog.e("AutoLoop", "kb query failed", it) }
                .getOrNull()
            if (hit != null && hit.depth >= KB_MIN_DEPTH) {
                val score = hit.mateIn?.let { m -> "杀 $m" }
                    ?: String.format("%+.1f", hit.bestScoreCp / 100.0)
                val threat = threatLevelOf(ThreatMarker.of(fen, hit.bestMove))
                val book = Candidate(
                    "库 ${score}  ${MoveFormatter.toChinese(hit.fen, hit.bestMove)}",
                    hit.bestMove, threat)
                AppLog.d("AutoLoop", "kb hit depth=${hit.depth} → 秒出, 引擎后台补齐")
                scheduleBookFill(fen, book)
                return listOf(book)
            }
        }
        val ctl = ensureController() ?: return listOf(
            Candidate("引擎初始化失败", "", ThreatLevel.NONE))
        return try {
            val result = ctl.search(fen)
            // 局面非法(幻影子/缺王)时引擎拒绝搜索:明确提示,不留旧推荐误导
            if (result.failed) listOf(Candidate("局面异常,重新定位中...", "", ThreatLevel.NONE))
            else result.candidates.map { c ->
                val move = c.pv.first()
                val score = c.mateIn?.let { m -> if (m > 0) "杀 $m" else "被杀 $m" }
                    ?: String.format("%+.1f", c.scoreCp!! / 100.0)
                val threat = threatLevelOf(ThreatMarker.of(fen, move))
                Candidate("$score  ${MoveFormatter.toChinese(fen, move)}", move, threat)
            }.ifEmpty { listOf(Candidate("无结果", "", ThreatLevel.NONE)) }
        } catch (t: Throwable) {
            listOf(Candidate("搜索失败: ${t.message}", "", ThreatLevel.NONE))
        }
    }

    /**
     * 库着法秒出后的后台补齐：等引擎就绪再补两条，局面仍未变才写回。
     * 不能阻塞首推——NNUE 在模拟器上要加载约 14s。
     */
    private fun scheduleBookFill(fen: String, book: Candidate) {
        bookFillJob?.cancel()
        bookFillJob = serviceScope.launch(Dispatchers.IO) {
            val ctl = ensureController(announce = false) ?: run {
                AppLog.w("AutoLoop", "book fill skipped: engine unavailable")
                return@launch
            }
            if (!samePosition(fen)) {
                AppLog.d("AutoLoop", "book fill skipped: position moved on")
                return@launch
            }
            val extras = runCatching {
                ctl.search(fen).candidates.map { c ->
                    val move = c.pv.first()
                    val s = c.mateIn?.let { m -> if (m > 0) "杀 $m" else "被杀 $m" }
                        ?: String.format("%+.1f", c.scoreCp!! / 100.0)
                    Candidate("$s  ${MoveFormatter.toChinese(fen, move)}", move,
                        threatLevelOf(ThreatMarker.of(fen, move)))
                }
            }.getOrDefault(emptyList()).filter { it.iccs != book.iccs }
            if (extras.isEmpty()) {
                AppLog.w("AutoLoop", "book fill: no extra candidates")
                return@launch
            }
            if (!samePosition(fen)) {
                AppLog.d("AutoLoop", "book fill dropped: position moved on")
                return@launch
            }
            val merged = listOf(book) + extras.take(2)
            lastSuggestions = merged
            GameLiveState.setCandidates(taggedCandidateLines(merged))
            repaintSuggestions(merged)
            AppLog.d("AutoLoop", "book fill done: +${extras.take(2).size} candidates")
        }
    }

    /** 当前棋谱局面是否仍等于 [fen]（后台补齐的写回门） */
    private fun samePosition(fen: String): Boolean {
        val target = Position.fromFen(fen) ?: return false
        val live = recorder.lastFen()?.let { Position.fromFen(it) } ?: return false
        return live.board.contentEquals(target.board) && live.redToMove == target.redToMove
    }

    private suspend fun analyzeText(fen: String): String =
        analyzeCandidates(fen).joinToString("\n") { it.line }

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
            // 识别失败帧自动落盘 files/dump(限5张,采样入库用)——CNN 硬负样本
            // 的来源(实测 r9c7 残影幻觉高置信判马→piece cap 死循环, 正缺此帧)
            val watcher = BoardWatcher(
                grid,
                TemplateStore.load(this@AssistantService, TARGET_APP).also {
                    // CharCNN 影子模式: 与 NCC 并行识别只记日志(docs/charcnn-upgrade.md)
                    PreferenceAccess.appContext = this@AssistantService
                    RecognizerEngineFactory.init(this@AssistantService)
                },
                dumpDir = File(filesDir, "dump"),
                kingFallback = {
                    // 王格锚点优先当前记录局面（纠错/每步都会更新），
                    // 回放只作后备；新局无记录才用 START
                    recorder.lastFen()?.let { com.cheese.assistant.board.Position.fromFen(it) }
                        ?: replayPosition()
                        ?: com.cheese.assistant.board.Position.START
                },
                boardFallback = {
                    // 期望/引导锚点优先 recorder 当前局面（含纠错），
                    // 棋谱回放只作后备；全新对局用初始局面。
                    // 回放可能因漏步而失真（2026-10-22 实测 32 vs 29 卡死）
                    recorder.lastFen()?.let { com.cheese.assistant.board.Position.fromFen(it) }
                        ?: replayPosition()
                        ?: if (recorder.moveCount() == 0)
                            com.cheese.assistant.board.Position.START else null
                },
                // 逃生门:兜底锚点连续失败 → 弃谱重建(孤儿备份落盘留痕)。
                // 毒谱会把每次自愈拉回毒锚点,永不收敛(2026-10-19 用户确认)
                onSalvage = {
                    val snap = recorder.movesSnapshot()
                    AppLog.e("AutoLoop", "escape salvage: moves=${snap.size}" +
                        (snap.lastOrNull()?.let { " lastFenBefore=${it.fenBefore.take(40)}" } ?: "") +
                        " → recorder reset")
                    recorder.reset()
                    serviceScope.launch(Dispatchers.Main) {
                        overlayView?.setGameStarted(false)
                    }
                },
                foregroundCheck = {
                    // 主轨: UsageStats 事件流对包名判定; 本模拟器事件流
                    // 基本不工作(dumpsys 近24h零事件) → 判定不出时必须
                    // 返回 null 交边条指纹兜底, 而非误判为非对局前台
                    val pkg = com.cheese.assistant.capture.ForegroundApp
                        .current(this@AssistantService)
                    if (pkg == null) null
                    else pkg == com.cheese.assistant.capture.ForegroundApp.TARGET
                },
                // 边条指纹采样跳过悬浮窗自身矩形: 悬浮窗文字每帧变化,
                // 旧版把它当"非对局页面", 每 20s 假暂停一轮识别
                overlayBounds = { overlayView?.boundsOnScreen() },
            )
            // 引擎已在 onCreate 预热；这里不阻塞识别循环。若尚未就绪，
            // 首次分析会在 analyzeCandidates 里等同一个 controllerMutex。
            serviceScope.launch(Dispatchers.IO) { ensureController(announce = false) }

            var last: Position? = null
            // last 对应的那一帧像素快照：残影幻步判定必须拿"上次接受的局面帧"，
            // 不能拿上一帧(上一帧可能已经是走子后的画面 → 永远判成静止)
            var lastSnap: IntArray? = null
            var redToMove = true
            var rejects = 0
            // 残影幻步连续拒绝计数：同一合法单步被连续判 10 帧静止就放行，
            // 防止快照异常时永久卡死（合法步优先于启发式闸门）
            var staticRejectRun = 0
            var staticRejectKey = ""
            var consecutiveFails = 0
            // 单帧 retag 缓存: JJ 选中/光圈标记的单帧误配若立即修正锚点,
            // 会把真实棋子替换成假子(实测选中卒→"士"),随后的真实走子帧
            // mover 对不上,宽松步/双步分解全部失效。连续两帧同 retag 才采纳
            var pendingRetag: Position? = null
            // 重置后的确认期:reset 时锚点可能含识别残缺帧,连续两次识别
            // 一致才出推荐(实测光圈假子直接进入分析 FEN 触发 set_position 失败)
            var pendingConfirm: Position? = null
            var pendingConfirmRed = true
            // 确认期计时: 超时强制接受+清谱, 防动画/异态永久卡(2026-10-21)
            var pendingSince = 0L
            // 连续 vanish-only 帧计数:到逃生门阈值即重锚定(见 vanish 分支)
            var vanishRun = 0
            // 连续"变化无法解释为合法走子"的轮数（每 3 帧算一轮）。
            // 识别若与棋谱持续对不上（例如颜色误配被固化），旧版会永远
            // 空转刷屏、永不恢复（2026-10-23 实测卡死十几秒）。到阈值后
            // 以识别盘为当前局面继续并留缺口，让用户可纠错。
            var implausibleRounds = 0
            withContext(Dispatchers.Main) {
                showOverlay("自动识别：等待棋盘画面...")
                overlayView?.setUserSide(recorder.userIsRed)
            }
            // 整个循环体兜底:任何一帧处理抛异常都会静默杀死协程,
            // 表现正是"对局进行到一半悬浮窗永远不动"。
            try {
            autoloop@ while (isActive) {
                // 浮窗纠错消费：先于抓帧处理，识别卡住时也能立即重同步
                val corr = correctionRequested
                if (corr != null) {
                    correctionRequested = null
                    last = corr
                    lastSnap = null
                    pendingConfirm = corr
                    pendingConfirmRed = corr.redToMove
                    pendingSince = System.currentTimeMillis()
                    redToMove = corr.redToMove
                    syncSideButton()
                    AppLog.i("AutoLoop", "correction applied, re-sync anchor")
                    continue@autoloop
                }
                val observed = runCatching { watcher.waitForPosition() }
                    .onFailure { AppLog.e("AutoLoop", "waitForPosition threw", it) }
                    .getOrNull()
                if (observed == null) {
                    // 连续 3 次失败才提示等待——单帧识别失败不该把候选从悬浮窗上刷掉
                    if (++consecutiveFails >= 3) {
                        overlayInWait = true
                        withContext(Dispatchers.Main) {
                            showOverlay("等待稳定棋盘画面...")
                            overlayView?.setDegraded(degradedHint(consecutiveFails))
                        }
                    }
                    continue
                }
                consecutiveFails = 0
                // 撤回一步消费:锚点回退到撤销后的局面,重新确认
                if (undoStepRequested) {
                    undoStepRequested = false
                    last = recorder.lastFen()?.let { Position.fromFen(it) }
                    lastSnap = null
                    pendingConfirm = last
                    pendingConfirmRed = recorder.userIsRed
                    withContext(Dispatchers.Main) {
                        showOverlay("已撤回一步,重新同步中...")
                    }
                    continue
                }
                var pos = observed.pos
                // 用户手点「开局」：以当前盘面为新局起点（清旧谱、重建锚点）。
                // 残局/摆子中途点也支持；不可能兵位先等下一帧修复后再开。
                if (newGameRequested && !MoveCheck.hasImpossiblePawn(pos)) {
                    newGameRequested = false
                    recorder.reset(explicitNewGame = true)
                    recorder.start(pos, observed.userIsRed)
                    last = pos
                    lastSnap = observed.snapCur
                    redToMove = if (pos.board.contentEquals(Position.START.board))
                        true else recorder.userIsRed
                    pendingConfirm = pos
                    pendingConfirmRed = redToMove
                    pendingSince = 0
                    analyzedKeys.clear()
                    lastOverlayText = null
                    syncSideButton()
                    AppLog.i("AutoLoop",
                        "explicit new game at fen=${pos.toFen().take(40)} userRed=${observed.userIsRed}")
                    withContext(Dispatchers.Main) {
                        overlayView?.setDegraded(null)
                        overlayView?.setGameStarted(true)
                        showOverlay("新局已开始，等待落子…")
                    }
                    continue@autoloop
                }
                // 选中棋子被 JJ"抬起"悬空,轮廓变化使该子被误读成不可能
                // 位置的兵/卒。先用锚点修复:修复结果与锚点相同 → 棋子
                // 未动,当作无变化;修复失败 → 整帧丢弃;修复后仍是新局面
                // → 继续走子流程
                if (MoveCheck.hasImpossiblePawn(pos)) {
                    val repaired = last?.let { MoveCheck.repairImpossiblePawns(it, pos) }
                    if (repaired == null) {
                        AppLog.d("AutoLoop", "impossible pawn, frame ignored")
                        if (++consecutiveFails >= 3) {
                            overlayInWait = true
                            withContext(Dispatchers.Main) {
                                showOverlay("等待稳定棋盘画面...")
                                overlayView?.setDegraded(degradedHint(consecutiveFails))
                            }
                        }
                        delay(300)
                        continue
                    }
                    if (repaired.board.contentEquals(last!!.board)) {
                        AppLog.d("AutoLoop", "impossible pawn repaired to anchor")
                        delay(300)
                        continue
                    }
                    AppLog.d("AutoLoop", "impossible pawn repaired, reprocess")
                    pos = repaired
                }
                consecutiveFails = 0
                if (overlayInWait) {
                    // 识别恢复:先把候选文本请回来,否则去重逻辑会让候选永远停在"等待"
                    overlayInWait = false
                    withContext(Dispatchers.Main) { overlayView?.setDegraded(null) }
                    lastOverlayText?.let { t ->
                        withContext(Dispatchers.Main) { showOverlay(t) }
                    }
                }
                // 新对局检测(2026-10-22): 用户会在 JJ 点开局；完整 START
                // 局面出现即代表新局。旧棋谱非空或锚点不是 START 时清谱
                // 重开，避免把上一局的记录/锚点带进新局。
                if (pos.board.contentEquals(Position.START.board)) {
                    val stale = !recorder.isEmpty ||
                        (last?.let { !it.board.contentEquals(Position.START.board) } ?: false)
                    if (stale) {
                        AppLog.i("AutoLoop",
                            "检测到完整开局局面: 清谱并停识别, 等用户点「开局」")
                        recorder.reset(explicitNewGame = true)
                        recorder.start(pos, observed.userIsRed)
                        last = pos
                        lastSnap = observed.snapCur
                        redToMove = true
                        pendingConfirm = pos
                        pendingConfirmRed = true
                        pendingSince = 0
                        syncSideButton()
                        withContext(Dispatchers.Main) {
                            showOverlay("检测到新对局\n点「开局」开始记账")
                            overlayView?.setDegraded(null)
                            // 局前不识别：停掉本轮循环，等用户手点「开局」
                            overlayView?.setGameStarted(false)
                        }
                        // 2026-10-23 用户要求：没点「开局」不识别。
                        // 旧版这里 continue 继续识别，会在用户点开局前后
                        // 自动记账并弹识别提示。
                        break@autoloop
                    }
                }
                val prev = last
                // 确认期:重置后等连续两次一致。一致则出推荐;不一致当作
                // 新锚点重新确认(此前 500ms 的 delay 期间画面在稳定)
                val awaiting = pendingConfirm
                if (awaiting != null) {
                    val waitMs = if (pendingSince == 0L) 0 else System.currentTimeMillis() - pendingSince
                    // 开局落定守门(2026-10-21): 与初始局面相近(差<=6格)的
                    // 确认帧必须满 32 子——入场动画间隙的残缺盘(实测 31 子,
                    // 黑马被读错格)若放行, 首推「兵九进一」停留数秒。残局
                    // (差异>6格)不受影响。30s 超时兜底仍在
                    var diffStart = 0
                    for (i in 0 until 90) if (pos.board[i] != Position.START.board[i]) diffStart++
                    val livePieces = pos.board.count { it != null }
                    val nearStartShort = diffStart <= 6 && livePieces < 32
                    if (awaiting.board.contentEquals(pos.board) &&
                        !observed.prunedThisFrame && !nearStartShort) {
                        pendingConfirm = null; pendingSince = 0
                        last = pos
                        lastSnap = observed.snapCur
                        postAnalysis(pos, pendingConfirmRed)
                    } else if (waitMs > 30000) {
                        // 2026-10-22 用户确认: 不再 30s 强制接受残缺盘。
                        // 实测 31 子(左上黑车漏读)被接受后直接推「兵九进一」。
                        // 保持等待 + 显式提示；用户可点重试/纠错。
                        AppLog.w("AutoLoop",
                            "pendingConfirm 30s: 拒绝强制接受不完整局面 " +
                                "(d=$diffStart pieces=$livePieces), 继续等待")
                        pendingSince = System.currentTimeMillis()
                        withContext(Dispatchers.Main) {
                            showOverlay("识别异常: 局面持续不完整/不稳定")
                            overlayView?.setDegraded(
                                "30秒未得到稳定局面；请点重试识别，或点「纠错」补一手")
                        }
                    } else {
                        if (pendingSince == 0L) pendingSince = System.currentTimeMillis()
                        if (nearStartShort) {
                            AppLog.w("AutoLoop", "载入期残缺盘(d=$diffStart pieces=$livePieces), 继续确认")
                        }
                        // 画面还在变:锚点跟随最新帧,继续确认。
                        // 不 reset 棋谱——撤回一步后的重同步会经过这里,
                        // 清谱会把保留的历史一起抹掉(2026-10-22)
                        pendingConfirm = pos
                        last = pos
                        lastSnap = observed.snapCur
                        redToMove = if (pos.board.contentEquals(Position.START.board))
                            true else recorder.userIsRed
                        pendingConfirmRed = redToMove
                    }
                    continue
                }
                when {
                    prev == null -> {
                        // 循环重启（重试/纠错/服务恢复）先与棋谱对齐：
                        // 旧版直接 recorder.start(pos) 会把重启前走掉的
                        // 1-2 步静默跳过（2026-10-22 实测：进黑车+平红车
                        // 被重试吞掉，之后回放断裂、期望局面失真）。
                        val recLast = recorder.lastFen()?.let { Position.fromFen(it) }
                        if (recLast != null && !recLast.board.contentEquals(pos.board)) {
                            val single = MoveCheck.legalStepPair(recLast, pos)
                            val singleMover = single?.let { MoveCheck.stepMoverIsRed(recLast, pos) }
                            var mid: Position? = null
                            var firstRed = recLast.redToMove
                            if (single == null) {
                                for (candidate in listOf(recLast.redToMove, !recLast.redToMove)) {
                                    val m = MoveCheck.tryTwoSteps(recLast, pos, candidate) ?: continue
                                    mid = m
                                    firstRed = MoveCheck.stepMoverIsRed(recLast, m) ?: candidate
                                    break
                                }
                            }
                            if (single != null && singleMover != null) {
                                recorder.record(recLast, pos, moverIsRed = singleMover)
                                AppLog.i("AutoLoop",
                                    "restart reconcile +1: ${diffText(recLast, pos)}")
                                last = pos
                                lastSnap = observed.snapCur
                                recorder.start(pos, observed.userIsRed)
                                redToMove = !singleMover
                                syncSideButton()
                                withContext(Dispatchers.Main) { overlayView?.setDegraded(null) }
                                postAnalysis(pos, redToMove, justMoved = true)
                                continue@autoloop
                            }
                            val m2 = mid
                            if (m2 != null) {
                                val secondRed = MoveCheck.stepMoverIsRed(m2, pos) ?: !firstRed
                                recorder.record(recLast, m2, moverIsRed = firstRed)
                                recorder.record(m2, pos, moverIsRed = secondRed)
                                AppLog.i("AutoLoop",
                                    "restart reconcile +2: ${diffText(recLast, m2)} | " +
                                        diffText(m2, pos))
                                last = pos
                                lastSnap = observed.snapCur
                                recorder.start(pos, observed.userIsRed)
                                redToMove = firstRed
                                syncSideButton()
                                withContext(Dispatchers.Main) { overlayView?.setDegraded(null) }
                                postAnalysis(pos, redToMove, justMoved = true)
                                continue@autoloop
                            }
                            // 3-4 步合法序列还原（S2）：重启期间可能连走多手
                            val chain = SequenceSolver.solve(
                                recLast, pos, minPlies = 3, maxPlies = 4)
                            if (chain != null) {
                                for (k in 0 until chain.size - 1) {
                                    recorder.record(chain[k], chain[k + 1],
                                        moverIsRed = chain[k].redToMove)
                                }
                                AppLog.i("AutoLoop",
                                    "restart reconcile +${chain.size - 1} (DFS): " +
                                        chain.zipWithNext().joinToString(" | ") { (a, b) ->
                                            diffText(a, b) })
                                last = pos
                                lastSnap = observed.snapCur
                                recorder.start(pos, observed.userIsRed)
                                redToMove = chain.last().redToMove
                                syncSideButton()
                                withContext(Dispatchers.Main) { overlayView?.setDegraded(null) }
                                postAnalysis(pos, redToMove, justMoved = true)
                                continue@autoloop
                            }
                            // 解释不了：以识别盘为当前局面，棋谱留缺口
                            AppLog.w("AutoLoop",
                                "restart reconcile: unexplained diff " +
                                    "${diffText(recLast, pos)}, rebase with gap")
                            recorder.setCurrentPosition(
                                Position(pos.board.copyOf(), recLast.redToMove))
                            recorder.start(pos, observed.userIsRed)
                            last = pos
                            lastSnap = observed.snapCur
                            redToMove = recLast.redToMove
                            syncSideButton()
                            withContext(Dispatchers.Main) {
                                overlayView?.setDegraded(
                                    "棋谱与识别不一致：已按当前盘面继续，可用「纠」补齐")
                            }
                            postAnalysis(pos, redToMove)
                            continue@autoloop
                        }
                        last = pos
                        lastSnap = observed.snapCur
                        recorder.start(pos, observed.userIsRed)
                        AppLog.d("AutoLoop", "recorder start userIsRed=${observed.userIsRed}")
                        // 轮走方:初始局面红先;中途开启(局面已变)时 AI 多半已走,
                        // 假定轮到用户——走一步后按交替自动校正
                        redToMove = if (pos.board.contentEquals(Position.START.board))
                            true else recorder.userIsRed
                        syncSideButton()
                        // 落定观察期(2026-10-21): JJ 对局开始的入场动画飞行子
                        // 被高置信读成中间位假子并写入棋谱(实测黑马 (0,1)→(0,7)
                        // 瞬移 7 格、黑车凭空消失)。复用确认期机制:连续两帧
                        // 一致才以稳定盘面出首推,动画期间不记谱
                        pendingConfirm = pos
                        pendingConfirmRed = redToMove
                        withContext(Dispatchers.Main) { showOverlay("对局载入中...") }
                    }
                    pos.board.contentEquals(prev.board) -> {
                        vanishRun = 0
                        // 从实况页/结算页切回:期间"等待稳定棋盘画面"等文本态
                        // 已把推荐区清空,而局面没变不会触发 postAnalysis 的
                        // dedup 回填——这里用最近一次候选回填(同一恢复路径)
                        if (lastSuggestions.isNotEmpty() &&
                            overlayView?.showingSuggestions != true) {
                            AppLog.d("AutoLoop", "restore suggestions after screen switch")
                            repaintSuggestions()
                        }
                        delay(250)
                    }
                    else -> {
                        when {
                            // 光圈遮蔽帧(被罩子判空):忽略,锚点保持完整版。
                            // 连发 10 帧后用像素裁决：消失格仍是木色棋子=误读,
                            // 保留锚点重推；已是冷色空盘=真被吃/走开,以当前帧
                            // 重锚并更新 recorder 当前局面（实况页改画识别直读）。
                            MoveCheck.isVanishOnly(prev, pos) -> {
                                AppLog.d("AutoLoop", "vanish-only: ${diffText(prev, pos)}")
                                vanishRun++
                                if (vanishRun >= 10) {
                                    val cells = diffCells(prev, pos)
                                    val warmCells = cells.filter { i ->
                                        val raw = if (observed.userIsRed) i else flipIndex(i)
                                        val w = observed.warmth?.getOrNull(raw) ?: 0f
                                        w >= BoardWatcher.WARMTH_PIECE_MIN
                                    }
                                    vanishRun = 0
                                    if (warmCells.isNotEmpty()) {
                                        AppLog.w("AutoLoop",
                                            "vanish-only ×10 但格子仍有棋子(误读): $warmCells" +
                                                "，保留锚点强制重推")
                                        RecognizerEngineFactory.invalidate(cells)
                                        withContext(Dispatchers.Main) {
                                            overlayView?.setDegraded(
                                                "棋子仍在画面里却被判空，已强制重推")
                                        }
                                    } else {
                                        AppLog.w("AutoLoop",
                                            "vanish-only ×10 且格子已空(真消失): " +
                                                "${diffText(prev, pos)}，以当前帧重锚")
                                        last = pos
                                        lastSnap = observed.snapCur
                                        // 只更新当前局面，保留历史棋谱供复盘/纠错；
                                        // 不把滞后的期望局面继续当真理
                                        recorder.setCurrentPosition(
                                            Position(pos.board.copyOf(), redToMove))
                                        RecognizerEngineFactory.invalidate(cells)
                                        postAnalysis(pos, redToMove)
                                        withContext(Dispatchers.Main) {
                                            overlayView?.setDegraded(null)
                                        }
                                    }
                                } else delay(250)
                            }
                            // 遮蔽恢复帧(子重新可见):新局面才是完整版,修正锚点并重析;
                            // 无真实走子,不记谱、不翻转轮走方
                            MoveCheck.isAppearOnly(prev, pos) -> {
                                AppLog.d("AutoLoop", "occlusion recovered")
                                last = pos
                                lastSnap = observed.snapCur
                                postAnalysis(pos, redToMove)
                            }
                            // 锚点帧误配(如光圈下的炮被配成仕)恢复:原地换子,
                            // 真实走子不可能单点变更。但标记类误配也是单点变更,
                            // 需连续两帧稳定呈现才修正锚点(单帧先暂存观察)
                            // 价值跳变帧(点击棋子"抬起"使车被配成卒等)直接忽略:
                            // 锚点保持旧版,等棋子放下/走开后识别自动恢复
                            MoveCheck.isValueJumpRetag(prev, pos) -> {
                                AppLog.d("AutoLoop",
                                    "value-jump retag ignored: ${diffText(prev, pos)}")
                                delay(250)
                            }
                            MoveCheck.isSingleRetag(prev, pos) -> {
                                // 物料不变量：象棋里棋子只减不增（只能被吃），
                                // 单格改判若让某一方凭空多子 = 误配，绝不采纳
                                // （实测红车被配成黑车 → 黑方多一个车，棋谱
                                // 永久对不上、每帧 implausible 卡死 2026-10-23）
                                val ref = recorder.lastFen()?.let { Position.fromFen(it) }
                                if (ref != null && MoveCheck.exceedsMaterialCount(pos, ref)) {
                                    AppLog.w("AutoLoop",
                                        "retag rejected (物料超量): ${diffText(prev, pos)}")
                                    RecognizerEngineFactory.invalidate(diffCells(prev, pos))
                                    delay(250)
                                    continue@autoloop
                                }
                                val pend = pendingRetag
                                pendingRetag = null
                                if (pend != null && pend.board.contentEquals(pos.board)) {
                                    AppLog.d("AutoLoop", "retag confirmed: ${diffText(prev, pos)}")
                                    last = pos
                                    lastSnap = observed.snapCur
                                    postAnalysis(pos, redToMove)
                                } else {
                                    AppLog.d("AutoLoop", "retag pending: ${diffText(prev, pos)}")
                                    pendingRetag = pos
                                    delay(250)
                                }
                            }
                            MoveCheck.isPlausibleStep(prev, pos) -> {
                                // 入谱前双重把关(2026-10-21): ①棋规合法
                                // ②origin 相对"上次接受的局面帧"非静止
                                //   (静止=残影幻步)。快照是 raw 方向，按执方翻格号。
                                val legalPair = MoveCheck.legalStepPair(prev, pos)
                                if (legalPair == null) {
                                    AppLog.w("AutoLoop",
                                        "step rejected by rule check: ${diffText(prev, pos)}")
                                    delay(250)
                                    continue@autoloop
                                }
                                val originRaw = if (observed.userIsRed) legalPair.first
                                                else flipIndex(legalPair.first)
                                val originDiff = if (lastSnap != null && observed.snapCur != null)
                                    watcher.cellDiff(lastSnap!!, observed.snapCur!!, originRaw)
                                else -1
                                // 2026-10-23：像素"静止"只证明快照旧，不能直接判幻步。
                                // 木色占比才是本帧证据：出发点仍暖 = 棋子没离开 = 残影
                                // 幻步；出发点已冷 = 棋子真走了（快照过期），首帧即放行
                                // （旧版在这里空等 10 帧，实测红马真走被卡 9 秒）。
                                val originWarm = observed.warmth?.getOrNull(originRaw)
                                    ?.let { it >= BoardWatcher.WARMTH_PIECE_MIN }
                                if (originDiff in 0..6 && MoveCheck.rejectStaticOrigin(originWarm)) {
                                    val key = diffText(prev, pos)
                                    if (key == staticRejectKey) staticRejectRun++
                                    else { staticRejectKey = key; staticRejectRun = 1 }
                                    if (staticRejectRun < 10) {
                                        AppLog.w("AutoLoop",
                                            "step rejected: origin static (残影幻步) diff=$originDiff " +
                                                "×$staticRejectRun: $key")
                                        delay(250)
                                        continue@autoloop
                                    }
                                    // 同一合法单步连续 10 帧被判静止：快照证据与棋规
                                    // 冲突，以棋规为准放行，并让这张快照格重推
                                    AppLog.w("AutoLoop",
                                        "origin static ×$staticRejectRun, 放行合法单步: $key")
                                    RecognizerEngineFactory.invalidate(
                                        listOf(legalPair.first, legalPair.second))
                                } else {
                                    if (originDiff in 0..6) {
                                        AppLog.i("AutoLoop",
                                            "origin cold (快照过期), 首帧放行合法单步: ${diffText(prev, pos)}")
                                    }
                                    staticRejectRun = 0
                                    staticRejectKey = ""
                                }
                                implausibleRounds = 0
                                AppLog.d("AutoLoop", "step: ${diffText(prev, pos)}")
                                // 归因以实际移动的棋子颜色为准，不盲信轮走方：
                                // 幽灵步错耗回合槽后，真实走子会被记到对方名下，
                                // 整条 FEN 毒化。发现不一致时按实际颜色记账并重同步
                                val actualRed = MoveCheck.stepMoverIsRed(prev, pos) ?: redToMove
                                if (actualRed != redToMove) {
                                    last = pos
                                    lastSnap = observed.snapCur
                                    // 同色连走=必有一手是动画中间帧假步（象棋严格
                                    // 轮流）。撤销上一条记录后，"其走子前局面→当前"
                                    // 恰为合法单步则合并重记（炮2进1+炮2进3→炮2进4）,
                                    // 否则按幽灵步回同步
                                    val lastRec = recorder.lastRecord()
                                    val pre2 = lastRec?.let { Position.fromFen(it.fenBefore) }
                                    if (pre2 != null && MoveCheck.isPlausibleStep(pre2, pos)) {
                                        recorder.undoLast()
                                        recorder.record(pre2, pos, moverIsRed = actualRed)
                                        vanishRun = 0
                                        AppLog.i(
                                            "AutoLoop",
                                            "anim merge: ${diffText(pre2, pos)}" +
                                                " (假步 ${diffText(prev, pos)} 已回滚)"
                                        )
                                        redToMove = !actualRed
                                        postAnalysis(pos, redToMove, justMoved = true)
                                        continue
                                    }
                                    AppLog.w(
                                        "AutoLoop",
                                        "turn desync: expected ${if (redToMove) "red" else "black"} " +
                                            "to move, but mover is ${if (actualRed) "red" else "black"}; resyncing"
                                    )
                                    redToMove = actualRed
                                    syncSideButton()
                                }
                                vanishRun = 0
                                recorder.record(prev, pos, moverIsRed = actualRed)
                                last = pos
                                lastSnap = observed.snapCur
                                redToMove = !actualRed
                                postAnalysis(pos, redToMove, justMoved = true)
                            }
                            // 出发点被行棋光圈罩住误配成假子(卒走后出发点亮圈配成士),
                            // 与落点出现同子凑成 2 处差异,严格步判不了——按走子记账
                            MoveCheck.isStepGhostOrigin(prev, pos) -> {
                                implausibleRounds = 0
                                AppLog.d("AutoLoop", "step(ghost): ${diffText(prev, pos)}")
                                val actualRed = MoveCheck.ghostMoverIsRed(prev, pos) ?: redToMove
                                if (actualRed != redToMove) {
                                    AppLog.w("AutoLoop",
                                        "turn desync(ghost): resyncing to ${if (actualRed) "red" else "black"}")
                                    redToMove = actualRed
                                    syncSideButton()
                                }
                                vanishRun = 0
                                recorder.record(prev, pos, moverIsRed = actualRed)
                                last = pos
                                lastSnap = observed.snapCur
                                redToMove = !actualRed
                                postAnalysis(pos, redToMove, justMoved = true)
                            }
                            else -> {
                                // AI 秒回:我方一步+AI一步叠加在同一识别窗口,
                                // 分解为先后两步记谱;分解失败才走 implausible
                                val mid = MoveCheck.tryTwoSteps(prev, pos, redToMove)
                                // 两步解释不了时再试 3-4 步合法序列（S2）：
                                // 快速连走/识别暂停后一次看到多手，比留缺口强
                                val chain = if (mid == null && rejects >= 1)
                                    SequenceSolver.solve(prev, pos, minPlies = 3, maxPlies = 4)
                                else null
                                if (mid != null) {
                                    implausibleRounds = 0
                                    AppLog.d("AutoLoop",
                                        "two steps: ${diffText(prev, mid)} | ${diffText(mid, pos)}")
                                    // 第一步归因同样按棋子实际颜色校正(双向枚举后
                                    // firstIsRed 只是猜测)，第二步随之交替
                                    val firstRed = MoveCheck.stepMoverIsRed(prev, mid)
                                        ?: MoveCheck.ghostMoverIsRed(prev, mid)
                                        ?: redToMove
                                    val secondRed = MoveCheck.stepMoverIsRed(mid, pos) ?: !firstRed
                                    if (firstRed != redToMove) {
                                        AppLog.w("AutoLoop",
                                            "turn desync(two-steps): resyncing to ${if (firstRed) "red" else "black"} first")
                                        redToMove = firstRed
                                        syncSideButton()
                                    }
                                    vanishRun = 0
                                    recorder.record(prev, mid, moverIsRed = firstRed)
                                    recorder.record(mid, pos, moverIsRed = secondRed)
                                    last = pos
                                    lastSnap = observed.snapCur
                                    // 两步后仍轮 firstRed 方
                                    postAnalysis(pos, firstRed, justMoved = true)
                                } else if (chain != null) {
                                    var lastMoverRed = redToMove
                                    for (k in 0 until chain.size - 1) {
                                        lastMoverRed = chain[k].redToMove
                                        recorder.record(chain[k], chain[k + 1],
                                            moverIsRed = lastMoverRed)
                                    }
                                    implausibleRounds = 0
                                    AppLog.d("AutoLoop",
                                        "sequence ${chain.size - 1} steps: " +
                                            chain.zipWithNext().joinToString(" | ") { (a, b) ->
                                                diffText(a, b) })
                                    if (chain.first().redToMove != redToMove) {
                                        AppLog.w("AutoLoop", "turn desync(sequence): resyncing")
                                        syncSideButton()
                                    }
                                    vanishRun = 0
                                    last = pos
                                    lastSnap = observed.snapCur
                                    postAnalysis(pos, !lastMoverRed, justMoved = true)
                                } else {
                                    // 动画中间帧或识别抖动。2026-10-22 用户确认:
                                    // 不再"3 次就清谱采纳"——那会把误读局面永久
                                    // 固化(实测清谱后 FEN 少一只黑车)。保留棋谱
                                    // 与锚点，强制重推变化格；用户可点纠错。
                                    rejects++
                                    AppLog.d("AutoLoop",
                                        "implausible step rejects=$rejects diff=${diffText(prev, pos)}")
                                    if (rejects >= 3) {
                                        rejects = 0
                                        implausibleRounds++
                                        val cells = diffCells(prev, pos)
                                        AppLog.w("AutoLoop",
                                            "implausible ×3 (轮 $implausibleRounds): " +
                                                "保留棋谱/锚点, 强制重推 $cells")
                                        RecognizerEngineFactory.invalidate(cells)
                                        if (implausibleRounds >= 4) {
                                            // 连续 4 轮（约 5s）解释不了：不再无限空转，
                                            // 以识别盘为当前局面继续并留缺口（用户可纠错）
                                            implausibleRounds = 0
                                            AppLog.w("AutoLoop",
                                                "implausible 持续 4 轮: 以识别盘为当前局面继续(留缺口)")
                                            recorder.setCurrentPosition(
                                                Position(pos.board.copyOf(), redToMove))
                                            last = pos
                                            lastSnap = observed.snapCur
                                            withContext(Dispatchers.Main) {
                                                overlayView?.setDegraded(
                                                    "识别持续对不上：已按当前盘面继续，请点「纠」核对")
                                            }
                                            postAnalysis(pos, redToMove)
                                            delay(300)
                                            continue@autoloop
                                        }
                                        withContext(Dispatchers.Main) {
                                            showOverlay("识别与走子对不上，正在重推…")
                                            overlayView?.setDegraded(
                                                "识别异常: 变化无法解释为合法走子；可点「纠错」或重试")
                                        }
                                        delay(300)
                                    } else delay(250)
                                }
                            }
                        }
                    }
                }
            }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (t: Throwable) {
                AppLog.e("AutoLoop", "loop crashed", t)
                withContext(Dispatchers.Main) {
                    overlayView?.setDegraded("识别循环异常：${t.message}，点「识别」重试")
                }
            } finally {
                // 停掉帧环监听（重试/纠错会重建 watcher，不关会叠监听器）
                watcher.close()
            }
        }
    }

    /**
     * 识别连续失败时的降级提示,按失败次数分级:
     * 3 次起=等待(动画/短暂遮挡);12 次起≈18 秒仍失败,大概率不是
     * 暂时抖动而是棋盘已不在画面里——实测对局结束停在 JJ「局终」结算页,
     * 深色面板盖住棋盘,每帧只剩上排几个子(redK=0),原文案「正在等待」
     * 让用户无限干等。给出明确的行动指引,点「再来一局」回棋盘自动恢复。
     */
    private fun degradedHint(fails: Int): String =
        if (fails >= 12) "画面疑似对局已结束或被弹窗遮挡——若已结束点「结」复盘,点「再来一局」回棋盘自动恢复"
        else "识别不稳定，正在等待画面…"

    /** 两局面差异格索引(重推可疑格用) */
    private fun diffCells(a: Position, b: Position): List<Int> =
        (0 until 90).filter { a.board[it] != b.board[it] }

    /** normal 方向格号 → raw 方向格号（上下翻；拍屏快照用 raw） */
    private fun flipIndex(i: Int): Int = (9 - i / 9) * 9 + i % 9

    /** 识别矩阵 row0 = 屏幕最上排;日志用 (屏幕行,列) 直读。
     *  棋子名带颜色前缀:红黑混淆(红车↔黑车,中文名相同)曾伪装成"车→车"
     *  无法归因——颜色一直要在日志里显式存在 */
    private fun diffText(a: Position, b: Position): String {
        val name = { p: com.cheese.assistant.board.Piece? ->
            p?.let { (if (it.isRed) "红" else "黑") + it.cnName } ?: "空"
        }
        val parts = ArrayList<String>()
        for (i in 0 until 90) {
            if (a.board[i] == b.board[i]) continue
            parts.add("(${i / 9},${i % 9})${name(a.board[i])}→${name(b.board[i])}")
            if (parts.size >= 6) break
        }
        return parts.joinToString(" ")
    }

    /** 识别到执方变化后同步悬浮窗按钮显示 */
    private suspend fun syncSideButton() {
        val isRed = recorder.userIsRed
        GameLiveState.setUserSide(isRed)
        withContext(Dispatchers.Main) { overlayView?.setUserSide(isRed) }
    }

    // 最近已分析的局面(fen|轮到用户)集合——识别抖动在两个棋盘版本间往返时,
    // 单槽去重会被交替冲穿(上次只记一个 FEN),保留最近 8 个彻底消除重复计算
    private val analyzedKeys = ArrayDeque<String>()

    // 悬浮窗最近一次有效文本:识别抖动刷掉候选("等待稳定棋盘画面...")后,
    // 识别恢复时用它原样恢复——analyzedKeys 去重会挡住 postAnalysis 重算
    private var lastOverlayText: String? = null
    private var overlayInWait = false

    private fun markAnalyzed(key: String) {
        if (analyzedKeys.contains(key)) return
        if (analyzedKeys.size >= 8) analyzedKeys.removeFirst()
        analyzedKeys.addLast(key)
    }

    // 分析失败的冷却:同一非法局面(误配色产生的超编 FEN,实测对局中
    // 红车被配成黑车 →"黑3车红0车")失败不进 analyzedKeys 是为保重试,
    // 但悬浮窗上局面没变时每个识别周期都会重烧 3.x 秒引擎。冷却期内
    // 直接跳过引擎,悬浮窗保留最后一次失败提示,局面变了自然重试
    private var lastFailFen: String? = null
    private var failCooldownUntil = 0L
    private var lastPostState = ""
    /** 实况页「撤回一步」待消费标志(循环下一帧处理锚点重同步) */
    @Volatile private var undoStepRequested = false

    /** 浮窗纠错后的新局面：autoLoop 下一轮以此为锚点重同步 */
    @Volatile private var correctionRequested: Position? = null

    /** 用户手点「开局」：下一帧以当前盘面为新局起点 */
    @Volatile private var newGameRequested = false
    /** 最近一次成功分析的候选(dedup 命中时若推荐区被清,用它回填) */
    private var lastSuggestions: List<Candidate> = emptyList()

    /** 把候选列表刷上悬浮窗(含上一手/执方态) */
    private suspend fun repaintSuggestions(cands: List<Candidate> = lastSuggestions) {
        withContext(Dispatchers.Main) {
            overlayView?.setDegraded(null)
            overlayView?.apply {
                setText(lastOverlayText ?: "")
                setSuggestions(cands.mapIndexed { i, c ->
                    Triple(i + 1, c.line, c.threat)
                })
                setLastMove(lastMoveLabel())
                setUserTurn(true)
            }
        }
    }

    private suspend fun postAnalysis(
        pos: Position,
        redToMove: Boolean,
        justMoved: Boolean = false,
    ) {
        // 已经走出一步（哪怕没点开局）：切到局中布局，隐藏「开局」
        if (justMoved) withContext(Dispatchers.Main) { overlayView?.setGameStarted(true) }
        val fen = Position(pos.board, redToMove).toFen()
        val userTurn = redToMove == recorder.userIsRed
        val key = "$fen|$userTurn"
        // 局面指纹:棋子数+HH:mm,便于核对推荐基于的盘面与实际是否一致
        val pieces = pos.board.count { it != null }
        val stamp = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
            .format(java.util.Date())
        // 状态变化才打日志(局面静止时每 1.2s 会被 dedup 拦,全打会刷屏);
        // 曾因这里全静默,悬浮窗空推荐+卡死无法归因(2026-10-13)
        val state = "turn=$userTurn dedup=${key in analyzedKeys} " +
            "cool=${fen == lastFailFen && System.currentTimeMillis() < failCooldownUntil} " +
            "pieces=$pieces fen=${fen.take(32)}"
        if (state != lastPostState) {
            AppLog.d("AutoLoop", "postAnalysis $state")
            lastPostState = state
        }
        // 对局实况:每识别一步/每次分析后同步到主界面(核对识别错误用)。
        // match=识别局面(board) vs 棋谱回放推演局面——不一致即识别中途出错
        val replay = replayPosition()
        GameLiveState.setUserSide(recorder.userIsRed)
        GameLiveState.update(
            fen, gameMoveLines(),
            replay?.toFen() ?: "",
            replay?.board?.contentEquals(pos.board),
        )
        if (key in analyzedKeys) {
            // dedup 命中=局面没变,悬浮窗应仍挂着上次推荐;若被"对手回合/
            // 分析中"等文本态覆盖(不数子节点数——文本节点也在容器里),
            // 把最近一次候选回填,局面没变,正确推荐就是它们
            if (lastSuggestions.isNotEmpty() && overlayView?.showingSuggestions != true) {
                AppLog.w("AutoLoop", "dedup hit with hidden suggestions, re-posting")
                repaintSuggestions()
            }
            return
        }
        // 失败冷却:同一 FEN 15 秒内不重复喂引擎(防每帧 3.8s 死循环)
        if (fen == lastFailFen && System.currentTimeMillis() < failCooldownUntil) {
            AppLog.d("AutoLoop", "fail cooldown, skip search ${fen.take(24)}")
            return
        }
        if (!userTurn) {
            // 实战辅助只给用户自己的走法;对手回合不占引擎。
            // 真实走子后旧推荐即过期(你会看到"建议我车5平8"的刽子手),
            // 清成等待态;回合抖动(局面没变)则保留防闪烁
            lastOverlayText = "对手回合 · 等你落子 (${pieces}子 $stamp)"
            withContext(Dispatchers.Main) {
                overlayView?.apply {
                    if (justMoved || !showingSuggestions) setText(lastOverlayText!!)
                    setLastMove(lastMoveLabel())
                    setUserTurn(false)
                }
            }
            return
        }
        val side = if (redToMove) "红方" else "黑方"
        withContext(Dispatchers.Main) { showOverlay("${side}回合 分析中...") }
        // 2026-10-23（S2）：分析改为独立 job，识别循环不再 await 引擎。
        // 旧版每步同步等 movetime（现在真跑 600ms+），这期间不抓帧——
        // 对方秒回的两步就会挤进同一个识别窗口。现在立刻返回去处理下一个
        // 落定窗口，结果回来时局面已变就丢弃（conflated 最新局面）。
        markAnalyzed(key)
        dispatchAnalysis(fen, key, side, pieces, stamp)
    }

    /** 最新待分析局面：结果回来时局面已变则丢弃，只服务最后一条 */
    @Volatile private var latestAnalysisFen: String = ""
    private var analysisJob: Job? = null

    private fun dispatchAnalysis(
        fen: String, key: String, side: String, pieces: Int, stamp: String,
    ) {
        latestAnalysisFen = fen
        analysisJob?.cancel()
        analysisJob = serviceScope.launch(Dispatchers.IO) {
            val candidates = analyzeCandidates(fen)
            if (latestAnalysisFen != fen) {
                AppLog.d("AutoLoop", "analysis dropped (stale) ${fen.take(24)}")
                return@launch
            }
            val first = candidates.firstOrNull()?.line ?: ""
            if (first.contains("局面异常") || first.contains("搜索失败")) {
                // 失败结果不缓存:去重 key 会挡住重试,悬浮窗从此卡死旧文本。
                // 不写 lastOverlayText,冷却 15s 后自动重试
                lastFailFen = fen
                failCooldownUntil = System.currentTimeMillis() + 15000L
                withContext(Dispatchers.Main) {
                    showOverlay("${side}回合 $first")
                    overlayView?.setUserTurn(false)
                }
                return@launch
            }
            lastFailFen = null
            lastOverlayText = "${side}回合 (${pieces}子 $stamp)"
            // 候选落盘:ICCS+中文+威胁级,排查着法文本/徽章误标(如两条同名"炮2平4")
            AppLog.i("AutoLoop", "candidates $key " +
                candidates.joinToString(" | ") { "${it.iccs}→${it.line}[${it.threat}]" })
            lastSuggestions = candidates
            GameLiveState.setCandidates(taggedCandidateLines(candidates))
            repaintSuggestions(candidates)
        }
    }

    /** 候选 → 带威胁标签的展示行(悬浮窗与实况页共用,改文案两处同步) */
    private fun taggedCandidateLines(cands: List<Candidate>): List<String> =
        cands.map { c ->
            val tag = when (c.threat) {
                ThreatLevel.CHECK -> "将军"
                ThreatLevel.THREAT_CHECK -> "叫将"
                ThreatLevel.CAPTURE -> "吃"
                ThreatLevel.THREAT_CAPTURE -> "打吃"
                ThreatLevel.NONE -> ""
            }
            // line 形如 "评分  中文着法",标签插在评分后
            val parts = c.line.split("  ")
            if (tag.isEmpty()) c.line
            else if (parts.size >= 2) "${parts[0]}  $tag  ${parts.drop(1).joinToString("  ")}"
            else "$tag  ${c.line}"
        }

    /**
     * 棋谱回放:第一手的走子前局面作起点,逐着应用 iccs 推演当前局面。
     * 识别若中途错乱,回放结果与识别局面不一致——实况页据此提醒"以棋谱为准"
     */
    private fun replayPosition(): Position? {
        val snap = recorder.movesSnapshot()
        if (snap.isEmpty()) return null
        var pos = Position.fromFen(snap.first().fenBefore) ?: return null
        for (m in snap) {
            val from = iccsIndex(m.iccs.substring(0, 2)) ?: return pos
            val to = iccsIndex(m.iccs.substring(2, 4)) ?: return pos
            val piece = pos.board[from] ?: return pos
            val board = pos.board.copyOf()
            board[from] = null
            board[to] = piece
            pos = Position(board, !pos.redToMove)
        }
        return pos
    }

    /** ICCS "e3" → 棋盘索引(与 GameRecorder.square 互逆) */
    private fun iccsIndex(sq: String): Int? {
        if (sq.length < 2 || !sq[1].isDigit()) return null
        val file = sq[0] - 'a'
        val rank = sq[1].digitToInt()
        if (file !in 0..8 || rank !in 0..9) return null
        return (9 - rank) * 9 + file
    }

    /** 棋谱格式化行:"3. 黑 车8进1"(识别错误核对时按着法逐条对照) */
    private fun gameMoveLines(): List<String> =
        recorder.movesSnapshot().mapIndexed { i, m ->
            val moverRed = m.fenBefore.split(" ").getOrNull(1) != "b"
            val cn = runCatching { MoveFormatter.toChinese(m.fenBefore, m.iccs) }
                .getOrDefault(m.iccs)
            "${i + 1}. ${if (moverRed) "红" else "黑"} $cn"
        }

    /** 上次走子标签:棋谱最后一步,按走子方显示"对手："或"你：" */
    private fun lastMoveLabel(): String {
        val moves = recorder.movesSnapshot()
        if (moves.isEmpty()) return ""
        val last = moves.last()
        // 走子方 = fenBefore 的行棋方
        val moverIsRed = last.fenBefore.split(" ").getOrNull(1) != "b"
        val moverIsUser = moverIsRed == recorder.userIsRed
        val who = if (moverIsUser) "你" else "对手"
        val cn = runCatching {
            MoveFormatter.toChinese(last.fenBefore, last.iccs)
        }.getOrDefault(last.iccs)
        return "$who：$cn"
    }

    /** 封存按钮：对局中=封存并复盘；复盘完成后按钮隐藏，回到只有「开局」的局前态 */
    private fun onSealButton() {
        AppLog.d("Seal", "click reviewActive=${reviewJob?.isActive}")
        // 复盘进行中的点击先拦(曾经 await 标志在协程内延迟置位,连点被
        // 误判为"下一局"重置棋谱+抢复盘引擎)
        if (reviewJob?.isActive == true) {
            serviceScope.launch {
                withContext(Dispatchers.Main) { overlayView?.showReviewingMode() }
            }
            return
        }
        sealAndReview()
    }

    /**
     * 封存当前对局并触发复盘（CONTEXT.md"对局边界=手动封存"）。
     * 停掉自动循环（引擎独占给复盘），棋谱入库，逐局面 3000ms 深搜，
     * 结论写 games.review_json 与知识库。复盘完成后 recorder 重置。
     */
    private fun sealAndReview() {
        if (reviewJob?.isActive == true) {
            serviceScope.launch {
                withContext(Dispatchers.Main) { overlayView?.showReviewingMode() }
            }
            return
        }
        if (recorder.isEmpty) {
            // 对局刚结束的结算页垃圾帧会触发"3 次异常重置",棋谱在点「结」
            // 前几秒被清——孤儿备份(不过期)立即救回,照常复盘
            if (recorder.restoreOrphan()) {
                AppLog.i("SealReview", "orphan moves restored after reset, sealing them")
            }
        }
        if (recorder.isEmpty) {
            AppLog.w("SealReview", "no moves recorded, skip → back to idle")
            serviceScope.launch {
                withContext(Dispatchers.Main) {
                    // 反馈契约:成功→复盘+回到局前;失败→必须显式可见。
                    // Toast 硬保险 + 降级条持久(瞬态 body 会被识别循环覆盖)
                    Toast.makeText(this@AssistantService,
                        "封存失败:本局没有识别到任何走子记录", Toast.LENGTH_LONG).show()
                    showOverlay("尚无对局记录")
                    overlayView?.setDegraded(
                        "封存失败:本局没有识别到任何走子记录" +
                            "(识别整局失败,或上一局已复盘过)")
                    // 四态闭环：没有可复盘内容，回到只有「开局」的局前态
                    overlayView?.setGameStarted(false)
                }
            }
            return
        }
        // 棋谱快照与状态标志同步落定:复盘启动瞬间按钮就切「复盘中…」,
        // 复盘期间所有点击被 onSealButton 的 review 检查拦下,不丢不误触
        val moves = recorder.movesSnapshot()
        val userIsRed = recorder.userIsRed
        serviceScope.launch {
            withContext(Dispatchers.Main) {
                showOverlay("复盘中:${moves.size}步...")
                overlayView?.showReviewingMode()
            }
        }
        reviewJob = serviceScope.launch(Dispatchers.IO) {
            // 库补齐/在跑的分析都停掉：复盘要独占引擎
            bookFillJob?.cancel()
            bookFillJob = null
            analysisJob?.cancel()
            analysisJob = null
            latestAnalysisFen = ""
            // 确保识别循环彻底退出(进行中的搜索要跑完native轮询)再复盘
            autoJob?.cancelAndJoin()
            autoJob = null
            val game = com.cheese.assistant.db.GameEntity(
                startedAt = recorder.startedAt,
                endedAt = System.currentTimeMillis(),
                userIsRed = userIsRed,
                moveListJson = recorder.toJson(),
            )
            val db = AppDb.get(this@AssistantService)
            val gameId = runCatching { db.gameDao().insert(game) }
                .onFailure { AppLog.e("SealReview", "insert game failed", it) }
                .getOrNull()
            val ctl = ensureController()
            if (ctl == null || gameId == null) {
                AppLog.w("SealReview",
                    "engine/db unavailable ctl=${ctl != null} gameId=$gameId → back to idle")
                withContext(Dispatchers.Main) {
                    showOverlay("复盘失败:引擎不可用")
                    // 四态闭环：失败的复盘不能停在「复盘中」，回到局前态
                    overlayView?.setGameStarted(false)
                }
                return@launch
            }
            val reviewStart = System.currentTimeMillis()
            val reviewer = com.cheese.assistant.review.GameReviewer(ctl)
            val reviews = runCatching {
                reviewer.review(moves, userIsRed) { done, total ->
                    AppLog.d("SealReview", "progress $done/$total")
                    withContext(Dispatchers.Main) {
                        // 模拟器每个 3000ms 搜索实际 6s+，必须让用户看到
                        // 进度在走，否则「复盘中」像卡死（2026-10-22 反馈）。
                        // 2026-10-23：按钮也带 X/Y，短局 1/1 不再像没反应
                        val secs = (System.currentTimeMillis() - reviewStart) / 1000
                        showOverlay("复盘 $done/$total 个局面 · 已用 ${secs}s")
                        overlayView?.setReviewProgress(done, total)
                    }
                }
            }.onFailure { AppLog.e("SealReview", "review failed", it) }
                .getOrDefault(emptyList())
            runCatching { saveToKnowledge(db, reviews) }
            val mistakes = reviews.count { it.blunderLevel == 1 }
            val majors = reviews.count { it.blunderLevel == 2 }
            db.gameDao().finishReview(
                gameId, com.cheese.assistant.db.GameEntity.STATUS_DONE,
                com.cheese.assistant.review.reviewsToJson(reviews), mistakes, majors)
            recorder.reset()
            GameLiveState.clear()
            AppLog.i("SealReview",
                "review done moves=${moves.size} mistakes=$mistakes majors=$majors " +
                    "cost=${System.currentTimeMillis() - reviewStart}ms")
            withContext(Dispatchers.Main) {
                showOverlay("复盘完成\n失误$mistakes 大漏着$majors\n(主界面「对局复盘」查看)")
                // 四态闭环：复盘完成回到局前态，只剩「开局」
                overlayView?.setGameStarted(false)
                AppLog.i("SealReview", "back to idle (open button only)")
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
        GameLiveState.setUserSide(recorder.userIsRed)
        // 进程重启后：已有走子=局中；只有起始局面/空=局前，显示「开局」
        view.setGameStarted(recorder.moveCount() > 0)
        view.setOnCloseListener { stopSelf() }
        // 直调而非 startService:后台 startService 间接层不必要,直调保证点击必达
        view.setOnSealListener { onSealButton() }
        view.setOnRetriggerListener { retriggerRecognition() }        // ♟ → 对局实况页(跳棋谱/推荐/识别对照);悬浮窗權限允许后台启 Activity。
        // 「纠」→ 四下拉输入一手棋，写回棋谱/锚点并重出推荐
        view.setOnCorrectListener { showCorrectionDialog() }
        // 「开局」→ 以当前盘面为新局起点，清旧谱重记
        view.setOnNewGameListener { requestNewGame() }
        // 跳转同时悬浮窗收起为圆点:实况页要看 JJ 棋盘,浮窗别挡路
        view.setOnLiveListener {
            view.setExpanded(false)
            resizeOverlayWindow(view, collapsed = true)
            startActivity(Intent(this, com.cheese.assistant.ui.LiveGameActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        view.setOnSideToggleListener { isRed ->
            recorder.setUserSide(isRed)
            GameLiveState.setUserSide(isRed)
        }
        // 点圆点展开:窗口尺寸同步恢复
        view.onExpand = {
            view.setExpanded(true)
            resizeOverlayWindow(view, collapsed = false)
            view.post { clampOverlayX(view) }
        }

        // 拖动：按住标题栏移动整个窗口；位移很小的抬起视为点击 → 收起为圆点
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
                MotionEvent.ACTION_UP -> {
                    if (kotlin.math.abs(event.rawX - downX) < 12 &&
                        kotlin.math.abs(event.rawY - downY) < 12) {
                        view.setExpanded(false)
                        resizeOverlayWindow(view, collapsed = true)
                    }
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
            // 用户要求默认贴最左；y 仍在棋盘下缘之外，不遮识别区
            x = 0
            y = 1560
        }
        windowManager.addView(view, lp)
        overlayView = view
        // 标题栏新增「开局/纠」后窗口更宽：布局完成后夹回屏幕内
        view.post { clampOverlayX(view) }
    }

    /** 悬浮窗展开后若右缘超出屏幕，向左夹回（默认 x=270 + 头部变宽） */
    private fun clampOverlayX(view: android.view.View) {
        val screenW = resources.displayMetrics.widthPixels
        val lp = view.layoutParams as? WindowManager.LayoutParams ?: return
        if (view.width <= 0) return
        if (lp.x + view.width > screenW) {
            lp.x = (screenW - view.width).coerceAtLeast(0)
            try { windowManager.updateViewLayout(view, lp) } catch (_: Exception) {}
        }
    }

    /** 收起/展开切换窗口尺寸:收起=固定小圆点大小,展开=内容自适应 */
    private fun resizeOverlayWindow(view: OverlayView, collapsed: Boolean) {
        val lp = view.layoutParams as WindowManager.LayoutParams
        val size = dp(36)
        lp.width = if (collapsed) size else WindowManager.LayoutParams.WRAP_CONTENT
        lp.height = if (collapsed) size else WindowManager.LayoutParams.WRAP_CONTENT
        try {
            windowManager.updateViewLayout(view, lp)
        } catch (_: Exception) {}
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(),
            resources.displayMetrics).toInt()

    /**
     * 手动重触发识别：识别降级/卡死时用户点的出口。
     * 强制重启 autoLoop（清掉 stale 的 pending 状态），重新走"等稳定→识别→校验"。
     */
    /**
     * 用户点「开局」：下一帧以当前盘面为新局起点（清旧谱、重建锚点）。
     * 不是 JJ 的开局按钮——是用户在我们的悬浮窗上手动告诉识别循环
     * "从这一手开始每一步都要记账"。
     */
    private fun requestNewGame() {
        if (!ScreenCapture.isRunning) {
            Toast.makeText(this, "请先回主界面完成截屏授权", Toast.LENGTH_SHORT).show()
            return
        }
        newGameRequested = true
        // 旧局的分析结果一律作废，防止写回新局的悬浮窗
        analysisJob?.cancel()
        analysisJob = null
        latestAnalysisFen = ""
        AppLog.i("AutoLoop", "new game requested by user")
        Toast.makeText(this, "新局：从当前盘面开始记账", Toast.LENGTH_SHORT).show()
        // 点击即切局中布局：开局消失，纠/实出现（用户要求立即反馈）
        overlayView?.setGameStarted(true)
        if (autoJob?.isActive != true) startAutoLoop()
        serviceScope.launch(Dispatchers.Main) {
            showOverlay("新局：正在读取当前盘面…")
        }
    }

    private fun retriggerRecognition() {
        autoJob?.cancel()
        autoJob = null
        serviceScope.launch(Dispatchers.Main) {
            showOverlay("重新识别中...")
        }
        if (ScreenCapture.isRunning) startAutoLoop()
    }

    // ---------------- 浮窗纠错（四下拉输入一手棋） ----------------

    private data class Correction(
        val prev: Position,
        val cur: Position,
        val moverIsRed: Boolean,
        val replace: Boolean,
        val input: MoveInput,
    )

    private fun showCorrectionDialog() {
        if (recorder.currentPosition() == null) {
            Toast.makeText(this, "还没有可信局面，请先等识别成功", Toast.LENGTH_SHORT).show()
            return
        }
        val dlg = CorrectionDialog(this, recorder.userIsRed)
        dlg.modeResolver = { input ->
            val c = resolveCorrection(input)
            when {
                c == null -> CorrectionPreview("✗ 不合法", false)
                c.replace -> CorrectionPreview("改掉上一手", true)
                else -> CorrectionPreview("补上这一步", true)
            }
        }
        dlg.onConfirm = { input -> applyCorrection(input) }
        dlg.onUndo = {
            serviceScope.launch(Dispatchers.IO) {
                if (recorder.undoLast()) {
                    undoStepRequested = true
                    AppLog.i("Correct", "undo last move, ${recorder.moveCount()} left")
                    withContext(Dispatchers.Main) {
                        overlayView?.setDegraded("已撤回最后一步识别,重新同步中…")
                        retriggerRecognition()
                    }
                }
            }
        }
        dlg.show()
    }

    /**
     * 自动判断纠错语义：
     * - 能在"实况页显示给用户的识别盘"走 → 补上（纠正基准=用户看到的盘）
     * - 能在"recorder 当前局面"走 → 补上
     * - 否则能在"上一手之前局面"走 → 改掉上一手（先移除末条再记）
     * 两个都不成立 → null（弹窗提示非法）
     */
    private fun resolveCorrection(input: MoveInput): Correction? {
        val now = recorder.currentPosition()
        // 1) 优先实况页/最近识别盘：识别与棋谱不一致时，用户看的是识别盘，
        //    纠错必须落在它上面（2026-10-22 实测用滞后 recorder 基准，
        //    黑马吃兵那步被漏掉，纠错后的 FEN 仍然缺子）
        val live = GameLiveState.fen.takeIf { it.isNotEmpty() }
            ?.let { Position.fromFen(it) }
        if (live != null && now?.board?.contentEquals(live.board) != true) {
            input.resolve(live.board)?.let { (f, t) ->
                return buildCorrection(live, f, t, input, replace = false)
            }
        }
        // 2) recorder 当前局面
        if (now != null) {
            input.resolve(now.board)?.let { (f, t) ->
                return buildCorrection(now, f, t, input, replace = false)
            }
        }
        // 3) 上一手之前局面
        val prev = recorder.positionBeforeLast() ?: return null
        if (now != null && prev.board.contentEquals(now.board)) return null
        input.resolve(prev.board)?.let { (f, t) ->
            return buildCorrection(prev, f, t, input, replace = true)
        }
        return null
    }

    private fun buildCorrection(
        base: Position, from: Int, to: Int, input: MoveInput, replace: Boolean,
    ): Correction? {
        val mover = base.board[from] ?: return null
        if (mover != input.piece) return null
        val board = base.board.copyOf()
        board[from] = null
        board[to] = mover
        return Correction(base, Position(board, !mover.isRed), mover.isRed, replace, input)
    }

    private fun applyCorrection(input: MoveInput) {
        serviceScope.launch(Dispatchers.IO) {
            val c = resolveCorrection(input)
            if (c == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@AssistantService,
                        "该着法在当前/上一手局面都不合法", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            val iccs = recorder.recordManual(c.prev, c.cur, c.moverIsRed, c.replace)
            if (iccs == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@AssistantService,
                        "纠错失败：盘面差异不是一手棋", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            AppLog.i("Correct", "manual ${c.input} iccs=$iccs replace=${c.replace} " +
                "moves=${recorder.moveCount()} fen=${c.cur.toFen().take(48)}")
            // 下一轮 autoLoop 以此为锚点重同步；同时强制全量重推，
            // 让 CNN 用纠正后的期望重新看这些格
            correctionRequested = c.cur
            RecognizerEngineFactory.invalidateAll()
            GameLiveState.update(
                c.cur.toFen(), gameMoveLines(),
                replayPosition()?.toFen() ?: "",
                replayPosition()?.board?.contentEquals(c.cur.board),
            )
            postAnalysis(c.cur, !c.moverIsRed)
            withContext(Dispatchers.Main) {
                overlayView?.setDegraded(null)
                // 重启识别循环：BoardWatcher 内部的 lastGood 旧锚点一并重建，
                // 后续帧以纠正后的棋谱局面为期望（只改 AutoLoop.last 不够）
                retriggerRecognition()
            }
        }
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
        const val REANALYZE = "com.cheese.assistant.REANALYZE"
        const val UNDO_STEP = "com.cheese.assistant.UNDO_STEP"
    }

    object Extra {
        const val FEN = "fen"
    }
}
