package com.cheese.assistant.util

import android.content.Context
import android.os.Process
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 应用日志器:所有业务日志经此同时进 logcat 与落盘文件。
 * 为什么不直接读 logcat:三方应用无 READ_LOGS 权限,生产包读不到自己的日志;
 * 而排障(如对局中途卡死)恰恰需要事后拿现场。
 * 文件按会话命名 files/logs/app-MMdd-HHmmss.log,启动时只保留最近 KEEP_FILES 份。
 * 拉取:adb exec-out run-as com.cheese.assistant cat files/logs/<最新文件名>
 */
object AppLog {
    private const val RING_CAP = 2500
    private const val KEEP_FILES = 4
    private val ring = ArrayDeque<String>(RING_CAP)
    private val lock = Any()
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "applog").apply { isDaemon = true }
    }

    @Volatile private var file: File? = null
    @Volatile private var started = false
    private val ts = { SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date()) }

    fun start(context: Context) {
        if (started) return
        started = true
        val dir = File(context.filesDir, "logs")
        io.execute {
            runCatching {
                dir.mkdirs()
                dir.listFiles { f -> f.name.endsWith(".log") }
                    ?.sortedBy { it.name }
                    ?.dropLast(KEEP_FILES - 1)
                    ?.forEach { it.delete() }
                val name = "app-" + SimpleDateFormat("MMdd-HHmmss", Locale.US).format(Date()) + ".log"
                file = File(dir, name).apply {
                    appendText("=== session start pid=${Process.myPid()} ${ts()} ===\n")
                }
            }.onFailure { android.util.Log.e("AppLog", "log file open failed", it) }
        }
        // 崩溃兜底:uncaught 堆栈在 logcat 管道断掉前未必写完,直接同步落盘
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            writeDirect(format("E", "CRASH", "uncaught on ${t.name}", e))
            prev?.uncaughtException(t, e)
        }
    }

    fun d(tag: String, msg: String) = write("D", tag, msg, null)
    fun i(tag: String, msg: String) = write("I", tag, msg, null)
    fun w(tag: String, msg: String) = write("W", tag, msg, null)
    fun e(tag: String, msg: String, tr: Throwable? = null) = write("E", tag, msg, tr)

    private fun write(level: String, tag: String, msg: String, tr: Throwable?) {
        android.util.Log.println(
            when (level) {
                "D" -> android.util.Log.DEBUG
                "I" -> android.util.Log.INFO
                "W" -> android.util.Log.WARN
                else -> android.util.Log.ERROR
            }, tag, msg + (tr?.let { "\n" + android.util.Log.getStackTraceString(it) } ?: ""))
        val line = format(level, tag, msg, tr)
        synchronized(lock) {
            ring.addLast(line)
            while (ring.size > RING_CAP) ring.removeFirst()
        }
        io.execute { runCatching { file?.appendText(line + "\n") } }
    }

    private fun format(level: String, tag: String, msg: String, tr: Throwable?): String =
        buildString {
            append(ts()).append(' ').append(level).append('/').append(tag).append(": ").append(msg)
            if (tr != null) append('\n').append(stackOf(tr))
        }

    private fun stackOf(t: Throwable): String =
        StringWriter().also { t.printStackTrace(PrintWriter(it)) }.toString()

    /** 崩溃现场:绕过执行器同步写,保证进程退出前落盘 */
    private fun writeDirect(line: String) {
        synchronized(lock) {
            ring.addLast(line)
            while (ring.size > RING_CAP) ring.removeFirst()
        }
        runCatching { file?.appendText(line + "\n") }
    }

    /** 最近 N 行(供调试接口/分享用) */
    fun recent(n: Int = 200): List<String> = synchronized(lock) { ring.takeLast(n) }
}
