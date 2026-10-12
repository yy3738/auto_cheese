package com.cheese.assistant.recognize

import android.graphics.Bitmap
import com.cheese.assistant.board.Piece
import com.cheese.assistant.board.Position
import com.cheese.assistant.capture.ScreenCapture
import java.io.File

/**
 * 自动识别循环核心：等待画面稳定（连续两帧棋盘区域哈希一致，即对局动画
 * 结束的信号）再做完整识别。识别结果需无未定点、双王俱在、棋子数合理
 * 才可信，否则调用方稍后重试。哈希只覆盖校准网格外扩区域，状态栏时钟、
 * 悬浮信息条等棋盘外的动静不触发识别。
 */
class BoardWatcher(
    private val grid: BoardGrid,
    private val templates: TemplateStore,
    /** 非空时识别失败的原始 MediaProjection 帧落盘此目录（限 DUMP_LIMIT 张），供 PC 端对比诊断 */
    private val dumpDir: File? = null,
) {

    companion object {
        private const val DUMP_LIMIT = 5
        private const val QUIET_MS = 350L
        private const val POLL_TIMEOUT_MS = 250L
        private const val STABLE_TIMEOUT_MS = 8000L
        // FNV 已弃用，改用差分比较；采样点总数约 48x48=2304，
        // 允许差异点覆盖少量装饰动画像素
        private const val SAMPLE_STEPS = 48
        private const val MAX_DIFF_PIXELS = SAMPLE_STEPS * 2
        private const val MIN_PIECES = 10
        private const val BBOX_MARGIN = 20f
    }

    // 网格外扩包围盒（原始校准坐标即可，吸附偏差 ~8px 被余量覆盖）
    private val bbox: IntArray by lazy {
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (i in 0 until 90) {
            minX = minOf(minX, grid.xs[i]); maxX = maxOf(maxX, grid.xs[i])
            minY = minOf(minY, grid.ys[i]); maxY = maxOf(maxY, grid.ys[i])
        }
        arrayOf(minX - BBOX_MARGIN, minY - BBOX_MARGIN,
            maxX + BBOX_MARGIN, maxY + BBOX_MARGIN)
            .map { it.toInt().coerceAtLeast(0) }.toIntArray()
    }

    // 最近一次可信局面:缺王帧(王被光圈遮蔽)时用于判定是否遮蔽抖动
    private var lastGood: Observed? = null

    /**
     * 阻塞等待一个可信局面。返回 null 表示本次没有可信结果
     * （动画太久 / 识别有未定点 / 当前画面不是棋盘）。
     * 可信标准：无未定点 + 双方各恰好一个王 + 棋子总数合理。
     * 非棋盘画面（大厅/弹窗）可能凑齐棋子数但没有双王，必须挡住，
     * 否则缺王 FEN 喂给 Pikafish 会触发断言 abort 整个进程。
     */
    fun waitForPosition(timeoutMs: Long = STABLE_TIMEOUT_MS): Observed? {
        val bmp = ScreenCapture.captureStable(QUIET_MS, timeoutMs, ::sameRegion)
            ?: return null.also {
                android.util.Log.e("BoardWatcher", "waitForPosition: captureStable null")
            }
        val result = BoardRecognizer.recognize(bmp, grid, templates)
        if (result.hasUnknown) {
            android.util.Log.e("BoardWatcher", "waitForPosition: unknown ${result.unknown.count { it }}")
            android.util.Log.d("BoardWatcher", result.matrixText.replace("\n", " | "))
            dumpFrame(bmp)
            return null
        }
        val pos = normalizeOrientation(result.position)
        var redKings = 0
        var blackKings = 0
        var pieces = 0
        for (p in pos.board) {
            if (p != null) {
                pieces++
                if (p == Piece.R_KING) redKings++
                if (p == Piece.B_KING) blackKings++
            }
        }
        if (redKings != 1 || blackKings != 1 || pieces < MIN_PIECES) {
            android.util.Log.e("BoardWatcher",
                "waitForPosition: credibility fail redK=$redKings blackK=$blackKings pieces=$pieces")
            // 矩阵行=屏幕行(normalize 前),直接对照截图定位多出的假王
            android.util.Log.d("BoardWatcher", result.matrixText.replace("\n", " | "))
            // 王被光圈/将军特效遮蔽的帧(缺王)若相对上次可信局面只是
            // "消失",属遮蔽抖动:复用上次结果
            lastGood?.let { good ->
                if (MoveCheck.isVanishOnly(good.pos, pos)) {
                    android.util.Log.d("BoardWatcher", "king occluded, reuse last good")
                    return good
                }
                // 棋局在走时与旧局面差异大,"纯消失"不成立——但王被特效
                // 罩住判空时,场上恰好没有王而旧局面王格有王:把王放回去
                // (王不可能离开棋盘)。修复失败才放弃
                MoveCheck.restoreMissingKings(good.pos, pos)?.let { repaired ->
                    android.util.Log.d("BoardWatcher", "king occluded, restored")
                    return Observed(repaired, good.userIsRed)
                }
            }
            return null
        }
        android.util.Log.d("BoardWatcher", "position OK pieces=$pieces")
        return Observed(pos, bottomSideIsRed(result.position)).also { lastGood = it }
    }

    /** 识别成功的一帧：归一化局面 + 用户执方（屏幕最下一排的王色 = 用户） */
    class Observed(val pos: Position, val userIsRed: Boolean)

    /**
     * 识别矩阵 row9（屏幕最下一排）的王色 = 用户执方（约定"下面的肯定是我"）。
     * 找不到王（异常局面）默认红。
     */
    private fun bottomSideIsRed(raw: Position): Boolean {
        // 正常情况王在 row9；网格吸附偶发漂移一行时王在 row8——按"靠屏幕下方的王"取执方
        for (row in 9 downTo 8) {
            for (c in 0..8) {
                when (raw.board[row * 9 + c]) {
                    Piece.R_KING -> return true
                    Piece.B_KING -> return false
                    else -> Unit
                }
            }
        }
        android.util.Log.e("BoardWatcher", "bottomSideIsRed: no king in bottom rows, fallback red")
        return true
    }

    /** 识别失败帧落盘（app 内部存储），adb pull 出来给 PC 端 diag 对比 */
    private fun dumpFrame(bmp: Bitmap) {
        val dir = dumpDir ?: return
        runCatching {
            if ((dir.listFiles()?.size ?: 0) >= DUMP_LIMIT) return
            val f = File(dir, "mp_${System.currentTimeMillis()}.png")
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            android.util.Log.e("BoardWatcher", "dumpFrame: ${f.name}")
        }.onFailure { android.util.Log.e("BoardWatcher", "dumpFrame failed", it) }
    }

    /**
     * 方向归一化：识别矩阵 row0 = 屏幕最上排，而 Position/FEN 约定 row0 =
     * 黑方底线。第三方棋盘（如 JJ）可能让红方坐在屏幕上方，此时直接转
     * FEN 会把红仕放进黑方底线，被引擎以"非法局面"拒绝。按双王行位判断
     * 屏幕方向，红在上时上下翻转（列不变——物理棋盘 a 线总在屏幕左；
     * 若对方 App 的"翻转棋盘"改变网格布局，需重新校准）。
     */
    private fun normalizeOrientation(pos: Position): Position {
        val redRow = pos.board.indexOfFirst { it == Piece.R_KING } / 9
        val blackRow = pos.board.indexOfFirst { it == Piece.B_KING } / 9
        if (redRow > blackRow) return pos
        val flipped = Array(90) { i ->
            val r = i / 9
            val c = i % 9
            pos.board[(9 - r) * 9 + c]
        }
        return Position(flipped, pos.redToMove)
    }

    /**
     * 棋盘区域稳定性判定：降采样逐点比较，差异点比例低于阈值即视为稳定。
     * 对局画面常有持续微动画（选中光圈脉动、云影飘动），严格相等永远
     * 等不到；这些装饰动画只影响少数采样点，棋子挪动则影响一大片。
     */
    private fun sameRegion(a: Bitmap, b: Bitmap): Boolean {
        val ha = sampleRegion(a)
        val hb = sampleRegion(b)
        if (ha.size != hb.size) return false
        var diff = 0
        for (i in ha.indices) if (ha[i] != hb[i] && ++diff > MAX_DIFF_PIXELS) return false
        return true
    }

    /** 棋盘区域降采样像素灰度（量化到 8 级，抵消压缩噪声） */
    private fun sampleRegion(bmp: Bitmap): IntArray {
        val x0 = bbox[0]; val y0 = bbox[1]
        val w = (bbox[2] - bbox[0]).coerceAtMost(bmp.width - x0)
        val h = (bbox[3] - bbox[1]).coerceAtMost(bmp.height - y0)
        if (w <= 0 || h <= 0) return IntArray(0)
        val stepX = maxOf(1, w / SAMPLE_STEPS)
        val stepY = maxOf(1, h / SAMPLE_STEPS)
        val out = ArrayList<Int>(SAMPLE_STEPS * SAMPLE_STEPS)
        var y = y0
        while (y < y0 + h) {
            var x = x0
            while (x < x0 + w) {
                val c = bmp.getPixel(x, y)
                val r = c shr 16 and 0xFF
                val g = c shr 8 and 0xFF
                val bl = c and 0xFF
                out.add(((0.299f * r + 0.587f * g + 0.114f * bl) / 32f).toInt())
                x += stepX
            }
            y += stepY
        }
        return out.toIntArray()
    }
}

/**
 * 识别自动循环的走子校验：新局面与上一局面必须恰好是"一枚棋子从 A 到 B"
 * （可吃子），其余 88 点不变。用于过滤动画中间帧与识别抖动。
 */
object MoveCheck {

    /**
     * 返回该步移动者的颜色(isPlausibleStep 的信息版)。null = 不是合法单步。
     * 双向尝试:吃子时落点索引可能小于出发点(AI 居上吃下方子),
     * 固定"第一个差异=出发点"会因落点残留被吃子而误判。
     */
    fun stepMoverIsRed(prev: Position, cur: Position): Boolean? {
        var i = -1
        var j = -1
        for (k in 0 until 90) {
            if (prev.board[k] == cur.board[k]) continue
            if (i < 0) i = k
            else if (j < 0) j = k
            else return null
        }
        if (i < 0 || j < 0) return null
        for ((from, to) in listOf(i to j, j to i)) {
            val mover = prev.board[from] ?: continue
            if (cur.board[from] != null) continue
            if (cur.board[to] != mover) continue
            val captured = prev.board[to]
            if (captured != null && captured.side == mover.side) continue
            return mover.isRed
        }
        return null
    }

    fun isPlausibleStep(prev: Position, cur: Position): Boolean =
        stepMoverIsRed(prev, cur) != null

    /**
     * 光圈遮蔽判定：JJ 的行棋/落子光圈罩在棋子上时，"白占比判空"会把被罩
     * 棋子误判为空——该子随光圈脉动在有无之间翻转（实测红马 (7,2) 每个脉动
     * 周期 31↔32 子交替）。抖动是双向的：
     * - isVanishOnly：仅消失（≤2 处，无任何出现/变更）——被遮蔽帧，忽略；
     * - isAppearOnly：仅出现（≤2 处）——遮蔽恢复帧，新局面才是完整版，
     *   以新局面为准（否则锚点卡死在残缺版上，引擎按错误物力给怪招）。
     * 真实走子/吃子必然同时有"消失+出现/变更"两处差异，不会被误吞。
     */
    fun isVanishOnly(prev: Position, cur: Position): Boolean {
        var n = 0
        for (i in 0 until 90) {
            val a = prev.board[i]
            val b = cur.board[i]
            if (a == b) continue
            if (a == null || b != null || ++n > 2) return false
        }
        return n > 0
    }

    fun isAppearOnly(prev: Position, cur: Position): Boolean {
        var n = 0
        for (i in 0 until 90) {
            val a = prev.board[i]
            val b = cur.board[i]
            if (a == b) continue
            if (a != null || b == null || ++n > 2) return false
        }
        return n > 0
    }

    /**
     * 单点原地变更(棋子A→棋子B,双方非空):锚点帧光圈残缺误配的恢复特征
     * (实测新走的炮在锚点里被配成仕,光圈减弱后恢复成炮)。真实走子必然
     * 同时空出出发点(2处差异),不可能原地换子。以 cur 为准修正锚点。
     * 价值跳变(车↔卒等)由 isValueJumpRetag 单独识别,调用方分流处理。
     */
    fun isSingleRetag(prev: Position, cur: Position): Boolean {
        var n = 0
        for (i in 0 until 90) {
            val a = prev.board[i]
            val b = cur.board[i]
            if (a == b) continue
            if (a == null || b == null || ++n > 1) return false
        }
        return n == 1
    }

    /**
     * 价值跳变的单点变更:同色的车马炮↔卒士象原地互换。真实误配恢复的
     * 互换对是"作战子↔防御子"的近邻混淆(实测炮被配成仕,恢复帧改回),
     * 而"车→卒"这种远距离跳变只会来自点击选中时棋子被"抬起"的残缺帧
     * (实测采纳后毒FEN直接进引擎 set_position failed,悬浮窗卡死)。
     * 调用方应忽略该帧保留旧锚点:棋子放下/走开后识别自动恢复,
     * 真走子仍能被正常识别。
     */
    fun isValueJumpRetag(prev: Position, cur: Position): Boolean {
        var n = 0
        var from: Piece? = null
        var to: Piece? = null
        for (i in 0 until 90) {
            val a = prev.board[i]
            val b = cur.board[i]
            if (a == b) continue
            if (a == null || b == null || ++n > 1) return false
            from = a
            to = b
        }
        if (n != 1) return false
        val a = from!!
        val b = to!!
        // 单向:只拦"大子→小子"(毒出现)。"小子→大子"是抬起结束后
        // 的恢复帧,放行才能让毒锚点自愈(retag 两帧确认后采纳修正)
        return a.isRed == b.isRed &&
            MAJOR_PIECES.contains(a) && !MAJOR_PIECES.contains(b)
    }

    /**
     * 规则不可能的兵卒位置:黑卒起始行 3 只能前进到行 9,行 0-2 出现黑卒
     * 必是误配(实测点击选中棋子时 JJ 把棋子抬起,底行黑车被配成黑卒,
     * 毒 FEN 进引擎 set_position failed);红兵起始行 6 只能前进到行 0,
     * 行 7-9 出现红兵同理。调用方应整帧忽略:不锚定、不分析,锚点保持
     * 选前好帧,选中期间的残缺帧全部透明。
     */
    fun hasImpossiblePawn(pos: Position): Boolean {
        for (i in 0 until 90) {
            when (pos.board[i]) {
                Piece.B_PAWN -> if (i / 9 < 3) return true
                Piece.R_PAWN -> if (i / 9 > 6) return true
                else -> Unit
            }
        }
        return false
    }

    /**
     * 用锚点修复不可能兵卒位:选中棋子被 JJ"抬起"悬空在原格上方,轮廓
     * 变化使其被误读成兵/卒(实测黑车抬起→黑卒出现在规则不可能的行),
     * 每个稳定帧都被 hasImpossiblePawn 拦截,选中期间识别完全瘫痪。
     * 修复:不可能兵卒格若锚点在同格有非兵卒棋子,用锚点棋子替换回来
     * (棋子实际未动)。修复失败(无锚点/锚点同格不是棋子)返回 null。
     */
    fun repairImpossiblePawns(anchor: Position, pos: Position): Position? {
        val board = pos.board.copyOf()
        var changed = false
        for (i in 0 until 90) {
            val impossible = when (board[i]) {
                Piece.B_PAWN -> i / 9 < 3
                Piece.R_PAWN -> i / 9 > 6
                else -> false
            }
            if (!impossible) continue
            val fix = anchor.board[i]
            if (fix == null || fix == Piece.B_PAWN || fix == Piece.R_PAWN) return null
            board[i] = fix
            changed = true
        }
        return if (changed) Position(board, pos.redToMove) else null
    }

    /**
     * 用锚点修复缺失的王:将/帅被将军特效或行棋光圈持续罩住时判空或
     * 被误读成同色其他子(实测帥+白光圈→红炮),每帧都被"双王俱在"校验
     * 拒绝,识别完全瘫痪。修复:场上缺红王时看锚点红王格——为空或被
     * 红子占据(=王被误读)都放回红王;被黑子占据说明王真被吃(对局已
     * 结束),拒绝修复返回 null。
     */
    fun restoreMissingKings(anchor: Position, pos: Position): Position? {
        val hasRedKing = pos.board.any { it == Piece.R_KING }
        val hasBlackKing = pos.board.any { it == Piece.B_KING }
        if (hasRedKing && hasBlackKing) return null
        val board = pos.board.copyOf()
        if (!hasRedKing) {
            val i = anchor.board.indexOfFirst { it == Piece.R_KING }
            if (i < 0) return null
            val cur = board[i]
            if (cur != null && !cur.isRed) return null
            board[i] = Piece.R_KING
        }
        if (!hasBlackKing) {
            val i = anchor.board.indexOfFirst { it == Piece.B_KING }
            if (i < 0) return null
            val cur = board[i]
            if (cur != null && cur.isRed) return null
            board[i] = Piece.B_KING
        }
        return Position(board, pos.redToMove)
    }

    /** 车马——与卒/士/象的轮廓差异巨大,原地互换必是"抬起"残缺帧。
     *  炮除外:炮↔仕是实测存在的近邻误配恢复对,必须放行 */
    private val MAJOR_PIECES = setOf(
        Piece.R_ROOK, Piece.R_KNIGHT,
        Piece.B_ROOK, Piece.B_KNIGHT,
    )

    /**
     * 宽松步判定:与 isPlausibleStep 相同,但容忍出发点的误配残留——
     * 行棋光圈罩住刚挪空的出发点时,残缺结构会被误配成某个同色棋子
     * (实测卒走后出发点亮红圈被配成"士",2 处差异无法解释为任何单步,
     * 连吃 3 次 implausible 触发 reset 丢棋谱)。判定:恰好 2 处差异,
     * prev[from] 完整出现在 cur[to](同子),目标点原为空或敌子;
     * cur[from] 是什么(空=严格步,误配残留=光圈帧)不再苛求。
     */
    fun ghostMoverIsRed(prev: Position, cur: Position): Boolean? {
        var i = -1
        var j = -1
        for (k in 0 until 90) {
            if (prev.board[k] == cur.board[k]) continue
            if (i < 0) i = k
            else if (j < 0) j = k
            else return null
        }
        if (i < 0 || j < 0) return null
        // 两个差异点谁是出发点、谁是落点须双向尝试:
        // 差异点索引顺序 ≠ 走子方向(落点索引可以小于出发点)
        for ((from, to) in listOf(i to j, j to i)) {
            val mover = prev.board[from] ?: continue
            if (cur.board[to] != mover) continue
            val target = prev.board[to]
            if (target != null && target.side == mover.side) continue
            return mover.isRed
        }
        return null
    }

    fun isStepGhostOrigin(prev: Position, cur: Position): Boolean =
        ghostMoverIsRed(prev, cur) != null

    /**
     * 双步叠加分解：AI 秒回时"我方一步 + AI 一步"落在同一识别窗口，
     * 4 处差异不是任何单步。枚举第一步(firstIsRed 方)的所有单步移动，
     * 要求剩余差异恰好是对方的单步。返回中间局面；失败返回 null
     * （调用方走 implausible/reset 路径）。
     */
    fun tryTwoSteps(prev: Position, cur: Position, firstIsRed: Boolean): Position? {
        for (from in 0 until 90) {
            val mover = prev.board[from] ?: continue
            if (mover.isRed != firstIsRed) continue
            for (to in 0 until 90) {
                if (to == from) continue
                val captured = prev.board[to]
                if (captured != null && captured.isRed == firstIsRed) continue
                val midBoard = prev.board.copyOf()
                midBoard[from] = null
                midBoard[to] = mover
                val mid = Position(midBoard, !prev.redToMove)
                if (!isPlausibleStep(mid, cur)) continue
                // 第二步必须动对方棋子(isPlausibleStep 不校验颜色)。
                // 吃子情形下被吃子位置也满足 mid≠cur 且 mid 有子,
                // firstOrNull 会选到它而非移动者离开的点——须双向配对
                // 找真正的出发点(特征:cur[from2] 已腾空)
                val diffs = (0 until 90).filter { mid.board[it] != cur.board[it] }
                var mover2Red: Boolean? = null
                for ((from2, _) in listOf(diffs[0] to diffs[1], diffs[1] to diffs[0])) {
                    val mover2 = mid.board[from2] ?: continue
                    if (cur.board[from2] != null) continue
                    mover2Red = mover2.isRed
                    break
                }
                if (mover2Red == null || mover2Red == firstIsRed) continue
                return mid
            }
        }
        return null
    }
}
