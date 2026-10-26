package com.cheese.assistant.recognize

import com.cheese.assistant.board.Position
import com.cheese.assistant.board.Zobrist

/**
 * 多步走子序列还原（S2 后半，2026-10-23）。
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
 * 返回的局面链每一步都过 `XiangqiRules.canMove`，最后一项必须与目标
 * 完全一致（含轮走方）——**宁可返回 null 留缺口，也不猜一个走不到的序列**。
 * 多解时返回搜索到的第一条（任一条合法解释都比丢步好，因为终局盘面
 * 一定正确）。
 */
object SequenceSolver {

    /**
     * @param minPlies 最小步数（调用方已用 1/2 步快路径试过时传 3）
     * @param maxPlies 最大步数上限
     * @param nodeBudget 搜索节点上限（防慢机卡顿；超限即放弃，走缺口路径）
     * @return 局面链（含起点，最后一项 == cur），无解返回 null
     */
    fun solve(
        prev: Position,
        cur: Position,
        minPlies: Int = 1,
        maxPlies: Int = 4,
        nodeBudget: Int = 8_000,
    ): List<Position>? {
        if (prev.board.contentEquals(cur.board)) return null
        // 双方局面都空的格 = 可能的"过渡格"（子先挪开、后面再落别处）
        val emptyBoth = BooleanArray(90) { prev.board[it] == null && cur.board[it] == null }
        val budget = intArrayOf(nodeBudget)
        val chain = ArrayList<Position>(maxPlies + 1)
        chain.add(prev)
        for (depth in minPlies..maxPlies) {
            if (budget[0] <= 0) break
            val seen = HashSet<Long>(256)
            val transit = HashSet<Int>(4)
            dfs(chain, cur, depth, emptyBoth, transit, budget, seen)?.let { return it }
        }
        return null
    }

    private fun dfs(
        chain: MutableList<Position>,
        target: Position,
        left: Int,
        emptyBoth: BooleanArray,
        transit: MutableSet<Int>,
        budget: IntArray,
        seen: MutableSet<Long>,
    ): List<Position>? {
        val last = chain.last()
        val board = last.board
        if (last.redToMove == target.redToMove && board.contentEquals(target.board)) {
            return chain.toList()
        }
        if (left <= 0 || budget[0]-- <= 0) return null

        val diff = BooleanArray(90)
        var diffCount = 0
        for (i in 0 until 90) {
            if (board[i] != target.board[i]) { diff[i] = true; diffCount++ }
        }
        // 盘面已同但轮走方不符：本支路无解（轮走方由步数奇偶决定）
        if (diffCount == 0) return null
        if (diffCount > 2 * left) return null

        val key = Zobrist.of(board, last.redToMove) * 7 + left
        if (!seen.add(key)) return null

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
                val added = transit.add(t)
                val r = dfs(chain, target, left - 1, emptyBoth, transit, budget, seen)
                if (added) transit.remove(t)
                if (r != null) return r
                chain.removeAt(chain.size - 1)
            }
        }
        return null
    }
}
