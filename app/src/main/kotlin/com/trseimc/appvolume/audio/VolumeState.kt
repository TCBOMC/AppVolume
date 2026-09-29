package com.trseimc.appvolume.audio

/**
 * 一个应用的音量配置。
 * 存的是滑条原始位置而不是 dB，因为滑条位置才是用户真正看到的量；
 * dB / 线性增益都由 [AudioCurve] 派生，避免反复存取造成精度漂移。
 *
 * @param masterFraction 计算衰减范围用的主音量比例（0..1），运行时信息、不落盘。
 *   NaN = 未指定，按 [MasterVolume.current] 的全局缓存算。
 */
data class AppVolumeState(
    val gainSlider: Float = 1f,
    val pan: Float = 0f,
    val masterFraction: Float = Float.NaN
) {
    // 衰减范围底端 = -(量程 × 主音量比例) - 额外衰减，公式见 [MasterVolume.minDb]
    val effectiveMaster: Float
        get() = if (masterFraction.isNaN()) MasterVolume.current() else masterFraction
    val minDb: Float get() = MasterVolume.minDb(effectiveMaster)
    val gainDb: Float get() = AudioCurve.sliderToGainDb(gainSlider, minDb)
    val isMuted: Boolean get() = gainDb.isInfinite()
    val stereo: AudioCurve.StereoGains get() = AudioCurve.panToStereoGains(pan)

    /** 左声道最终线性增益，将来做样本级 DSP 时直接乘在 PCM 上。 */
    val leftLinear: Float get() = AudioCurve.dbToLinear(gainDb) * stereo.left

    /** 右声道最终线性增益。 */
    val rightLinear: Float get() = AudioCurve.dbToLinear(gainDb) * stereo.right

    val isDefault: Boolean get() = gainSlider == 1f && pan == 0f

    fun serialize(): String = "$gainSlider|$pan"

    companion object {
        val DEFAULT = AppVolumeState()

        fun parse(raw: String?): AppVolumeState {
            if (raw.isNullOrBlank()) return DEFAULT
            val parts = raw.split("|")
            return AppVolumeState(
                gainSlider = parts.getOrNull(0)?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f,
                pan = parts.getOrNull(1)?.toFloatOrNull()?.coerceIn(-1f, 1f) ?: 0f
            )
        }
    }
}
