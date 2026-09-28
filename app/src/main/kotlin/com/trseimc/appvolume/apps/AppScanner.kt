package com.trseimc.appvolume.apps

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import kotlin.math.max

/**
 * 「使用情况访问」的结果，**不再负责组成列表**。
 *
 * 它只回答两件辅助问题：
 * 1. 这个正在运行的应用最后一次被用是几点（用来显示「几分钟前用过」）；
 * 2. 它现在是前台还是后台（用来给运行中的应用标状态）。
 *
 * 列表主体是 [ProcessScanner] 的进程表。UsageStats 本质是「最近用过」，拿它当列表主体
 * 必然出现「Shizuku 明明没开却躺在列表里」和「刚启动的应用刷新不出来」—— 这个坑踩过一次就够了。
 * 只有进程表完全读不到（ROM 裁剪了 dumpsys）时，才用 [openPackages] 做降级兜底。
 *
 * 踩过的另一个坑：单纯依赖 queryUsageStats() 会「刷新不出来」，系统对用量统计是攒一批再落盘的，
 * 刚启动的应用在落盘前查不到。所以以 queryEvents() 事件流为主，queryUsageStats() 只补 lastTimeUsed。
 */
class AppScanner(private val context: Context) {

    /** 是否拿到了「使用情况访问权限」。这个权限只能在系统设置里手动开，不能运行时申请。 */
    fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** 包名 → 最近一次被使用的时间戳。 */
    fun lastUsed(windowMillis: Long): Map<String, Long> {
        if (!hasUsageAccess()) return emptyMap()
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val from = now - windowMillis
        val events = lastEvents(manager, from, now)
        val stats = manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, from, now).orEmpty()
        val result = HashMap<String, Long>()
        for (s in stats) {
            val t = max(s.lastTimeUsed, max(s.lastTimeVisible, s.lastTimeForegroundServiceUsed))
            if (t > (result[s.packageName] ?: 0L)) result[s.packageName] = t
        }
        for ((pkg, value) in events) {
            if (value.second > (result[pkg] ?: 0L)) result[pkg] = value.second
        }
        return result
    }

    /** 包名 → 最近一次生命周期事件 `(事件类型, 时间戳)`。 */
    fun lastEvents(windowMillis: Long): Map<String, Pair<Int, Long>> {
        if (!hasUsageAccess()) return emptyMap()
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        return lastEvents(manager, now - windowMillis, now)
    }

    /**
     * 进程表读不到时的降级来源：这些包在 [windowMillis] 内还有「前台/可见」事件收尾
     * （RESUMED / PAUSED / 前台服务），说明这会儿大概率还开着。
     * 被 STOPPED / DESTROYED 收尾的不算 —— 那才是真的退出了。
     */
    fun openPackages(windowMillis: Long): Set<String> {
        val now = System.currentTimeMillis()
        val result = HashSet<String>()
        for ((pkg, value) in lastEvents(windowMillis)) {
            if (now - value.second > windowMillis) continue
            val state = RunState.fromEventType(value.first) ?: continue
            if (state == RunState.RECENT) continue
            result.add(pkg)
        }
        return result
    }

    /** 事件类型 → 运行状态，供界面标注前台/后台。进程已确认在跑时，RECENT 一律降级成 BACKGROUND。 */
    fun stateOf(pkg: String, events: Map<String, Pair<Int, Long>>): RunState {
        val event = events[pkg] ?: return RunState.BACKGROUND
        val state = RunState.fromEventType(event.first) ?: return RunState.BACKGROUND
        return if (state == RunState.RECENT) RunState.BACKGROUND else state
    }

    private fun lastEvents(
        manager: UsageStatsManager,
        from: Long,
        now: Long
    ): Map<String, Pair<Int, Long>> {
        val events = manager.queryEvents(from, now)
        val event = UsageEvents.Event()
        val result = HashMap<String, Pair<Int, Long>>()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (RunState.fromEventType(event.eventType) == null) continue
            val previous = result[event.packageName]
            if (previous == null || event.timeStamp >= previous.second) {
                result[event.packageName] = event.eventType to event.timeStamp
            }
        }
        return result
    }
}
