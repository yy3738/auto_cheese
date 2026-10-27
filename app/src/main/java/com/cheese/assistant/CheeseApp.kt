package com.cheese.assistant

import android.app.Application
import com.cheese.assistant.engine.EngineHolder
import com.cheese.assistant.util.AppLog

/**
 * 进程入口：尽早开启文件日志与崩溃兜底（2026-10-26）。
 * 之前 AppLog 只在 AssistantService.onCreate 启动，投屏授权页等更早的
 * 崩溃不会落盘，真机排障时拿不到堆栈。
 */
class CheeseApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.start(this)
        // 进程级引擎预热（S4）：用户打开 App 就开始加载 NNUE，点开局时多半已就绪
        EngineHolder.prewarm(this)
    }
}
