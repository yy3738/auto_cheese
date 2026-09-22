package com.cheese.assistant

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cheese.assistant.board.Position
import com.cheese.assistant.recognize.BoardGrid
import com.cheese.assistant.recognize.BoardRecognizer
import com.cheese.assistant.recognize.TemplateStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 验证 Kotlin 移植的 NCC 识别与 PC 端一致：
 * 用 PC 生成并推送的模板/校准/截图，识别结果应无未定点。
 * test_board.png 为实战帧（选中光圈、新棋盘尺寸），曾暴露两处移植 bug：
 * 陈旧帧（captureFresh 修复）与双线性缩放混叠（BOX 区域平均修复）。
 */
@RunWith(AndroidJUnit4::class)
class RecognizerPortTest {

    /** 实战帧真实局面（JJ 对局中局，红方走子） */
    private val EXPECTED_FEN =
        "rnbakab2/9/1c2c1n2/p1p1p1p1p/9/9/P1P1P1P1P/1C2B4/3N5/R2AKABN1 w - - 0 1"

    /** 残局帧，带 JJ 红色落子标记框（曾污染窗口 NCC 使仕/车距离差收窄到 0.013） */
    private val EXPECTED_FEN_ENDGAME =
        "3aka3/1R7/6c2/3P5/4P4/9/9/3A5/3KA3r/9 w - - 0 1"

    @Test
    fun recognizeRealFrame() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val bmpFile = java.io.File(ctx.filesDir, "test_board.png")
        assertTrue("缺 test_board.png，请 adb push 到 files/", bmpFile.exists())
        val bmp = BitmapFactory.decodeFile(bmpFile.absolutePath)
        val grid = BoardGrid.load(ctx, "default")!!
        val templates = TemplateStore.load(ctx, "default")

        val result = BoardRecognizer.recognize(bmp, grid, templates)

        assertEquals(
            "存在未定点: ${result.matrixText}",
            0, result.unknown.count { it })
        assertEquals(EXPECTED_FEN, result.fen)
    }

    @Test
    fun recognizeEndgameFrameWithMarkers() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val bmpFile = java.io.File(ctx.filesDir, "test_board2.png")
        assertTrue("缺 test_board2.png，请 adb push 到 files/", bmpFile.exists())
        val bmp = BitmapFactory.decodeFile(bmpFile.absolutePath)
        val grid = BoardGrid.load(ctx, "default")!!
        val templates = TemplateStore.load(ctx, "default")

        val result = BoardRecognizer.recognize(bmp, grid, templates)

        assertEquals(
            "存在未定点: ${result.matrixText}",
            0, result.unknown.count { it })
        assertEquals(EXPECTED_FEN_ENDGAME, result.fen)
    }
}
