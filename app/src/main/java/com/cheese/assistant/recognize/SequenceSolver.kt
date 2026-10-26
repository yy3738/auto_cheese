package com.cheese.assistant.recognize

import com.cheese.assistant.board.Position
import com.cheese.assistant.board.Zobrist

/**
 * 多步走子序列还原（S2 后半，2026-10-23；S3 扩展为多解枚举，2026-10-26）。
 *
 * 用于"两次观测之间走了 3-4 手"的场景：循环重启、识别被暂停、快速连走。
 * 旧版只能解释 1-2 步（`legalStepPair` / `tryTwoSteps`），解释不了就留
 * "棋谱缺口"——那几步永久丢失（实测 `restart reconcile: unexplained diff
 * ... rebase with gap` 连丢两步）。
 *
 * 搜索空间靠三条硬约束剪枝（不是启发式猜测）：
 * 1. **每步最多改 2 格**（起点变空 + 落点变成该子）→ 剩余差异格数 >
 *    2×剩余步数即可剪枝；
 * 2. **起点必须是"错位"的子**（所在格与目标局面不同），或本支路刚移动
 *    到的格（允许同一子连走两步）；
 * 3. **落点必须与目标局面相关**：差异格，或双方局面都空的过渡格。
 *
 * 每一步都过 `XiangqiRules.canMove`，最后一项必须与目标完全一致（含轮
 * 走方）——**宁可返回空、留缺口，也不猜一个走不到的序列**。
 *
 * 多解（如"红走 A、黑走 B"与"红走 C、黑走 D"都能到达同一终局）时，
 * `solveAll` 返回全部（上限 [maxSolutions]），由调用方用落定平台
 * （[scoreByPlateaus]）消歧，仍歧义则交给用户确认。
 */
object SequenceSolver {

    /**
     * @return 全部合法解（按步数从少到多）；无解返回空列表
     */
    fun solveAll(
        prev: Position,
        cur: Position,
        minPlies: Int = 1,
        maxPlies: Int = 4,
        maxSolutions: Int = 8,
        nodeBudget: Int = 8_000,
    ): List<List<Position>> {
        if (prev.board.contentEquals(cur.board)) return emptyList()
        val emptyBoth = BooleanArray(90) { prev.board[it] == null && cur.board[it] == null }
        val budget = intArrayOf(nodeBudget)
        val out = ArrayList<List<Position>>(maxSolutions)
        val seenMoves = HashSet<String>()
        for (depth in minPlies..maxPlies) {
            if (budget[0] <= 0 || out.size >= maxSolutions) break
            val chain = ArrayList<Position>(depth + 1).apply { add(prev) }
            val moves = ArrayList<Int>(depth)
            val seen = HashSet<Long>(256)
            val transit = HashSet<Int>(4)
            collect(chain, moves, cur, depth, emptyBoth, transit, budget, seen,
                out, seenMoves, maxSolutions)
        }
        return out
    }

    /** 只要一条解（旧调用方兼容）：优先步数最少的第一条 */
    fun solve(
        prev: Position,
        cur: Position,
        minPlies: Int = 1,
        maxPlies: Int = 4,
        nodeBudget: Int = 8_000,
    ): List<Position>? = solveAll(prev, cur, minPlies, maxPlies, 1, nodeBudget).firstOrNull()

    private fun collect(
        chain: MutableList<Position>,
        moves: MutableList<Int>,
        target: Position,
        left: Int,
        emptyBoth: BooleanArray,
        transit: MutableSet<Int>,
        budget: IntArray,
        seen: MutableSet<Long>,
        out: MutableList<List<Position>>,
        seenMoves: MutableSet<String>,
        maxSolutions: Int,
    ) {
        if (out.size >= maxSolutions || budget[0] <= 0) return
        val last = chain.last()
        val board = last.board
        if (last.redToMove == target.redToMove && board.contentEquals(target.board)) {
            val sig = moves.joinToString(",")
            if (seenMoves.add(sig)) out.add(chain.toList())
            return
        }
        if (left <= 0 || budget[0]-- <= 0) return

        val diff = BooleanArray(90)
        var diffCount = 0
        for (i in 0 until 90) {
            if (board[i] != target.board[i]) { diff[i] = true; diffCount++ }
        }
        // 盘面已同但轮走方不符：本支路无解（轮走方由步数奇偶决定）
        if (diffCount == 0) return
        if (diffCount > 2 * left) return

        val key = Zobrist.of(board, last.redToMove) * 7 + left
        if (!seen.add(key)) return

        val moverRed = last.redToMove
        val froms = ArrayList<Int>(12)
        for (i in 0 until 90) {
            if (!diff[i]) continue
            if (board[i]?.isRed == moverRed) froms.add(i)
        }
        // 允许同一子连走：起点可以是本支路刚移动到的格
        for (i in transit) {
            val p = board[i] ?: continue
            if (p.isRed == moverRed && !diff[i]) froms.add(i)
        }

        for (f in froms) {
            val piece = board[f] ?: continue
            for (t in 0 until 90) {
                if (f == t) continue
                if (!diff[t] && !emptyBoth[t]) continue
                if (board[t] == piece) continue          // 落点已是同型同色，无意义
                if (!XiangqiRules.canMove(board, f, t)) continue
                val nb = board.copyOf()
                nb[t] = piece
                nb[f] = null
                chain.add(Position(nb, !moverRed))
                moves.add(f * 90 + t)
                val added = transit.add(t)
                collect(chain, moves, target, left - 1, emptyBoth, transit, budget,
                    seen, out, seenMoves, maxSolutions)
                if (added) transit.remove(t)
                moves.removeAt(moves.size - 1)
                chain.removeAt(chain.size - 1)
                if (out.size >= maxSolutions || budget[0] <= 0) return
            }
        }
    }

    /**
     * 用落定平台给候选序列打分：每个平台的变化格集合（相对窗口起点）若
     * 等于某个"前 j 步"的变化格集合，记 1 分。分高者更可能是真实顺序。
     *
     * @param plateaus 平台变化格（raw 屏幕方向，来自 MotionTracker）
     * @param flip 识别局面是否上下翻转（normalized→raw）
     */
    fun scoreByPlateaus(
        chain: List<Position>,
        plateaus: List<BooleanArray>,
        flip: Boolean = false,
    ): Int {
        if (plateaus.isEmpty() || chain.size < 2) return 0
        val n = chain.size - 1
        val prefix = ArrayList<BooleanArray>(n)
        for (j in 1..n) {
            val set = BooleanArray(90)
            for (i in 0 until 90) set[i] = chain[j].board[i] != chain[0].board[i]
            prefix.add(set)
        }
        var score = 0
        for (p in plateaus) {
            for (set in prefix) {
                var same = true
                for (i in 0 until 90) {
                    val want = if (flip) set[flipIndex(i)] else set[i]
                    if (want != p[i]) { same = false; break }
                }
                if (same) { score++; break }
            }
        }
        return score
    }

    private fun flipIndex(i: Int): Int = (9 - i / 9) * 9 + i % 9
}
