package com.cheese.assistant.review

import com.cheese.assistant.board.Piece
import com.cheese.assistant.board.Position
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 棋谱中的一步：走法与走子前/后局面（复盘按"走子前局面"分析）。 */
data class MoveRecord(val iccs: String, val fenBefore: String, val fenAfter: String)

/**
 * 对局棋谱累积器：自动循环每识别出一步（恰好一子移动）就追加记录。
 * 执方判定按识别矩阵"屏幕最下一排（row9）的王色 = 用户"——识别矩阵 row0
 * 是屏幕最上排，与 FEN 的 row0=黑底线无关。手动切换直接改 userIsRed 字段。
 *
 * 持久化（2026-10-14）：进程会被系统整杀（实测复盘完成后 9 分钟静默死亡,
 * 整局棋谱随进程蒸发 →「结」提示尚无对局记录）。attachPersistence 后每次
 * start/record/reset 同步写 files/state/recorder-state.json,服务重启时
 * restoreFromDisk 恢复——进程死亡不再丢局。
 */
class GameRecorder {

    var userIsRed: Boolean = true
    var startedAt: Long = 0L
        private set
    private val moves = ArrayList<MoveRecord>()
    /** 尚未落 FEN 的最新局面（每步识别到新局面时更新） */
    private var lastPosition: Position? = null
    private var persistFile: File? = null
    /** 用户显式开了新局：空谱点「结」时不要用孤儿备份把上一局复活 */
    private var skipOrphanRestore = false

    val isEmpty: Boolean get() = moves.isEmpty()

    /** 首次识别到可信局面时调用：记开局时间与执方（可被手动覆盖） */
    @Synchronized
    fun start(pos: Position, userIsRed: Boolean) {
        if (startedAt == 0L) startedAt = System.currentTimeMillis()
        this.userIsRed = userIsRed
        if (moves.isEmpty()) lastPosition = pos
        persist()
    }

    @Synchronized
    fun setUserSide(userIsRed: Boolean) {
        this.userIsRed = userIsRed
        persist()
    }

    /**
     * 追加一步：prev（走子前）→ cur（走子后），两个都是归一化后的局面；
     * moverIsRed 为走子方（= 走子前轮走方），用于 FEN 的行棋字段。
     * 返回该步 ICCS（方向不可判时返回 null，调用方按识别抖动处理）。
     */
    @Synchronized
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
        skipOrphanRestore = false
        persist()
        return iccs
    }

    /** 重置（新对局/识别异常/复盘完成）——先备份未封存棋谱再清内存。
     *  explicitNewGame=true 表示用户手点「开局」/识别到完整 START：
     *  此时空谱点「结」不应把上一局孤儿备份复活。 */
    @Synchronized
    fun reset(explicitNewGame: Boolean = false) {
        backupOrphan()
        skipOrphanRestore = explicitNewGame
        startedAt = 0L
        moves.clear()
        lastPosition = null
        persistFile?.delete()
    }

    /**
     * 撤回最后一步识别(用户在实况页发现识别错时一键撤销):
     * 移除末步,lastPosition 回退到前一步的走后局面
     */
    @Synchronized
    fun undoLast(): Boolean {
        if (moves.isEmpty()) return false
        moves.removeAt(moves.size - 1)
        lastPosition = if (moves.isNotEmpty()) {
            Position.fromFen(moves.last().fenAfter)
        } else null
        persist()
        return true
    }

    /** 当前是否轮到用户走（基于最后记录的局面） */
    @Synchronized
    fun isUserToMove(): Boolean {
        val pos = lastPosition ?: return false
        return pos.redToMove == userIsRed
    }

    /** 最后一个局面的 FEN（走子后） */
    @Synchronized
    fun lastFen(): String? = lastPosition?.toFen()

    /** 当前局面（走子后；手动纠错以它为基准） */
    @Synchronized
    fun currentPosition(): Position? = lastPosition

    /**
     * 只更新"当前局面"，不动历史棋谱。用于识别与棋谱回放长期不一致
     * （漏记吃子）时以识别直读继续；实况页 match=false 会改画识别盘。
     */
    @Synchronized
    fun setCurrentPosition(pos: Position) {
        lastPosition = pos
        persist()
    }

    /**
     * 上一手之前的局面：有棋谱=末条的 fenBefore；无棋谱=当前局面。
     * 「改掉上一手」以它为基准重新记一步。
     */
    @Synchronized
    fun positionBeforeLast(): Position? {
        if (moves.isEmpty()) return lastPosition
        return Position.fromFen(moves.last().fenBefore)
    }

    /**
     * 手动纠错记一步（2026-10-22 浮窗四下拉）。
     * @param replaceLast true=改掉上一手（先移除末条再记）；
     *                    false=补上漏识别的一手（直接追加）
     * 返回 ICCS；盘面差异不是恰好一子移动时返回 null（调用方校验过合法性）
     */
    @Synchronized
    fun recordManual(prev: Position, cur: Position, moverIsRed: Boolean,
                     replaceLast: Boolean): String? {
        if (replaceLast && moves.isNotEmpty()) {
            moves.removeAt(moves.size - 1)
            lastPosition = prev
        }
        return record(prev, cur, moverIsRed)
    }

    @Synchronized
    fun moveCount(): Int = moves.size

    /** 棋谱快照 */
    @Synchronized
    fun movesSnapshot(): List<MoveRecord> = moves.toList()

    /** 末条记录（动画中间帧合并需读 fenBefore 还原走子前局面） */
    @Synchronized
    fun lastRecord(): MoveRecord? = moves.lastOrNull()

    /** 序列化为 JSON 存入 games.move_list_json */
    @Synchronized
    fun toJson(): String {
        val arr = JSONArray()
        for (m in moves) {
            arr.put(JSONObject().put("m", m.iccs).put("f", m.fenBefore).put("a", m.fenAfter))
        }
        return arr.toString()
    }

    /** 开启落盘持久化（服务启动时调用一次） */
    fun attachPersistence(dir: File) {
        dir.mkdirs()
        persistFile = File(dir, "recorder-state.json")
    }

    /** 进程死亡后恢复棋谱。有未封存记录返回 true（moves/执方/开局时间已还原） */
    @Synchronized
    fun restoreFromDisk(): Boolean {
        val f = persistFile ?: return false
        return runCatching {
            if (!f.exists()) {
                com.cheese.assistant.util.AppLog.d("GameRecorder", "restore: no state file")
                return false
            }
            val obj = JSONObject(f.readText())
            val list = moveListFrom(obj)
            if (list.isEmpty()) {
                com.cheese.assistant.util.AppLog.d("GameRecorder", "restore: state has no moves")
                return false
            }
            userIsRed = obj.getBoolean("u")
            startedAt = obj.optLong("s", System.currentTimeMillis())
            moves.addAll(list)
            lastPosition = obj.optString("last").takeIf { it.isNotEmpty() }
                ?.let { Position.fromFen(it) }
            com.cheese.assistant.util.AppLog.i("GameRecorder",
                "restore: ${moves.size} moves recovered")
            true
        }.onFailure {
            com.cheese.assistant.util.AppLog.e("GameRecorder", "restore failed", it)
        }.getOrDefault(false)
    }

    /** moves 的 JSON 兼容读:persist 曾将数组存成字符串(JSONObject.put(String)),
     *  optJSONArray 直接读为 null——两种形态都接;元素兼容对象{m,f,a}与数组 */
    private fun moveListFrom(obj: JSONObject): List<MoveRecord> {
        val raw = obj.optJSONArray("m")
            ?: runCatching { JSONArray(obj.optString("m")) }.getOrNull()
            ?: return emptyList()
        val list = ArrayList<MoveRecord>()
        for (i in 0 until raw.length()) {
            val rec = when (val item = raw.get(i)) {
                is JSONObject -> MoveRecord(
                    item.getString("m"), item.getString("f"), item.getString("a"))
                is JSONArray -> MoveRecord(item.getString(0), item.getString(1), item.getString(2))
                else -> null
            } ?: continue
            list.add(rec)
        }
        return list
    }

    /** 全量状态写盘（每步一次,量小,直接同步写） */
    @Synchronized
    private fun persist() {
        val f = persistFile ?: return
        runCatching {
            f.parentFile?.mkdirs()
            f.writeText(JSONObject()
                .put("u", userIsRed)
                .put("s", startedAt)
                .put("last", lastPosition?.toFen() ?: "")
                .put("m", toJson())
                .toString())
        }
    }

    /**
     * reset 前的孤儿备份:对局刚结束时结算页垃圾帧会触发"识别 3 次异常
     * 重置",把刚打完整局的棋谱在用户点「结」前几秒清掉。备份到
     * state/recorder-orphan.json(单文件覆盖),restoreOrphan 可在时限内救回
     */
    private fun backupOrphan() {
        val f = persistFile ?: return
        val dest = File(f.parentFile, "recorder-orphan.json")
        runCatching {
            // 只在有棋谱时覆盖备份;空 reset 绝不删备份(结算页垃圾帧的
            // 后续 reset 曾把刚备份的棋谱自毁,用户重开 app 点结时已空)
            if (moves.isNotEmpty()) {
                dest.writeText(JSONObject()
                    .put("u", userIsRed)
                    .put("s", startedAt)
                    .put("t", System.currentTimeMillis())
                    .put("last", lastPosition?.toFen() ?: "")
                    .put("m", toJson())
                    .toString())
            }
        }
    }

    /**
     * 从孤儿备份救回被重置清掉的一局。备份一旦写入就不过期,只在被
     * 封存消费、被下一局的备份覆盖、或新对局记录了走子时更新
     */
    @Synchronized
    fun restoreOrphan(): Boolean {
        if (skipOrphanRestore) return false
        val f = persistFile ?: return false
        return runCatching {
            val src = File(f.parentFile, "recorder-orphan.json")
            if (!src.exists()) return false
            val obj = JSONObject(src.readText())
            val list = moveListFrom(obj)
            if (list.isEmpty()) return false
            userIsRed = obj.getBoolean("u")
            startedAt = obj.optLong("s", System.currentTimeMillis())
            moves.addAll(list)
            lastPosition = obj.optString("last").takeIf { it.isNotEmpty() }
                ?.let { Position.fromFen(it) }
            src.delete()
            true
        }.getOrDefault(false)
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
