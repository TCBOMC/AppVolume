package com.trseimc.appvolume.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 最近一次扫描到的音频会话。后台服务负责更新，界面负责读。
 * 服务和 Activity 在同一个进程，所以直接用单例共享，不必绕 binder。
 */
object SessionRegistry {

    private val _sessions = MutableStateFlow<List<AudioSessionInfo>>(emptyList())
    val sessions: StateFlow<List<AudioSessionInfo>> = _sessions.asStateFlow()

    private val _source = MutableStateFlow(Source.NONE)
    val source: StateFlow<Source> = _source.asStateFlow()

    /** 上一次扫描失败的原因，直接显示给用户，不做美化。 */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** 会话表的原始行（诊断面板用），帮用户看到 ROM 打印的真实内容。 */
    private val _rawDiag = MutableStateFlow<String?>(null)
    val rawDiag: StateFlow<String?> = _rawDiag.asStateFlow()

    /** dumpsys 是走本进程的 DUMP 权限，还是借 Shizuku 的 shell。 */
    enum class Source(val text: String) {
        NONE("尚未扫描"),
        LOCAL("本进程 DUMP 权限"),
        SHIZUKU("Shizuku shell")
    }

    fun publish(
        list: List<AudioSessionInfo>,
        source: Source,
        error: String? = null,
        rawDiag: String? = null
    ) {
        _sessions.value = list
        _source.value = source
        _error.value = error
        _rawDiag.value = rawDiag
    }

    fun packages(): Set<String> = _sessions.value.mapTo(HashSet()) { it.packageName }
}
