package com.trseimc.appvolume.audio

import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.LoudnessEnhancer
import kotlin.math.roundToInt

/**
 * 真正的音量下发：给 AudioFlinger 的音频会话挂音效。
 *
 * 原理：AudioFlinger 对「往某个 session 上挂音效」不做归属校验，只要知道 session id，
 * 任何进程都能把自己的 AudioEffect 插进别的应用的播放链路。所以不需要 Root，
 * 只需要 DUMP 权限跑一次 dumpsys 拿到 session id。
 *
 * 两个音效分工：
 * - [LoudnessEnhancer] 负责主音量。原生实现是 `amp = 10^(gainmB/2000)`，负值即衰减，
 *   所以推子拉到底就是大幅衰减而不是「增益为负」。
 * - [DynamicsProcessing] 负责左右平衡。它是唯一提供「每通道独立增益」的公开音效，
 *   用 `setInputGainbyChannel` 给左右声道各下发一个 dB 值。构造时只保留一个 0dB 的
 *   PreEQ 段（默认配置会带一个 -2dB 的限幅器，会把声音压掉，必须避开）。
 *
 * 只在需要时才挂：音量满格不挂 LoudnessEnhancer，声道居中不挂 DynamicsProcessing，
 * 这样对没有配置过的应用是完全透明的。
 */
object SessionEffectEngine {

    data class Report(
        val volumeApplied: Int = 0,
        val balanceApplied: Int = 0,
        val failures: List<String> = emptyList()
    )

    private class Handles(val sessionId: Int) {
        var loudness: LoudnessEnhancer? = null
        var balance: DynamicsProcessing? = null
        var gainMb: Int? = null
        var leftDb: Float? = null
        var rightDb: Float? = null

        fun release() {
            runCatching { loudness?.release() }
            runCatching { balance?.release() }
            loudness = null
            balance = null
            gainMb = null
            leftDb = null
            rightDb = null
        }
    }

    private val lock = Any()
    private val handles = HashMap<Int, Handles>()

    /** 挂上音效的 session 数，界面用来显示「正在生效」。 */
    @Volatile
    var attachedCount: Int = 0
        private set

    fun sync(sessions: List<AudioSessionInfo>, settings: Map<String, AppVolumeState>): Report =
        syncInternal(sessions, settings, restrictTo = null)

    /**
     * 只更新单个应用。和 [sync] 的差别是不会顺手释放别的应用的音效 ——
     * 用户拖滑条时不该让其他正在播放的应用闪一下。
     */
    fun syncPackage(
        packageName: String,
        state: AppVolumeState,
        sessions: List<AudioSessionInfo>
    ): Report = syncInternal(sessions, mapOf(packageName to state), restrictTo = packageName)

    private fun syncInternal(
        sessions: List<AudioSessionInfo>,
        settings: Map<String, AppVolumeState>,
        restrictTo: String?
    ): Report {
        val wanted = HashMap<Int, AppVolumeState>()
        val candidateIds = HashSet<Int>()
        for (session in sessions) {
            if (session.packageName == SELF_PACKAGE) continue
            if (restrictTo != null && session.packageName != restrictTo) continue
            candidateIds += session.sessionId
            val state = settings[session.packageName] ?: continue
            if (state.isDefault) continue
            wanted[session.sessionId] = state
        }

        val failures = ArrayList<String>()
        var volumeApplied = 0
        var balanceApplied = 0

        synchronized(lock) {
            val iterator = handles.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                // 只动本次涉及的会话，别的应用的音效原样保留
                if (entry.key in candidateIds && entry.key !in wanted) {
                    entry.value.release()
                    iterator.remove()
                }
            }

            for ((sessionId, state) in wanted) {
                val handle = handles.getOrPut(sessionId) { Handles(sessionId) }
                val volumeOk = applyVolume(handle, state, failures)
                val balanceOk = applyBalance(handle, state, failures)
                if (volumeOk) volumeApplied++
                if (balanceOk) balanceApplied++
            }
            attachedCount = handles.size
        }
        return Report(volumeApplied, balanceApplied, failures)
    }

    fun releaseAll() {
        synchronized(lock) {
            handles.values.forEach { it.release() }
            handles.clear()
            attachedCount = 0
        }
    }

    private fun applyVolume(handle: Handles, state: AppVolumeState, failures: MutableList<String>): Boolean {
        val db = state.gainDb
        val gainMb = if (db.isInfinite()) MIN_GAIN_MB else (db * 100f).roundToInt().coerceIn(MIN_GAIN_MB, 0)
        if (gainMb == 0) {
            handle.loudness?.let {
                runCatching { it.release() }
                handle.loudness = null
                handle.gainMb = null
            }
            return false
        }
        val effect = handle.loudness ?: runCatching {
            LoudnessEnhancer(handle.sessionId).apply { setEnabled(true) }
        }.getOrElse {
            failures += "会话 ${handle.sessionId}：LoudnessEnhancer 挂载失败 (${reasonOf(it)})"
            return false
        }
        handle.loudness = effect
        if (handle.gainMb == gainMb) return true
        runCatching {
            effect.setTargetGain(gainMb)
            handle.gainMb = gainMb
        }.onFailure {
            failures += "会话 ${handle.sessionId}：写入增益失败 (${reasonOf(it)})"
        }
        return true
    }

    private fun applyBalance(handle: Handles, state: AppVolumeState, failures: MutableList<String>): Boolean {
        if (state.pan == 0f) {
            handle.balance?.let {
                runCatching { it.release() }
                handle.balance = null
                handle.leftDb = null
                handle.rightDb = null
            }
            return false
        }

        val stereo = state.stereo
        val left = stereo.leftDb.coerceAtLeast(MIN_PAN_DB)
        val right = stereo.rightDb.coerceAtLeast(MIN_PAN_DB)
        if (handle.leftDb == left && handle.rightDb == right) return true

        val effect = handle.balance ?: runCatching {
            createBalanceEffect(handle.sessionId).apply { setEnabled(true) }
        }.getOrElse {
            failures += "会话 ${handle.sessionId}：DynamicsProcessing 挂载失败 (${reasonOf(it)})"
            return false
        }
        handle.balance = effect

        return runCatching {
            val channelCount = effect.channelCount
            if (channelCount >= 1) effect.setInputGainbyChannel(0, left)
            if (channelCount >= 2) effect.setInputGainbyChannel(1, right)
            handle.leftDb = left
            handle.rightDb = right
            true
        }.getOrElse {
            failures += "会话 ${handle.sessionId}：写入声道增益失败 (${reasonOf(it)})"
            false
        }
    }

    /**
     * 只保留一段 0dB 的 PreEQ。默认的 DynamicsProcessing 配置会启用 MBC 和
     * 一个 -2dB/10:1 的限幅器，那会把动态压掉，不是我们要的「只改增益」。
     * 留一段 EQ 是为了保证链路里确实有处理节点，输入增益一定会被施加。
     */
    private fun createBalanceEffect(sessionId: Int): DynamicsProcessing {
        val config = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_TIME_RESOLUTION,
            2,
            true, 1,   // PreEQ：启用，1 段，默认 0dB
            false, 1,  // MBC：不用
            false, 1,  // PostEQ：不用
            false      // Limiter：不用
        ).build()
        return DynamicsProcessing(0, sessionId, config)
    }

    private fun reasonOf(throwable: Throwable): String =
        throwable.cause?.message ?: throwable.message ?: throwable.javaClass.simpleName

    private const val MIN_PAN_DB = -60f

    // -60dB 已经听不见了，再低没有意义
    private const val MIN_GAIN_MB = -6000

    private const val SELF_PACKAGE = "com.trseimc.appvolume"
}
