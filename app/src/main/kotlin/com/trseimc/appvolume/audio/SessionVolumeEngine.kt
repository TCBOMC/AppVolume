package com.trseimc.appvolume.audio

import com.trseimc.appvolume.apps.AppEntry

/**
 * 基于 AudioFlinger 会话音效的音量引擎。
 *
 * 和常见的「远程音量」方案不同，这条路径不挑应用：只要某个应用的 AudioTrack / MediaPlayer
 * 在 AudioFlinger 里占了一个 session，就能把增益插进去。代价是需要 DUMP 权限来查 session id。
 */
class SessionVolumeEngine : VolumeEngine {

    override val name: String = "AudioFlinger 会话音效（LoudnessEnhancer + DynamicsProcessing）"

    override fun capability(app: AppEntry): EngineCapability {
        val source = SessionRegistry.source.value
        val error = SessionRegistry.error.value
        val sessions = SessionRegistry.sessions.value.filter { it.packageName == app.packageName }

        return when {
            source == SessionRegistry.Source.NONE -> EngineCapability(
                canControlVolume = false,
                canControlBalance = false,
                reason = "还没拿到音频会话列表。需要先用 Shizuku 授予 DUMP 权限，或让 Shizuku 代跑 dumpsys。"
            )

            error != null -> EngineCapability(
                canControlVolume = false,
                canControlBalance = false,
                reason = "扫描音频会话失败：$error"
            )

            sessions.isEmpty() -> EngineCapability(
                canControlVolume = true,
                canControlBalance = true,
                reason = "这个应用现在没有在放声音。配置已经保存好，它一开始播放，音量与声道平衡会立刻生效。"
            )

            else -> EngineCapability(
                canControlVolume = true,
                canControlBalance = true,
                reason = "已接管 ${sessions.size} 个音频会话，音量与左右平衡正在实时生效。"
            )
        }
    }

    override fun applyVolume(app: AppEntry, state: AppVolumeState): ApplyResult {
        val sessions = SessionRegistry.sessions.value.filter { it.packageName == app.packageName }
        if (sessions.isEmpty()) {
            return ApplyResult.Ok(
                volumeApplied = false,
                balanceApplied = false,
                detail = "配置已保存。该应用当前没有活跃的音频会话，开始播放时会自动生效。"
            )
        }

        val report = SessionEffectEngine.syncPackage(app.packageName, state, sessions)
        val detail = buildString {
            append("已对 ${report.volumeApplied} 个会话下发音量")
            if (report.balanceApplied > 0) append("，左右平衡同步生效")
            if (report.failures.isNotEmpty()) append("。失败：${report.failures.first()}")
        }
        return ApplyResult.Ok(
            volumeApplied = report.volumeApplied > 0,
            balanceApplied = report.balanceApplied > 0,
            detail = detail
        )
    }
}
