package com.cheese.assistant.engine

/**
 * Pikafish 引擎的 JNI 封装。
 * native 层直接驱动 Stockfish::Engine，输出以 UCI 行文本投递（nativePollLine）。
 */
class PikafishEngine {

    companion object {
        init {
            System.loadLibrary("cheese_engine")
        }
    }

    /**
     * 初始化引擎。nnueDir 为 NNUE 权重所在目录（通常为 App filesDir），
     * 权重文件名固定为 pikafish.nnue。
     */
    external fun nativeInit(nnueDir: String): Boolean

    external fun nativeSetOption(name: String, value: String): Boolean

    external fun nativeSetPosition(fen: String, moves: Array<String>): Boolean

    /** 启动搜索。时限控制由 Kotlin 侧在 movetime 后调用 stop() 实现。 */
    external fun nativeGo()

    external fun nativeStop()

    /** 阻塞读一行引擎输出，超时返回 null。 */
    external fun nativePollLine(timeoutMs: Int): String?

    external fun nativeQuit()
}
