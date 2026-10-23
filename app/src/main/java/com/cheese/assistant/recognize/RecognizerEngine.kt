package com.cheese.assistant.recognize

import android.graphics.Bitmap
import com.cheese.assistant.board.Piece

/**
 * 识别引擎策略接口（Strategy）：主路引擎的可替换抽象。
 * 实现一 NccEngine（模板匹配+规则门），实现二 CnnEngine（CharCNN）。
 */
interface RecognizerEngine {

    /** 引擎名（日志/影子对照用） */
    val name: String

    /**
     * 识别整帧。grid 已经过线吸附（GridRefiner 在门面统一调用）。
     * 返回逐格棋子草稿 + 置信分层 + 调试矩阵文本。
     */
    fun recognize(bmp: Bitmap, grid: BoardGrid): EngineResult

    /**
     * 强制这些格下一帧重新推理（清懒推理指纹）。
     * 仲裁层发现"疑似误读/残影/颜色翻转"时调用，避免错误分类被缓存冻结。
     */
    fun invalidate(cells: Collection<Int>) = Unit

    /** 强制下一帧全量推理（锚点重同步/连续失败自愈时调用）。 */
    fun invalidateAll() = Unit
}

/**
 * 逐格置信分层：仲裁策略依赖此分层而非布尔 unknown。
 */
class EngineResult(
    /** 90 格棋子草稿（null=空或未判出；可信度见 confidence） */
    val board: Array<Piece?>,
    /** 逐格置信（与 board 同序） */
    val confidence: Array<Confidence>,
    /** 调试矩阵文本（含低置信统计） */
    val matrixText: String,
    /** 逐格数值 margin(0-1, 与 confidence 同序); null=引擎不提供 */
    val margins: FloatArray? = null,
) {
    enum class Confidence {
        /** 直接采信 */
        CONFIDENT,

        /** 弱采信：静态格可采信，变化格需锚点仲裁 */
        WEAK,

        /** 拒判：回填或整帧作废 */
        UNKNOWN,
    }

    val hasUnknown: Boolean get() = confidence.any { it == Confidence.UNKNOWN }
}
