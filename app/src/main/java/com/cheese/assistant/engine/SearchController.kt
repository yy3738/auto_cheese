package com.cheese.assistant.engine

import com.cheese.assistant.util.AppLog
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class SearchResult(
    val candidates: List<Candidate>,
    val bestMove: String,
    val depth: Int,
    val failed: Boolean = false,
) {
    val best: Candidate? get() = candidates.firstOrNull()
}

/**
 * 引擎控制器：管理 Pikafish 生命周期，把一次限时搜索封装为挂起函数。
 */
class SearchController(
    private val movetimeMs: Int = 800,
    private val multiPv: Int = 3,
    private val threads: Int = 3,
) {
    private var engine: PikafishEngine? = null

    // 引擎是单进程单状态:set_position/go 出口只有一个。autoLoop 的协作式
    // 取消打断不了进行中的搜索(native 轮询无挂起点),复盘若不串行等待
    // 就会与残留搜索交错,污染引擎状态(实测表现:分析中途整体卡死)
    private val searchMutex = Mutex()

    /** 解压 NNUE 权重到内部存储并启动引擎。 */
    suspend fun start(context: Context): Boolean = withContext(Dispatchers.IO) {
        val nnueDir = copyNnue(context)
        val e = PikafishEngine().also { engine = it }
        if (!e.nativeInit(nnueDir)) return@withContext false
        e.nativeSetOption("Threads", "$threads")
        e.nativeSetOption("MultiPV", "$multiPv")
        e.nativeSetOption("Hash", "64")
        true
    }

    private fun copyNnue(context: Context): String {
        val dir = context.filesDir.apply { mkdirs() }
        val out = java.io.File(dir, "pikafish.nnue")
        if (!out.exists() || out.length() == 0L) {
            context.assets.open("pikafish.nnue").use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return out.absolutePath
    }

    /** 对给定 FEN 做 movetime 限时搜索。非法局面（如缺王）返回空结果而非崩溃。
     *  multiPv 覆盖本局的 MultiPV（复盘需要更宽候选把用户着法包进来），随后恢复。 */
    suspend fun search(fen: String, movetime: Int = movetimeMs, multiPv: Int = this.multiPv): SearchResult {
        val e = engine ?: error("engine not started")
        return if (multiPv != this@SearchController.multiPv) {
            searchMutex.withLock {
                e.nativeSetOption("MultiPV", "$multiPv")
                try {
                    searchInternal(e, fen, movetime)
                } finally {
                    e.nativeSetOption("MultiPV", "${this@SearchController.multiPv}")
                }
            }
        } else {
            searchMutex.withLock { searchInternal(e, fen, movetime) }
        }
    }

    private suspend fun searchInternal(
        e: PikafishEngine,
        fen: String,
        movetime: Int,
    ): SearchResult = withContext(Dispatchers.Default) {
        // Pikafish 在缺王局面会触发断言 abort,必须先把关
        val boardPart = fen.substringBefore(' ')
        if (boardPart.count { it == 'K' } != 1 || boardPart.count { it == 'k' } != 1) {
            return@withContext SearchResult(emptyList(), "", 0, failed = true)
        }
        // set_position 失败（仕象越界等非法局面）后引擎内部是空局面，
        // 继续同样触发缺王断言，必须直接放弃本次搜索
        if (!e.nativeSetPosition(fen, emptyArray())) {
            AppLog.e("SearchCtl", "set_position failed fen=[$fen]")
            return@withContext SearchResult(emptyList(), "", 0, failed = true)
        }
        val byRank = HashMap<Int, Candidate>()
        var bestMove = ""
        var maxDepth = 0

        // 清掉上一局残留的输出（stop 后补发的行可能迟到），避免污染本次解析
        while (e.nativePollLine(0) != null) Unit

        val startedAt = System.currentTimeMillis()
        e.nativeGo()

        val deadline = System.currentTimeMillis() + movetime + 3000L
        while (System.currentTimeMillis() < deadline) {
            val line = e.nativePollLine(100) ?: continue
            when (val parsed = UciParser.parse(line)) {
                is UciLine.Info -> {
                    val c = parsed.candidate
                    val old = byRank[c.rank]
                    if (old == null || c.depth >= old.depth) byRank[c.rank] = c
                    maxDepth = maxOf(maxDepth, c.depth)
                }
                is UciLine.BestMove -> {
                    bestMove = parsed.move
                    break
                }
                else -> Unit
            }
        }
        if (bestMove.isEmpty()) {
            // 时限到了还没等来 bestmove：stop 后引擎必然补发 bestmove，再等 2s
            e.nativeStop()
            val bmDeadline = System.currentTimeMillis() + 2000L
            while (System.currentTimeMillis() < bmDeadline) {
                val line = e.nativePollLine(100) ?: continue
                if (UciParser.parse(line) is UciLine.BestMove) break
            }
        }
        e.nativeStop()

        val cost = System.currentTimeMillis() - startedAt
        when {
            bestMove.isEmpty() ->
                AppLog.w("SearchCtl", "no bestmove fen=[${fen.take(24)}…] cost=${cost}ms")
            cost > movetime + 2000L ->
                AppLog.w("SearchCtl", "slow search cost=${cost}ms movetime=$movetime best=${bestMove.take(8)}")
        }

        SearchResult(
            candidates = byRank.values.sortedBy { it.rank },
            bestMove = bestMove,
            depth = maxDepth,
        )
    }

    fun shutdown() {
        engine?.nativeQuit()
        engine = null
    }
}
