package com.cheese.assistant.review

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.cheese.assistant.R
import com.cheese.assistant.db.AppDb
import com.cheese.assistant.db.GameEntity
import com.cheese.assistant.engine.EngineHolder
import com.cheese.assistant.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 实战识别循环是否在跑：复盘在每个局面边界让路（2026-10-27 异步复盘） */
object ReviewGate {
    @Volatile var liveActive = false
}

/**
 * 后台复盘（进程级，2026-10-27）。
 *
 * 点「结」选择复盘后：棋谱入库 + 清当前局 → 立即回局前可开下一局，
 * 复盘在独立进程级 scope 里跑（服务停止也不中断，完成发通知）。
 *
 * 引擎与实战共用（`SearchController.searchMutex` 串行）：
 * - 每个局面开始前 `yieldGate`：实战循环在跑就等（实战优先）；
 * - 共用时单局面 3000→1500ms，最坏只让实战等一次搜索。
 *
 * 队列：复盘进行中再封存的新局不会丢（旧版直接跳过 → 永远停在「复盘中」）。
 */
object ReviewRunner {

    private const val CHANNEL_ID = "review"
    private const val NOTIF_ID = 4821
    private const val IDLE_MOVETIME = 3000
    private const val SHARED_MOVETIME = 1500

    private data class Request(
        val gameId: Long,
        val moves: List<MoveRecord>,
        val userIsRed: Boolean,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = ArrayDeque<Request>()
    private var worker: Job? = null

    @Volatile var running = false
        private set
    @Volatile var done = 0
        private set
    @Volatile var total = 0
        private set
    /** 正在复盘的对局 id（复盘页据此显示 x/y；0=空闲） */
    @Volatile var currentGameId = 0L
        private set
    /** 排队中的复盘数 */
    @Volatile var queued = 0
        private set

    val isRunning: Boolean get() = worker?.isActive == true

    fun start(context: Context, gameId: Long, moves: List<MoveRecord>, userIsRed: Boolean) {
        val app = context.applicationContext
        synchronized(queue) {
            queue.addLast(Request(gameId, moves, userIsRed))
            queued = queue.size
        }
        if (worker?.isActive == true) {
            AppLog.i("Review", "queued game=$gameId (worker busy, queue=$queued)")
            return
        }
        worker = scope.launch { drain(app) }
    }

    private suspend fun drain(app: Context) {
        while (true) {
            val req = synchronized(queue) {
                queue.removeFirstOrNull().also { queued = queue.size }
            } ?: break
            runOne(app, req)
        }
        running = false
        currentGameId = 0
    }

    private suspend fun runOne(app: Context, req: Request) {
        running = true
        done = 0
        total = 0
        currentGameId = req.gameId
        val started = System.currentTimeMillis()
        try {
            val ctl = EngineHolder.ensure(app) ?: run {
                AppLog.e("Review", "engine unavailable, game=${req.gameId}")
                mark(req.gameId, GameEntity.STATUS_INTERRUPTED, app)
                notify(app, "复盘失败：引擎不可用")
                return
            }
            val reviewer = GameReviewer(ctl)
            val reviews = runCatching {
                reviewer.review(
                    req.moves, req.userIsRed,
                    onProgress = { d, t -> done = d; total = t },
                    yieldGate = { while (ReviewGate.liveActive) delay(500) },
                    movetimeFor = {
                        if (ReviewGate.liveActive) SHARED_MOVETIME else IDLE_MOVETIME
                    },
                )
            }.onFailure { AppLog.e("Review", "review failed game=${req.gameId}", it) }
                .getOrDefault(emptyList())
            val db = AppDb.get(app)
            runCatching { saveToKnowledge(db, reviews) }
            val mistakes = reviews.count { it.blunderLevel == 1 }
            val majors = reviews.count { it.blunderLevel == 2 }
            db.gameDao().finishReview(
                req.gameId, GameEntity.STATUS_DONE, reviewsToJson(reviews), mistakes, majors)
            AppLog.i("Review",
                "done game=${req.gameId} moves=${req.moves.size} " +
                    "mistakes=$mistakes majors=$majors " +
                    "cost=${System.currentTimeMillis() - started}ms")
            notify(app, "复盘完成：失误 $mistakes · 大漏着 $majors")
        } catch (ce: CancellationException) {
            mark(req.gameId, GameEntity.STATUS_INTERRUPTED, app)
            throw ce
        } catch (t: Throwable) {
            AppLog.e("Review", "runner crashed game=${req.gameId}", t)
            mark(req.gameId, GameEntity.STATUS_INTERRUPTED, app)
        } finally {
            running = false
            currentGameId = 0
        }
    }

    private suspend fun mark(gameId: Long, status: String, context: Context) {
        runCatching { AppDb.get(context).gameDao().finishReview(gameId, status, "[]", 0, 0) }
            .onFailure { AppLog.e("Review", "mark $status failed game=$gameId", it) }
    }

    /**
     * 进程启动时：上次进程被杀留下的 `reviewing` 局标成 `interrupted`，
     * 否则会永远显示「复盘中」（2026-10-27 用户实测卡住）。
     */
    fun recoverStale(context: Context) {
        val app = context.applicationContext
        scope.launch {
            runCatching {
                val db = AppDb.get(app)
                db.gameDao().byStatus(GameEntity.STATUS_REVIEWING).forEach { g ->
                    AppLog.w("Review", "mark interrupted game=${g.id}")
                    db.gameDao().finishReview(
                        g.id, GameEntity.STATUS_INTERRUPTED,
                        g.reviewJson ?: "[]", g.blunderCount, g.blunderCountMajor)
                }
            }.onFailure { AppLog.e("Review", "recover stale failed", it) }
        }
    }

    /** 完成/失败通知（服务已停也能提示；13+ 需通知权限，未授权则静默） */
    private fun notify(context: Context, text: String) {
        runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (!nm.areNotificationsEnabled()) {
                AppLog.w("Review", "notifications disabled by user; completion not shown")
                return
            }
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "复盘",
                        NotificationManager.IMPORTANCE_DEFAULT))
            }
            val n = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("弈枰")
                .setContentText(text)
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIF_ID, n)
        }.onFailure { AppLog.e("Review", "notify failed", it) }
    }
}
