package com.cheese.assistant.recognize

import com.cheese.assistant.util.AppLog
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
    /** 会话开局即遇王误读(lastGood 未建立)时的王格锚点来源——棋谱回放局面 */
    private val kingFallback: (() -> Position?)? = null,
    /** 引导锚点来源(棋谱回放/recorder 基准/初始局面):lastGood 未建立时
     *  拒判格从它回填,替代"默认空格"。空格默认会把开局盲格的真实棋子
     *  抹掉(实测 2026-10-19 新皮肤 (0,8) 黑车从第 1 帧消失→整局推荐对
     *  敌方少车;自愈引导又抹掉双车触发 implausible×3 reset 毒化棋谱)
     */
    private val boardFallback: (() -> Position?)? = null,
    /**
     * 前台 App 判定(主轨 UsageStats, 2026-10-21): 返回
     * true=对局在前台 / false=非对局前台(暂停识别) / null=无法判定
     * (未授权)→ 交给边条指纹兜底。
     */
    private val foregroundCheck: (() -> Boolean?)? = null,
    /**
     * 逃生门回调:兜底锚点连续作废时触发。服务侧在此弃谱重建
     * (GameRecorder.reset 自带孤儿备份落盘,供事后定位问题)。
     * 用户确认方案:重建即弃谱(2026-10-19),毒谱留着也会把每次
     * 自愈拉回毒锚点,永不收敛
     */
    private val onSalvage: (() -> Unit)? = null,
    /**
     * 悬浮窗当前屏幕矩形(像素)：边条指纹采样时跳过。
     * 悬浮窗自身文字每帧变化，旧版把它采样进"前台是 JJ"的判据，
     * 导致每 20 秒一轮「前台非对局界面, 识别暂停」假暂停。
     */
    private val overlayBounds: (() -> android.graphics.Rect?)? = null,
) {

    companion object {
        private const val SKIP = -1000
        private const val DUMP_LIMIT = 5
        private const val QUIET_MS = 350L
        private const val POLL_TIMEOUT_MS = 250L
        private const val STABLE_TIMEOUT_MS = 8000L
        // FNV 已弃用，改用差分比较；采样点总数约 48x48=2304，
        // 允许差异点覆盖少量装饰动画像素
        private const val SAMPLE_STEPS = 48
        private const val MAX_DIFF_PIXELS = SAMPLE_STEPS * 2
        // 合法终局可以只剩双方王（2 子）；用双王+期望子数做可信门，
        // 固定下限会误杀真实残局（实测 9 子残局被拒整局）
        private const val MIN_PIECES = 2
        private const val BBOX_MARGIN = 20f
        /** 连续识别失败帧数达到此值 → 弃锚点自愈（等价手点"重试识别"） */
        private const val FAIL_RUN_RESET = 5
        // 拒判格像素差分：每格 4×4 采样点，量化 8 级灰度；差分点超过此值
        // 判定"该格发生真实变化"（对方走子落点）
        private const val CELL_SNAP_GRID = 4
        private const val CELL_SNAP_DIFF_LIMIT = 6
        // 引导锚点冲突门:兜底局面与本帧 CONFIDENT 草稿的冲突格数上限。
        // 真新局≈0-2(偶发高置信误读);残局/滞后者≥每步2格,超限即弃用
        private const val MAX_FALLBACK_CONFLICTS = 8
        /** 木色占比 ≥ 该值视为"该格仍有棋子"（实测棋子 0.56+，空位 0.02-） */
        const val WARMTH_PIECE_MIN = 0.25f
    }

        /** 连续识别失败计数（credibility/piece cap/unknown fail 共用） */
        private var failRun = 0

        /** 连续"全量重推仍失败"轮数：3 轮才弃锚点（不再第一轮就弃） */
        private var hardFailRun = 0

        /** 冻结/无新帧连续计数：投屏死亡自动停投屏走重授权 */
        private var frozenRun = 0

        /** JJ 对局页边条指纹(顶部条带, 底部条带): 非对局界面判定用 */
        private var edgeFp: Pair<IntArray, IntArray>? = null

        /** 前台非对局界面连续计数(自适应重学指纹用) */
        private var mismatchRun = 0

        /** 中局"期望有、本帧空"缺格集合的上一份快照（日志限频用） */
        private var lastMissingSkipKey = ""

        /** 期望局面冲突数的上一份快照（日志限频用） */
        private var lastExpectedConflictKey = ""

    /** 兜底锚点已被逃生门禁用（本轮识别会话内不再使用，重建成功后复位） */
    private var fallbackDisabled = false

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

    /**
     * 锚点帧状态：局面必须保持识别矩阵 raw 方向（未 normalizeOrientation）。
     * 旧版存 normalize 后的局面与 raw 草稿逐格比较——用户执黑（红在屏幕
     * 上方）时锚点被翻转，比较全部错位，回填的是翻转位置的值，执黑局
     * 识别表现为"永远识别中"（2026-10-18 定位）。
     */
    private class AnchorState(val raw: Position, val userIsRed: Boolean)

    private var lastGood: AnchorState? = null

    /** 锚点帧每格 4×4 灰度量化快照（90*16），拒判格像素差分判"真实变化"用 */
    private var anchorSnap: IntArray? = null

    /**
     * 阻塞等待一个可信局面。返回 null 表示本次没有可信结果
     * （动画太久 / 识别有未定点 / 当前画面不是棋盘）。
     * 可信标准：无未定点 + 双方各恰好一个王 + 棋子总数合理。
     * 非棋盘画面（大厅/弹窗）可能凑齐棋子数但没有双王，必须挡住，
     * 否则缺王 FEN 喂给 Pikafish 会触发断言 abort 整个进程。
     */
    /** 灰度量化(24 级步长) */
    private fun grayQ(c: Int): Int {
        val g = (0.299f * (c shr 16 and 0xFF) +
            0.587f * (c shr 8 and 0xFF) + 0.114f * (c and 0xFF)) / 24f
        return g.toInt()
    }

    /** 对局页边条采样: 顶部条带(30-350)+底部条带(1470-1822) 各 40 点。
     *  跳过悬浮窗自身占据的采样点与顶部正中(JJ 计时器)——它们每帧都在变，
     *  会把"前台是 JJ"误判成"非对局页面"并暂停识别 20s(2026-10-22 实测)。 */
    private fun edgeSample(bmp: Bitmap): Pair<IntArray, IntArray> {
        val top = IntArray(40) { SKIP }
        val bot = IntArray(40) { SKIP }
        val ob = overlayBounds?.invoke()
        var k = 0
        for (ty in 0 until 5) for (tx in 0 until 8) {
            val x = ((tx + 0.5f) * bmp.width / 8).toInt().coerceIn(0, bmp.width - 1)
            val ty0 = (30 + ty * 80).coerceAtMost(bmp.height - 1)
            val by0 = (1470 + ty * 88).coerceAtMost(bmp.height - 1)
            val topCenter = x in (bmp.width * 35 / 100)..(bmp.width * 65 / 100)
            if (!topCenter && (ob == null || !ob.contains(x, ty0))) {
                top[k] = grayQ(bmp.getPixel(x, ty0))
            }
            if (ob == null || !ob.contains(x, by0)) {
                bot[k] = grayQ(bmp.getPixel(x, by0))
            }
            k++
        }
        return top to bot
    }

    /** 量化值逐点一致率(|a-b|<=1 记一致; 双方都 SKIP 的点不计) */
    private fun matchRatio(a: IntArray, b: IntArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var m = 0
        var n = 0
        for (i in a.indices) {
            if (a[i] == SKIP || b[i] == SKIP) continue
            n++
            if (Math.abs(a[i] - b[i]) <= 1) m++
        }
        return if (n == 0) 1f else m.toFloat() / n
    }

    /** 最近一帧稳定画面：静止棋盘 ImageReader 不推新帧时复用它继续识别 */
    private var lastBmp: Bitmap? = null

    fun waitForPosition(timeoutMs: Long = STABLE_TIMEOUT_MS): Observed? {
        var prunedThisFrame = false
        // 前台判定主轨: 非对局前台 → 暂停(不截帧不喂引擎)
        if (foregroundCheck?.invoke() == false) {
            Thread.sleep(QUIET_MS)
            return null
        }
        // 实况页盖住棋盘时暂停识别(读到的是实况页自己渲染的盘)
        if (com.cheese.assistant.service.GameLiveState.livePageOpen) {
            Thread.sleep(QUIET_MS)
            return null
        }
        // 静止画面 ImageReader 不推新帧是正常现象（2026-10-20 教训）。
        // 旧版这里直接 return null → 对手思考/画面静止时悬浮窗一直
        // 「等待稳定棋盘画面」十几秒，用户以为卡死并点重试（2026-10-22
        // 实测重试把两步棋跳掉）。没有新帧就用上一帧继续识别，局面不变
        // 时循环会保持现有推荐。
        val fresh0 = ScreenCapture.captureFresh(QUIET_MS)
        val bmp0 = fresh0 ?: lastBmp ?: run {
            Thread.sleep(QUIET_MS)
            return null
        }
        if (fresh0 != null) lastBmp = fresh0
        // 前台 App 判定(2026-10-21): JJ 对局页边条指纹不符 = 当前不是
        // 对局界面(弈枰主页面/实况页/弹窗)——暂停识别, 不喂引擎不记谱。
        // 曾因此把主页面预览盘当棋盘读出 33-34 子假象并引发连环风暴。
        val fp = if (foregroundCheck == null || foregroundCheck.invoke() == null) edgeFp else null
        if (fp != null) {
            val cur = edgeSample(bmp0)
            val okTop = matchRatio(fp.first, cur.first) >= 0.8f
            val okBot = matchRatio(fp.second, cur.second) >= 0.8f
            if (!(okTop && okBot)) {
                mismatchRun++
                if (mismatchRun == 1 || mismatchRun % 40 == 0) {
                    AppLog.w("BoardWatcher", "前台非对局界面(边条指纹不符), 识别暂停 ×$mismatchRun")
                }
                if (mismatchRun >= 60) {
                    // 自适应重学: 长期不符可能是换桌/换皮肤, 学一次放行,
                    // 之后靠 credibility 硬门与落定观察期把关
                    AppLog.w("BoardWatcher", "非对局界面持续 60 帧, 重学边条指纹(自适应)")
                    edgeFp = cur
                    mismatchRun = 0
                }
                Thread.sleep(QUIET_MS)
                return null
            }
        }
        // 稳定等待: 连续两帧一致才识别(动画结束的信号, 识别时序的基础)
        val bmp = if (fresh0 == null) {
            // 没有新帧 = 画面静止，直接用上一帧，不再空等
            bmp0
        } else {
            ScreenCapture.captureStable(QUIET_MS, timeoutMs, ::sameRegion)
                ?: lastBmp
                ?: run {
                    AppLog.e("BoardWatcher", "waitForPosition: captureStable null")
                    return null
                }
        }
        lastBmp = bmp
        val result = BoardRecognizer.recognize(bmp, grid, templates)
        // 本帧像素快照只算一次：拒判变化判定/少子修复/超限核对/输出共用
        val frameSnap = cellSnapshot(bmp)
        // 每格木色占比：棋子是暖色木盘，空位是冷色石纹。用于少子修复与
        // "棋子无故消失"的像素裁决（0.56+ vs 0.00，实测分离度很高）
        val frameWarmth = cellWarmth(bmp)
        var pos = result.position
        // 本帧朝向只判一次：期望局面(raw) 与 normalize 都按它翻转
        val flip = shouldFlip(pos)
        if (result.hasUnknown || result.unknown.any { it }) {
        // 拒判格先走锚点仲裁：静态拒判格用锚点回填（带新鲜度
        // 记账），变化格上的拒判 → 走消失子推测/整帧作废。
        // lastGood 未建立(自愈/中途开启)时用 boardFallback(棋谱
        // 回放)做引导锚点——但兜底局面可能与真实盘面差太远(实测
        // 点「结」后残局棋盘上 START 兜底复活开局子,锚点永不通约,
        // 每帧推测失败 2026-10-19)。冲突门:与本帧 CONFIDENT 草稿
        // 逐格比对,冲突格多 = 兜底不可信,弃用回空格默认引导
        var anchor: Position? = lastGood?.raw
        var anchorIsFallback = false
        if (anchor == null && !fallbackDisabled) {
            // 兜底局面是 FEN/normalize 域 → 翻回 raw 再与本帧草稿逐格比较
            val fb = boardFallback?.invoke()?.let { orient(it, flip) }
            if (fb != null) {
                // 冲突门只在 CNN 有足够视野时有效:首帧近乎全盲(实测 84 格
                // 拒判仅 6 格置信)时冲突数必然小,START 兜底被放进残局棋盘,
                // 回填出垃圾局面 set_position failed,锚点永久带毒(2026-10-19
                // 实测"识别不稳定"整局)。盲帧一律不用兜底,回空格默认
                var confident = 0
                var conflicts = 0
                for (i in 0 until 90) {
                    if (result.confidence[i] == EngineResult.Confidence.CONFIDENT) {
                        confident++
                        if (pos.board[i] != fb.board[i]) conflicts++
                    }
                }
                if (confident >= 30 && conflicts <= MAX_FALLBACK_CONFLICTS) {
                    anchor = fb
                    anchorIsFallback = true
                } else if (fb != null) {
                    AppLog.w("BoardWatcher",
                        "board fallback rejected: confident=$confident conflicts=$conflicts")
                }
            }
        }
            val changed = BooleanArray(90)
            if (anchor != null) {
                // 拒判格草稿无值可比较：改用与锚点帧的像素差分判"该格是否
                // 真实变化"。旧版一律按静态处理，对方走子落点被回填旧值 →
                // 局面恒等于锚点 → 走子被吞、永不记账（2026-10-18 实测
                // 8 分钟零 step；落点原为空格时 backfillRun 不递增，连
                // 自愈都不触发）。石纹性拒判像素与锚点帧一致，照旧回填。
                val snap = anchorSnap
                val curSnap = if (snap != null) frameSnap else null
                for (i in 0 until 90) {
                    changed[i] = if (result.unknown[i]) {
                        curSnap != null && cellDiff(snap!!, curSnap, i) > CELL_SNAP_DIFF_LIMIT
                    } else {
                        anchor.board[i] != pos.board[i]
                    }
                }
            }
            // 无锚点时 changed 保持全 false：拒判格全部走 bootstrap 路径
            // （默认空格 + credibility 硬门把关），否则首帧锚点永远建不起来
            val draft = EngineResult(pos.board, result.confidence, result.matrixText)
            val resolved = AnchorRecovery.resolve(draft, anchor, changed,
                fallbackAnchor = anchorIsFallback)
            if (resolved == null) {
                AppLog.e("BoardWatcher", "waitForPosition: unknown ${result.unknown.count { it }} (anchor recovery failed)")
                AppLog.d("BoardWatcher", result.matrixText.replace("\n", " | "))
                dumpFrame(bmp)
                onFrameFailed(fallbackPoisoned = anchorIsFallback)
                return null
            }
            pos = resolved
        }
        // 期望局面可信门：与本帧高置信格冲突 > 阈值 = 过期/毒谱。
        // 不能拿它做子数门、补子或超限核对——否则真实盘面会被它永久拒绝
        // （2026-10-22 实测：棋谱回放 32 子、真实画面 29 子，每帧 countTooLow）。
        val expected = expectedRaw(pos)
        var expectedConflicts = 0
        if (expected != null) {
            for (i in 0 until 90) {
                if (result.confidence[i] == EngineResult.Confidence.CONFIDENT &&
                    expected.board[i] != pos.board[i]) expectedConflicts++
            }
        }
        val expectedBoard = expected?.takeIf { expectedConflicts <= MAX_FALLBACK_CONFLICTS }
        if (expectedBoard != null) {
            repairMissingExpected(expectedBoard, pos, frameWarmth)?.let { pos = it }
        } else if (expected != null) {
            val key = expectedConflicts.toString()
            if (key != lastExpectedConflictKey) {
                AppLog.w("BoardWatcher",
                    "expected board unreliable: conflicts=$expectedConflicts " +
                        "(> $MAX_FALLBACK_CONFLICTS), ignore for count/repair/cap")
                lastExpectedConflictKey = key
            }
        } else {
            lastExpectedConflictKey = ""
        }
        val expectedNorm = expectedBoard?.let { orient(it, flip) }
        val normalized = orient(pos, flip)
        var normalizedPruned = normalized
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
        // 终局合法性：不能再用固定 MIN_PIECES 拦（实测残局 9 子被当"非棋盘"
        // 每帧拒绝，纠错后悬浮窗永远不出推荐）。改为：双王必须齐全 +
        // 与本帧期望局面（棋谱回放/上次可信帧）棋子数相差不超过 2。
        // 期望不可信时不做相对比较；START 兜底在残局画面明显过期时也不比。
        var diffStart = 0
        for (i in 0 until 90) if (pos.board[i] != Position.START.board[i]) diffStart++
        val expectedCount = expectedBoard?.board?.count { it != null }
        val startFallbackStale =
            expectedBoard?.board?.contentEquals(Position.START.board) == true && diffStart > 6
        val countTooLow = expectedCount != null && !startFallbackStale &&
            pieces < expectedCount - 2
        if (redKings != 1 || blackKings != 1 || pieces < MIN_PIECES || countTooLow) {
            // 探针: 6 个采样点像素, 与 adb screencap 同点对拍——区分
            // "投屏冻结" 与 "识别冻结" (2026-10-20)
            val probe = (0 until 6).joinToString(" ") { k ->
                val px = 200 + k * 140
                val py = 600 + (k % 3) * 250
                Integer.toHexString(bmp.getPixel(px.coerceAtMost(bmp.width - 1),
                    py.coerceAtMost(bmp.height - 1)))
            }
            AppLog.e("BoardWatcher",
                "waitForPosition: credibility fail redK=$redKings blackK=$blackKings " +
                    "pieces=$pieces expected=$expectedCount conflicts=$expectedConflicts " +
                    "countTooLow=$countTooLow probe=$probe")
            // 全空帧(子数 0)连发多为界面切换(大厅/结算/重授权等待),
            // 识别层不做自动停投屏——曾把一切静止场景错杀成重授权
            // 循环(2026-10-20 回归, 已撤)。识别不出来由用户手动重试
            // 连续失败自愈: 先全量重推保留锚点, 3 轮仍失败才弃锚点
            onFrameFailed()
            // 矩阵行=屏幕行(normalize 前),直接对照截图定位多出的假王
            AppLog.d("BoardWatcher", result.matrixText.replace("\n", " | "))
            // 王被光圈/将军特效遮蔽的帧(缺王)若相对上次可信局面只是
            // "消失",属遮蔽抖动:复用上次结果。会话从未成功过(lastGood
            // 未建立)时,用棋谱回放局面兜底——否则开局王误读会死锁整局
            val anchorForKing = lastGood?.raw ?: kingFallback?.invoke()
            AppLog.d("BoardWatcher",
                "king fail: anchor=${anchorForKing != null} lastGood=${lastGood != null}")
            anchorForKing?.let { a ->
                // a 与 pos 都在 raw 识别矩阵方向，restore 与 diff 计数同域
                MoveCheck.restoreMissingKings(a, pos)?.let { repaired ->
                    // 门禁:恢复结果与锚点差异必须很小(王格误读+至多一两格)。
                    // 无门禁时大厅/结算页的任意画面被强塞双王,垃圾局面变成
                    // "position OK"挤占识别循环,真实对局的推荐反而被顶掉
                    // (2026-10-14 回归教训:王恢复必须带差异门禁)
                    var anchorDiffs = 0
                    for (i in 0 until 90) if (a.board[i] != repaired.board[i]) anchorDiffs++
                    if (anchorDiffs > 3) {
                        AppLog.w("BoardWatcher",
                            "king restore rejected: anchor diff=$anchorDiffs (非棋盘画面)")
                        return null
                    }
                    AppLog.d("BoardWatcher", "king occluded, restored" +
                        (if (lastGood == null) " (bootstrap)" else ""))
                    val userRed = lastGood?.userIsRed ?: MoveCheck.userIsRed(a)
                    lastGood = AnchorState(repaired, userRed)
                    anchorSnap = cellSnapshot(bmp)
                    failRun = 0
                    hardFailRun = 0
                    fallbackDisabled = false
                    return Observed(normalizeOrientation(repaired), userRed,
                        warmth = frameWarmth)
                }
            }
            return null
        }
        // 每色棋子上限：颜色误配(红车↔黑车)或残影会产生 3 车/3 马类非法局面。
        // 2026-10-22 用户确认：绝不按 margin 删真子（实测被剔的正是 (0,0)
        // 真黑车）。顺序：期望局面核对 → 仍超限整帧作废 + 强制重推。
        val snapCurCap = if (anchorSnap != null) cellSnapshot(bmp) else null
        for (p in Piece.entries) {
            val cap = when (p) {
                Piece.R_KING, Piece.B_KING -> 1
                Piece.R_PAWN, Piece.B_PAWN -> 5
                else -> 2
            }
            var over = normalizedPruned.board.count { it == p } - cap
            if (over <= 0) continue
            // ① 期望局面核对：期望格不是 p（空/异子）且像素静止 → 按期望改回。
            //    首帧没有像素快照时不猜颜色，宁可整帧作废也不删真子。
            if (expectedNorm != null) {
                val b = normalizedPruned.board.copyOf()
                var restored = 0
                var phantomRemoved = false
                for (i in 0 until 90) {
                    if (over <= 0) break
                    if (b[i] != p) continue
                    val e = expectedNorm.board[i]
                    if (e == p) continue
                    val static = snapCurCap != null && anchorSnap != null &&
                        cellDiff(anchorSnap!!, snapCurCap, i) <= CELL_SNAP_DIFF_LIMIT
                    // 只认"像素没动过"的证据：首帧没有锚点快照时不猜颜色，
                    // 宁可整帧作废，也不能基于过期期望删掉真子
                    if (static) {
                        b[i] = e
                        over--
                        restored++
                        if (e == null) phantomRemoved = true
                        AppLog.w("BoardWatcher",
                            "piece cap $p: expected restore (${i / 9},${i % 9}) → " +
                                (e?.cnName ?: "空") + " [static]")
                    }
                }
                if (restored > 0) {
                    normalizedPruned = Position(b, normalizedPruned.redToMove)
                    // 只有"删掉期望为空的幻影"才需要让确认期多等一帧；
                    // 颜色/同形核对是确定性的，不能每帧都挡住确认
                    if (phantomRemoved) prunedThisFrame = true
                }
            }
            if (over <= 0) continue
            // ② 仍超限：整帧拒收 + 强制重推这些格；绝不删子
            val cells = (0 until 90).filter { normalizedPruned.board[it] == p }
            AppLog.e("BoardWatcher",
                "piece cap $p=${cells.size} > $cap, reject frame; cells=" +
                    cells.joinToString { "(${it / 9},${it % 9}):" +
                        (result.margins?.get(it)?.toString()?.take(5) ?: "?") })
            AppLog.d("BoardWatcher", result.matrixText.replace("\n", " | "))
            dumpFrame(bmp)
            RecognizerEngineFactory.invalidate(cells)
            onFrameFailed()
            return null
        }
        failRun = 0
        hardFailRun = 0
        fallbackDisabled = false
        frozenRun = 0
        val finalPieces = normalizedPruned.board.count { it != null }
        AppLog.d("BoardWatcher", "position OK pieces=$finalPieces")
        if (edgeFp == null) {
            edgeFp = edgeSample(bmp0)
            AppLog.i("BoardWatcher", "边条指纹已记录(对局页基线, 依据成功识别帧)")
        }
        val userRed = MoveCheck.userIsRed(pos)
        val snapCur = cellSnapshot(bmp)
        val snapAnchor = anchorSnap
        // 锚点存最终盘面：旧版存 pre-prune 的 pos，3 红车的毒会带进下一帧
        lastGood = AnchorState(orient(normalizedPruned, flip), userRed)
        anchorSnap = snapCur
        return Observed(normalizedPruned, userRed, prunedThisFrame,
            snapCur.clone(), snapAnchor?.clone(), frameWarmth)
    }

    /** 识别成功的一帧：归一化局面 + 用户执方 + 裁剪/快照上下文 */
    class Observed(
        val pos: Position,
        val userIsRed: Boolean,
        val prunedThisFrame: Boolean = false,
        val snapCur: IntArray? = null,
        val snapAnchor: IntArray? = null,
        /** 每格木色占比（raw 域）：棋子 0.56+，空位 0.02- */
        val warmth: FloatArray? = null,
    )

    /** 每格中心 4×4 采样（覆盖约整格），灰度量化 8 级，抵消压缩/云影噪声 */
    private fun cellSnapshot(bmp: Bitmap): IntArray {
        val n = CELL_SNAP_GRID * CELL_SNAP_GRID
        val out = IntArray(90 * n)
        val half = (CELL_SNAP_GRID - 1) / 2f
        val step = (grid.cellSize / (CELL_SNAP_GRID - 1)).toInt().coerceAtLeast(1)
        for (i in 0 until 90) {
            val cx = grid.xs[i].toInt()
            val cy = grid.ys[i].toInt()
            for (dy in 0 until CELL_SNAP_GRID) {
                for (dx in 0 until CELL_SNAP_GRID) {
                    val x = (cx + (dx - half) * step).toInt().coerceIn(0, bmp.width - 1)
                    val y = (cy + (dy - half) * step).toInt().coerceIn(0, bmp.height - 1)
                    val c = bmp.getPixel(x, y)
                    val g = 0.299f * (c shr 16 and 0xFF) + 0.587f * (c shr 8 and 0xFF) +
                        0.114f * (c and 0xFF)
                    out[i * n + dy * CELL_SNAP_GRID + dx] = (g / 32f).toInt()
                }
            }
        }
        return out
    }

    fun cellDiff(a: IntArray, b: IntArray, cell: Int): Int {
        val n = CELL_SNAP_GRID * CELL_SNAP_GRID
        val o = cell * n
        var d = 0
        for (k in 0 until n) if (a[o + k] != b[o + k]) d++
        return d
    }

    /**
     * 每格木色占比：中心 60% 的 5×5 采样中 `R-B>40` 的比例。
     * 棋子=暖色木盘（实测 0.56-1.00），空位=冷色石纹（0.00-0.02）。
     * 用于"期望有子、本帧空"到底是漏读还是真被吃。
     */
    private fun cellWarmth(bmp: Bitmap): FloatArray {
        val out = FloatArray(90)
        val n = 5
        val step = grid.cellSize * 0.60f / (n - 1)
        for (i in 0 until 90) {
            val cx = grid.xs[i]
            val cy = grid.ys[i]
            var warm = 0
            for (dy in 0 until n) for (dx in 0 until n) {
                val x = (cx + (dx - 2) * step).toInt().coerceIn(0, bmp.width - 1)
                val y = (cy + (dy - 2) * step).toInt().coerceIn(0, bmp.height - 1)
                val c = bmp.getPixel(x, y)
                if (((c shr 16 and 0xFF) - (c and 0xFF)) > 40) warm++
            }
            out[i] = warm / (n * n).toFloat()
        }
        return out
    }

    /**
     * 连续失败自愈（2026-10-22 改）：先让懒推理全量重推，保留锚点；
     * 连续 3 轮（≈15 帧）仍失败才丢锚点。旧版第 5 帧就丢锚点，
     * 而错误分类往往正是被懒缓存冻结的——先重推比丢锚点更对症。
     *
     * @param fallbackPoisoned 本帧锚点来自棋谱兜底：多轮失败说明兜底
     *        局面本身是毒的（旧棋谱/残局），走逃生门弃谱重建。
     */
    private fun onFrameFailed(fallbackPoisoned: Boolean = false) {
        if (++failRun < FAIL_RUN_RESET) return
        failRun = 0
        RecognizerEngineFactory.invalidateAll()
        AppLog.w("BoardWatcher",
            "连续失败 $FAIL_RUN_RESET 帧, 全量重推(保留锚点)")
        if (++hardFailRun >= 3) {
            hardFailRun = 0
            if (lastGood != null) {
                AppLog.w("BoardWatcher", "连续 3 轮全量重推仍失败, 重置锚点自愈")
                lastGood = null
            } else if (fallbackPoisoned && !fallbackDisabled) {
                fallbackDisabled = true
                AppLog.e("BoardWatcher",
                    "fallback anchor poisoned, disable fallback, rebuild from bare CNN view")
                onSalvage?.invoke()
            }
        }
    }

    /** 识别失败帧落盘（app 内部存储），adb pull 出来给 PC 端 diag 对比。
     *  轮转删除最旧——固定上限若不轮转, 早期帧永久占坑, 后续失败帧
     *  全部丢失(2026-10-17 实测 18:49 的 5 张占满, 20:32 的关键帧没存到) */
    private fun dumpFrame(bmp: Bitmap) {
        val dir = dumpDir ?: return
        runCatching {
            val files = dir.listFiles()?.sortedBy { it.name } ?: return
            if (files.size >= DUMP_LIMIT) files.first().delete()
            val f = File(dir, "mp_${System.currentTimeMillis()}.png")
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            AppLog.e("BoardWatcher", "dumpFrame: ${f.name}")
        }.onFailure { AppLog.e("BoardWatcher", "dumpFrame failed", it) }
    }

    /**
     * 方向归一化：识别矩阵 row0 = 屏幕最上排，而 Position/FEN 约定 row0 =
     * 黑方底线。第三方棋盘（如 JJ）可能让红方坐在屏幕上方，此时直接转
     * FEN 会把红仕放进黑方底线，被引擎以"非法局面"拒绝。按双王行位判断
     * 屏幕方向，红在上时上下翻转（列不变——物理棋盘 a 线总在屏幕左；
     * 若对方 App 的"翻转棋盘"改变网格布局，需重新校准）。
     */
    private fun normalizeOrientation(pos: Position): Position =
        orient(pos, shouldFlip(pos))

    /** 本帧是否红在屏幕上方（需要上下翻转） */
    private fun shouldFlip(pos: Position): Boolean {
        val redRow = pos.board.indexOfFirst { it == Piece.R_KING } / 9
        val blackRow = pos.board.indexOfFirst { it == Piece.B_KING } / 9
        return !(redRow > blackRow)
    }

    /** 按同一 flip 决定翻转局面——期望局面必须与本帧同域(raw)比较 */
    private fun orient(pos: Position, flip: Boolean): Position {
        if (!flip) return pos
        val flipped = Array(90) { i ->
            val r = i / 9
            val c = i % 9
            pos.board[(9 - r) * 9 + c]
        }
        return Position(flipped, pos.redToMove)
    }

    /**
     * 期望局面（raw 域）：优先棋谱回放/recorder 基准/START，其次上一可信帧。
     *
     * 为什么 fallback 优先于 lastGood：lastGood 只是"上一帧被接受的盘面"，
     * 它本身可能已经被误读污染；棋谱回放是经过走子合法性校验的记录。
     * fallback 返回的是 normalize 后的局面（FEN 域），按本帧王的朝向翻回 raw。
     */
    private fun expectedRaw(pos: Position): Position? {
        val flip = shouldFlip(pos)
        boardFallback?.invoke()?.let { return orient(it, flip) }   // FEN 域 → raw
        lastGood?.raw?.let { return it }                            // 已是 raw
        kingFallback?.invoke()?.let { return orient(it, flip) }     // FEN 域 → raw
        return null
    }

    /**
     * 用期望局面补齐"无故消失"的棋子（少子根因，2026-10-22）。
     *
     * 只在以下条件同时满足时生效：
     * - 本帧棋子集合是期望的子集：期望有子→本帧空（漏读）才补；
     *   本帧多出期望没有的子 = 可能是真实走子落点，交给走子校验，不补；
     *   同格异子 = 冲突，交给颜色门/超限核对，不在这里猜。
     * - 缺失格数 1..3：一步走子最多让 1-2 格变化，超过说明期望本身过期。
     *
     * 象棋里棋子不会凭空消失（只能被吃，且吃子会在落点出现敌子），
     * 所以"期望有、本帧空"一定是识别漏读。修复只补回期望值。
     */
    private fun repairMissingExpected(
        expected: Position, pos: Position, warmth: FloatArray,
    ): Position? {
        val repaired = BoardRepair.restoreMissing(expected, pos) ?: return null
        val b = repaired.board.copyOf()
        val restored = ArrayList<Int>()
        val skipped = ArrayList<Int>()
        for (i in 0 until 90) {
            if (pos.board[i] != null || repaired.board[i] == null) continue
            if (warmth[i] >= WARMTH_PIECE_MIN) {
                restored.add(i)                 // 该格仍是木色棋子：真漏读
            } else {
                b[i] = null                     // 已是冷色空盘：真被吃/走开
                skipped.add(i)
            }
        }
        if (restored.isEmpty()) {
            val key = skipped.joinToString(",")
            if (skipped.isNotEmpty() && key != lastMissingSkipKey) {
                AppLog.w("BoardWatcher",
                    "expected restore skipped (cold/empty cells): " +
                        skipped.joinToString { "(${it / 9},${it % 9})${expected.board[it]?.cnName}" })
            }
            lastMissingSkipKey = key
            return null
        }
        if (skipped.isNotEmpty()) {
            AppLog.w("BoardWatcher", "expected restore skipped cold: " +
                skipped.joinToString { "(${it / 9},${it % 9})" })
        }
        AppLog.w("BoardWatcher", "expected-piece restore: " +
            restored.joinToString { "(${it / 9},${it % 9})${b[it]?.cnName}" })
        lastMissingSkipKey = ""
        return Position(b, pos.redToMove)
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
     * 用户执方：屏幕矩阵中两个王行号较大（靠屏幕下方）的王 = 用户。
     * 不限定扫描窗（旧版只看 row9/row8）：残局用户的將可以合法走到九宫
     * 顶线（row7），限定窗会落空——旧版此时静默 fallback 成红方，执方
     * 整体翻转（2026-10-12 实测"红方回合"误报的根因）。调用前局面已过
     * 双王俱在校验，两王必然存在，无需兜底。
     */
    fun userIsRed(raw: Position): Boolean {
        var redRow = -1
        var blackRow = -1
        for (i in 0 until 90) {
            when (raw.board[i]) {
                Piece.R_KING -> redRow = i / 9
                Piece.B_KING -> blackRow = i / 9
                else -> Unit
            }
        }
        return redRow > blackRow
    }

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
     * 严格单步 + 象棋规则校验: 恰好 2 处差异、移动者身份吻合、且
     * XiangqiRules 判定是合法走法时返回 (from,to); 否则 null。
     * 用途: 记谱前把关——棋规上不可能的"瞬移"(实测黑马 (0,1)→(0,7)
     * 后排瞬移 7 格被记进棋谱, 推演盘黑车凭空消失, 2026-10-21)在
     * 入谱前拦下, 落入推测/作废路径。
     */
    fun legalStepPair(prev: Position, cur: Position): Pair<Int, Int>? {
        var i = -1
        var j = -1
        for (k in 0 until 90) {
            if (prev.board[k] == cur.board[k]) continue
            if (i < 0) i = k else if (j < 0) j = k else return null
        }
        if (i < 0 || j < 0) return null
        for ((from, to) in listOf(i to j, j to i)) {
            val mover = prev.board[from] ?: continue
            if (cur.board[from] != null) continue
            if (cur.board[to] != mover) continue
            val captured = prev.board[to]
            if (captured != null && captured.side == mover.side) continue
            if (XiangqiRules.canMove(prev.board, from, to)) return from to to
        }
        return null
    }

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
     * 用锚点修复缺失的王:将/帅被将军特效或行棋光圈持续罩住时判空或被
     * 误读成其他子(实测帥+白光圈→红炮,又见红帥→黑炮的异色误读),每帧
     * 都被"双王俱在"校验拒绝,识别完全瘫痪。修复:锚点王格无条件放回王
     * 并清除该格误读子——"王真被吃(异色子占王格)则拒绝"的旧规则会卡死
     * 识别(王不可能在对局中被吃:JJ 王被将死即入结算页,识别已无意义);
     * 误放回的代价只是锚点回到上一可信局面,走子校验会继续把关。
     */
    fun restoreMissingKings(anchor: Position, pos: Position): Position? {
        val hasRedKing = pos.board.any { it == Piece.R_KING }
        val hasBlackKing = pos.board.any { it == Piece.B_KING }
        if (hasRedKing && hasBlackKing) return null
        val board = pos.board.copyOf()
        if (!hasRedKing) {
            val i = anchor.board.indexOfFirst { it == Piece.R_KING }
            if (i < 0) return null
            board[i] = Piece.R_KING
        }
        if (!hasBlackKing) {
            val i = anchor.board.indexOfFirst { it == Piece.B_KING }
            if (i < 0) return null
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
