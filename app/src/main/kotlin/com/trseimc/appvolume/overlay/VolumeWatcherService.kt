package com.trseimc.appvolume.overlay

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.trseimc.appvolume.audio.AppVolumeState
import com.trseimc.appvolume.audio.AudioCurve
import com.trseimc.appvolume.audio.SessionEffectEngine
import com.trseimc.appvolume.audio.SessionRegistry
import com.trseimc.appvolume.audio.SessionVolumeEngine
import com.trseimc.appvolume.apps.AppEntry
import com.trseimc.appvolume.apps.RunState
import com.trseimc.appvolume.data.VolumeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 音量键悬浮控制。
 *
 * 工作方式：
 * 1. [onKeyEvent] 旁观音量键（永远返回 false，不消费），按下时弹出悬浮按钮 ——
 *    触发时机与系统音量面板弹出一致。
 * 2. [onAccessibilityEvent] 观察窗口列表，识别 SystemUI 音量面板（VolumeDialog）窗口：
 *    面板出现 → 按钮保留；面板消失 → 按钮同步隐藏。识别不到的 ROM 上退化为
 *    超时自动隐藏（每按一次音量键都顺延）。
 * 3. 点按钮弹出浮窗：列出正在发声的应用，每个应用一条 SeekBar 实时调音量 ——
 *    下发链路与主界面完全相同（SessionRegistry → SessionEffectEngine）。
 *
 * 浮窗 UI 用经典 View 而不是 Compose：overlay 窗口没有现成的
 * LifecycleOwner/SavedStateRegistryOwner，ComposeView 要手动伪造一堆 owner，
 * 对一个列表 + 滑条来说不值得。
 */
class VolumeWatcherService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())

    // Service 没有 Activity 那个 windowManager 属性，自己从 SYSTEM_SERVICE 拿
    private val windowManager by lazy {
        getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    // 与主界面共享同一套引擎/仓库：无障碍服务和 AudioControlService 同进程
    private val repository by lazy { VolumeRepository(this) }
    private val engine by lazy { SessionVolumeEngine() }

    // 配置缓存：浮窗行初始化、拖动时合并 pan 值都用它
    private val configs = ConcurrentHashMap<String, AppVolumeState>()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // 音量下发与落盘的串行 worker：拖动产生的高频回调在此排队执行，天然不乱序。
    // 不再单独开 scope —— livePush 的 collector 本身就是串行的
    private val livePush = MutableStateFlow<LivePush?>(null)

    // 悬浮按钮 / 浮窗的窗口句柄
    private var buttonView: View? = null
    private var panelView: View? = null
    private var rowsContainer: LinearLayout? = null
    private var panelTitle: TextView? = null

    // 最近一次音量键按下的时刻（elapsedRealtime），用于把窗口变化和按键关联起来
    @Volatile
    private var lastKeyAt = 0L

    // 已经确认过「系统音量面板窗口」出现，之后它消失就跟着隐藏
    @Volatile
    private var panelWindowSeen = false

    // 正在被用户拖动的浮窗滑条所属包名；拖动期间不重建行、轮询同步豁免该包
    @Volatile
    private var draggingPkg: String? = null

    private val hideButtonRunnable = Runnable { hideButton() }

    private data class LivePush(val pkg: String, val state: AppVolumeState, val final: Boolean)

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceScope.launch {
            repository.all.collect { all ->
                configs.clear()
                configs.putAll(all)
            }
        }
        serviceScope.launch {
            livePush.filterNotNull().collect { push -> applyAndSave(push) }
        }
        // 浮窗开着的时候，发声应用集合变了就重建行（拖动中不动）
        serviceScope.launch {
            SessionRegistry.sessions.collect {
                if (panelView != null && draggingPkg == null) rebuildRows()
            }
        }
    }

    // ---- 音量键：只旁观，不消费 ----

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
        ) {
            lastKeyAt = SystemClock.elapsedRealtime()
            if (panelView == null) showButton()
        }
        // 永远不消费：系统音量照变、面板照弹
        return false
    }

    // ---- 窗口观察：与系统音量面板同步出没 ----

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) return
        if (buttonView == null || panelView != null) return

        val panelVisible = findVolumePanelWindow() != null
        if (panelVisible) {
            // 音量面板确实弹出来了（和按键关联上了），接下来它消失我们就跟着消失
            panelWindowSeen = true
        } else if (panelWindowSeen) {
            panelWindowSeen = false
            hideButton()
        }
        // 从没观察到面板窗口的 ROM 上，靠 scheduleHide 的超时兜底
    }

    /** 在窗口列表里找 SystemUI 的音量面板。判定依据：包名 systemui + 根节点类名含 volume。 */
    private fun findVolumePanelWindow(): AccessibilityWindowInfo? {
        val list = runCatching { windows }.getOrNull() ?: return null
        for (window in list) {
            val root = runCatching { window.root }.getOrNull() ?: continue
            // 包名要从根节点拿 —— AccessibilityWindowInfo 没有包名接口（API 36 实测）
            val pkg = root.packageName?.toString() ?: continue
            if (pkg != VOLUME_PANEL_PACKAGE) continue
            val className = root.className?.toString() ?: continue
            if (className.contains("volume", ignoreCase = true)) return window
        }
        return null
    }

    // ---- 悬浮按钮 ----

    private fun showButton() {
        if (!Settings.canDrawOverlays(this)) return
        panelWindowSeen = false

        if (buttonView == null) {
            val view = TextView(this).apply {
                text = "🔊"
                textSize = 20f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(BUTTON_BG)
                }
                setOnClickListener { showPanel() }
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                // 音量面板一般在按键一侧垂直居中，按钮放同侧但偏下，跟着它一起出没
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                x = dp(12)
                y = dp(140)
            }
            runCatching {
                windowManager.addView(view, lp)
                buttonView = view
            }
        }
        scheduleHide()
    }

    private fun hideButton() {
        buttonView?.let {
            runCatching { windowManager.removeView(it) }
            buttonView = null
        }
        mainHandler.removeCallbacks(hideButtonRunnable)
    }

    private fun scheduleHide() {
        mainHandler.removeCallbacks(hideButtonRunnable)
        mainHandler.postDelayed(hideButtonRunnable, BUTTON_AUTO_HIDE_MS)
    }

    // ---- 浮窗 ----

    private fun showPanel() {
        if (!Settings.canDrawOverlays(this)) return
        hideButton() // 打开浮窗后按钮使命完成；音量键继续照常调系统音量

        panelView?.let {
            runCatching { windowManager.removeView(it) }
            panelView = null
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(PANEL_BG)
            }
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }

        val titleRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val title = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
        }
        panelTitle = title
        titleRow.addView(
            title,
            LinearLayout.LayoutParams(0, WindowManager.LayoutParams.WRAP_CONTENT, 1f)
        )
        titleRow.addView(TextView(this).apply {
            text = "✕"
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 16f
            setPadding(dp(12), dp(4), 0, dp(4))
            setOnClickListener { hidePanel() }
        })
        content.addView(titleRow)

        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        rowsContainer = rows
        content.addView(rows)
        rebuildRows()

        val scroll = ScrollView(this).apply { addView(content) }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            horizontalMargin = 0.06f
        }
        runCatching {
            windowManager.addView(scroll, lp)
            panelView = scroll
        }.onFailure {
            panelView = null
            rowsContainer = null
        }
    }

    private fun hidePanel() {
        panelView?.let {
            runCatching { windowManager.removeView(it) }
            panelView = null
        }
        rowsContainer = null
        panelTitle = null
        draggingPkg = null
    }

    /** 按 SessionRegistry 当前的发声应用重建浮窗行。 */
    private fun rebuildRows() {
        val rows = rowsContainer ?: return
        val title = panelTitle ?: return
        val sessions = SessionRegistry.sessions.value
            .filter { it.packageName != packageName }
            .groupBy { it.packageName }

        mainHandler.post {
            title.text = "正在发声 · ${sessions.size} 个应用"
            rows.removeAllViews()
            if (sessions.isEmpty()) {
                rows.addView(TextView(this).apply {
                    text = "现在没有应用在发声。播放音乐或视频后再按一次音量键。"
                    setTextColor(0xAAFFFFFF.toInt())
                    textSize = 13f
                    setPadding(0, dp(12), 0, dp(4))
                })
                return@post
            }
            for ((pkg, _) in sessions) {
                buildAppRow(pkg)?.let { rows.addView(it) }
            }
        }
    }

    /** 一行：图标 + 应用名 + dB 读数 + 滑条。返回 null 表示包已卸载、画不了。 */
    private fun buildAppRow(pkg: String): View? {
        val pm = packageManager
        val info = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() ?: return null
        val label = runCatching { pm.getApplicationLabel(info).toString() }.getOrDefault(pkg)
        val state = configs[pkg] ?: AppVolumeState.DEFAULT

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(2))
        }

        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val icon = ImageView(this)
        icon.setImageDrawable(runCatching { pm.getApplicationIcon(pkg) }.getOrNull())
        icon.scaleType = ImageView.ScaleType.FIT_CENTER
        header.addView(icon, LinearLayout.LayoutParams(dp(30), dp(30)))

        val name = TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 14f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        header.addView(
            name,
            LinearLayout.LayoutParams(0, WindowManager.LayoutParams.WRAP_CONTENT, 1f)
                .also { params -> params.marginStart = dp(10) }
        )

        val dbLabel = TextView(this).apply {
            text = AudioCurve.formatDb(state.gainDb)
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 12f
        }
        header.addView(dbLabel)
        row.addView(header)

        val seek = SeekBar(this).apply {
            max = 100
            progress = (state.gainSlider * 100).roundToInt()
            thumbTintList = android.content.res.ColorStateList.valueOf(0xFF7CB8FF.toInt())
            progressTintList = android.content.res.ColorStateList.valueOf(0xFF7CB8FF.toInt())
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val merged = AppVolumeState(
                        gainSlider = value / 100f,
                        pan = configs[pkg]?.pan ?: 0f
                    )
                    configs[pkg] = merged
                    dbLabel.text = AudioCurve.formatDb(merged.gainDb)
                    livePush.value = LivePush(pkg, merged, final = false)
                }

                override fun onStartTrackingTouch(bar: SeekBar) {
                    draggingPkg = pkg
                    // 和主界面同一个套路：拖动期间轮询同步豁免，别拿旧保存值顶回实时值
                    SessionEffectEngine.suppressedPackage = pkg
                }

                override fun onStopTrackingTouch(bar: SeekBar) {
                    draggingPkg = null
                    val merged = AppVolumeState(
                        gainSlider = bar.progress / 100f,
                        pan = configs[pkg]?.pan ?: 0f
                    )
                    livePush.value = LivePush(pkg, merged, final = true)
                }
            })
        }
        row.addView(
            seek,
            LinearLayout.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
                .also { params -> params.topMargin = dp(2) }
        )
        return row
    }

    /** 下发 + 落盘 + 收尾豁免，单 worker 串行执行。 */
    private suspend fun applyAndSave(push: LivePush) {
        configs[push.pkg] = push.state
        val pm = packageManager
        val info = runCatching { pm.getApplicationInfo(push.pkg, 0) }.getOrNull()
        val entry = AppEntry(
            packageName = push.pkg,
            label = info?.let { runCatching { pm.getApplicationLabel(it).toString() }.getOrNull() } ?: push.pkg,
            uid = info?.uid ?: 0,
            lastUsedMillis = 0L,
            runState = RunState.BACKGROUND,
            isSystemApp = false
        )
        engine.applyVolume(entry, push.state)

        if (push.final) {
            if (push.state.isDefault) repository.remove(push.pkg) else repository.save(push.pkg, push.state)
            if (SessionEffectEngine.suppressedPackage == push.pkg) {
                SessionEffectEngine.suppressedPackage = null
            }
        }
    }

    override fun onDestroy() {
        hideButton()
        hidePanel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        hideButton()
        hidePanel()
        return super.onUnbind(intent)
    }

    override fun onInterrupt() = Unit

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val VOLUME_PANEL_PACKAGE = "com.android.systemui"
        private const val BUTTON_AUTO_HIDE_MS = 3500L

        private val BUTTON_BG = 0xE6303030.toInt()
        private val PANEL_BG = 0xF2202020.toInt()

        /** 主界面检查无障碍服务是否已开启。设置里存的是「包/类」或「包/短类名」两种形态。 */
        fun isEnabled(context: Context): Boolean {
            val component = ComponentName(context, VolumeWatcherService::class.java)
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabled.split(':').any {
                it.equals(component.flattenToString(), ignoreCase = true) ||
                    it.equals(component.flattenToShortString(), ignoreCase = true)
            }
        }
    }
}
