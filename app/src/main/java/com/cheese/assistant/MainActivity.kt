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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        findViewById<Button>(R.id.btnCapture).setOnClickListener { testCapture() }
        findViewById<Button>(R.id.btnRecognize).setOnClickListener { testRecognize() }
        findViewById<Button>(R.id.btnReview).setOnClickListener {
            startActivity(android.content.Intent(this, com.cheese.assistant.review.ReviewActivity::class.java))
        }
    }

    /**
     * 识别流程：与校准相同的倒计时抓屏，然后直接进识别工作台
     * （采样模板 → 识别 → 应用到主棋盘核对）
     */
    private fun testRecognize() {
        if (!com.cheese.assistant.capture.ScreenCapture.isRunning) {
            startActivity(Intent(this, com.cheese.assistant.capture.CapturePermissionActivity::class.java))
            Toast.makeText(this, "授权后请再点一次此按钮", Toast.LENGTH_LONG).show()
            return
        }
        val target = "default"
        if (!com.cheese.assistant.capture.CalibrateActivity.calibrationFile(this, "default").exists()) {
            Toast.makeText(this, "请先完成棋盘校准", Toast.LENGTH_LONG).show()
            testCapture()
            return
        }
        Toast.makeText(this, "请在 3 秒内切换到目标棋盘界面", Toast.LENGTH_LONG).show()
        lifecycleScope.launch {
            kotlinx.coroutines.delay(3000)
            val ok = withContext(Dispatchers.IO) {
                com.cheese.assistant.capture.ScreenCapture.captureToCache()
            }
            if (!ok) { Toast.makeText(this@MainActivity, "截屏失败", Toast.LENGTH_SHORT).show(); return@launch }
            startActivity(android.content.Intent(this@MainActivity,
                com.cheese.assistant.recognize.RecognizeActivity::class.java)
                .putExtra(com.cheese.assistant.recognize.RecognizeActivity.EXTRA_TARGET_APP,
                    "default"))
        }
    }

    override fun onResume() {
        super.onResume()
        com.cheese.assistant.recognize.RecognizeActivity.lastApplied?.let { pos ->
            boardView.position = pos
            fenView.setText(pos.toFen())
            com.cheese.assistant.recognize.RecognizeActivity.lastApplied = null
        }
    }

    /**
     * 校准流程：退后台 → 用户 3 秒内切到目标棋盘 → 自动抓屏 → 弹悬浮「校准」按钮
     * （悬浮窗点击可合法启动 Activity，规避后台启动限制）
     */
    private fun testCapture() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
            return
        }
        if (!com.cheese.assistant.capture.ScreenCapture.isRunning) {
            startActivity(Intent(this, com.cheese.assistant.capture.CapturePermissionActivity::class.java))
            Toast.makeText(this, "授权后请再点一次此按钮", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "请在 3 秒内切换到目标棋盘界面", Toast.LENGTH_LONG).show()
        lifecycleScope.launch {
            kotlinx.coroutines.delay(3000)
            withContext(Dispatchers.IO) {
                com.cheese.assistant.capture.ScreenCapture.captureToCache()
            }
            com.cheese.assistant.capture.CalibrateActivity.showCalibrateButton(this@MainActivity)
        }
    }

    /** 悬浮窗自动模式：授权检查 → 启动前台服务，自动抓帧识别并出候选 */
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
            return
        }
        if (!com.cheese.assistant.capture.ScreenCapture.isRunning) {
            startActivity(Intent(this, com.cheese.assistant.capture.CapturePermissionActivity::class.java))
            Toast.makeText(this, "授权后请再点一次此按钮", Toast.LENGTH_LONG).show()
            return
        }
        startService(overlayIntent(AssistantService.Action.START_AUTO, ""))
        Toast.makeText(this, "已开启自动识别,请切到对局界面", Toast.LENGTH_LONG).show()
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
