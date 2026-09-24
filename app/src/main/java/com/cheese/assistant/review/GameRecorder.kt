package com.cheese.assistant.review

import com.cheese.assistant.board.Piece
import com.cheese.assistant.board.Position
import org.json.JSONArray
import org.json.JSONObject

/** 棋谱中的一步：走法与走子前/后局面（复盘按"走子前局面"分析）。 */
data class MoveRecord(val iccs: String, val fenBefore: String, val fenAfter: String)

/**
 * 对局棋谱累积器：自动循环每识别出一步（恰好一子移动）就追加记录。
 * 执方判定按识别矩阵"屏幕最下一排（row9）的王色 = 用户"——识别矩阵 row0
 * 是屏幕最上排，与 FEN 的 row0=黑底线无关。手动切换直接改 userIsRed 字段。
 */
class GameRecorder {

    var userIsRed: Boolean = true
    var startedAt: Long = 0L
        private set
    private val moves = ArrayList<MoveRecord>()
    /** 尚未落 FEN 的最新局面（每步识别到新局面时更新） */
    private var lastPosition: Position? = null

    val isEmpty: Boolean get() = moves.isEmpty()

    /** 首次识别到可信局面时调用：记开局时间与执方（可被手动覆盖） */
    fun start(pos: Position, userIsRed: Boolean) {
        if (startedAt == 0L) startedAt = System.currentTimeMillis()
        this.userIsRed = userIsRed
        if (moves.isEmpty()) lastPosition = pos
    }

    fun setUserSide(userIsRed: Boolean) { this.userIsRed = userIsRed }

    /**
     * 追加一步：prev（走子前）→ cur（走子后），两个都是归一化后的局面；
     * moverIsRed 为走子方（= 走子前轮走方），用于 FEN 的行棋字段。
     * 返回该步 ICCS（方向不可判时返回 null，调用方按识别抖动处理）。
     */
    fun record(prev: Position, cur: Position, moverIsRed: Boolean): String? {
        var from = -1
        var to = -1
        for (i in 0 until 90) {
            if (prev.board[i] == cur.board[i]) continue
            if (cur.board[i] == null) { from = i }      // 原有子、现没了 = 起点
            else { to = i }                              // 现有子、原先没有 = 终点
        }
        if (from < 0 || to < 0) return null
        val iccs = "${square(from)}${square(to)}"
        moves.add(MoveRecord(
            iccs,
            Position(prev.board, moverIsRed).toFen(),
            Position(cur.board, !moverIsRed).toFen(),
        ))
        lastPosition = cur
        return iccs
    }

    /** 重置（新对局/识别 3 次异常）——只清内存，不封存 */
    fun reset() {
        startedAt = 0L
        moves.clear()
        lastPosition = null
    }

    /** 当前是否轮到用户走（基于最后记录的局面） */
    fun isUserToMove(): Boolean {
        val pos = lastPosition ?: return false
        return pos.redToMove == userIsRed
    }

    /** 最后一个局面的 FEN（走子后） */
    fun lastFen(): String? = lastPosition?.toFen()

    fun moveCount(): Int = moves.size

    /** 封存用：棋谱快照 */
    fun movesSnapshot(): List<MoveRecord> = moves.toList()

    /** 序列化为 JSON 存入 games.move_list_json */
    fun toJson(): String {
        val arr = JSONArray()
        for (m in moves) {
            arr.put(JSONObject().put("m", m.iccs).put("f", m.fenBefore).put("a", m.fenAfter))
        }
        return arr.toString()
    }

    companion object {
        /** index 0..89 → ICCS 坐标（col a-i, row 0-9；row0=黑底 = FEN 第一行） */
        fun square(index: Int): String {
            val col = 'a' + index % 9
            val row = index / 9
            return "$col${9 - row}"
        }
    }
}
