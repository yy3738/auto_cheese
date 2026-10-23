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
}
