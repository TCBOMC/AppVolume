package com.trseimc.appvolume.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.trseimc.appvolume.apps.AppEntry
import com.trseimc.appvolume.audio.AppVolumeState
import com.trseimc.appvolume.audio.AudioCurve
import com.trseimc.appvolume.data.ButtonAnimStyle
import com.trseimc.appvolume.data.ButtonSettings
import com.trseimc.appvolume.data.ButtonSide
import com.trseimc.appvolume.audio.ShizukuBridge
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

// 停留在前台时的列表刷新间隔
private const val FOREGROUND_REFRESH_INTERVAL_MS = 8_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppListScreen(vm: AppVolumeViewModel) {
    val context = LocalContext.current
    val apps by vm.apps.collectAsStateWithLifecycle()
    val visibleApps by vm.visibleApps.collectAsStateWithLifecycle()
    val volumes by vm.volumes.collectAsStateWithLifecycle()
    val hasUsageAccess by vm.hasUsageAccess.collectAsStateWithLifecycle()
    val includeSystem by vm.includeSystem.collectAsStateWithLifecycle()
    val refreshing by vm.isRefreshing.collectAsStateWithLifecycle()
    val shizukuState by vm.shizukuState.collectAsStateWithLifecycle()
    val shizukuDiag by vm.shizukuDiag.collectAsStateWithLifecycle()
    val shizukuProbe by vm.shizukuProbe.collectAsStateWithLifecycle()
    val hasDump by vm.hasDump.collectAsStateWithLifecycle()
    val hasUsageStatsPermission by vm.hasUsageStatsPermission.collectAsStateWithLifecycle()
    val hasProcessTable by vm.hasProcessTable.collectAsStateWithLifecycle()
    val processViaShell by vm.processViaShell.collectAsStateWithLifecycle()
    val degraded by vm.degraded.collectAsStateWithLifecycle()
    val sourceDiag by vm.sourceDiag.collectAsStateWithLifecycle()
    val activePackages by vm.activePackages.collectAsStateWithLifecycle()
    val accessibilityOn by vm.accessibilityOn.collectAsStateWithLifecycle()
    val overlayGranted by vm.overlayGranted.collectAsStateWithLifecycle()
    val watcherConnected by vm.watcherConnected.collectAsStateWithLifecycle()
    val buttonSettings by vm.buttonSettings.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(status) {
        status?.let {
            snackbarHostState.showSnackbar(it)
            vm.consumeStatus()
        }
    }

    // 回到前台立刻刷一次（刚从授权页返回时靠这个），并在前台期间定期刷新。
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            vm.refresh()
            while (true) {
                delay(FOREGROUND_REFRESH_INTERVAL_MS)
                vm.refresh()
            }
        }
    }

    var selectedApp by remember { mutableStateOf<AppEntry?>(null) }
    var showSettings by remember { mutableStateOf(false) }

    // 权限诊断默认收起 —— 之前它平铺在列表上方，内容一多就把整屏占满，
    // LazyColumn 拿不到剩余高度，列表完全滚不出来。现在所有内容都在同一个 LazyColumn 里，
    // 想看多长都能滚；只有「一个权限都没拿到」这种死局才自动展开。
    var diagExpanded by remember { mutableStateOf(false) }
    val autoExpand = !hasDump && !hasProcessTable
    val showDiag = diagExpanded || autoExpand

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("后台应用音量")
                        Text(
                            text = if (refreshing) {
                                "正在扫描…"
                            } else {
                                buildString {
                                    append("发声 ").append(activePackages.size).append(" 个")
                                    append(" · 列表 ").append(visibleApps.size).append(" 个")
                                    if (!hasProcessTable) append(" · 进程表不可用")
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    TextButton(onClick = { vm.setIncludeSystem(!includeSystem) }) {
                        Text(if (includeSystem) "含系统组件" else "仅第三方")
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "悬浮按钮设置")
                    }
                    IconButton(onClick = vm::refresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新列表")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item(key = "status") {
                StatusCard(
                    hasDump = hasDump,
                    hasUsageStatsPermission = hasUsageStatsPermission,
                    hasProcessTable = hasProcessTable,
                    processViaShell = processViaShell,
                    degraded = degraded,
                    shizukuState = shizukuState,
                    showDiag = showDiag,
                    onToggleDiag = { diagExpanded = !diagExpanded },
                    onOpenUsageAccess = {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                        }
                    },
                    vm = vm
                )
            }

            if (showDiag) {
                item(key = "diag") {
                    DiagCard(
                        sourceDiag = sourceDiag,
                        shizukuDiag = shizukuDiag,
                        shizukuProbe = shizukuProbe,
                        adbCommands = remember { vm.adbGrantCommands() },
                        onCopy = { copyToClipboard(context, it) }
                    )
                }
            }

            if (!accessibilityOn || !overlayGranted) {
                item(key = "overlay_perm") {
                    OverlayPermissionCard(
                        accessibilityOn = accessibilityOn,
                        overlayGranted = overlayGranted,
                        onOpenAccessibility = {
                            runCatching {
                                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            }
                        },
                        onOpenOverlay = {
                            runCatching {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        android.net.Uri.parse("package:${context.packageName}")
                                    )
                                )
                            }
                        }
                    )
                }
            }

            if (accessibilityOn && !watcherConnected) {
                item(key = "watcher_not_connected") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Text("无障碍服务没有连上", style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = "开关显示已打开，但服务实际没被系统绑定 —— 按音量键不会有任何反应。" +
                                    "把无障碍里的「音量键悬浮控制」关掉再打开一次；侧载应用若开关被灰掉，" +
                                    "先去系统「应用信息」里点「允许受限设置」。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (apps.isEmpty() && !refreshing) {
                item(key = "empty") {
                    EmptyState(
                        hasDump = hasDump,
                        hasProcessTable = hasProcessTable,
                        hasUsageAccess = hasUsageAccess,
                        includeSystem = includeSystem,
                        onOpenUsageAccess = {
                            runCatching {
                                context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                            }
                        }
                    )
                }
            } else {
                // 「仅第三方」对三段同时生效；正在发声的系统组件也会被隐藏，点「含系统组件」可看
                val visible = apps.filter { includeSystem || !it.isSystemApp }
                val playing = visible.filter { it.isPlayingAudio }
                val running = visible.filter { it.isRunning && !it.isPlayingAudio }
                val recent = visible.filter { !it.isRunning && !it.isPlayingAudio }

                if (playing.isNotEmpty()) {
                    item(key = "h_playing") { SectionHeader("正在发声 · 可以立即调节", playing.size) }
                    items(playing, key = { it.packageName }) { AppRow(it, volumes[it.packageName] ?: AppVolumeState.DEFAULT) { selectedApp = it } }
                }
                if (running.isNotEmpty()) {
                    item(key = "h_running") { SectionHeader("正在运行", running.size) }
                    items(running, key = { it.packageName }) { AppRow(it, volumes[it.packageName] ?: AppVolumeState.DEFAULT) { selectedApp = it } }
                }
                if (recent.isNotEmpty()) {
                    item(key = "h_recent") {
                        RecentHeader(
                            count = recent.size,
                            note = if (hasProcessTable) {
                                "进程表正常时这一段通常很短"
                            } else {
                                "进程表还没权限读，先靠这段凑合；补齐权限后这里只留真正还开着的"
                            }
                        )
                    }
                    items(recent, key = { it.packageName }) { AppRow(it, volumes[it.packageName] ?: AppVolumeState.DEFAULT) { selectedApp = it } }
                }
            }

            if (refreshing && apps.isEmpty()) {
                item(key = "loading") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        horizontalArrangement = Arrangement.Center
                    ) { CircularProgressIndicator() }
                }
            }
        }
    }

    selectedApp?.let { app ->
        VolumeSheet(
            app = app,
            initial = volumes[app.packageName] ?: AppVolumeState.DEFAULT,
            vm = vm,
            onDismiss = { selectedApp = null }
        )
    }

    if (showSettings) {
        ButtonSettingsSheet(
            settings = buttonSettings,
            onUpdate = vm::updateButtonSettings,
            onDismiss = { showSettings = false }
        )
    }
}

/**
 * 悬浮按钮设置浮窗：消失时间、动画样式、动画时长、位置、大小。
 * 滑条拖动中只更新本地值，松手才写盘 —— 别让一次拖动打出几十次 DataStore 写入。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ButtonSettingsSheet(
    settings: ButtonSettings,
    onUpdate: ((ButtonSettings) -> ButtonSettings) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("悬浮按钮设置", style = MaterialTheme.typography.titleMedium)
            Text(
                "按键触发：超时自动消失；随音量条触发：与音量条同步出没，不按时间。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            SettingLabel("消失时间", "${"%.1f".format(settings.hideTimeoutMs / 1000f)} 秒")
            var timeout by remember(settings.hideTimeoutMs) { mutableStateOf(settings.hideTimeoutMs.toFloat()) }
            Slider(
                value = timeout,
                onValueChange = { timeout = it },
                onValueChangeFinished = {
                    onUpdate { s -> s.copy(hideTimeoutMs = ((timeout / 500).roundToInt().coerceIn(1, 20)) * 500) }
                },
                valueRange = 500f..10000f
            )

            SettingLabel("动画样式")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = settings.animStyle == ButtonAnimStyle.SLIDE,
                    onClick = { onUpdate { it.copy(animStyle = ButtonAnimStyle.SLIDE) } },
                    label = { Text("平移") }
                )
                FilterChip(
                    selected = settings.animStyle == ButtonAnimStyle.FADE,
                    onClick = { onUpdate { it.copy(animStyle = ButtonAnimStyle.FADE) } },
                    label = { Text("渐显/渐隐") }
                )
            }

            SettingLabel("动画时长", "${settings.animDurationMs} ms")
            var animMs by remember(settings.animDurationMs) { mutableStateOf(settings.animDurationMs.toFloat()) }
            Slider(
                value = animMs,
                onValueChange = { animMs = it },
                onValueChangeFinished = {
                    onUpdate { s -> s.copy(animDurationMs = ((animMs / 50).roundToInt().coerceIn(2, 12)) * 50) }
                },
                valueRange = 100f..600f
            )

            SettingLabel("位置")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = settings.side == ButtonSide.LEFT,
                    onClick = { onUpdate { it.copy(side = ButtonSide.LEFT) } },
                    label = { Text("左侧") }
                )
                FilterChip(
                    selected = settings.side == ButtonSide.RIGHT,
                    onClick = { onUpdate { it.copy(side = ButtonSide.RIGHT) } },
                    label = { Text("右侧") }
                )
            }
            SettingLabel("纵向位置", "${settings.verticalPercent}%（0 顶 / 100 底）")
            var vertical by remember(settings.verticalPercent) { mutableStateOf(settings.verticalPercent.toFloat()) }
            Slider(
                value = vertical,
                onValueChange = { vertical = it },
                onValueChangeFinished = {
                    onUpdate { s -> s.copy(verticalPercent = (vertical / 5).roundToInt().coerceIn(2, 18) * 5) }
                },
                valueRange = 10f..90f
            )

            SettingLabel("大小", "${settings.sizeDp} dp")
            var size by remember(settings.sizeDp) { mutableStateOf(settings.sizeDp.toFloat()) }
            Slider(
                value = size,
                onValueChange = { size = it },
                onValueChangeFinished = {
                    onUpdate { s -> s.copy(sizeDp = (size / 4).roundToInt().coerceIn(12, 32) * 4) }
                },
                valueRange = 48f..128f
            )
        }
    }
}

@Composable
private fun SettingLabel(text: String, value: String? = null) {
    Row(modifier = Modifier.padding(top = 12.dp)) {
        Text(text, style = MaterialTheme.typography.titleSmall)
        value?.let {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                it,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/**
 * 顶部状态卡：永远只有两三行。完整诊断收进「详情」里，不许它再撑满屏幕。
 */
@Composable
private fun StatusCard(
    hasDump: Boolean,
    hasUsageStatsPermission: Boolean,
    hasProcessTable: Boolean,
    processViaShell: Boolean,
    degraded: Boolean,
    shizukuState: ShizukuBridge.State,
    showDiag: Boolean,
    onToggleDiag: () -> Unit,
    onOpenUsageAccess: () -> Unit,
    vm: AppVolumeViewModel
) {
    val (title, desc, action) = when {
        !hasDump -> Triple(
            "还缺 DUMP 权限",
            "读不到音频会话表，就没法给别的应用挂音量。用 Shizuku 执行一次 pm grant 即可，之后永久生效。",
            if (shizukuState == ShizukuBridge.State.NOT_GRANTED) "授权 Shizuku" else "用 Shizuku 授权"
        )

        !hasProcessTable -> Triple(
            "进程表还差 PACKAGE_USAGE_STATS",
            "现在只能列出「正在发声」和「最近打开」的应用。" +
                "AMS 的进程表要求 DUMP 和 PACKAGE_USAGE_STATS 两个权限都在手 —— " +
                "后者设置页的「使用情况访问」开关给不了，只能 shell 跑一次 pm grant。",
            "一键补齐"
        )

        else -> Triple(
            if (processViaShell) "就绪（进程表借 shell 身份读取）" else "全部就绪",
            "音频会话表与系统进程表都能读，列表完整。",
            "重新检查"
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                !hasDump -> MaterialTheme.colorScheme.errorContainer
                !hasProcessTable -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = onToggleDiag) {
                    Text(if (showDiag) "收起" else "详情")
                    Icon(
                        imageVector = if (showDiag) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null
                    )
                }
            }
            if (!hasProcessTable) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        when {
                            shizukuState == ShizukuBridge.State.NOT_GRANTED -> vm.requestShizukuPermission()
                            else -> vm.grantDumpViaShizuku()
                        }
                    }) { Text(action) }
                    TextButton(onClick = onOpenUsageAccess) { Text("使用情况访问") }
                }
            } else if (degraded) {
                TextButton(onClick = { vm.refresh(forceGrant = true) }) { Text(action) }
            }
        }
    }
}

/**
 * 音量键悬浮控制（可选功能）的权限引导。
 * 两个开关：无障碍服务（旁观音量键 + 观察音量面板窗口）、悬浮窗（显示按钮和浮窗）。
 */
@Composable
private fun OverlayPermissionCard(
    accessibilityOn: Boolean,
    overlayGranted: Boolean,
    onOpenAccessibility: () -> Unit,
    onOpenOverlay: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text("音量键悬浮控制（可选）", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "按音量键时在系统音量条旁弹出一个悬浮按钮，点开可给正在发声的应用单独调音量。" +
                    "无障碍服务只旁观按键，不拦截、不改变系统音量行为。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!accessibilityOn) {
                    Button(onClick = onOpenAccessibility) { Text("① 开无障碍") }
                }
                if (!overlayGranted) {
                    Button(onClick = onOpenOverlay) { Text("② 授予悬浮窗") }
                }
            }
            Text(
                text = if (android.os.Build.VERSION.SDK_INT >= 33) {
                    "提示：侧载应用的无障碍开关若被灰掉，去系统「应用信息」里点「允许受限设置」。"
                } else {
                    ""
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DiagCard(
    sourceDiag: String,
    shizukuDiag: String,
    shizukuProbe: String?,
    adbCommands: List<String>,
    onCopy: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("列表来源诊断", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = sourceDiag,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Shizuku：$shizukuDiag",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            shizukuProbe?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "权限一次都给上（电脑 adb 也可以，只需一次）：",
                style = MaterialTheme.typography.bodySmall
            )
            adbCommands.forEach { command ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = command,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { onCopy(command) }) { Text("复制") }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String, count: Int) {
    Text(
        text = "$text · $count",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, start = 4.dp)
    )
}

@Composable
private fun RecentHeader(count: Int, note: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                text = "最近打开（未必还在运行）· $count",
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun EmptyState(
    hasDump: Boolean,
    hasProcessTable: Boolean,
    hasUsageAccess: Boolean,
    includeSystem: Boolean,
    onOpenUsageAccess: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
        Text(
            text = when {
                !hasDump -> "还没有 DUMP 权限，连会话表都读不到。点上面的按钮授权。"
                !hasProcessTable && !hasUsageAccess -> {
                    "进程表缺 PACKAGE_USAGE_STATS、使用记录也没开。" +
                        "现在两条路都能补：① 开一次 Shizuku 点「一键补齐」（推荐，进程表就通了）；" +
                        "② 打开系统的「使用情况访问」，先靠最近记录凑合。"
                }
                !hasProcessTable -> "进程表还缺权限，只能靠「最近打开」。点上面的「一键补齐」最彻底。"
                includeSystem -> "这会儿没有应用在运行，也没有应用在发声。刚开过的话点右上角刷新。"
                else -> "「仅第三方」下没有匹配的应用，点右上角切到「含系统组件」看看。"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (!hasProcessTable && !hasUsageAccess) {
            TextButton(onClick = onOpenUsageAccess) { Text("打开「使用情况访问」设置") }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    manager.setPrimaryClip(ClipData.newPlainText("adb command", text))
    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRow(app: AppEntry, state: AppVolumeState, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIcon(packageName = app.packageName, size = 44.dp)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = app.label,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (app.isPlayingAudio) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Default.GraphicEq,
                            contentDescription = "正在播放音频",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AssistChip(
                        onClick = {},
                        label = { Text(app.runState.text, style = MaterialTheme.typography.labelSmall) }
                    )
                    if (app.isSystemApp) {
                        AssistChip(
                            onClick = {},
                            label = { Text("系统", style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                    Text(
                        text = when {
                            app.isPlayingAudio -> "正在发声"
                            app.isRunning -> "运行中"
                            else -> formatRelativeTime(app.lastUsedMillis)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterVertically)
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = AudioCurve.formatDb(state.gainDb),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = AudioCurve.formatPan(state.pan),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun formatRelativeTime(timeMillis: Long): String {
    if (timeMillis <= 0L) return "未知"
    val seconds = (System.currentTimeMillis() - timeMillis) / 1000
    return when {
        seconds < 60 -> "刚刚"
        seconds < 3600 -> "${seconds / 60} 分钟前"
        seconds < 86400 -> "${seconds / 3600} 小时前"
        else -> "${seconds / 86400} 天前"
    }
}
