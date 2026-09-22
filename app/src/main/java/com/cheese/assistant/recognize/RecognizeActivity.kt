package com.cheese.assistant.recognize

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.cheese.assistant.board.Piece
import com.cheese.assistant.board.Position

/**
 * 识别工作台：显示抓取的棋盘截图 + 90 交叉点网格。
 * 1) 采样：选中棋子类型后点某个交叉点，把该点取样存为模板（每类 1~2 次）
 * 2) 微调网格：拖动 4 角让绿点贴合棋盘交叉线，保存校准
 * 3) 识别：全部 90 点最近邻匹配，矩阵文本展示（'？'=不确定）
 * 4) 应用到主界面：把识别出的局面摆到主棋盘核对
 */
class RecognizeActivity : Activity() {

    private lateinit var grid: BoardGrid
    private lateinit var templates: TemplateStore
    private lateinit var gridView: BoardGridView
    private lateinit var typePicker: Spinner
    private lateinit var statusView: TextView
    private lateinit var resultView: TextView
    private lateinit var btnApply: Button
    private lateinit var btnAdjust: Button
    private var lastResult: BoardRecognizer.Result? = null

    private val targetApp: String get() = intent.getStringExtra(EXTRA_TARGET_APP) ?: "default"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bmp = com.cheese.assistant.capture.ScreenCapture.lastFrame
        if (bmp == null) {
            Toast.makeText(this, "没有截图，请先截屏校准", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val g = BoardGrid.load(this, targetApp)
        if (g == null) {
            Toast.makeText(this, "请先完成棋盘校准", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        grid = g
        templates = TemplateStore.load(this, targetApp)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        gridView = BoardGridView(this, bmp, grid)
        gridView.onSelected = { idx ->
            if (!gridView.adjustMode) {
                statusView.text = "选中 ${idx / 9} 行 ${idx % 9} 列；选中类型后点「采样入库」"
            }
        }
        gridView.onGridChanged = {
            statusView.text = "微调中：拖动角点使绿点贴合棋盘，完成后点「结束微调」"
        }

        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        typePicker = Spinner(this).apply {
            adapter = ArrayAdapter(this@RecognizeActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("(空点)") + Piece.entries.map { it.cnName + if (it.isRed) "红" else "黑" })
        }
        val btnSample = Button(this).apply {
            text = "采样入库"
            setOnClickListener { sampleSelected(bmp) }
        }
        top.addView(typePicker, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(btnSample)

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnApply = Button(this).apply {
            text = "应用到主界面"
            isEnabled = false
            setOnClickListener {
                lastApplied = lastResult?.position
                finish()
            }
        }
        val btnRecognize = Button(this).apply {
            text = "识别"
            setOnClickListener { doRecognize(bmp) }
        }
        btnAdjust = Button(this).apply {
            text = "微调网格"
            setOnClickListener { toggleAdjust(bmp) }
        }
        btnRow.addView(btnApply)
        btnRow.addView(btnRecognize)
        btnRow.addView(btnAdjust)

        statusView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0xCC000000.toInt())
            setPadding(16, 6, 16, 8)
            text = "①微调网格→②采样→③识别。绿点应落在棋子中心"
        }
        resultView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0xCC000000.toInt())
            setPadding(24, 4, 8, 8)
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
        }
        val resultScroll = ScrollView(this).apply {
            addView(resultView)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(150))
        }

        val bottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        bottom.addView(btnRow)
        bottom.addView(statusView)
        bottom.addView(resultScroll)

        root.addView(gridView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(top)
        root.addView(bottom)
        setContentView(root)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun toggleAdjust(bmp: Bitmap) {
        gridView.adjustMode = !gridView.adjustMode
        if (gridView.adjustMode) {
            btnAdjust.text = "结束微调"
            statusView.text = "微调：点住某个角点拖动（仅 4 角可拖），绿网贴合棋盘线即可"
        } else {
            btnAdjust.text = "微调网格"
            grid.save(this, targetApp, bmp.width, bmp.height)
            statusView.text = "网格已保存；继续采样或识别"
        }
    }

    private fun sampleName(): String =
        if (typePicker.selectedItemPosition == 0) "EMPTY"
        else Piece.entries[typePicker.selectedItemPosition - 1].name

    /** 把选中交叉点的窗口取样缩放为 24x24 存为当前类型模板 */
    private fun sampleSelected(bmp: Bitmap) {
        val idx = gridView.selectedIndex
        if (idx < 0) {
            Toast.makeText(this, "先点一个交叉点", Toast.LENGTH_SHORT).show()
            return
        }
        val row = idx / 9
        val col = idx % 9
        val patch = cropScaled(bmp, grid.x(row, col), grid.y(row, col), grid.cellSize * 0.48f)
        templates.add(sampleName(), patch)
        templates.save(this, targetApp)
        statusView.text = "已存模板：${typePicker.selectedItem}（该类共 ${templates.countOf(sampleName())} 张）"
    }

    /** 最近邻匹配识别并显示矩阵 */
    private fun doRecognize(bmp: Bitmap) {
        val result = BoardRecognizer.recognize(bmp, grid, templates)
        lastResult = result
        val tip = if (result.hasUnknown) "\n'？'=不确定：补充采样后重新识别" else ""
        resultView.text = result.matrixText + "FEN: ${result.fen}\n$tip"
        btnApply.isEnabled = true
        gridView.showResult(result.unknown)
        gridView.invalidate()
    }

    companion object {
        const val EXTRA_TARGET_APP = "targetApp"

        /** 识别结果传递给 MainActivity（onResume 读取后清空） */
        var lastApplied: Position? = null
    }
}

/** 从大图裁出以 (cx,cy) 为中心、边长 2r 的窗口并缩放为 24x24 */
private fun cropScaled(bmp: Bitmap, cx: Float, cy: Float, radius: Float): Bitmap {
    val left = (cx - radius).toInt().coerceIn(0, bmp.width - 1)
    val top = (cy - radius).toInt().coerceIn(0, bmp.height - 1)
    val right = (cx + radius).toInt().coerceIn(left + 1, bmp.width)
    val bottom = (cy + radius).toInt().coerceIn(top + 1, bmp.height)
    val src = Bitmap.createBitmap(bmp, left, top, right - left, bottom - top)
    return Bitmap.createScaledBitmap(src, Patch.SIZE, Patch.SIZE, true)
}
