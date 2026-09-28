package com.trseimc.appvolume.ui

import android.app.Application
import android.content.pm.ApplicationInfo
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.trseimc.appvolume.apps.AppEntry
import com.trseimc.appvolume.apps.AppScanner
import com.trseimc.appvolume.apps.ProcessScanner
import com.trseimc.appvolume.overlay.VolumeWatcherService
import com.trseimc.appvolume.apps.RunState
import com.trseimc.appvolume.audio.AppVolumeState
import com.trseimc.appvolume.audio.ApplyResult
import com.trseimc.appvolume.audio.EngineCapability
import com.trseimc.appvolume.audio.SessionEffectEngine
import com.trseimc.appvolume.audio.SessionRegistry
import com.trseimc.appvolume.audio.SessionScanner
import com.trseimc.appvolume.audio.SessionVolumeEngine
import com.trseimc.appvolume.audio.ShizukuBridge
import com.trseimc.appvolume.data.VolumeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppVolumeViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application
    private val usageScanner = AppScanner(application)
    private val processScanner = ProcessScanner(application)
    private val sessionScanner = SessionScanner(application)
    private val engine = SessionVolumeEngine()
    private val shizuku = ShizukuBridge.obtain(application)
    private val repository = VolumeRepository(application)

    val volumes: StateFlow<Map<String, AppVolumeState>> =
        repository.all.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    // 列表 = 【进程表里正在运行】 ∪ 【会话表里正在发声】，其它一律不进主列表。
    // 这三路都是即时数据，UsageStats 只贡献「最后使用时间」和「前台/后台」标注。
    private val _runningPackages = MutableStateFlow<Set<String>>(emptySet())
    private val _activePackages = MutableStateFlow<Set<String>>(emptySet())
    private val _usageTimes = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val _usageStates = MutableStateFlow<Map<String, RunState>>(emptyMap())

    /**
     * 「最近打开」—— 既不在进程表、也没有音频会话，但使用记录显示它还没收尾。
     * 界面上单独一段、默认收起，绝不混进「正在运行」里冒充实时数据。
     */
    private val _recentPackages = MutableStateFlow<Set<String>>(emptySet())

    val apps: StateFlow<List<AppEntry>> = combine(
        _runningPackages, _activePackages, _recentPackages, _usageTimes, _usageStates
    ) { running, playing, recent, times, states ->
        buildEntries(running, playing, recent, times, states)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _includeSystem = MutableStateFlow(true)
    val includeSystem: StateFlow<Boolean> = _includeSystem

    private val _hasUsageAccess = MutableStateFlow(false)
    val hasUsageAccess: StateFlow<Boolean> = _hasUsageAccess

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing

    private val _shizukuState = MutableStateFlow(ShizukuBridge.State.NOT_INSTALLED)
    val shizukuState: StateFlow<ShizukuBridge.State> = _shizukuState

    // Shizuku 各环节的实测状态，直接显示给用户 —— 绑不上时这是唯一的线索来源
    val shizukuDiag: StateFlow<String> = shizuku.diag

    /** 一键诊断的完整输出，多行，直接贴在卡片里。 */
    private val _shizukuProbe = MutableStateFlow<String?>(null)
    val shizukuProbe: StateFlow<String?> = _shizukuProbe

    // DUMP 权限决定了能不能自己跑 dumpsys 拿会话表，是整套方案的前提
    private val _hasDump = MutableStateFlow(false)
    val hasDump: StateFlow<Boolean> = _hasDump

    // 进程表的清单权限。和「使用情况访问」开关（只改 appop）不是一回事，见 SessionScanner
    private val _hasUsageStatsPermission = MutableStateFlow(false)
    val hasUsageStatsPermission: StateFlow<Boolean> = _hasUsageStatsPermission

    val sessionSource: StateFlow<SessionRegistry.Source> = SessionRegistry.source
    val sessionError: StateFlow<String?> = SessionRegistry.error

    val activePackages: StateFlow<Set<String>> = _activePackages

    /** 进程表是否可用（拿到 DUMP 权限才有），界面据此说明列表的完整性。 */
    private val _hasProcessTable = MutableStateFlow(false)
    val hasProcessTable: StateFlow<Boolean> = _hasProcessTable

    /** 进程表读不到、退化成「使用记录」兜底时为 true。 */
    private val _degraded = MutableStateFlow(false)
    val degraded: StateFlow<Boolean> = _degraded

    // 音量键悬浮控制（可选功能）的两个开关
    private val _accessibilityOn = MutableStateFlow(false)
    val accessibilityOn: StateFlow<Boolean> = _accessibilityOn

    private val _overlayGranted = MutableStateFlow(false)
    val overlayGranted: StateFlow<Boolean> = _overlayGranted

    /** 进程表是靠借 Shizuku 的 shell 身份读到的（本进程权限被 ROM 拒了）。 */
    private val _processViaShell = MutableStateFlow(false)
    val processViaShell: StateFlow<Boolean> = _processViaShell

    /** 列表每个来源的实测结果，多行文本，直接贴在卡片里。列表对不上时就靠它定位。 */
    private val _sourceDiag = MutableStateFlow("尚未扫描")
    val sourceDiag: StateFlow<String> = _sourceDiag

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status

    val visibleApps: StateFlow<List<AppEntry>> =
        combine(apps, _includeSystem) { list, includeSystem ->
            if (includeSystem) list else list.filter { !it.isSystemApp }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** DUMP 授权只自动尝试一次，避免前台 8 秒轮询时反复失败刷屏。 */
    private var grantAttempted = false

    // 拖动滑条的实时推送队列：null 初始，Pair 只保最新（conflate）
    private val livePush = MutableStateFlow<Pair<AppEntry, AppVolumeState>?>(null)

    // 各应用的延迟落盘任务，去抖用；只在 init 的 worker 协程里读写
    private val pendingSaves = HashMap<String, Job>()

    init {
        // 后台服务在扫会话，这里负责把结果反映到列表上
        viewModelScope.launch {
            SessionRegistry.sessions.collect { sessions ->
                _activePackages.value = sessions.mapTo(HashSet()) { it.packageName }
            }
        }
        // 用户在 Shizuku 里点完「允许」后立刻重跑一次，不用等下一个轮询周期
        shizuku.onPermissionResult = { granted -> if (granted) refresh(forceGrant = true) }

        // 拖动滑条的实时下发通道。StateFlow 天然 conflate：拖动产生的中间值会被丢掉，
        // 只留下最新值交给唯一的 worker 顺序执行 —— 既做到实时生效，又不会堆积协程、
        // 也不会出现旧值晚到把新值覆盖回去的乱序问题。
        viewModelScope.launch(Dispatchers.Default) {
            livePush.filterNotNull().collect { (target, state) ->
                engine.applyVolume(target, state)
                // 持久化去抖：拖动期间别每个 tick 都写一次 DataStore，
                // 停顿 250ms 再落盘。后台服务 1.5s 轮询用的是这份配置，
                // 拖动中哪怕轮询读到旧值，下一次实时推送也会立刻纠正。
                pendingSaves.remove(target.packageName)?.cancel()
                pendingSaves[target.packageName] = viewModelScope.launch {
                    delay(SAVE_DEBOUNCE_MS)
                    repository.save(target.packageName, state)
                }
            }
        }

        refresh()
    }

    fun refresh(forceGrant: Boolean = false) {
        viewModelScope.launch {
            if (forceGrant) grantAttempted = false
            _isRefreshing.value = true
            _accessibilityOn.value = withContext(Dispatchers.Default) {
                VolumeWatcherService.isEnabled(app)
            }
            _overlayGranted.value = Settings.canDrawOverlays(app)
            val usage = usageScanner.hasUsageAccess()
            _hasUsageAccess.value = usage

            val shizukuState = withContext(Dispatchers.Default) { shizuku.state() }
            _shizukuState.value = shizukuState

            var hasDump = withContext(Dispatchers.Default) { sessionScanner.hasDumpPermission() }
            // Shizuku 可用时补一次授权（DUMP + PACKAGE_USAGE_STATS）：AMS 的 dump 两个都要，
            // 只授过一次 DUMP 的老版本留下的状态会缺一半。grantAttempted 保证一次会话只试一次。
            if (shizukuState == ShizukuBridge.State.READY && !grantAttempted) {
                grantAttempted = true
                val error = shizuku.grantDumpPermission()
                hasDump = withContext(Dispatchers.Default) { sessionScanner.hasDumpPermission() }
                if (!hasDump) {
                    _status.value = "授予权限失败：${error ?: "pm grant 无输出"}"
                }
            }
            _hasDump.value = hasDump
            _hasUsageStatsPermission.value = withContext(Dispatchers.Default) {
                processScanner.hasUsageStatsPermission()
            }
            _hasProcessTable.value = withContext(Dispatchers.Default) { processScanner.hasPermission() }

            // 主数据源：系统进程表。先试本进程的权限，不行就借 Shizuku 的 shell 身份（uid 2000
            // 天然过得了那道「DUMP + USAGE_STATS」的权限门）
            val local = if (_hasProcessTable.value) {
                withContext(Dispatchers.Default) { processScanner.scan() }
            } else {
                ProcessScanner.Snapshot(emptySet(), emptyList())
            }
            var snapshot = local
            var viaShell = false
            if (!local.worked) {
                val shellSnapshot = withContext(Dispatchers.IO) {
                    processScanner.scanVia { command -> shizuku.runShell(command) }
                }
                if (shellSnapshot.worked) {
                    snapshot = shellSnapshot
                    viaShell = true
                } else if (local.probes.isEmpty()) {
                    // 本进程连原因都没记下来（没权限时不会去 exec），就把 shell 的结果留下
                    snapshot = shellSnapshot
                }
            }
            _processViaShell.value = viaShell

            // 使用记录：只用来标注「最后使用时间 / 前台后台」，外加一段单独展示的「最近打开」
            val times = if (usage) withContext(Dispatchers.Default) { usageScanner.lastUsed(USAGE_WINDOW_MS) } else emptyMap()
            val events = if (usage) withContext(Dispatchers.Default) { usageScanner.lastEvents(USAGE_WINDOW_MS) } else emptyMap()

            val running = snapshot.packages
            val playing = _activePackages.value
            val recent = if (usage) {
                withContext(Dispatchers.Default) { usageScanner.openPackages(RECENT_WINDOW_MS) }
            } else {
                emptySet()
            }
            val foldedRecent = recent.filterTo(HashSet()) { it !in running && it !in playing }

            _degraded.value = !snapshot.worked
            _runningPackages.value = running
            _recentPackages.value = foldedRecent
            _usageTimes.value = times
            _usageStates.value = events.mapValues { (pkg, _) -> usageScanner.stateOf(pkg, events) }
            _sourceDiag.value = describe(snapshot, usage, times.size, foldedRecent.size)
            _isRefreshing.value = false
        }
    }
    /**
     * 把「正在运行」「正在发声」「最近打开」合成一张列表。
     *
     * 前两路都是「此刻」的即时事实，排在前面；「最近打开」来自使用记录，可能早就退出了，
     * 所以永远排在最后，并且在界面上单独一段、默认收起 —— 不许它冒充「正在运行」。
     */
    private fun buildEntries(
        running: Set<String>,
        playing: Set<String>,
        recent: Set<String>,
        times: Map<String, Long>,
        states: Map<String, RunState>
    ): List<AppEntry> {
        val pm = app.packageManager
        val merged = LinkedHashMap<String, AppEntry>()

        for (pkg in running + playing + recent) {
            if (pkg == app.packageName) continue
            val info = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() ?: continue
            merged[pkg] = AppEntry(
                packageName = pkg,
                label = pm.getApplicationLabel(info).toString(),
                uid = info.uid,
                lastUsedMillis = times[pkg] ?: 0L,
                runState = states[pkg] ?: RunState.BACKGROUND,
                isSystemApp = isPureSystemApp(info),
                isRunning = pkg in running,
                isPlayingAudio = pkg in playing
            )
        }

        return merged.values.sortedWith(
            compareByDescending<AppEntry> { it.isPlayingAudio }
                .thenByDescending { it.isRunning }
                .thenBy { it.runState.order }
                .thenBy { it.label }
        )
    }

    /**
     * 「第三方/系统」的判据。
     *
     * 不能用裸的 FLAG_SYSTEM：国产 ROM 预装的微信、腾讯视频这类都会带上它，
     * 结果是「我自己装的应用明明开着却不在列表里」。用户更新过（FLAG_UPDATED_SYSTEM_APP）
     * 或者已经落到 /data 里的，一律当第三方。
     */
    private fun isPureSystemApp(info: ApplicationInfo): Boolean {
        if ((info.flags and ApplicationInfo.FLAG_SYSTEM) == 0) return false
        if ((info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) return false
        val dir = info.sourceDir ?: return true
        return !dir.startsWith("/data/") && !dir.contains("/data/app")
    }

    private fun describe(
        snapshot: ProcessScanner.Snapshot,
        usage: Boolean,
        usageCount: Int,
        recentCount: Int
    ): String = buildString {
        append("进程表（「正在运行」一段就来自这里）\n")
        if (snapshot.probes.isEmpty()) {
            append("· 未执行（权限没凑齐：DUMP + PACKAGE_USAGE_STATS 都要有）")
        } else {
            snapshot.probes.forEachIndexed { index, probe ->
                if (index > 0) append('\n')
                append("· ").append(probe.command).append(" → ").append(probe.count).append(" 个")
                append("（").append(probe.note).append("）")
            }
        }
        if (_processViaShell.value) {
            append("\n· 本进程权限被拒，以上是借 Shizuku 的 shell 身份读到的")
        }
        append('\n').append("会话表（「正在发声」一段）\n")
        val sessions = SessionRegistry.sessions.value
        append("· dumpsys media.audio_flinger → ").append(sessions.size).append(" 条会话")
        // 归属看 uid 而不是表里的 name：audioserver 打印的 name 是它自己按 uid 反查的缓存，会过期
        sessions.forEach { session ->
            append("\n· session=").append(session.sessionId)
                .append(" pid=").append(session.pid)
                .append(" uid=").append(session.uid)
                .append(" 表里写的=").append(session.rawName.ifBlank { "<空>" })
                .append(" → 归属 ").append(session.packageName)
        }
        // 原始行：解析漏没漏、列对没对，一眼可查
        SessionRegistry.rawDiag.value?.let { raw ->
            append("\n· 原始行（ROM 打印的原样）\n")
            raw.lineSequence().forEach { line ->
                append("  ").append(line).append('\n')
            }
        }
        append('\n').append("使用记录\n")
        append("· appop ").append(if (usage) "已允许" else "未允许")
            .append("，窗口内 ").append(usageCount).append(" 个应用")
        append("，「最近打开」一段收了 ").append(recentCount).append(" 个")
    }

    fun setIncludeSystem(enabled: Boolean) {
        _includeSystem.value = enabled
    }

    fun requestShizukuPermission() {
        shizuku.requestPermission()
    }

    /**
     * 用户点「一键补齐」：让 Shizuku 跑 pm grant / appops（DUMP + PACKAGE_USAGE_STATS + appop）。
     * 成功与否都会在状态条上写清楚，绝不静默。
     */
    fun grantDumpViaShizuku() {
        viewModelScope.launch {
            val error = shizuku.grantDumpPermission()
            _hasDump.value = withContext(Dispatchers.Default) { sessionScanner.hasDumpPermission() }
            _hasUsageStatsPermission.value = withContext(Dispatchers.Default) {
                processScanner.hasUsageStatsPermission()
            }
            _hasProcessTable.value = withContext(Dispatchers.Default) { processScanner.hasPermission() }
            _shizukuState.value = withContext(Dispatchers.Default) { shizuku.state() }
            _status.value = if (_hasProcessTable.value) {
                "权限已补齐，进程表和会话表现在都能读了"
            } else {
                "授权没成：${error ?: "pm grant 无输出"}"
            }
            refresh()
        }
    }

    /** shell 服务卡住时的手动重连：解绑旧进程、重新拉起、再试一次授权。 */
    fun reconnectShell() {
        viewModelScope.launch {
            val error = shizuku.reconnect()
            _shizukuState.value = withContext(Dispatchers.Default) { shizuku.state() }
            error?.let { _status.value = it }
            refresh(forceGrant = true)
        }
    }

    fun runDiagnostic() {
        viewModelScope.launch {
            _shizukuProbe.value = "正在诊断…"
            _shizukuProbe.value = withContext(Dispatchers.IO) { shizuku.probe() }
            _hasDump.value = withContext(Dispatchers.Default) { sessionScanner.hasDumpPermission() }
            _shizukuState.value = withContext(Dispatchers.Default) { shizuku.state() }
        }
    }

    /** Shizuku 死活拉不起来时的兜底：三条命令，用电脑 adb 敲一次即可（和设备上 Shizuku 执行的完全一致）。 */
    fun adbGrantCommands(): List<String> = ShizukuBridge.adbGrantCommands(app.packageName)

    fun consumeStatus() {
        _status.value = null
    }

    /** 保存配置。不管引擎能不能下发，用户调过的值都记下来。 */
    fun saveVolume(app: AppEntry, state: AppVolumeState) {
        viewModelScope.launch { repository.save(app.packageName, state) }
    }

    /**
     * 拖动滑条时的实时推送：只走引擎（幂等、有缓存，增益没变就不重写），
     * 落盘交给 init 里那条去抖 worker。松手后的最终值由 [pushToEngine] 收尾。
     * 拖动期间轮询同步会跳过这个包，保存值不会把实时值顶回去。
     */
    fun pushLive(app: AppEntry, state: AppVolumeState) {
        SessionEffectEngine.suppressedPackage = app.packageName
        livePush.value = app to state
    }

    /** 把配置真正推给目标应用。失败原因会原样显示给用户，不做美化。 */
    fun pushToEngine(app: AppEntry, state: AppVolumeState) {
        viewModelScope.launch {
            repository.save(app.packageName, state)
            val result = withContext(Dispatchers.Default) { engine.applyVolume(app, state) }
            // 拖动结束，把轮询同步的豁免交还。只清自己名下的，别误伤别的拖动
            if (SessionEffectEngine.suppressedPackage == app.packageName) {
                SessionEffectEngine.suppressedPackage = null
            }
            _status.value = when (result) {
                is ApplyResult.Ok -> result.detail
                is ApplyResult.Failed -> result.reason
            }
        }
    }

    suspend fun capabilityOf(app: AppEntry): EngineCapability =
        withContext(Dispatchers.Default) { engine.capability(app) }

    private companion object {
        // 使用记录的回溯窗口，只用来标注「最后使用时间」和前台/后台
        const val USAGE_WINDOW_MS = 6 * 60 * 60 * 1000L

        // 「最近打开」一段的窗口：这么近还开着，大概率只是被切到后台、进程没死
        const val RECENT_WINDOW_MS = 30 * 60 * 1000L

        // 拖动期间配置落盘的去抖间隔
        const val SAVE_DEBOUNCE_MS = 250L
    }
}
