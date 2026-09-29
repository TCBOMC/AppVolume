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
import android.provider.Settings
import android.util.Log
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
import com.trseimc.appvolume.data.ButtonAnimStyle
import com.trseimc.appvolume.data.ButtonSettings
import com.trseimc.appvolume.data.ButtonSettingsStore
import com.trseimc.appvolume.data.ButtonSide
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

    // 最近一次音量键按下 / 窗口事件 / 音量面板窗口出现的时刻（墙钟，供主界面诊断显示）
    // 见 companion 里的静态字段 —— 无障碍服务由系统绑定，主界面没法拿它的实例

    // 已经确认过「系统音量面板窗口」出现，之后它消失就跟着隐藏
    @Volatile
    private var panelWindowSeen = false

    // 正在被用户拖动的浮窗滑条所属包名；拖动期间不重建行、轮询同步豁免该包
    @Volatile
    private var draggingPkg: String? = null

    private val hideButtonRunnable = Runnable { hideButton("超时") }

    // 面板消失的防抖：荣耀的音量条弹出动画期间，窗口列表偶尔瞬时扫不到它，
    // 按钮刚加上就被「面板消失」逻辑摘掉，看起来就是从来没弹过。到点时还要复核一次
    // 面板窗口是否真的不在了，不在才真隐藏。
    private val delayedHideRunnable = Runnable {
        if (panelView == null && !panelWindowStillThere()) {
            hideButton("音量面板消失确认")
        } else {
            // 面板其实还在，反悔：恢复同步状态，等它的下一次消失事件
            panelWindowSeen = true
        }
    }

    /** 同步模式下判断音量面板窗口是否还挂着（见 onAccessibilityEvent 里的判定说明）。 */
    private fun panelWindowStillThere(): Boolean {
        val windows = runCatching { windows }.getOrNull() ?: return true // 拿不到列表就不下结论
        val remembered = panelWindowId > 0 && windows.any { it.id == panelWindowId }
        // 有窗口此刻解析不出根节点（多半在动画中），不能断定面板没了
        val indeterminate = windows.any { runCatching { it.root }.getOrNull() == null }
        return remembered || indeterminate
    }

    // 按钮设置：DataStore 流在服务连接时收集进这个快照，showButton/hideButton 直接读
    private val buttonStore by lazy { ButtonSettingsStore(this) }

    @Volatile
    private var buttonSettings = ButtonSettings()

    // 消失语义：TIMEOUT = 按键通道，超时自动隐藏；SYNC = 窗口扫描通道，与音量条同步出没
    private enum class HideMode { TIMEOUT, SYNC }

    @Volatile
    private var hideMode = HideMode.TIMEOUT

    // 正在播放退场动画的按钮视图；期间再次显示时先收掉它，避免叠两个窗口
    private var pendingOut: View? = null

    // 当前同步跟随的音量面板窗口 id：命中时记下，消失判定只认「这个窗口从列表里移除」
    @Volatile
    private var panelWindowId = -1

    private data class LivePush(val pkg: String, val state: AppVolumeState, val final: Boolean)

    override fun onServiceConnected() {
        super.onServiceConnected()
        watchConnected = true
        Log.i(TAG, "onServiceConnected: 无障碍服务已连接")
        serviceScope.launch {
            buttonStore.settings.collect { buttonSettings = it }
        }
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
            lastKeyEventAtMs = System.currentTimeMillis()
            Log.i(TAG, "onKeyEvent: 收到音量键 keyCode=${event.keyCode}")
            if (panelView == null) showButton(viaPanel = false)
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
        lastWindowEventAtMs = System.currentTimeMillis()
        snapshotWindowsDiag()
        if (panelView != null) return

        val scanHit = findVolumePanelWindow()
        val panelVisible = scanHit != null || isVolumePanelEvent(event)
        when {
            panelVisible -> {
                // 音量面板弹出来了。这里同时也是兜底触发点：有些 ROM 不把按键事件
                // 下发给无障碍服务（onKeyEvent 收不到），但按音量键必然弹出系统音量条，
                // 窗口事件一定有 —— 靠这个也能把按钮带出来。
                panelMatchCount++
                // 记住音量窗口的身份：荣耀的音量条动画期间节点扫不到、类名也可能不含
                // volume，靠反复类名匹配会误判消失。之后「消失」的唯一判据是
                // 这个窗口 id 真的从窗口列表里移除。
                panelWindowId = scanHit?.id ?: event.windowId
                if (!panelWindowSeen) Log.i(TAG, "onAccessibilityEvent: 检测到音量面板窗口 id=$panelWindowId（触发悬浮按钮）")
                panelWindowSeen = true
                lastPanelSeenAtMs = System.currentTimeMillis()
                mainHandler.removeCallbacks(delayedHideRunnable)
                showButton(viaPanel = true)
            }
            panelWindowSeen -> {
                if (!panelWindowStillThere()) {
                    Log.i(TAG, "onAccessibilityEvent: 音量面板窗口消失（id=$panelWindowId），防抖后隐藏")
                    panelWindowSeen = false
                    mainHandler.removeCallbacks(hideButtonRunnable)
                    mainHandler.postDelayed(delayedHideRunnable, PANEL_HIDE_DEBOUNCE_MS)
                }
                // 面板窗口还挂着（或列表暂时解析不出）：什么都不做，按钮继续跟着
            }
        }
        // 从没观察到面板窗口的 ROM 上，靠 scheduleHide 的超时兜底
    }

    /**
     * 从事件本身判断是不是音量面板：TYPE_WINDOW_STATE_CHANGED 的 packageName /
     * className 是系统直接填的，不依赖 getWindows 的窗口枚举，是第三条独立信号。
     */
    private fun isVolumePanelEvent(event: AccessibilityEvent): Boolean {
        val pkg = event.packageName?.toString() ?: return false
        if (!pkg.contains("systemui", ignoreCase = true)) return false
        val cls = event.className?.toString() ?: return false
        return cls.contains("volume", ignoreCase = true)
    }

    /**
     * 在窗口列表里找 SystemUI 的音量面板。
     *
     * 荣耀/华为实测（2026-09-29）：包名不一定是 com.android.systemui、根类名也不一定
     * 含 volume —— 原来的严格匹配（== 且根节点）从未命中过。放宽为：包名**包含**
     * systemui，且根节点以下 5 层内任何节点的类名含 volume。
     */
    private fun findVolumePanelWindow(): AccessibilityWindowInfo? {
        val list = runCatching { windows }.getOrNull() ?: return null
        for (window in list) {
            val root = runCatching { window.root }.getOrNull() ?: continue
            // 包名要从根节点拿 —— AccessibilityWindowInfo 没有包名接口（API 36 实测）
            val pkg = root.packageName?.toString() ?: continue
            if (!pkg.contains("systemui", ignoreCase = true)) continue
            if (subtreeLooksLikeVolume(root, 0)) return window
        }
        return null
    }

    /** 限深 5 层的子树扫描：任何节点的类名含 volume 即命中。 */
    private fun subtreeLooksLikeVolume(node: android.view.accessibility.AccessibilityNodeInfo, depth: Int): Boolean {
        if (depth > 5) return false
        if (node.className?.toString()?.contains("volume", ignoreCase = true) == true) return true
        for (i in 0 until node.childCount) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            if (subtreeLooksLikeVolume(child, depth + 1)) return true
        }
        return false
    }

    /** 记录当前所有窗口的「包名 | 根类名」，给诊断卡用 —— 面板匹配不上时，这里能直接看到荣耀音量窗口的真实标识。 */
    private fun snapshotWindowsDiag() {
        val list = runCatching { windows }.getOrNull() ?: return
        val snapshot = list.mapNotNull { window ->
            val root = runCatching { window.root }.getOrNull()
                ?: return@mapNotNull "id=${window.id} | <根节点不可用> | type=${window.type} layer=${window.layer}"
            val pkg = root.packageName?.toString() ?: "<null>"
            val cls = root.className?.toString() ?: "<null>"
            val base = "$pkg | $cls | type=${window.type} layer=${window.layer} id=${window.id}"
            // systemui 窗口把子树类名也 dump 出来：荣耀音量条内部的类名长什么样，一看便知
            if (pkg.contains("systemui", ignoreCase = true)) {
                val subtree = describeSubtree(root, 0)
                if (subtree.isNotBlank()) "$base\n    子树: $subtree" else base
            } else {
                base
            }
        }.sorted().joinToString("\n")
        if (snapshot != windowsDiagSnapshot) {
            windowsDiagSnapshot = snapshot
            Log.d(TAG, "窗口列表变化:\n$snapshot")
        }
    }

    /** 收集节点子树的类名（限深 3 层、最多 12 个，简单类名），用于诊断窗口内部结构。 */
    private fun describeSubtree(node: android.view.accessibility.AccessibilityNodeInfo, depth: Int): String {
        if (depth > 3) return ""
        val names = ArrayDeque<String>()
        fun walk(n: android.view.accessibility.AccessibilityNodeInfo, d: Int) {
            if (names.size >= 12) return
            n.className?.toString()?.let { full ->
                names.add(full.substringAfterLast('.'))
            }
            if (d < 3) {
                for (i in 0 until n.childCount) {
                    val child = runCatching { n.getChild(i) }.getOrNull() ?: continue
                    walk(child, d + 1)
                }
            }
        }
        walk(node, depth)
        return names.joinToString(" / ")
    }

    // ---- 悬浮按钮 ----

    /**
     * 显示悬浮按钮。
     *
     * viaPanel=false（按键通道）：超时自动隐藏，时长用设置里的「消失时间」（默认 2s）。
     * viaPanel=true（窗口扫描通道）：与音量条窗口同步出没，不按时间隐藏。
     * 按键先弹、面板随后出现时，会从 TIMEOUT 切到 SYNC 并取消超时。
     */
    private fun showButton(viaPanel: Boolean) {
        showButtonCalls++
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "showButton: 悬浮窗权限（SYSTEM_ALERT_WINDOW）没授予，按钮弹不出来")
            return
        }
        // 上一次退场动画还没播完又要点亮：直接收掉旧视图，别叠两个窗口
        pendingOut?.let { stale ->
            stale.animate().cancel()
            runCatching { windowManager.removeView(stale) }
            pendingOut = null
        }

        if (buttonView == null) {
            val s = buttonSettings
            val view = VolumeButtonView(this, s.sizeDp, resources.displayMetrics.density).apply {
                setOnClickListener { showPanel() }
            }
            val lp = WindowManager.LayoutParams(
                dp(s.sizeDp),
                dp((s.sizeDp * 0.62f).roundToInt()),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                // 音量条一般贴着按键一侧垂直居中，按钮放同侧；纵向位置按设置的百分比
                gravity = (if (s.side == ButtonSide.RIGHT) Gravity.END else Gravity.START) or
                    Gravity.CENTER_VERTICAL
                x = dp(12)
                y = ((s.verticalPercent - 50) / 100f * resources.displayMetrics.heightPixels).toInt()
            }
            runCatching {
                windowManager.addView(view, lp)
                buttonView = view
                buttonShowing = true
                addWindowOk++
                playEnterAnimation(view, s)
                Log.i(TAG, "showButton: 悬浮按钮已显示（viaPanel=$viaPanel）")
            }.onFailure {
                addWindowFail++
                lastAddError = it.message ?: it.javaClass.simpleName
                Log.w(TAG, "showButton: 加窗失败", it)
            }
        }

        if (viaPanel) {
            // 与音量条同步出没：不按时间隐藏
            hideMode = HideMode.SYNC
            mainHandler.removeCallbacks(hideButtonRunnable)
        } else {
            hideMode = HideMode.TIMEOUT
            scheduleHide()
        }
    }

    private fun playEnterAnimation(view: View, s: ButtonSettings) {
        when (s.animStyle) {
            ButtonAnimStyle.SLIDE -> {
                val direction = if (s.side == ButtonSide.RIGHT) 1f else -1f
                // 出发点在屏幕外（视图宽 + 边距），平移到落点
                view.translationX = direction * (dp(s.sizeDp) + dp(12))
                view.animate().translationX(0f).setDuration(s.animDurationMs.toLong()).start()
            }
            ButtonAnimStyle.FADE -> {
                view.alpha = 0f
                view.animate().alpha(1f).setDuration(s.animDurationMs.toLong()).start()
            }
        }
    }

    private fun hideButton(reason: String, animate: Boolean = true) {
        lastHideReason = reason
        buttonView?.let { view ->
            buttonView = null
            buttonShowing = false
            val s = buttonSettings
            if (animate && s.animDurationMs > 0) {
                pendingOut = view
                val animator = when (s.animStyle) {
                    ButtonAnimStyle.SLIDE -> {
                        val direction = if (s.side == ButtonSide.RIGHT) 1f else -1f
                        view.animate().translationX(direction * (dp(s.sizeDp) + dp(12)))
                    }
                    ButtonAnimStyle.FADE -> view.animate().alpha(0f)
                }
                animator.setDuration(s.animDurationMs.toLong()).withEndAction {
                    pendingOut = null
                    runCatching { windowManager.removeView(view) }
                }
            } else {
                runCatching { windowManager.removeView(view) }
            }
        }
        mainHandler.removeCallbacks(hideButtonRunnable)
        mainHandler.removeCallbacks(delayedHideRunnable)
    }

    private fun scheduleHide() {
        mainHandler.removeCallbacks(hideButtonRunnable)
        mainHandler.postDelayed(hideButtonRunnable, buttonSettings.hideTimeoutMs.toLong())
    }

    // ---- 浮窗 ----

    private fun showPanel() {
        if (!Settings.canDrawOverlays(this)) return
        hideButton("打开浮窗") // 打开浮窗后按钮使命完成；音量键继续照常调系统音量

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
        watchConnected = false
        Log.i(TAG, "onDestroy: 无障碍服务断开")
        hideButton("服务解绑", animate = false)
        hidePanel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        watchConnected = false
        Log.i(TAG, "onUnbind: 无障碍服务解绑")
        hideButton("服务解绑", animate = false)
        hidePanel()
        return super.onUnbind(intent)
    }

    override fun onInterrupt() = Unit

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "AppVolumeWatch"

        // 面板消失 → 真隐藏按钮的防抖时长
        private const val PANEL_HIDE_DEBOUNCE_MS = 600L

        // 供主界面诊断的运行状态。无障碍服务由系统直接绑定、主界面拿不到实例，
        // 同进程静态字段是最省事的观测口：诊断卡能看出「开关开了但服务没连上」
        // （侧载应用被荣耀这类 ROM 的受限设置拦住时就是这个表现）。
        @Volatile
        var watchConnected = false
            private set

        @Volatile
        var lastKeyEventAtMs = 0L
            private set

        @Volatile
        var lastWindowEventAtMs = 0L
            private set

        @Volatile
        var lastPanelSeenAtMs = 0L
            private set

        /** 最近一次窗口事件时全部窗口的「包名 | 根类名 | type | layer」快照，面板匹配不上时靠它定位。 */
        @Volatile
        var windowsDiagSnapshot = ""
            private set

        // 按钮弹不弹得出来的全过程观测：面板命中几次、showButton 调几次、加窗成没成、
        // 失败原因是什么、此刻按钮是否还在。荣耀上「检测到了但按钮没出现」时，这组数字直接定位。
        @Volatile
        var panelMatchCount = 0
            private set

        @Volatile
        var showButtonCalls = 0
            private set

        @Volatile
        var addWindowOk = 0
            private set

        @Volatile
        var addWindowFail = 0
            private set

        @Volatile
        var lastAddError = ""
            private set

        @Volatile
        var buttonShowing = false
            private set

        /** 最近一次按钮隐藏的原因（超时 / 音量面板消失确认 / 打开浮窗 / 服务解绑）。 */
        @Volatile
        var lastHideReason = ""
            private set

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
