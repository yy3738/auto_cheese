package com.cheese.assistant

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.cheese.assistant.board.MoveFormatter
import com.cheese.assistant.board.Piece
import com.cheese.assistant.board.Position
import com.cheese.assistant.engine.SearchController
import com.cheese.assistant.service.AssistantService
import com.cheese.assistant.ui.BoardView
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var boardView: BoardView
    private lateinit var resultView: TextView
    private lateinit var piecePicker: Spinner
    private lateinit var fenView: EditText
    private lateinit var btnAnalyze: Button

    private var controller: SearchController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        boardView = findViewById(R.id.boardView)
        resultView = findViewById(R.id.resultView)
        piecePicker = findViewById(R.id.piecePicker)
        fenView = findViewById(R.id.fenView)
        btnAnalyze = findViewById(R.id.btnAnalyze)

        // 棋子画笔选择
        val pieces = Piece.entries.map { it.cnName + if (it.isRed) "红" else "黑" }
        piecePicker.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("(擦除)") + pieces)

        boardView.onPositionChanged = {
            fenView.setText(boardView.position.toFen())
        }

        findViewById<Button>(R.id.btnReset).setOnClickListener {
            boardView.position = Position.START
            fenView.setText(boardView.position.toFen())
        }
        findViewById<Button>(R.id.btnClear).setOnClickListener {
            boardView.position = Position(Array(90) { null }, boardView.position.redToMove)
            fenView.setText(boardView.position.toFen())
        }
        findViewById<Button>(R.id.btnFlipSide).setOnClickListener {
            boardView.position = Position(boardView.position.board, !boardView.position.redToMove)
            fenView.setText(boardView.position.toFen())
        }

        btnAnalyze.setOnClickListener { analyze() }
        findViewById<Button>(R.id.btnOverlay).setOnClickListener { toggleOverlay() }
    }

    /** 悬浮窗模式：授权检查 → 启动前台服务，由服务持有引擎和信息条 */
    private fun toggleOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
            Toast.makeText(this, "请授予\"显示在其他应用上层\"权限", Toast.LENGTH_LONG).show()
            return
        }
        if (AssistantService.isRunning) {
            startService(overlayIntent(AssistantService.Action.STOP, ""))
        } else {
            startService(overlayIntent(AssistantService.Action.ANALYZE, boardView.position.toFen()))
        }
    }

    private fun overlayIntent(action: String, fen: String): Intent =
        Intent(this, AssistantService::class.java).apply {
            this.action = action
            putExtra(AssistantService.Extra.FEN, fen)
        }

    private fun analyze() {
        val ctl = controller
        if (ctl == null) {
            resultView.text = "引擎启动中..."
            lifecycleScope.launch {
                controller = SearchController().also { c ->
                    val ok = c.start(applicationContext)
                    resultView.text = if (ok) "引擎已就绪，再点一次分析" else "引擎初始化失败"
                }
            }
            return
        }
        val fen = boardView.position.toFen()
        btnAnalyze.isEnabled = false
        lifecycleScope.launch {
            try {
                val result = ctl.search(fen)
                resultView.text = result.candidates.joinToString("\n") { c ->
                    val score = c.mateIn?.let { if (it > 0) "杀! $it" else "被杀 $it" }
                        ?: ((c.scoreCp!! / 100.0).let { "+$it" })
                    "${c.rank}. ${MoveFormatter.toChinese(fen, c.pv.first())}  $score  (深度${c.depth})"
                }.ifEmpty { "无结果" }
            } catch (t: Throwable) {
                resultView.text = "搜索失败: ${t.message}"
            } finally {
                btnAnalyze.isEnabled = true
            }
        }
    }

    override fun onDestroy() {
        controller?.shutdown()
        super.onDestroy()
    }
}
