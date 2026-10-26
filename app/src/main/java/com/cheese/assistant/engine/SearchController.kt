package com.cheese.assistant.engine

import com.cheese.assistant.util.AppLog
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
 * 退化搜索结果：引擎首搜抢在 NNUE 就绪前、或上一轮搜索残留状态时，
 * 会返回深度≤1、单候选、评分 0（且非杀）的随手着法。
 * 曾导致首推固定「兵九进一 +0.0」。JNI 已等 NNUE 就绪，这里再兜一层。
 */
internal fun isDegenerateSearch(r: SearchResult): Boolean {
    if (r.failed) return false
    if (r.depth > 1 || r.candidates.size > 1) return false
    val c = r.candidates.firstOrNull() ?: return true
    return c.scoreCp == 0 && c.mateIn == null
}

/**
 * 同一 root move 只保留一条（深度高者优先，同深度保留排名靠前者）。
 * 引擎 stop 收尾阶段的残留 info 行曾让同一个 `f0e1 仕四进五` 以
 * multipv 1/2 两个不同评分同时出现在推荐里（2026-10-22 实测）。
 */
internal fun dedupCandidates(list: List<Candidate>): List<Candidate> {
    if (list.size <= 1) return list
    val byMove = LinkedHashMap<String, Candidate>()
    for (c in list) {
        val move = c.pv.firstOrNull() ?: continue
        val old = byMove[move]
        if (old == null) {
            byMove[move] = c
            continue
        }
        val keepNew = c.depth > old.depth ||
            (c.depth == old.depth && c.rank < old.rank)
        AppLog.w("SearchCtl",
            "duplicate root move $move: rank ${old.rank}/${c.rank} " +
                "depth ${old.depth}/${c.depth} score ${old.scoreCp}/${c.scoreCp} " +
                "→ keep ${if (keepNew) "new" else "old"}")
        if (keepNew) byMove[move] = c
    }
    return byMove.values.sortedBy { it.rank }
}

/**
 * 引擎控制器：管理 Pikafish 生命周期，把一次限时搜索封装为挂起函数。
 */
class SearchController(
    private val movetimeMs: Int = 900,
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
        // 预热一次短搜索：NNUE 刚 ready 时首搜仍可能返回 depth1/评分0
        // （实测 15:31:31 cost=8ms best=a3a4 depth=1），预热后首推才正常
        runCatching { warmUp(e) }.onFailure {
            AppLog.e("SearchCtl", "warm-up failed", it)
        }
        true
    }

    /** 引擎首搜预热：跑 60ms 开局搜索并丢弃结果，让搜索线程/网络副本就绪 */
    private fun warmUp(e: PikafishEngine) {
        val fen = "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1"
        if (!e.nativeSetPosition(fen, emptyArray())) return
        while (e.nativePollLine(0) != null) Unit
        val started = System.currentTimeMillis()
        e.nativeGo(60)
        val deadline = System.currentTimeMillis() + 3000L
        while (System.currentTimeMillis() < deadline) {
            val line = e.nativePollLine(100) ?: continue
            if (line.startsWith("bestmove")) break
        }
        e.nativeStop()
        e.nativeWaitForSearchFinished()
        while (e.nativePollLine(0) != null) Unit
        AppLog.d("SearchCtl", "warm-up done ${System.currentTimeMillis() - started}ms")
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
                    searchWithRetry(e, fen, movetime)
                } finally {
                    e.nativeSetOption("MultiPV", "${this@SearchController.multiPv}")
                }
            }
        } else {
            searchMutex.withLock { searchWithRetry(e, fen, movetime) }
        }
    }

    /** 退化结果延迟 300ms 重搜一次；JNI 已等 NNUE 就绪，这里是状态异常兜底。 */
    private suspend fun searchWithRetry(
        e: PikafishEngine, fen: String, movetime: Int,
    ): SearchResult {
        val first = searchInternal(e, fen, movetime)
        if (!isDegenerateSearch(first)) return first
        AppLog.w("SearchCtl",
            "degenerate search depth=${first.depth} candidates=${first.candidates.size}, retry")
        delay(300)
        val second = searchInternal(e, fen, movetime)
        if (isDegenerateSearch(second))
            AppLog.w("SearchCtl", "degenerate search again, give up")
        return second
    }

    private suspend fun searchInternal(
        e: PikafishEngine,
        fen: String,
        movetime: Int,
    ): SearchResult = withContext(Dispatchers.Default) {
        // 等上一轮搜索线程真正退出，再清队列/设局面。只靠 searchMutex
        // 串行化 Kotlin 调用不够：stop 后 native 线程仍在收尾，残留
        // info 行会串进本轮（实测同 root move 两个 multipv 评分）。
        e.nativeWaitForSearchFinished()
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
        // 2026-10-23 诊断：本轮出现多次 cost=29-75ms 的快搜（depth 只有 3-7），
        // 与 movetime=600 不符。记录 info 行数/首个 info 时刻/bestmove 时刻，
        // 区分"真快搜"、“残留 bestmove”、“movetime 未生效（3.6s 慢搜）”。
        var infoLines = 0
        var firstInfoMs = -1L
        var bestAtMs = -1L

        // 清掉上一局残留的输出（stop 后补发的行可能迟到），避免污染本次解析
        while (e.nativePollLine(0) != null) Unit

        val startedAt = System.currentTimeMillis()
        // 引擎自查自停(movetime);宽限 3s 等 bestmove 补发
        e.nativeGo(movetime)

        val deadline = System.currentTimeMillis() + movetime + 3000L
        while (System.currentTimeMillis() < deadline) {
            val line = e.nativePollLine(100) ?: continue
            when (val parsed = UciParser.parse(line)) {
                is UciLine.Info -> {
                    val c = parsed.candidate
                    if (firstInfoMs < 0) firstInfoMs = System.currentTimeMillis() - startedAt
                    infoLines++
                    val old = byRank[c.rank]
                    if (old == null || c.depth >= old.depth) byRank[c.rank] = c
                    maxDepth = maxOf(maxDepth, c.depth)
                }
                is UciLine.BestMove -> {
                    bestMove = parsed.move
                    bestAtMs = System.currentTimeMillis() - startedAt
                    break
                }
                else -> Unit
            }
        }
        if (bestMove.isEmpty()) {
            // 时限到了还没等来 bestmove：stop 后引擎必然补发，再等 3s。
            // 期间 info 行照常入候选,bestmove 到达必须赋值——旧版只
            // break 不赋值,慢机(模拟器满载)上 bestmove 全落在宽限期,
            // 结果永远空,日志永远"no bestmove"
            e.nativeStop()
            val bmDeadline = System.currentTimeMillis() + 3000L
            while (System.currentTimeMillis() < bmDeadline) {
                val line = e.nativePollLine(100) ?: continue
                when (val parsed = UciParser.parse(line)) {
                    is UciLine.Info -> {
                        val c = parsed.candidate
                        if (firstInfoMs < 0) firstInfoMs = System.currentTimeMillis() - startedAt
                        infoLines++
                        val old = byRank[c.rank]
                        if (old == null || c.depth >= old.depth) byRank[c.rank] = c
                        maxDepth = maxOf(maxDepth, c.depth)
                    }
                    is UciLine.BestMove -> {
                        bestMove = parsed.move
                        bestAtMs = System.currentTimeMillis() - startedAt
                        break
                    }
                    else -> Unit
                }
            }
        }
        e.nativeStop()
        // 等收尾线程退出，下一轮 start 的 wait 才真正干净
        e.nativeWaitForSearchFinished()

        val cost = System.currentTimeMillis() - startedAt
        // 诊断串：快搜到底是"引擎真算完"还是"吃到残留 bestmove"，
        // 以及慢搜是否 movetime 根本没生效（infos=0 说明没开搜）
        val diag = "infos=$infoLines firstInfo=${firstInfoMs}ms bestAt=${bestAtMs}ms " +
            "bestInCands=${byRank.values.any { it.pv.firstOrNull() == bestMove }}"
        when {
            bestMove.isEmpty() ->
                AppLog.w("SearchCtl", "no bestmove fen=[${fen.take(24)}…] " +
                    "cost=${cost}ms $diag")
            cost > movetime + 2000L ->
                AppLog.w("SearchCtl", "slow search cost=${cost}ms movetime=$movetime " +
                    "best=${bestMove.take(8)} depth=$maxDepth $diag")
            // 远快于 movetime 的搜索多半是残留输出/引擎未真正开搜，留证
            cost < movetime / 2 ->
                AppLog.w("SearchCtl", "fast search cost=${cost}ms movetime=$movetime " +
                    "best=${bestMove.take(8)} depth=$maxDepth cands=${byRank.size} $diag")
        }

        SearchResult(
            candidates = dedupCandidates(byRank.values.sortedBy { it.rank }),
            bestMove = bestMove,
            depth = maxDepth,
        )
    }

    fun shutdown() {
        engine?.nativeQuit()
        engine = null
    }
}
