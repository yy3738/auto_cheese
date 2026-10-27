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
import com.cheese.assistant.engine.EngineHolder
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


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        com.cheese.assistant.util.AppLog.start(applicationContext)
        com.cheese.assistant.util.AppLog.i("Main", "activity created")
        // Android 13+ 通知权限默认拒绝：不申请的话前台服务通知和
        // 「复盘完成」通知都会被系统静默丢弃（2026-10-27 实测）
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 3001)
        }

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
        findViewById<Button>(R.id.btnExportLogs).setOnClickListener { exportLogs() }
        findViewById<Button>(R.id.btnLive).setOnClickListener {
            startActivity(Intent(this, com.cheese.assistant.ui.LiveGameActivity::class.java))
        }
        findViewById<Button>(R.id.btnOverlay).setOnClickListener {
            com.cheese.assistant.util.AppLog.i("Main",
                "btnOverlay clicked, usageAccess=" +
                    com.cheese.assistant.capture.ForegroundApp.hasUsageAccess(this) +
                    " overlays=" + Settings.canDrawOverlays(this) +
                    " svcRunning=" + com.cheese.assistant.service.AssistantService.isRunning)
            if (!com.cheese.assistant.capture.ForegroundApp.hasUsageAccess(this)) {
                Toast.makeText(this, "请开启弈枰的『使用情况访问』(用于判定前台是否在对局), 开启后回来再点此按钮", Toast.LENGTH_LONG).show()
                startActivity(com.cheese.assistant.capture.ForegroundApp.usageAccessSettingsIntent())
                return@setOnClickListener
            }
            if (com.cheese.assistant.service.AssistantService.isRunning) {
                if (com.cheese.assistant.capture.ScreenCapture.isRunning) {
                    Toast.makeText(this, "识别运行中, 已可切到对局界面", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                // 服务活但投屏死(进程被杀/授权失效): 先停再走授权重启
                startService(overlayIntent(com.cheese.assistant.service.AssistantService.Action.STOP, ""))
                Toast.makeText(this, "投屏已失效, 请再点一次此按钮重新启动", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            // 单路径: 直接起授权页, 授权成功后服务+悬浮窗+循环全自动
            startActivity(Intent(this, com.cheese.assistant.capture.CapturePermissionActivity::class.java))
        }
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
        updateOverlayButton()
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
            Toast.makeText(this, "已停止实战辅助（再次开启需重新授权截屏）",
                Toast.LENGTH_LONG).show()
            updateOverlayButton()
            return
        }
        if (!com.cheese.assistant.capture.ScreenCapture.isRunning) {
            startActivity(Intent(this, com.cheese.assistant.capture.CapturePermissionActivity::class.java))
            Toast.makeText(this, "请完成截屏授权（授权后悬浮窗会自动出现）",
                Toast.LENGTH_LONG).show()
            return
        }
        startService(overlayIntent(AssistantService.Action.START_AUTO, ""))
        Toast.makeText(this, "悬浮窗已开启，切到对局后点「开局」开始记账", Toast.LENGTH_LONG).show()
    }

    /** 主按钮跟随服务状态：运行中显示「停止」，否则「开始」 */
    private fun updateOverlayButton() {
        val running = AssistantService.isRunning
        findViewById<Button>(R.id.btnOverlay).text =
            if (running) "停止\n实战辅助" else "开始\n实战辅助"
    }

    private fun overlayIntent(action: String, fen: String): Intent =
        Intent(this, AssistantService::class.java).apply {
            this.action = action
            putExtra(AssistantService.Extra.FEN, fen)
        }

    /**
     * 导出日志到公共「下载」目录（2026-10-26，真机无法 adb 时排障用）：
     * 把 files/logs 下的会话日志逐份复制到 Download/象棋助手日志/，
     * 用户可直接把这个文件夹发给开发者。API 29+ 走 MediaStore，无需存储权限。
     */
    private fun exportLogs() {
        val dir = java.io.File(filesDir, "logs")
        val logs = dir.listFiles { f -> f.isFile && f.name.endsWith(".log") }
            ?.sortedBy { it.name } ?: emptyList()
        if (logs.isEmpty()) {
            Toast.makeText(this, "暂无日志", Toast.LENGTH_SHORT).show()
            return
        }
        val stamp = java.text.SimpleDateFormat("MMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        var saved = 0
        for (f in logs) {
            val name = "chesslog-$stamp-${f.name}"
            val ok = runCatching {
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    val values = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                        put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain")
                        put(android.provider.MediaStore.Downloads.RELATIVE_PATH,
                            android.os.Environment.DIRECTORY_DOWNLOADS + "/象棋助手日志")
                    }
                    val uri = contentResolver.insert(
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: return@runCatching false
                    contentResolver.openOutputStream(uri)?.use { out ->
                        f.inputStream().use { it.copyTo(out) }
                    }
                } else {
                    val outDir = java.io.File(getExternalFilesDir(null), "logs-export")
                        .apply { mkdirs() }
                    f.copyTo(java.io.File(outDir, name), overwrite = true)
                }
                true
            }.getOrDefault(false)
            if (ok) saved++
        }
        val where = if (android.os.Build.VERSION.SDK_INT >= 29)
            "下载/象棋助手日志" else "Android/data/com.cheese.assistant/files/logs-export"
        Toast.makeText(this, "已导出 $saved 份日志到 $where，请把整个文件夹发我",
            Toast.LENGTH_LONG).show()
    }

    private fun analyze() {
        val fen = boardView.position.toFen()
        btnAnalyze.isEnabled = false
        lifecycleScope.launch {
            try {
                if (!EngineHolder.isReady) {
                    resultView.text = "引擎预热中 ${EngineHolder.loadingSeconds()}s…"
                }
                val ctl = EngineHolder.ensure(applicationContext)
                if (ctl == null) {
                    resultView.text = "引擎初始化失败：${EngineHolder.lastError ?: "未知原因"}"
                    return@launch
                }
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
        // 引擎是进程级（EngineHolder），退出主界面不释放
        super.onDestroy()
    }
}
