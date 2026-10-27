package com.cheese.assistant.engine

import android.content.Context
import com.cheese.assistant.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 进程级引擎持有者（S4，2026-10-26）。
 *
 * 为什么必须是进程级：
 * - NNUE 64MiB 加载在模拟器约 14s、手机 2-4s，越早开始越好；以前从
 *   `AssistantService.onCreate` 才开始，用户点「开局」时可能还没就绪。
 * - JNI 里 `g_engine` 是**进程级单例**，两个 `SearchController` 会互相
 *   抢输出队列/互相 stop（主界面分析与悬浮窗分析各建一个就是隐患）。
 *   统一由这里持有，Service 与主界面共用。
 *
 * 生命周期：随进程存活（用户接受约 64MiB NNUE + Hash 64MiB 常驻）；
 * 进程死亡自动释放；停止服务/回主界面**不**释放，下次开局直接可用。
 */
object EngineHolder {

    enum class State { IDLE, LOADING, READY, FAILED }

    @Volatile var state: State = State.IDLE
        private set

    /** 开始加载的时刻（用于"预热中 Xs"显示） */
    @Volatile private var loadingSince = 0L

    @Volatile var lastError: String? = null
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var controller: SearchController? = null

    val isReady: Boolean get() = state == State.READY && controller != null
    val isLoading: Boolean get() = state == State.LOADING
    val controllerOrNull: SearchController? get() = controller

    /** 预热已用秒数（非加载态返回 0） */
    fun loadingSeconds(): Long =
        if (state == State.LOADING && loadingSince > 0)
            (System.currentTimeMillis() - loadingSince) / 1000 else 0

    /** 进程启动即调用（CheeseApp）：后台加载，幂等 */
    fun prewarm(context: Context) {
        if (state != State.IDLE) return
        state = State.LOADING
        loadingSince = System.currentTimeMillis()
        scope.launch { ensure(context) }
    }

    /** 幂等获取；并发调用串行化（NNUE 只加载一次） */
    suspend fun ensure(context: Context): SearchController? {
        controller?.let { return it }
        return mutex.withLock {
            controller?.let { return it }
            if (state != State.LOADING) {
                state = State.LOADING
                loadingSince = System.currentTimeMillis()
            }
            val started = System.currentTimeMillis()
            val c = SearchController()
            val ok = runCatching { c.start(context.applicationContext) }.getOrDefault(false)
            if (!ok) {
                runCatching { c.shutdown() }
                state = State.FAILED
                lastError = "NNUE/引擎初始化失败"
                AppLog.e("Engine",
                    "init failed after ${System.currentTimeMillis() - started}ms")
                return null
            }
            controller = c
            state = State.READY
            lastError = null
            AppLog.i("Engine", "ready in ${System.currentTimeMillis() - started}ms")
            c
        }
    }

    /** 仅用于进程退出/测试；正常使用不要调用 */
    fun shutdown() {
        runCatching { controller?.shutdown() }
        controller = null
        state = State.IDLE
        loadingSince = 0
    }
}
