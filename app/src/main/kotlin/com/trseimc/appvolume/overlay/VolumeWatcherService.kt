package com.trseimc.appvolume.overlay

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.viewpager2.widget.ViewPager2
import com.trseimc.appvolume.MainActivity
import com.trseimc.appvolume.audio.AppVolumeState
import com.trseimc.appvolume.audio.AudioCurve
import com.trseimc.appvolume.audio.SessionEffectEngine
import com.trseimc.appvolume.audio.SessionRegistry
import com.trseimc.appvolume.audio.SessionVolumeEngine
import com.trseimc.appvolume.apps.AppEntry
import com.trseimc.appvolume.apps.RunState
import com.trseimc.appvolume.data.ButtonAnimStyle
import com.trseimc.appvolume.data.ButtonPosition
import com.trseimc.appvolume.data.ButtonSettings
import com.trseimc.appvolume.data.ButtonSettingsStore
import com.trseimc.appvolume.data.ButtonSide
import com.trseimc.appvolume.data.VolumeRepository
import com.trseimc.appvolume.util.FileLog
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
    private var panelPager: ViewPager2? = null
    private var panelDots: LinearLayout? = null
    private var panelEmptyText: TextView? = null
    private var panelAdapter: PanelPagerAdapter? = null
    private var panelPages: List<List<String>> = emptyList()

    // 最近一次音量键按下 / 窗口事件 / 音量面板窗口出现的时刻（墙钟，供主界面诊断显示）
    // 见 companion 里的静态字段 —— 无障碍服务由系统绑定，主界面没法拿它的实例

    // 已经确认过「系统音量面板窗口」出现，之后它消失就跟着隐藏
    @Volatile
    private var panelWindowSeen = false

    // 正在被用户拖动的浮窗滑条所属包名；拖动期间不重建行、轮询同步豁免该包
    @Volatile
    private var draggingPkg: String? = null

    private val hideButtonRunnable = Runnable { hideButton("超时") }

    // 面板消失的防抖：到点时再查一次记住的窗口 id 是否真的从列表移除（纯 id 比对，零 IPC），
    // 真没了才隐藏。音量条弹出/收起动画期间窗口列表偶尔抖动，防抖吸收这种瞬时误判。
    private val delayedHideRunnable = Runnable {
        if (panelView != null) return@Runnable
        val stillThere = panelWindowId > 0 &&
            runCatching { windows }.getOrNull()?.any { it.id == panelWindowId } == true
        if (stillThere) {
            // 反悔：面板其实还在，恢复同步状态等它真正的消失
            panelWindowSeen = true
        } else {
            Log.i(TAG, "delayedHide: 音量面板窗口确认移除（id=$panelWindowId），隐藏按钮")
            panelWindowId = -1
            hideButton("音量面板消失确认")
        }
    }

    // ---- 冷启动补扫：systemui 窗口事件后短间隔探几次，不等下一个窗口事件 ----
    // 音量面板弹出动画期间节点经常解析不出来，下一个窗口事件可能来得很晚；
    // 与其干等，不如在收到 systemui 事件的瞬间按 60/90/120/160/200ms 补扫，
    // 任一次命中就立即点亮按钮。只在「还没记住音量窗口」时启动，命中即停。

    private val probeRunnable = object : Runnable {
        override fun run() {
            if (panelWindowSeen || panelView != null) return
            val hit = findVolumePanelWindow()
            if (hit != null) {
                adoptPanelWindow(hit.id)
                return
            }
            probeIndex++
            if (probeIndex < PROBE_DELAYS_MS.size) {
                mainHandler.postDelayed(this, PROBE_DELAYS_MS[probeIndex])
            }
        }
    }

    private var probeIndex = 0

    private fun startProbeBurst() {
        mainHandler.removeCallbacks(probeRunnable)
        probeIndex = 0
        mainHandler.postDelayed(probeRunnable, PROBE_DELAYS_MS[0])
    }

    private fun cancelProbeBurst() {
        mainHandler.removeCallbacks(probeRunnable)
    }

    /** 认领音量面板窗口：记住 id、点亮按钮（同步模式），并撤掉待执行的隐藏。 */
    private fun adoptPanelWindow(id: Int) {
        if (id <= 0) return
        panelMatchCount++
        val first = !panelWindowSeen
        panelWindowId = id
        panelWindowSeen = true
        lastPanelSeenAtMs = System.currentTimeMillis()
        mainHandler.removeCallbacks(delayedHideRunnable)
        if (first) Log.i(TAG, "adoptPanelWindow: 音量面板窗口 id=$id（触发悬浮按钮）")
        showButton(viaPanel = true)
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

    // 按钮本次显示所用位置（横竖屏各有一份配置）；显示期间旋转屏幕时，
    // 退场动画仍按显示时的方向和边距回退，避免方向错乱
    private var shownPosition = ButtonPosition()

    /** 当前屏幕方向对应的位置配置。 */
    private fun currentPosition(): ButtonPosition {
        val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        return if (landscape) buttonSettings.landscape else buttonSettings.portrait
    }

    // 当前同步跟随的音量面板窗口 id：命中时记下，消失判定只认「这个窗口从列表里移除」
    @Volatile
    private var panelWindowId = -1

    private data class LivePush(val pkg: String, val state: AppVolumeState, val final: Boolean)

    override fun onServiceConnected() {
        super.onServiceConnected()
        // 服务进程可能比 MainActivity 先起（开机自启/重启后），这里兜底初始化（幂等）
        FileLog.init(this)
        FileLog.log("watcher", "onServiceConnected")
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
        // 自己的按钮/浮窗平移动画逐帧改窗口位置，会刷出大量窗口事件；
        // 它们与音量面板判定毫无关系，直接短路，否则事件队列被自己堵死，
        // 音量面板的出现/消失事件就要排队等 —— 表现就是按钮跟着迟钝。
        if (event.packageName?.toString() == packageName) return
        lastWindowEventAtMs = System.currentTimeMillis()
        snapshotWindowsDiag()
        if (panelView != null) return

        // 快路径：已记住音量窗口 id —— 只做「这个 id 还在窗口列表里吗」的比对。
        // window.id 是本地元数据，不走节点 IPC，也不再全量遍历/子树扫描。
        if (panelWindowId > 0) {
            val stillThere = runCatching { windows }.getOrNull()?.any { it.id == panelWindowId } == true
            if (stillThere) {
                if (!panelWindowSeen) {
                    adoptPanelWindow(panelWindowId)
                } else {
                    mainHandler.removeCallbacks(delayedHideRunnable)
                    if (buttonView == null) showButton(viaPanel = true)
                }
                return
            }
            // 记住的 id 没了：窗口可能被重建换了 id，全量扫一次认领；认领不到才算消失
            val hit = findVolumePanelWindow()
            if (hit != null) {
                adoptPanelWindow(hit.id)
                return
            }
            if (panelWindowSeen) {
                Log.i(TAG, "onAccessibilityEvent: 音量面板窗口移除（id=$panelWindowId），防抖后隐藏")
                panelWindowSeen = false
                mainHandler.removeCallbacks(hideButtonRunnable)
                mainHandler.postDelayed(delayedHideRunnable, PANEL_HIDE_DEBOUNCE_MS)
            }
            return
        }

        // 冷路径：还没记住音量窗口 id。
        // 1) 事件直读零 IPC 命中（事件自带的包名/类名，不走窗口枚举）
        if (isVolumePanelEvent(event)) {
            val wid = if (event.windowId > 0) event.windowId else findVolumePanelWindow()?.id ?: -1
            adoptPanelWindow(wid)
            return
        }
        // 2) systemui 的窗口事件 = 音量条可能在弹出，启动短间隔补扫（最多 ~570ms 内命中）
        if (event.packageName?.toString()?.contains("systemui", ignoreCase = true) == true) {
            startProbeBurst()
        }
        // 3) 本事件顺手扫一次（保留 838a3e4 的即时性）
        findVolumePanelWindow()?.let { adoptPanelWindow(it.id) }
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
    private var lastSnapshotAt = 0L

    private fun snapshotWindowsDiag() {
        // 取 root 是每窗口一次 binder IPC；限频 1s，只做诊断不抢主线程。
        // 注意：这里绝不做子树遍历（getChild 更贵），否则触发事件被拖慢、按钮跟着迟钝。
        val now = System.currentTimeMillis()
        if (now - lastSnapshotAt < 1000) return
        lastSnapshotAt = now
        val list = runCatching { windows }.getOrNull() ?: return
        val snapshot = list.mapNotNull { window ->
            val root = runCatching { window.root }.getOrNull()
                ?: return@mapNotNull "id=${window.id} | <根节点不可用> | type=${window.type} layer=${window.layer}"
            val pkg = root.packageName?.toString() ?: "<null>"
            val cls = root.className?.toString() ?: "<null>"
            "$pkg | $cls | type=${window.type} layer=${window.layer} id=${window.id}"
        }.sorted().joinToString("\n")
        if (snapshot != windowsDiagSnapshot) {
            windowsDiagSnapshot = snapshot
            Log.d(TAG, "窗口列表变化:\n$snapshot")
        }
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
            val pos = currentPosition()
            shownPosition = pos
            val view = VolumeButtonView(this, s.sizeDp, resources.displayMetrics.density).apply {
                setOnClickListener { showPanel() }
            }
            val lp = WindowManager.LayoutParams(
                dp(s.sizeDp),
                dp((s.sizeDp * 0.62f).roundToInt()),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    // 允许内容画出窗口边界：没有它，平移动画里按钮滑出窗口的部分全被
                    // 裁剪掉 —— 滑入全程隐身，动画播完才在终点突然出现（表现为延迟）
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                // 音量条一般贴着按键一侧垂直居中，按钮放同侧；横竖屏位置各按各的配置
                gravity = (if (pos.side == ButtonSide.RIGHT) Gravity.END else Gravity.START) or
                    Gravity.CENTER_VERTICAL
                x = dp(pos.edgeMarginDp)
                y = ((pos.verticalPercent - 50) / 100f * resources.displayMetrics.heightPixels).toInt()
            }
            runCatching {
                windowManager.addView(view, lp)
                buttonView = view
                buttonShowing = true
                addWindowOk++
                playEnterAnimation(view, s, pos)
                Log.i(TAG, "showButton: 悬浮按钮已显示（viaPanel=$viaPanel, pos=${pos.side}/${pos.edgeMarginDp}dp/${pos.verticalPercent}%）")
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

    private fun playEnterAnimation(view: View, s: ButtonSettings, pos: ButtonPosition) {
        if (s.animDurationMs <= 0) return // 0 = 不动画，直接出现在落点
        when (s.animStyle) {
            ButtonAnimStyle.SLIDE -> {
                val direction = if (pos.side == ButtonSide.RIGHT) 1f else -1f
                // 出发点在屏幕外（视图宽 + 边距），平移到落点
                view.translationX = direction * (dp(s.sizeDp) + dp(pos.edgeMarginDp))
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
            val pos = shownPosition
            if (animate && s.animDurationMs > 0) {
                pendingOut = view
                val animator = when (s.animStyle) {
                    ButtonAnimStyle.SLIDE -> {
                        val direction = if (pos.side == ButtonSide.RIGHT) 1f else -1f
                        view.animate().translationX(direction * (dp(s.sizeDp) + dp(pos.edgeMarginDp)))
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
        FileLog.log("panel", "showPanel 开始")
        hideButton("打开浮窗") // 打开浮窗后按钮使命完成；音量键继续照常调系统音量

        panelView?.let {
            runCatching { windowManager.removeView(it) }
            panelView = null
        }
        panelPager = null
        panelDots = null
        panelEmptyText = null
        panelAdapter = null

        val dm = resources.displayMetrics
        val marginPx = dp(OverlayChrome.MARGIN_DP)
        val widthPx = minOf(dm.widthPixels - 2 * marginPx, dp(OverlayChrome.MAX_WIDTH_DP))
        val maxHPx = dm.heightPixels - 2 * marginPx // 高度上限 = 屏幕高 - 上下边距

        // 卡片 = 窗口本身，视觉参数与 ui/WindowKit.kt 的应用内浮窗完全一致
        // （白半透明 CARD_BG=0xB3FFFFFF、圆角 24dp）。样式参考荣耀音量面板：
        // 顶部居中标题 + 右上角设置按钮，中间一排纵向滑条，底部翻页圆点。
        // 毛玻璃做不了：overlay 悬浮在其他应用上面，拿不到对方像素，
        // 系统 blurBehind 在荣耀上又被强制成全屏模糊，只能纯半透明
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = OverlayChrome.roundedBg(
                this@VolumeWatcherService,
                OverlayChrome.CARD_BG,
                OverlayChrome.CARD_RADIUS_DP
            )
            setPadding(dp(12), dp(12), dp(12), dp(10))
        }

        // 标题行：居中标题 + 右上角设置按钮（点开应用主页）
        val titleRow = FrameLayout(this)
        val title = TextView(this).apply {
            text = "音量调节"
            setTextColor(0xE6000000.toInt())
            textSize = 17f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        titleRow.addView(
            title,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )
        // 设置按钮：与主页 IconButton 同款 —— Material 齿轮图标 24dp、无背景、
        // 48dp 触控区（点开应用主页）
        val gear = FrameLayout(this).apply {
            isClickable = true
            val icon = ImageView(this@VolumeWatcherService).apply {
                setImageResource(com.trseimc.appvolume.R.drawable.ic_settings)
                setColorFilter(0xE6000000.toInt())
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            addView(icon, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
            setOnClickListener {
                runCatching {
                    startActivity(
                        Intent(this@VolumeWatcherService, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
                hidePanel()
            }
        }
        titleRow.addView(
            gear,
            FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER_VERTICAL or Gravity.END)
        )
        card.addView(
            titleRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        // 纵向滑条翻页区（ViewPager2 不支持 wrap_content，钉固定高度）
        val pager = ViewPager2(this).apply {
            adapter = PanelPagerAdapter().also { panelAdapter = it }
            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    FileLog.log("panel", "onPageSelected=$position")
                    // 适配器里页序是反的（第一页在最右），换算回真实页号
                    updateDots(panelPages.size, panelPages.size - 1 - position)
                }
            })
        }
        panelPager = pager
        card.addView(
            pager,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(240)
            ).also { it.topMargin = dp(8) }
        )

        // 翻页圆点（第一页在最右）
        val dots = LinearLayout(this).apply { gravity = Gravity.CENTER }
        panelDots = dots
        card.addView(
            dots,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(10) }
        )

        // 空态文本
        val empty = TextView(this).apply {
            text = "现在没有应用在发声。播放音乐或视频后再按一次音量键。"
            setTextColor(0x99000000.toInt())
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(24), dp(8), dp(24))
        }
        panelEmptyText = empty
        card.addView(
            empty,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val lp = WindowManager.LayoutParams(
            widthPx,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 不做任何变暗：半透明和模糊效果只属于卡片矩形，窗口外内容原样可见
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            // 注意：不用 FLAG_BLUR_BEHIND——荣耀新系统会把 blurBehind 强制扩成全屏
            // 模糊（实测），其他应用的窗口也一样。overlay 面板悬浮在其他应用上面，
            // 拿不到对方的像素，自绘模糊不可行，只能纯半透明
        }

        // 点空白处（窗口外）关闭
        card.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                hidePanel()
                true
            } else {
                false
            }
        }

        // 先量自然高度：超出上限就把窗口钉在上限上
        card.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        lp.height = card.measuredHeight.coerceAtMost(maxHPx)
        FileLog.log("panel", "measure 完：宽=$widthPx 高=${card.measuredHeight} 最终窗口高=${lp.height}（上限 $maxHPx）")

        rebuildRows()
        runCatching {
            windowManager.addView(card, lp)
            panelView = card
            FileLog.log("panel", "addView 成功")
        }.onFailure {
            FileLog.log("panel", "addView 失败", it)
            panelView = null
            panelPager = null
            panelDots = null
            panelEmptyText = null
            panelAdapter = null
        }
    }

    /** 发声应用集合变化后重算窗口高度：内容变少时收回来，变多时封顶滚动。 */
    private fun resizePanel() {
        val root = panelView ?: return
        val dm = resources.displayMetrics
        val widthPx = minOf(
            dm.widthPixels - 2 * dp(OverlayChrome.MARGIN_DP),
            dp(OverlayChrome.MAX_WIDTH_DP)
        )
        root.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val lp = root.layoutParams as? WindowManager.LayoutParams ?: return
        lp.height = root.measuredHeight.coerceAtMost(dm.heightPixels - 2 * dp(OverlayChrome.MARGIN_DP))
        runCatching { windowManager.updateViewLayout(root, lp) }
    }

    private fun hidePanel() {
        panelView?.let {
            runCatching { windowManager.removeView(it) }
            panelView = null
        }
        panelPager = null
        panelDots = null
        panelEmptyText = null
        panelAdapter = null
        draggingPkg = null
    }

    /** 按 SessionRegistry 当前的发声应用重建滑条翻页。 */
    private fun rebuildRows() {
        val pager = panelPager ?: return
        val adapter = panelAdapter ?: return
        val dots = panelDots ?: return
        val empty = panelEmptyText ?: return
        val sessions = SessionRegistry.sessions.value
            .filter { it.packageName != packageName }
            .groupBy { it.packageName }

        mainHandler.post {
            val pkgs = sessions.keys.toList()
            panelPages = pkgs.chunked(PAGE_SIZE)
            FileLog.log("panel", "rebuildRows：应用数=${pkgs.size} 页数=${panelPages.size}")
            if (panelPages.isEmpty()) {
                pager.visibility = View.GONE
                dots.visibility = View.GONE
                empty.visibility = View.VISIBLE
                resizePanel()
                return@post
            }
            empty.visibility = View.GONE
            pager.visibility = View.VISIBLE
            dots.visibility = View.VISIBLE
            // 页序反过来放：第一页显示在最右，其它页在左（对齐荣耀面板）
            adapter.pages = panelPages.reversed()
            adapter.notifyDataSetChanged()
            FileLog.log("panel", "notifyDataSetChanged 后 setCurrentItem=${adapter.itemCount - 1}")
            pager.setCurrentItem(adapter.itemCount - 1, false)
            updateDots(panelPages.size, 0)
            resizePanel()
        }
    }

    /** 翻页圆点：page 0 的点在最右，active 的点高亮。 */
    private fun updateDots(pageCount: Int, activePage: Int) {
        val dots = panelDots ?: return
        dots.removeAllViews()
        if (pageCount <= 1) return
        for (page in pageCount - 1 downTo 0) {
            val dot = View(this).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(if (page == activePage) 0xFF7CB8FF.toInt() else 0x33000000)
                }
            }
            dots.addView(
                dot,
                LinearLayout.LayoutParams(dp(8), dp(8)).also { p ->
                    p.marginStart = dp(5)
                    p.marginEnd = dp(5)
                }
            )
        }
    }

    /** 一页中的一列：纵向滑条（底部应用图标）+ 应用名。返回 null 表示包已卸载、画不了。 */
    private fun buildSliderColumn(pkg: String): View? {
        val pm = packageManager
        val info = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() ?: return null
        val label = runCatching { pm.getApplicationLabel(info).toString() }.getOrDefault(pkg)
        val state = configs[pkg] ?: AppVolumeState.DEFAULT
        FileLog.log("panel", "buildSliderColumn：$pkg value=${state.gainSlider}")

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(6), dp(8), dp(6), 0)
        }

        val slider = VolumeSliderView(this).apply {
            value = state.gainSlider
            icon = runCatching { pm.getApplicationIcon(pkg) }.getOrNull()
            onStartDrag = {
                draggingPkg = pkg
                // 和主界面同一个套路：拖动期间轮询同步豁免，别拿旧保存值顶回实时值
                SessionEffectEngine.suppressedPackage = pkg
            }
            onValueChange = { v, final ->
                val merged = AppVolumeState(
                    gainSlider = v,
                    pan = configs[pkg]?.pan ?: 0f
                )
                configs[pkg] = merged
                livePush.value = LivePush(pkg, merged, final = final)
                if (final) draggingPkg = null
            }
        }
        col.addView(
            slider,
            LinearLayout.LayoutParams(dp(52), dp(190))
        )

        val name = TextView(this).apply {
            text = label
            setTextColor(0xE6000000.toInt())
            textSize = 12f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
        }
        col.addView(
            name,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { p -> p.topMargin = dp(8) }
        )
        return col
    }

    /** 音量面板的翻页适配器：一页最多 5 个应用的纵向滑条。 */
    private inner class PanelPagerAdapter :
        androidx.recyclerview.widget.RecyclerView.Adapter<PageViewHolder>() {

        var pages: List<List<String>> = emptyList()

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): PageViewHolder {
            FileLog.log("panel", "onCreateViewHolder")
            val row = LinearLayout(this@VolumeWatcherService).apply {
                orientation = LinearLayout.HORIZONTAL
                // ViewPager2 强制页视图必须 match_parent（默认给的是 wrap_content，
                // 布局阶段直接抛 "Pages must fill the whole ViewPager2" 闪退）
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            return PageViewHolder(row)
        }

        override fun getItemCount(): Int = pages.size

        override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
            val row = holder.row
            row.removeAllViews()
            for (pkg in pages[position]) {
                buildSliderColumn(pkg)?.let { col ->
                    row.addView(
                        col,
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                    )
                }
            }
        }
    }

    /** 翻页的页 ViewHolder（inner class 里不能嵌套非 inner class，放外层）。 */
    private class PageViewHolder(val row: LinearLayout) :
        androidx.recyclerview.widget.RecyclerView.ViewHolder(row)

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
        cancelProbeBurst()
        hideButton("服务解绑", animate = false)
        hidePanel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        watchConnected = false
        Log.i(TAG, "onUnbind: 无障碍服务解绑")
        cancelProbeBurst()
        hideButton("服务解绑", animate = false)
        hidePanel()
        return super.onUnbind(intent)
    }

    override fun onInterrupt() = Unit

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "AppVolumeWatch"

        /** 音量面板一页最多几个应用，超出的横向翻页。 */
        private const val PAGE_SIZE = 5

        // 面板消失 → 真隐藏按钮的防抖时长。防抖到点有「窗口 id 还在吗」复核兜底，
        // 防抖本身不承担防误判职责，取短保持「跟着音量条消失」的跟手度
        private const val PANEL_HIDE_DEBOUNCE_MS = 150L

        // 冷启动补扫的间隔序列（毫秒）：systemui 窗口事件后按此节奏探几次音量窗口，
        // 覆盖弹出动画期（节点解析不出的时段），命中即停
        private val PROBE_DELAYS_MS = longArrayOf(60L, 90L, 120L, 160L, 200L)

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
