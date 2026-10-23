package com.cheese.assistant.capture

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import android.app.usage.UsageEvents

/**
 * 前台 App 判定（主轨: UsageStatsManager, 2026-10-21）。
 * 系统〈使用情况访问〉授权后可确定查询前台包名, 不受 JJ 换肤/弹窗影响。
 * 边条指纹(BoardWatcher)作为未授权时的启发式兜底。
 */
object ForegroundApp {

    /** JJ 象棋包名(对局所在) */
    const val TARGET = "cn.jj.chess"

    /** 〈使用情况访问〉是否已授权(设置里一次性开关)。
     *  双信号: AppOps 查询 + queryEvents 试探(部分 ROM/模拟器 op 查询
     *  漏报未授权, 2026-10-21 实测)。 */
    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(), context.packageName)
        if (mode == AppOpsManager.MODE_ALLOWED) return true
        // 试探: 真未授权时 queryEvents 抛 SecurityException
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE)
                    as UsageStatsManager
            val events = usm.queryEvents(
                System.currentTimeMillis() - 1000, System.currentTimeMillis())
            events.hasNextEvent() or true   // 能走到这里即视为已授权
        } catch (e: SecurityException) {
            false
        }
    }

    /** 引导跳转系统设置页 */
    fun usageAccessSettingsIntent(): android.content.Intent =
        android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)

    /**
     * 当前前台包名 = 最近一条 MOVE_TO_FOREGROUND 事件的包(事件只在
     * 切换时产生, 停留多久都有效——禁止用短窗口, 2026-10-21: 停留
     * 超 4s 窗口内无事件被误判非对局前台而暂停识别)。
     * 窗口取 10 分钟; 完全无事件返回 null(兜底接管)。
     */
    fun current(context: Context): String? {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE)
                as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - 600_000, now)
        val ev = UsageEvents.Event()
        var last: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            if (ev.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                last = ev.packageName
            }
        }
        return last
    }
}
