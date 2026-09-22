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
 * 用 PC 生成并推送的模板/校准/截图，识别结果应等于标准开局（90/90）。
 */
@RunWith(AndroidJUnit4::class)
class RecognizerPortTest {

    @Test
    fun recognizeDefaultStart() {
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
        assertEquals(Position.START.toFen(), result.fen)
    }
}
