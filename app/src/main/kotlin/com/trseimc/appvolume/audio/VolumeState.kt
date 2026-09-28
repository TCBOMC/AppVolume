package com.trseimc.appvolume.audio

/**
 * 一个应用的音量配置。
 * 存的是滑条原始位置而不是 dB，因为滑条位置才是用户真正看到的量；
 * dB / 线性增益都由 [AudioCurve] 派生，避免反复存取造成精度漂移。
 */
data class AppVolumeState(
    val gainSlider: Float = 1f,
    val pan: Float = 0f
) {
    val gainDb: Float get() = AudioCurve.sliderToGainDb(gainSlider)
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
