package com.cheese.assistant.recognize

import com.cheese.assistant.board.Position

/**
 * 纯函数盘面修复（无 Android 依赖，便于单元测试）。
 */
object BoardRepair {

    /**
     * 用期望局面补齐"无故消失"的棋子（少子根因，2026-10-22）。
     *
     * 只在以下条件同时满足时生效：
     * - 本帧棋子集合是期望的子集：期望有子→本帧空（漏读）才补；
     *   本帧多出期望没有的子 = 可能是真实走子落点，交给走子校验，不补；
     *   同格异子 = 冲突，交给颜色门/超限核对，不在这里猜。
     * - 缺失格数 1..maxMissing：一步走子最多让 1-2 格变化，超过说明
     *   期望局面本身过期。
     *
     * 象棋里棋子不会凭空消失（只能被吃，且吃子会在落点出现敌子），
     * 所以"期望有、本帧空"一定是识别漏读，补回期望值是安全的。
     */
    fun restoreMissing(expected: Position, pos: Position, maxMissing: Int = 3): Position? {
        var missing = 0
        for (i in 0 until 90) {
            val e = expected.board[i]
            val c = pos.board[i]
            when {
                e == null && c == null -> Unit
                e == null && c != null -> return null      // 期望空、本帧有子
                e != null && c == null -> missing++        // 漏读
                else -> if (e != c) return null            // 同格异子
            }
        }
        if (missing == 0 || missing > maxMissing) return null
        val board = pos.board.copyOf()
        for (i in 0 until 90) {
            if (expected.board[i] != null && board[i] == null) {
                board[i] = expected.board[i]
            }
        }
        return Position(board, pos.redToMove)
    }

    /**
     * 同兵种异色的静态格按期望局面纠正（红车↔黑车误配，2026-10-23）。
     *
     * 象棋里棋子颜色**永不改变**，所以"该格像素没动过、期望局面在这里是
     * 红车、本帧却读成黑车"只能是识别误配。实测链条：红车吃士走到 (0,5)
     * 后，颜色门按笔画最暗 18% 算出的 R-G 只有 31.5（正常红子 p5≈76），
     * 于是把红车改判成黑车；仲裁层两帧确认后采纳 → 黑方凭空多一个车，
     * 棋谱从此与真实局面永久不一致，每帧 `implausible` 永不恢复。
     *
     * 只在"同兵种异色 + 该格静态"时纠正，不改兵种（马读成士这类交给
     * 锚点/超限逻辑），也不动期望局面为空的格。
     *
     * @param isStatic 该格像素相对锚点帧是否未变（没动过才谈得上"误配"）
     * @return 修正后的局面 + 被修正的格号；无需修正返回 null
     */
    fun fixColors(
        expected: Position,
        pos: Position,
        isStatic: (Int) -> Boolean,
    ): Pair<Position, List<Int>>? {
        var board: Array<com.cheese.assistant.board.Piece?>? = null
        val fixed = ArrayList<Int>(2)
        for (i in 0 until 90) {
            val cur = pos.board[i] ?: continue
            val exp = expected.board[i] ?: continue
            if (cur == exp) continue
            if (cur.cnName != exp.cnName) continue     // 只纠同兵种异色
            if (!isStatic(i)) continue
            if (board == null) board = pos.board.copyOf()
            board[i] = exp
            fixed.add(i)
        }
        val b = board ?: return null
        return Position(b, pos.redToMove) to fixed
    }
}
