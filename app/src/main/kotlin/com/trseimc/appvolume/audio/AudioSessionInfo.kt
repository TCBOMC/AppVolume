package com.trseimc.appvolume.audio

/**
 * AudioFlinger 里的一条播放会话记录。
 *
 * [sessionId] 是音效系统的全局句柄：拿到它就能用 AudioEffect 把增益插进这条播放链路，
 * 不管这条链路属于哪个应用 —— AudioFlinger 对 session 挂载音效不做归属校验。
 *
 * [rawName] 是会话表里原样打印的 name 列。它是 audioserver 用 `mediautils::UidInfo` 按 uid
 * 反查出来的包名：是真实包名时归属就按它走（与示例 APK 一致）；反查失败会写成
 * `android.uid.systemui` 这类占位、甚至过期缓存，那种情况才退回用 [uid] 反查解析。
 */
data class AudioSessionInfo(
    val sessionId: Int,
    val pid: Int,
    val uid: Int,
    val packageName: String,
    val rawName: String = ""
)
