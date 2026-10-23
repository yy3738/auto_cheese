package com.cheese.assistant.recognize

import com.cheese.assistant.board.Position
import com.cheese.assistant.util.AppLog

/**
 * 锚点仲裁（AnchorRecovery）：CNN 拒判/弱判格的回填决策。
 *
 * 输入：引擎草稿（含 WEAK/UNKNOWN 分层）、上一可信局面锚点、变化格掩码
 * 输出：补全后的 Position，或 null（整帧作废，调用方等下一帧）
 *
 * 策略（与用户确认的设计）：
 * - CONFIDENT 格：直接采信
 * - WEAK 格：静态格（无变化）采信草稿；变化格 → 作废（等帧，安全优先）
 * - UNKNOWN 格：静态格用锚点回填（新鲜度记账）；变化格 → 作废
 *
 * 防漂移（三层防线之一）：同格连续回填 ≥ MAX_CONSECUTIVE_BACKFILL 次
 * 触发"全量重识别"信号（返回 null 且日志提醒），避免锚点把已消失的
 * 棋子永久复活。子上限/双王硬门仍由 BoardWatcher 在此之后把关。
 */
object AnchorRecovery {

    private const val TAG = "engine"

    /** 同格连续回填上限（帧数）：超过即强制全量重识别 */
    const val MAX_CONSECUTIVE_BACKFILL = 5

    /** 每格连续回填计数 */
    private val backfillRun = IntArray(90)

    /**
     * @param draft 引擎结果（board+confidence）
     * @param anchor 上一可信局面（null=无锚点，任何 UNKNOWN/WEAK 变化格都作废）
     * @param changed 与锚点相比发生变化的格（CONFIDENT/WEAK 格按草稿值比较，
     *                拒判格由调用方用像素差分判定——草稿无值可比较）
     * @param fallbackAnchor anchor 来自棋谱回放等兜底而非 lastGood——它可能
     *                滞后于真实盘面：回填须受子上限约束（残影不得顶破上限,
     *                否则 piece cap 风暴每帧必拒死循环 2026-10-19 实测），
     *                且不触发回填超限重识别（引导期该机制只会连续作废）
     */
    fun resolve(draft: EngineResult, anchor: Position?, changed: BooleanArray,
                fallbackAnchor: Boolean = false): Position? {
        val board = draft.board.copyOf()
        var backfilled = 0
        // 已采信棋子（CONFIDENT/WEAK 草稿值）的同型计数——回填复活须留在此上限内
        val counts = HashMap<com.cheese.assistant.board.Piece, Int>()
        for (i in 0 until 90) {
            val p = board[i] ?: continue
            if (draft.confidence[i] != EngineResult.Confidence.UNKNOWN) {
                counts[p] = (counts[p] ?: 0) + 1
            }
        }
        // 变化格上的拒判格不再一律作废：先收集，事后走"走法推测"。
        // 对方走子出发/落点落在低置信区（每帧 27-29 格拒判的界面）时，
        // 典型形态是"出发点（空格石纹）拒判+落点（棋子）CONFIDENT 判出"，
        // 旧版仅支持"出发点 CONFIDENT 判空"一种形态，其余整帧作废等
        // failRun×5 自愈 ≈ 9s 才恢复推荐（2026-10-19 实测定位）
        var changedUnknownCount = 0

        for (i in 0 until 90) {
            val conf = draft.confidence[i]
            if (conf == EngineResult.Confidence.CONFIDENT) {
                backfillRun[i] = 0
                continue
            }
            if (!changed[i]) {
                // 静态格：WEAK 直接采信草稿；UNKNOWN 用锚点回填
                if (conf == EngineResult.Confidence.UNKNOWN) {
                    if (anchor != null) {
                        val a = anchor.board[i]
                        if (a != null && (counts[a] ?: 0) >= capOf(a)) {
                            // 同型已满:锚点是滞后残影,按空格处理,不复活
                            board[i] = null
                            backfilled++
                        } else {
                            // 回填锚点值（null=该格确实为空，同样合法）
                            board[i] = a
                            backfilled++
                            if (a != null) {
                                counts[a] = (counts[a] ?: 0) + 1
                                if (!fallbackAnchor) {
                                    backfillRun[i]++
                                    if (backfillRun[i] >= MAX_CONSECUTIVE_BACKFILL) {
                                        // 重置后再作废本帧：否则计数永超限,此后每帧
                                        // 都走 null(实测 run=445 卡死整局)
                                        backfillRun[i] = 0
                                        AppLog.w(TAG,
                                            "backfill run over limit at i=$i, force re-recognize")
                                        return null
                                    }
                                }
                            }
                        }
                    } else {
                        // 无锚点（bootstrap）：拒判格默认为空格，交由调用方
                        // credibility 硬门（双王/子上限）把关。死锁教训：
                        // 对局中途开启时若因无锚点整帧作废，将永远建立不了
                        // 首帧锚点（2026-10-17 实测残局 11 格拒判死循环）
                        backfilled++
                    }
                }
                // WEAK 静态格：采信草稿（board[i] 已含草稿值）
            } else {
                // 变化格上的非 CONFIDENT
                backfillRun[i] = 0
                if (conf == EngineResult.Confidence.WEAK) {
                    // 弱判不再整帧作废(2026-10-20): CNN 改用 MobileNet 后
                    // 每帧仅 7-8 个 WEAK 格,margin≥0.75 本身就是一次可信
                    // 读取; 作废反而把好帧拖进 5 帧自愈循环(实测 20s 延迟
                    // 的主成分)。留 credibility 硬门兜底
                    AppLog.d(TAG, "changed weak trusted at (${i / 9},${i % 9})")
                    continue
                }
                // UNKNOWN 变化格（像素差分确认真实变化）：收集待推测
                changedUnknownCount++
            }
        }

        if (changedUnknownCount > 0 && anchor != null) {
            val changedCells = (0 until 90).filter { changed[it] }
            val solved = speculate(anchor, board, draft.confidence, changedCells)
            if (solved != null) {
                return Position(solved, true)
            }
            AppLog.d(TAG, "frame invalid: $changedUnknownCount changed unknown cell(s), " +
                "cells=${changedCells.joinToString { "(${it / 9},${it % 9})" }} backfilled=$backfilled")
            return null
        }
        if (backfilled > 0) {
            AppLog.d(TAG, "backfill=$backfilled (静态拒判格用锚点补位)")
        }
        return Position(board, true)
    }

    /**
     * 走法推测：把本帧相对锚点的 2-4 个变化格解释为 1-2 步合法走子。
     * - 出发点：锚点有子，且草稿判空（CONFIDENT）或拒判（无值）
     * - 落点：拒判格无约束；CONFIDENT 草稿必须恰为移动子（吃子允许，
     *   canMove 用锚点盘校验路径与目标）
     * - 奇数变化格容 1 格噪声(2026-10-20): 对方走完 光圈/残影常留在
     *   第 3 格上——n=3 时解释 1 步,余下 1 格用锚点值回填,不再整帧
     *   作废(实测 (0,8)+(7,6)+(9,7) 三格真实双步被奇数卡死 20s)
     * - 解释唯一才采纳（双步按结果盘面去重，歧义放弃）。
     * 推测错有 credibility 硬门与 AutoLoop 走子校验兜底。
     * 返回恢复后的盘面（在已回填静态格的 board 基础上应用走子）。
     */
    private fun speculate(
        anchor: Position,
        board: Array<com.cheese.assistant.board.Piece?>,
        conf: Array<EngineResult.Confidence>,
        cells: List<Int>,
    ): Array<com.cheese.assistant.board.Piece?>? {
        val n = cells.size
        if (n < 2 || n > 4) return null

        fun isOrigin(o: Int): Boolean {
            val a = anchor.board[o] ?: return false
            // 草稿 CONFIDENT 有子（非锚点同子）= 原地换子，不是出发点
            return conf[o] != EngineResult.Confidence.CONFIDENT || board[o] == null
        }

        fun fitsDest(d: Int, mover: com.cheese.assistant.board.Piece): Boolean =
            conf[d] != EngineResult.Confidence.CONFIDENT || board[d] == mover

        // 解按结果盘面去重:双步的两个枚举顺序(先红炮/先黑马)产生同一
        // 盘面,按路径计数会误判歧义放弃(2026-10-19 实测 4 变化格全对
        // 却推测失败,又走 10s 自愈)。不同盘面 → 真歧义 → 保守放弃。
        fun tryApply(o: Int, d: Int, onBoard: Array<com.cheese.assistant.board.Piece?>): Array<com.cheese.assistant.board.Piece?>? {
            val mover = onBoard[o] ?: return null
            if (!isOrigin(o) || !fitsDest(d, mover)) return null
            if (!XiangqiRules.canMove(onBoard, o, d)) return null
            val b = board.copyOf()
            b[o] = null
            b[d] = mover
            // 上限校验直接数结果盘面: 位移(出发点空出)不增总数,天然合法;
            // 旧写法"草稿数量>=上限即拒"把合法位移也挡死(实测红仕 (9,3)→
            // (8,4) 被拒 → 5 帧作废 → 逃生门 11s 重建,2026-10-20 定位)
            if (b.count { it == mover } > capOf(mover)) return null
            return b
        }

        if (n == 3) {
            // 1 步 + 1 噪声格（噪声=光圈/残影: 草稿值无效, 用锚点值回填）
            var solution: Array<com.cheese.assistant.board.Piece?>? = null
            for (o in cells) {
                for (d in cells) {
                    if (d == o) continue
                    var b = tryApply(o, d, anchor.board) ?: continue
                    val noise = cells.first { it != o && it != d }
                    b[noise] = anchor.board[noise]
                    // 噪声回填可能再添一子(实测噪声格锚点=红车, 落子上限
                    // 校验在回填前已过 → 3 车局面写出"黑马→红车"毒账,
                    // 2026-10-20)。上限校验必须对最终盘面再做一次
                    val noisePiece = b[noise]
                    if (noisePiece != null &&
                        b.count { it == noisePiece } > capOf(noisePiece)) continue
                    val s = solution
                    if (s == null) solution = b
                    else if (!s.contentEquals(b)) return null
                }
            }
            if (solution != null) {
                AppLog.i(TAG, "speculative move +1 noise: cells=" +
                    cells.joinToString { "(${it / 9},${it % 9})" })
            }
            return solution
        }
        val k = n / 2

        var solution: Array<com.cheese.assistant.board.Piece?>? = null

        if (k == 1) {
            for (o in cells) {
                for (d in cells) {
                    if (d == o) continue
                    val b = tryApply(o, d, anchor.board) ?: continue
                    val s = solution
                    if (s == null) solution = b
                    else if (!s.contentEquals(b)) return null
                }
            }
        } else {
            // 双步叠加（我方+对方落在同一识别窗口）：枚举第一步，
            // 剩余 2 格枚举第二步（第二步路径用第一步后的中间盘校验）
            for (o1 in cells) {
                for (d1 in cells) {
                    if (d1 == o1) continue
                    val mover1 = anchor.board[o1] ?: continue
                    if (!isOrigin(o1) || !fitsDest(d1, mover1)) continue
                    if (!XiangqiRules.canMove(anchor.board, o1, d1)) continue
                    val mid = anchor.board.copyOf()
                    mid[o1] = null
                    mid[d1] = mover1
                    val rest = cells.filter { it != o1 && it != d1 }
                    if (rest.size != 2) continue
                    for (o2 in rest) {
                        for (d2 in rest) {
                            if (d2 == o2) continue
                            val mover2 = mid[o2] ?: continue
                            if (!isOrigin(o2) || !fitsDest(d2, mover2)) continue
                            if (!XiangqiRules.canMove(mid, o2, d2)) continue
                            val b = board.copyOf()
                            b[o1] = null; b[d1] = mover1
                            b[o2] = null; b[d2] = mover2
                            // 结果盘面上限校验(位移合法,残影复活超限才拦)
                            if (b.count { it == mover1 } > capOf(mover1) ||
                                b.count { it == mover2 } > capOf(mover2)) continue
                            val s = solution
                            if (s == null) solution = b
                            else if (!s.contentEquals(b)) return null
                        }
                    }
                }
            }
        }
        if (solution != null) {
            AppLog.i(TAG, "speculative move(s): cells=" +
                cells.joinToString { "(${it / 9},${it % 9})" })
        }
        return solution
    }

    /** 重置计数（新对局/重定位时调用） */
    fun reset() {
        backfillRun.fill(0)
    }

    /** 每型棋子上限（与 BoardWatcher 的 piece cap 硬门一致） */
    private fun capOf(p: com.cheese.assistant.board.Piece): Int = when (p) {
        com.cheese.assistant.board.Piece.R_KING,
        com.cheese.assistant.board.Piece.B_KING -> 1
        com.cheese.assistant.board.Piece.R_PAWN,
        com.cheese.assistant.board.Piece.B_PAWN -> 5
        else -> 2
    }
}
