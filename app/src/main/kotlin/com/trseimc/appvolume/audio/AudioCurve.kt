package com.trseimc.appvolume.audio

import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import java.util.Locale

/**
 * 听觉感知曲线 —— 滑条位置与实际增益之间的映射。
 *
 * 两条曲线都不是线性的，原因如下：
 *
 * 1) 主音量走「dB 线性推子」（专业调音台 fader 的标准做法）。
 *    人耳对响度的感知近似于对声压级（dB）的线性感知，而不是对振幅的线性感知：
 *    振幅每减半（-6dB）听起来是「明显变小」，但从 100% 线性滑到 50% 只等于 -6dB，
 *    而滑条后半段 50%→0% 的行程却要覆盖剩下 -6dB 到 -∞，听感上会变成「前半段几乎没变化，
 *    后半段突然掉光」。所以让滑条行程等分 dB，听感才均匀。
 *    范围取 [-60dB, 0dB]：0dB 为原始音量（不放大，避免削波），-60dB 以下人耳基本听不到。
 *
 * 2) 左右平衡走「等功率声像律」（equal-power pan law）。
 *    若用线性交叉衰减（左 = 1-p，右 = p），中间位置两个声道各 0.5，
 *    总功率只有 0.5，听感上「声音跑到中间就变小了」。
 *    改用 cos/sin 三角分配后 L² + R² ≡ 1，左右移动时总响度恒定，只移动声像位置。
 */
object AudioCurve {

    // 推子底端（dB）。低于此值视为听不见。
    const val MIN_DB = -60f

    // 推子顶端（dB）。0dB = 原始音量，不做正向增益以免数字削波。
    const val MAX_DB = 0f

    // 推子底部的静音区比例：滑到底直接 -∞，避免「滑到 0 还有一点底噪」。
    const val MUTE_ZONE = 0.04f

    /**
     * 滑条位置 → 增益 dB。
     * @param slider 0f（静音）..1f（原始音量）
     * @return dB 值，可能是 [Float.NEGATIVE_INFINITY] 表示完全静音
     */
    fun sliderToGainDb(slider: Float): Float {
        val s = slider.coerceIn(0f, 1f)
        if (s <= MUTE_ZONE) return Float.NEGATIVE_INFINITY
        val t = (s - MUTE_ZONE) / (1f - MUTE_ZONE)
        return MIN_DB + t * (MAX_DB - MIN_DB)
    }

    /** dB → 滑条位置，[sliderToGainDb] 的逆变换，用于回显已保存的配置。 */
    fun gainDbToSlider(db: Float): Float {
        if (db.isInfinite() || db <= MIN_DB) return 0f
        val t = (db.coerceIn(MIN_DB, MAX_DB) - MIN_DB) / (MAX_DB - MIN_DB)
        return MUTE_ZONE + t * (1f - MUTE_ZONE)
    }

    /** dB → 线性振幅倍数。-∞ dB 会得到 0f。 */
    fun dbToLinear(db: Float): Float {
        if (db.isInfinite()) return 0f
        return 10f.pow(db / 20f)
    }

    /** 滑条位置 → 线性振幅倍数（中间曲线，给将来做 DSP 的引擎用）。 */
    fun sliderToLinear(slider: Float): Float = dbToLinear(sliderToGainDb(slider))

    /**
     * 平衡位置 → 左右声道增益，等功率声像律。
     * @param pan -1f（全左）..0f（居中）..1f（全右）
     */
    fun panToStereoGains(pan: Float): StereoGains {
        val p = pan.coerceIn(-1f, 1f)
        // 把 [-1,1] 映射到 [0, π/2]：0 → 左满右零，π/2 → 左零右满，π/4 → 左右各 0.707
        val theta = (p + 1f) * (Math.PI.toFloat() / 4f)
        return StereoGains(cos(theta), sin(theta))
    }

    /**
     * 主观响度百分比。+10dB 在听感上大约是「响一倍」，所以按 2^(dB/10) 归一到 0..100。
     * 它与滑条位置不是同一个东西：滑条 50% 时 dB 是 -30，但听起来只剩约 12.5%。
     */
    fun loudnessPercent(db: Float): Float {
        if (db.isInfinite()) return 0f
        return (2f.pow(db / 10f) * 100f).coerceIn(0f, 100f)
    }

    fun formatDb(db: Float): String {
        if (db.isInfinite()) return "-∞ dB"
        return String.format(Locale.US, "%+.1f dB", db)
    }

    /** 把 [-1,1] 的平衡值写成「左 60% / 右 40%」这样的人话。 */
    fun formatPan(pan: Float): String {
        val p = pan.coerceIn(-1f, 1f)
        return when {
            p == 0f -> "居中"
            p < 0f -> "偏左 ${(p * -100).round1()}%"
            else -> "偏右 ${(p * 100).round1()}%"
        }
    }

    private fun Float.round1(): Int = (this + 0.5f).toInt()

    class StereoGains(val left: Float, val right: Float) {
        val leftDb: Float get() = if (left <= 0f) Float.NEGATIVE_INFINITY else 20f * log10(left)
        val rightDb: Float get() = if (right <= 0f) Float.NEGATIVE_INFINITY else 20f * log10(right)
    }
}
