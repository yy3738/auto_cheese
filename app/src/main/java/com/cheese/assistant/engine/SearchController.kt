package com.cheese.assistant.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SearchResult(
    val candidates: List<Candidate>,
    val bestMove: String,
    val depth: Int,
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

    /** 对给定 FEN 做 movetime 限时搜索。 */
    suspend fun search(fen: String, movetime: Int = movetimeMs): SearchResult =
        withContext(Dispatchers.Default) {
            val e = engine ?: error("engine not started")
            val byRank = HashMap<Int, Candidate>()
            var bestMove = ""
            var maxDepth = 0

            e.nativeSetPosition(fen, emptyArray())
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
            e.nativeStop()

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
