package com.trseimc.appvolume.audio

import android.content.Context
import android.media.AudioManager

/**
 * 系统主音量（STREAM_MUSIC）的运行时缓存。
 *
 * 应用增益的调节范围跟着主音量走：主音量本身已经在衰减，再给应用全量程的
 * 衰减就没有意义（滑条后半段全是「听不出区别的静音」）。底端公式：
 *   minDb = -(量程 × 主音量比例) - 额外衰减
 * 例：量程 80、主音量 75% → -60dB；主音量 10% → -8dB；额外衰减 2 → 再加深到 -10dB。
 *
 * 量程与额外衰减来自设置浮窗的「音量设置」组，持久化在 [com.trseimc.appvolume.data.VolumeSettingsStore]，
 * 由 ViewModel 和两个服务的设置流收集器写进这里（进程内共享一份缓存）。
 *
 * 进程内全局缓存：AudioControlService 的轮询、音量面板推送、ViewModel 轮询
 * 都会顺手刷新主音量，消费方（AppVolumeState）只读 [current]。
 */
object MasterVolume {

    @Volatile
    private var cached = 1f

    // 全量程（主音量 100% 时的底端绝对值）与额外衰减。默认 80 / 0，
    // 设置改动后由各收集器调用 [applySettings] 同步
    @Volatile
    var fullRangeDb: Float = AudioCurve.FULL_RANGE_DB
        private set

    @Volatile
    var extraDb: Float = 0f
        private set

    /** 设置浮窗改了量程/额外衰减后同步到缓存，所有消费方立刻生效。 */
    fun applySettings(rangeDb: Int, extra: Int) {
        fullRangeDb = rangeDb.coerceIn(20, 120).toFloat()
        extraDb = extra.coerceIn(0, 20).toFloat()
    }

    /** 读一遍系统主音量并刷新缓存，返回 0..1。 */
    fun refresh(context: Context): Float {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return cached
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return cached
        val fraction = am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
        cached = fraction.coerceIn(0f, 1f)
        return cached
    }

    /** 当前缓存值（0..1）。没刷新过时默认 1f = 全量程。 */
    fun current(): Float = cached

    /** 主音量比例 → 推子底端 dB：-(量程 × 比例) - 额外衰减。 */
    fun minDb(fraction: Float): Float =
        -(fullRangeDb * fraction.coerceIn(0f, 1f)) - extraDb
}
