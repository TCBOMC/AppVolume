package com.trseimc.appvolume.audio

import com.trseimc.appvolume.apps.AppEntry

/** 某个应用当前能被控制到什么程度。 */
data class EngineCapability(
    val canControlVolume: Boolean,
    val canControlBalance: Boolean,
    val reason: String
)

sealed class ApplyResult {
    data class Ok(val volumeApplied: Boolean, val balanceApplied: Boolean, val detail: String) : ApplyResult()
    data class Failed(val reason: String) : ApplyResult()
}

/**
 * 音量下发引擎。
 *
 * 做成接口是因为「改别的应用的音量」在 Android 上没有正式 API：
 * 当前的 [SessionVolumeEngine] 走的是「给 AudioFlinger 会话挂音效」这条路，
 * 将来如果要在 Root/Xposed 环境下改成样本级 DSP，只要再实现一个引擎替换掉即可，
 * UI 与曲线部分完全不用动。
 */
interface VolumeEngine {
    val name: String

    /** 探测这个应用当前能不能被控制，同时给出人话解释，直接显示在 UI 上。 */
    fun capability(app: AppEntry): EngineCapability

    /** 下发音量。平衡能不能一起下发由返回值说明。 */
    fun applyVolume(app: AppEntry, state: AppVolumeState): ApplyResult
}
