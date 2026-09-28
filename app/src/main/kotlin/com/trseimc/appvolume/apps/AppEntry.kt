package com.trseimc.appvolume.apps

/**
 * 列表里的一行。
 * [runState] 说明这个应用当前到底处在什么状态：真在前台、只在后台跑服务、
 * 退到了后台、还是早就停了只是最近用过。
 */
data class AppEntry(
    val packageName: String,
    val label: String,
    val uid: Int,
    val lastUsedMillis: Long,
    val runState: RunState,
    val isSystemApp: Boolean,
    // 进程此刻确实存在（来自 ActivityManagerService 的进程表）
    val isRunning: Boolean = false,
    // 此刻有活跃的音频会话（来自 AudioFlinger 的会话表）
    val isPlayingAudio: Boolean = false
)

enum class RunState(val order: Int, val text: String) {
    // 当前真正占据屏幕
    FOREGROUND(0, "前台"),

    // 界面不可见，但起了前台服务在后台跑（音乐、导航、下载这类）
    BACKGROUND_SERVICE(1, "后台服务"),

    // 刚退下去，进程大概率还在
    BACKGROUND(2, "后台"),

    // Activity 已经 stop，只是最近用过
    RECENT(3, "最近使用");

    companion object {
        /** UsageEvents.Event 的事件类型 → 运行状态，返回 null 表示该事件与运行状态无关。 */
        fun fromEventType(eventType: Int): RunState? = when (eventType) {
            // ACTIVITY_RESUMED / MOVE_TO_FOREGROUND（两者值都是 1）
            1 -> FOREGROUND
            // FOREGROUND_SERVICE_START / CONTINUING_FOREGROUND_SERVICE / ROLLOVER_FOREGROUND_SERVICE
            19, 21, 22 -> BACKGROUND_SERVICE
            // ACTIVITY_PAUSED / MOVE_TO_BACKGROUND（值为 2）/ FOREGROUND_SERVICE_STOP（20）
            2, 20 -> BACKGROUND
            // ACTIVITY_STOPPED / ACTIVITY_DESTROYED
            23, 24 -> RECENT
            else -> null
        }
    }
}
