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
import com.cheese.assistant.recognize.TemplateStore
import com.cheese.assistant.review.saveToKnowledge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
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
    private val recorder: com.cheese.assistant.review.GameRecorder by lazy {
        com.cheese.assistant.review.GameRecorder().also { r ->
            r.attachPersistence(java.io.File(filesDir, "state"))
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
                startAutoLoop()
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

    private suspend fun ensureController(): SearchController? {
        controller?.let { return it }
        withContext(Dispatchers.Main) { showOverlay("引擎启动中...") }
        val c = SearchController()
        val ok = c.start(applicationContext)
        controller = c
        return if (ok) c else null
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
        val ctl = ensureController() ?: return listOf(
            Candidate("引擎初始化失败", "", ThreatLevel.NONE))
        // 知识库快速路径：用户行棋局面命中且深度达标秒回
        val pos = Position.fromFen(fen)
        if (pos != null && recorder.isUserToMove()) {
            val hash = Zobrist.of(pos.board, pos.redToMove)
            val hit = runCatching { AppDb.get(this@AssistantService).positionDao().get(hash) }
                .onFailure { AppLog.e("AutoLoop", "kb query failed", it) }
                .getOrNull()
            if (hit != null && hit.depth >= KB_MIN_DEPTH) {
                AppLog.d("AutoLoop", "kb hit depth=${hit.depth}")
                val score = hit.mateIn?.let { m -> "杀 $m" }
                    ?: String.format("%+.1f", hit.bestScoreCp / 100.0)
                val threat = threatLevelOf(ThreatMarker.of(fen, hit.bestMove))
                return listOf(Candidate(
                    "库 ${score}  ${MoveFormatter.toChinese(hit.fen, hit.bestMove)}",
                    hit.bestMove, threat))
            }
        }
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
            // 排障时临时传入 File(filesDir, "dump") 可把识别失败帧落盘供 PC 端分析
            val watcher = BoardWatcher(
                grid,
                TemplateStore.load(this@AssistantService, TARGET_APP),
                kingFallback = {
                    // 会话开局即遇王误读(lastGood 尚未建立)时,用棋谱回放
                    // 局面做王格锚点(棋谱持久化正好覆盖进程死亡场景);
                    // 新局无棋谱时用初始局面(开局王格恒定 home)
                    replayPosition() ?: com.cheese.assistant.board.Position.START
                },
            )
            if (ensureController() == null) {
                withContext(Dispatchers.Main) { showOverlay("引擎初始化失败") }
                return@launch
            }

            var last: Position? = null
            var redToMove = true
            var rejects = 0
            var consecutiveFails = 0
            // 单帧 retag 缓存: JJ 选中/光圈标记的单帧误配若立即修正锚点,
            // 会把真实棋子替换成假子(实测选中卒→"士"),随后的真实走子帧
            // mover 对不上,宽松步/双步分解全部失效。连续两帧同 retag 才采纳
            var pendingRetag: Position? = null
            // 重置后的确认期:reset 时锚点可能含识别残缺帧,连续两次识别
            // 一致才出推荐(实测光圈假子直接进入分析 FEN 触发 set_position 失败)
            var pendingConfirm: Position? = null
            var pendingConfirmRed = true
            // 连续 vanish-only 帧计数:到逃生门阈值即重锚定(见 vanish 分支)
            var vanishRun = 0
            withContext(Dispatchers.Main) {
                showOverlay("自动识别：等待棋盘画面...")
                overlayView?.setUserSide(recorder.userIsRed)
            }
            // 整个循环体兜底:任何一帧处理抛异常都会静默杀死协程,
            // 表现正是"对局进行到一半悬浮窗永远不动"。
            try {
            while (isActive) {
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
                    pendingConfirm = last
                    pendingConfirmRed = recorder.userIsRed
                    withContext(Dispatchers.Main) {
                        showOverlay("已撤回一步,重新同步中...")
                    }
                    continue
                }
                var pos = observed.pos
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
                        delay(600)
                        continue
                    }
                    if (repaired.board.contentEquals(last!!.board)) {
                        AppLog.d("AutoLoop", "impossible pawn repaired to anchor")
                        delay(600)
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
                val prev = last
                // 确认期:重置后等连续两次一致。一致则出推荐;不一致当作
                // 新锚点重新确认(此前 500ms 的 delay 期间画面在稳定)
                val awaiting = pendingConfirm
                if (awaiting != null) {
                    if (awaiting.board.contentEquals(pos.board)) {
                        pendingConfirm = null
                        last = pos
                        postAnalysis(pos, pendingConfirmRed)
                    } else {
                        // 画面还在变:锚点跟随最新帧,继续确认
                        pendingConfirm = pos
                        last = pos
                        recorder.reset()
                        recorder.start(pos, observed.userIsRed)
                        redToMove = if (pos.board.contentEquals(Position.START.board))
                            true else recorder.userIsRed
                        pendingConfirmRed = redToMove
                    }
                    continue
                }
                when {
                    prev == null -> {
                        last = pos
                        recorder.start(pos, observed.userIsRed)
                        AppLog.d("AutoLoop", "recorder start userIsRed=${observed.userIsRed}")
                        // 轮走方:初始局面红先;中途开启(局面已变)时 AI 多半已走,
                        // 假定轮到用户——走一步后按交替自动校正
                        redToMove = if (pos.board.contentEquals(Position.START.board))
                            true else recorder.userIsRed
                        syncSideButton()
                        postAnalysis(pos, redToMove)
                    }
                    pos.board.contentEquals(prev.board) -> {
                        vanishRun = 0
                        delay(500)
                    }
                    else -> {
                        when {
                            // 光圈遮蔽帧(被罩子判空):忽略,锚点保持完整版。
                            // 逃生门:连发 10 帧(≈12s)说明不是抖动而是局面真变了
                            // (实测 AI 回合起点闪烁/h9 白圈脉动把锚点永久卡在
                            // 旧局面,推荐从此不动)——以当前帧重锚定并分析
                            MoveCheck.isVanishOnly(prev, pos) -> {
                                AppLog.d("AutoLoop", "vanish-only: ${diffText(prev, pos)}")
                                vanishRun++
                                if (vanishRun >= 10) {
                                    AppLog.w("AutoLoop",
                                        "vanish-only ×$vanishRun, re-anchor to current frame")
                                    vanishRun = 0
                                    last = pos
                                    postAnalysis(pos, redToMove)
                                } else delay(500)
                            }
                            // 遮蔽恢复帧(子重新可见):新局面才是完整版,修正锚点并重析;
                            // 无真实走子,不记谱、不翻转轮走方
                            MoveCheck.isAppearOnly(prev, pos) -> {
                                AppLog.d("AutoLoop", "occlusion recovered")
                                last = pos
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
                                delay(500)
                            }
                            MoveCheck.isSingleRetag(prev, pos) -> {
                                val pend = pendingRetag
                                pendingRetag = null
                                if (pend != null && pend.board.contentEquals(pos.board)) {
                                    AppLog.d("AutoLoop", "retag confirmed: ${diffText(prev, pos)}")
                                    last = pos
                                    postAnalysis(pos, redToMove)
                                } else {
                                    AppLog.d("AutoLoop", "retag pending: ${diffText(prev, pos)}")
                                    pendingRetag = pos
                                    delay(500)
                                }
                            }
                            MoveCheck.isPlausibleStep(prev, pos) -> {
                                AppLog.d("AutoLoop", "step: ${diffText(prev, pos)}")
                                // 归因以实际移动的棋子颜色为准，不盲信轮走方：
                                // 幽灵步错耗回合槽后，真实走子会被记到对方名下，
                                // 整条 FEN 毒化。发现不一致时按实际颜色记账并重同步
                                val actualRed = MoveCheck.stepMoverIsRed(prev, pos) ?: redToMove
                                if (actualRed != redToMove) {
                                    AppLog.w("AutoLoop",
                                        "turn desync: expected ${if (redToMove) "red" else "black"} " +
                                            "to move, but mover is ${if (actualRed) "red" else "black"}; resyncing")
                                    redToMove = actualRed
                                    syncSideButton()
                                }
                                vanishRun = 0
                                recorder.record(prev, pos, moverIsRed = actualRed)
                                last = pos
                                redToMove = !actualRed
                                postAnalysis(pos, redToMove, justMoved = true)
                            }
                            // 出发点被行棋光圈罩住误配成假子(卒走后出发点亮圈配成士),
                            // 与落点出现同子凑成 2 处差异,严格步判不了——按走子记账
                            MoveCheck.isStepGhostOrigin(prev, pos) -> {
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
                                redToMove = !actualRed
                                postAnalysis(pos, redToMove, justMoved = true)
                            }
                            else -> {
                                // AI 秒回:我方一步+AI一步叠加在同一识别窗口,
                                // 分解为先后两步记谱;分解失败才走 implausible
                                val mid = MoveCheck.tryTwoSteps(prev, pos, redToMove)
                                if (mid != null) {
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
                                    // 两步后仍轮 firstRed 方
                                    postAnalysis(pos, firstRed, justMoved = true)
                                } else {
                                    // 动画中间帧或识别抖动；连续 3 次视为新对局/大变化，重置局面
                                    rejects++
                                    AppLog.d("AutoLoop",
                                        "implausible step rejects=$rejects diff=${diffText(prev, pos)}")
                                    if (rejects >= 3) {
                                        rejects = 0
                                        recorder.reset()
                                        last = pos
                                        recorder.start(pos, observed.userIsRed)
                                        // 跳变原因不明,假定轮用户(最常见:用户走完等建议)
                                        redToMove = recorder.userIsRed
                                        syncSideButton()
                                        // 重置后锚点可能仍是识别残缺帧:先不分析,
                                        // 连续两次识别一致才出推荐,防幽灵子污染 FEN
                                        pendingConfirm = pos
                                        pendingConfirmRed = redToMove
                                        withContext(Dispatchers.Main) { showOverlay("重新定位局面...") }
                                    } else delay(400)
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
    /** 最近一次成功分析的候选(dedup 命中时若推荐区被清,用它回填) */
    private var lastSuggestions: List<Candidate> = emptyList()

    /** 把候选列表刷上悬浮窗(含上一手/执方态) */
    private suspend fun repaintSuggestions(cands: List<Candidate> = lastSuggestions) {
        withContext(Dispatchers.Main) {
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
        val candidates = analyzeCandidates(Position(pos.board, redToMove).toFen())
        val first = candidates.firstOrNull()?.line ?: ""
        if (first.contains("局面异常") || first.contains("搜索失败")) {
            // 失败结果不缓存:去重 key 会挡住重试,悬浮窗从此卡死旧文本。
            // 不记 analyzedKeys、不写 lastOverlayText,冷却 15s 后自动重试
            lastFailFen = fen
            failCooldownUntil = System.currentTimeMillis() + 15000L
            withContext(Dispatchers.Main) {
                showOverlay("${side}回合 $first")
                overlayView?.setUserTurn(false)
            }
            return
        }
        lastFailFen = null
        markAnalyzed(key)
        lastOverlayText = "${side}回合 (${pieces}子 $stamp)"
        // 候选落盘:ICCS+中文+威胁级,排查着法文本/徽章误标(如两条同名"炮2平4")
        AppLog.i("AutoLoop", "candidates $key " +
            candidates.joinToString(" | ") { "${it.iccs}→${it.line}[${it.threat}]" })
        lastSuggestions = candidates
        GameLiveState.setCandidates(taggedCandidateLines(candidates))
        repaintSuggestions(candidates)
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
            val from = iccsIndex(m.iccs.substring(0, 2)) ?: return null
            val to = iccsIndex(m.iccs.substring(2, 4)) ?: return null
            val piece = pos.board[from] ?: return null
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

    /** 封存/下一局按钮:对局中=封存并复盘;复盘完成后=下一局(清状态归位) */
    private var awaitingNextGame = false

    private fun onSealButton() {
        AppLog.d("Seal", "click awaitingNextGame=$awaitingNextGame reviewActive=${reviewJob?.isActive}")
        // 复盘进行中的点击先拦(曾经 await 标志在协程内延迟置位,连点被
        // 误判为"下一局"重置棋谱+抢复盘引擎)
        if (reviewJob?.isActive == true) {
            serviceScope.launch {
                withContext(Dispatchers.Main) { overlayView?.showReviewingMode() }
            }
            return
        }
        if (awaitingNextGame) {
            awaitingNextGame = false
            // 清棋谱与悬浮窗状态机归位。cancelAndJoin 等进行中的分析退出,
            // 否则残留搜索与重启动的 autoLoop 抢同一个引擎
            serviceScope.launch(Dispatchers.IO) {
                autoJob?.cancelAndJoin()
                recorder.reset()
                AppLog.d("Seal", "next-game: state reset, restarting loop")
                analyzedKeys.clear()
                lastOverlayText = null
                autoJob = null
                if (ScreenCapture.isRunning) {
                    startAutoLoop()
                } else {
                    withContext(Dispatchers.Main) {
                        showOverlay("新对局已就绪\n请回主界面完成截屏授权后开始")
                    }
                }
                withContext(Dispatchers.Main) { overlayView?.resetSealButton() }
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
            AppLog.d("SealReview", "no moves recorded, skip")
            serviceScope.launch {
                withContext(Dispatchers.Main) {
                    // 反馈契约:成功→复盘+「下一局」;失败→必须显式可见。
                    // Toast 硬保险 + 降级条持久(瞬态 body 会被识别循环覆盖)
                    Toast.makeText(this@AssistantService,
                        "封存失败:本局没有识别到任何走子记录", Toast.LENGTH_LONG).show()
                    showOverlay("尚无对局记录")
                    overlayView?.setDegraded(
                        "封存失败:本局没有识别到任何走子记录" +
                            "(识别整局失败,或上一局已复盘过)")
                }
            }
            return
        }
        // 棋谱快照与状态标志同步落定:复盘启动瞬间按钮就切「复盘中…」,
        // 复盘期间所有点击被 onSealButton 的 review 检查拦下,不丢不误触
        val moves = recorder.movesSnapshot()
        val userIsRed = recorder.userIsRed
        awaitingNextGame = true
        serviceScope.launch {
            withContext(Dispatchers.Main) {
                showOverlay("复盘中:${moves.size}步...")
                overlayView?.showReviewingMode()
            }
        }
        reviewJob = serviceScope.launch(Dispatchers.IO) {
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
                AppLog.w("SealReview", "engine/db unavailable ctl=${ctl != null} gameId=$gameId")
                withContext(Dispatchers.Main) { showOverlay("复盘失败:引擎不可用") }
                return@launch
            }
            val reviewStart = System.currentTimeMillis()
            val reviewer = com.cheese.assistant.review.GameReviewer(ctl)
            val reviews = runCatching { reviewer.review(moves, userIsRed) }
                .onFailure { AppLog.e("SealReview", "review failed", it) }
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
                overlayView?.showNextGameMode()
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
        // 直调而非 startService:后台 startService 间接层不必要,直调保证点击必达
        view.setOnSealListener { onSealButton() }
        view.setOnRetriggerListener { retriggerRecognition() }        // ♟ → 对局实况页(跳棋谱/推荐/识别对照);悬浮窗權限允许后台启 Activity。
        // 跳转同时悬浮窗收起为圆点:实况页要看 JJ 棋盘,浮窗别挡路
        view.setOnLiveListener {
            view.setExpanded(false)
            resizeOverlayWindow(view, collapsed = true)
            startActivity(Intent(this, com.cheese.assistant.ui.LiveGameActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        view.setOnSideToggleListener { isRed ->
            recorder.setUserSide(isRed)
        }
        // 点圆点展开:窗口尺寸同步恢复
        view.onExpand = {
            view.setExpanded(true)
            resizeOverlayWindow(view, collapsed = false)
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
            x = 270
            y = 1560
        }
        windowManager.addView(view, lp)
        overlayView = view
    }

    /** 收起/展开切换窗口尺寸:收起=固定小圆点大小,展开=内容自适应 */
    private fun resizeOverlayWindow(view: OverlayView, collapsed: Boolean) {
        val lp = view.layoutParams as WindowManager.LayoutParams
        val size = dp(40)
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
    private fun retriggerRecognition() {
        autoJob?.cancel()
        autoJob = null
        serviceScope.launch(Dispatchers.Main) {
            showOverlay("重新识别中...")
        }
        if (ScreenCapture.isRunning) startAutoLoop()
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
