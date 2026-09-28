package com.trseimc.appvolume.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.trseimc.appvolume.R
import com.trseimc.appvolume.data.VolumeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 常驻的音频会话监控服务。
 *
 * 为什么必须常驻：AudioEffect 的生命周期跟进程绑定，进程一被杀，挂在别人播放链路上的
 * 音效就全部释放，音量立刻恢复原样。所以只要希望「退出界面也一直生效」，就得有个前台服务。
 *
 * 做的事很单一：定期扫一次 AudioFlinger 的会话表，把结果同步给 [SessionEffectEngine]，
 * 让引擎按当前保存的配置挂/摘音效。会话变化（有应用开始或停止播放）由
 * AudioPlaybackCallback 立刻触发一次扫描，不用干等轮询。
 */
class AudioControlService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var scanner: SessionScanner
    private lateinit var repository: VolumeRepository
    private var bridge: ShizukuBridge? = null
    private var audioManager: AudioManager? = null
    private var playbackCallback: AudioManager.AudioPlaybackCallback? = null

    @Volatile
    private var settings: Map<String, AppVolumeState> = emptyMap()

    override fun onCreate() {
        super.onCreate()
        scanner = SessionScanner(this)
        repository = VolumeRepository(this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        createChannel()
        // 起不了前台服务就退化成普通后台服务，界面在前台时功能照常
        runCatching { startForeground(NOTIFICATION_ID, buildNotification()) }
            .onFailure { Log.w(TAG, "startForeground 失败，退化为后台服务", it) }

        scope.launch { repository.all.collect { settings = it } }
        scope.launch {
            while (currentCoroutineContext().isActive) {
                rescan()
                delay(SCAN_INTERVAL_MS)
            }
        }
        registerPlaybackCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        unregisterPlaybackCallback()
        scope.cancel()
        bridge?.unbind()
        SessionEffectEngine.releaseAll()
        super.onDestroy()
    }

    private suspend fun rescan() {
        val shizuku = ensureShizuku()
        val canUseShell = runCatching { shizuku.isGranted }.getOrDefault(false)
        val result = if (canUseShell && !scanner.hasDumpPermission()) {
            // 优先走同步的 newProcess 通道；只有它不可用时才会去等 UserService 那个独立进程
            shizuku.ensureExecutable(SHELL_WAIT_MS)
            scanner.scan { command -> shizuku.runShell(command) }
        } else {
            scanner.scan()
        }
        SessionRegistry.publish(result.sessions, result.source, result.error, result.rawSection)
        SessionEffectEngine.sync(result.sessions, settings)
    }

    private fun ensureShizuku(): ShizukuBridge = ShizukuBridge.obtain(this).also { bridge = it }

    private fun registerPlaybackCallback() {
        val manager = audioManager ?: return
        val callback = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
                // 有应用开始/停止发声，立刻重扫，免得等下一个轮询周期
                handler.removeCallbacks(rescanRunnable)
                handler.postDelayed(rescanRunnable, CALLBACK_DEBOUNCE_MS)
            }
        }
        runCatching { manager.registerAudioPlaybackCallback(callback, handler) }
            .onFailure { Log.w(TAG, "注册播放回调失败", it) }
        playbackCallback = callback
    }

    private fun unregisterPlaybackCallback() {
        val callback = playbackCallback ?: return
        handler.removeCallbacks(rescanRunnable)
        runCatching { audioManager?.unregisterAudioPlaybackCallback(callback) }
        playbackCallback = null
    }

    private val rescanRunnable = Runnable {
        scope.launch { rescan() }
    }

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "应用音量监控",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "保持对每个应用的音量与声道平衡生效" }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("应用音量正在生效")
            .setContentText("正在监控音频会话并维持已配置的音量与声道平衡")
            .setSmallIcon(R.drawable.ic_volume_stat)
            .setOngoing(true)
            .build()

    companion object {
        private const val TAG = "AudioControlService"
        private const val CHANNEL_ID = "session_volume"
        private const val NOTIFICATION_ID = 4271

        // 应用开始/停止播放时会话表不会瞬间稳定，去抖一下再扫
        private const val CALLBACK_DEBOUNCE_MS = 400L
        private const val SCAN_INTERVAL_MS = 1500L

        // 兜底通道里等 shell 服务连上的时间，比 UI 上短一些，免得把扫描周期拖太长
        private const val SHELL_WAIT_MS = 5000L
    }
}
