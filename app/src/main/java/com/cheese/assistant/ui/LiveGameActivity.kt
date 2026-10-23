package com.cheese.assistant.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.cheese.assistant.R
import com.cheese.assistant.board.Position
import com.cheese.assistant.service.GameLiveState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 对局实况页:核对识别的入口。
 * - 棋盘按走过的招法回放摆盘(识别错误时棋谱忠实呈现,识别只作对照)
 * - 棋盘可手动纠正:点棋子→点目标格移动,改完自动按纠正局面重算推荐
 *   (经由服务的 REANALYZE 动作,与识别循环共用同一引擎,互斥由其管理)
 * - 手动纠正后本页不再跟随回放同步(否则会覆盖纠正),离开页面即失效
 */
class LiveGameActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }
    private var editScheduled = false
    private var manualEdited = false
    private var reanalyzeScheduled = Runnable { run() }

    private fun run() {
        editScheduled = false
        reanalyze()
    }
    private lateinit var boardView: com.cheese.assistant.ui.BoardView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_live)
        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }
        boardView = findViewById(R.id.boardView)

        // BoardView 不自带纵横比锁定:网格按 w/9×h/10 变形,按宽度定高
        boardView.post {
            boardView.layoutParams = boardView.layoutParams.apply {
                height = boardView.width * 10 / 9
            }
            boardView.requestLayout()
        }

        // 手动纠正:棋盘一变,防抖 800ms 后送服务按纠正局面重算推荐
        boardView.onPositionChanged = {
            manualEdited = true
            editScheduled = true
            handler.removeCallbacks(reanalyzeScheduled)
            handler.postDelayed(reanalyzeScheduled, 800)
        }

        findViewById<Button>(R.id.btnUndoStep).setOnClickListener {
            startService(Intent(this, com.cheese.assistant.service.AssistantService::class.java)
                .setAction(com.cheese.assistant.service.AssistantService.Action.UNDO_STEP))
            Toast.makeText(this, "已撤回最后一步识别", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        GameLiveState.livePageOpen = true
        manualEdited = false
        refresh()
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        GameLiveState.livePageOpen = false
        super.onPause()
    }

    /** 1s 轮询:棋谱/推荐/对照;棋盘仅在无手动纠正时跟随回放 */
    private fun refresh() {
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date(GameLiveState.updatedAt))
        findViewById<TextView>(R.id.tvStamp).text = "对局实况 · 更新于 $stamp"

        findViewById<TextView>(R.id.tvCandidates).text =
            GameLiveState.candidateLines.joinToString("\n").ifEmpty { "等待识别..." }
        findViewById<TextView>(R.id.tvMoves).text =
            GameLiveState.moveLines.joinToString("\n").ifEmpty { "(无记录)" }

        val matchView = findViewById<TextView>(R.id.tvMatch)
        val replayOk = GameLiveState.replayFen.isNotEmpty()
        when {
            !replayOk && GameLiveState.fen.isEmpty() -> {
                matchView.text = "识别对照：等待识别..."
                matchView.setTextColor(getColor(R.color.ink_soft))
            }
            !replayOk -> {
                matchView.text = "识别直读（本局尚无棋谱记录，棋盘为识别局面）"
                matchView.setTextColor(getColor(R.color.ink_soft))
            }
            GameLiveState.match == true -> {
                matchView.text =
                    "识别对照：与棋谱一致 ✓(${GameLiveState.moveLines.size}步)"
                matchView.setTextColor(getColor(R.color.ink_soft))
            }
            else -> {
                matchView.text = "⚠ 棋谱与识别不一致——棋盘已切为识别直读" +
                    "(幽灵步污染过的棋谱推演不可信, 2026-10-21)"
                matchView.setTextColor(getColor(R.color.accent_red))
            }
        }

        // 有手动纠正(或纠正防抖中)不动棋盘,否则覆盖用户的纠正。
        // 渲染策略(2026-10-21): 棋谱一致 → 画推演盘; 不一致/无棋谱 →
        // 画识别直读盘。旧策略"永远以棋谱为准"在棋谱被幽灵步污染后
        // 会画出"黑 车凭空消失"的假盘(实测左上角黑车消失)
        if (manualEdited || editScheduled) return
        val trusted = GameLiveState.match == true
        val fenForBoard = if (trusted) GameLiveState.replayFen.ifEmpty { GameLiveState.fen }
                          else GameLiveState.fen
        if (fenForBoard.isEmpty()) return
        if (fenForBoard.isEmpty()) return
        val target = Position.fromFen(fenForBoard) ?: return
        if (!boardView.position.board.contentEquals(target.board)) {
            boardView.position = target
        }
    }

    /** 手动纠正→按纠正局面重算(fen 自带行棋方,沿用识别判定的轮走方) */
    private fun reanalyze() {
        val fen = boardView.position.toFen()
        if (GameLiveState.fen.isEmpty()) return
        startService(Intent(this, com.cheese.assistant.service.AssistantService::class.java)
            .setAction(com.cheese.assistant.service.AssistantService.Action.REANALYZE)
            .putExtra(com.cheese.assistant.service.AssistantService.Extra.FEN, fen))
        Toast.makeText(this, "已按纠正局面重算推荐", Toast.LENGTH_SHORT).show()
    }
}
