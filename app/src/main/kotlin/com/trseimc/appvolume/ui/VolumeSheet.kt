package com.trseimc.appvolume.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.trseimc.appvolume.apps.AppEntry
import com.trseimc.appvolume.audio.AppVolumeState
import com.trseimc.appvolume.audio.AudioCurve
import com.trseimc.appvolume.audio.EngineCapability
import kotlin.math.roundToInt

@Composable
fun VolumeSheet(
    app: AppEntry,
    initial: AppVolumeState,
    vm: AppVolumeViewModel,
    onDismiss: () -> Unit
) {
    var draft by remember(app.packageName) { mutableStateOf(initial) }
    var dragging by remember { mutableStateOf(false) }

    // 外部配置变了（比如刚重置过）就同步过来，正在拖的时候不打断
    LaunchedEffect(initial) {
        if (!dragging) draft = initial
    }

    val capability by produceState<EngineCapability?>(null, app.packageName) {
        value = vm.capabilityOf(app)
    }

    val stereo = draft.stereo
    val gainDb = draft.gainDb

    FloatingWindow(
        titleContent = {
            AppIcon(packageName = app.packageName, size = 22.dp)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = app.label,
                color = Color(0x8A000000),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        onDismiss = onDismiss
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
        ) {
            // 包名与运行状态（图标和名称已在标题胶囊里）
            Text(
                text = "${app.packageName} · ${app.runState.text}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 引擎能力
            capability?.let { cap ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (cap.canControlVolume) {
                            MaterialTheme.colorScheme.tertiaryContainer
                        } else {
                            MaterialTheme.colorScheme.errorContainer
                        }
                    )
                ) {
                    Row(modifier = Modifier.padding(12.dp)) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(cap.reason, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // 主音量
            SectionTitle("主音量")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Text(
                    text = AudioCurve.formatDb(gainDb),
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    text = "响度 ${AudioCurve.loudnessPercent(gainDb).roundToInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Slider(
                value = draft.gainSlider,
                onValueChange = { value ->
                    dragging = true
                    draft = draft.copy(gainSlider = value)
                    // 拖动中实时生效：引擎侧幂等且带缓存，中间值会被 conflate 掉
                    vm.pushLive(app, draft)
                },
                onValueChangeFinished = {
                    dragging = false
                    vm.pushToEngine(app, draft)
                },
                valueRange = 0f..1f,
                colors = SliderDefaults.colors()
            )
            Text(
                text = "推子 ${(draft.gainSlider * 100).roundToInt()}% → 衰减 ${AudioCurve.formatDb(gainDb).removePrefix("+")}，" +
                    "等 dB 分度（听感均匀，非振幅线性）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(20.dp))

            // 左右平衡
            SectionTitle("左右声道平衡")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("左 ${AudioCurve.formatDb(stereo.leftDb)}", style = MaterialTheme.typography.bodyMedium)
                Text("右 ${AudioCurve.formatDb(stereo.rightDb)}", style = MaterialTheme.typography.bodyMedium)
            }
            Slider(
                value = draft.pan,
                onValueChange = { value ->
                    dragging = true
                    draft = draft.copy(pan = value)
                    vm.pushLive(app, draft)
                },
                onValueChangeFinished = {
                    dragging = false
                    vm.pushToEngine(app, draft)
                },
                valueRange = -1f..1f
            )
            Text(
                text = "${AudioCurve.formatPan(draft.pan)} · 等功率声像律（L²+R²≡1），移动声像时总响度不变",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(20.dp))

            // 快捷操作
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    draft = draft.copy(gainSlider = if (draft.isMuted) 1f else 0f)
                    vm.saveVolume(app, draft)
                    vm.pushToEngine(app, draft)
                }) {
                    Text(if (draft.isMuted) "取消静音" else "静音")
                }
                OutlinedButton(onClick = {
                    draft = draft.copy(pan = 0f)
                    vm.saveVolume(app, draft)
                    vm.pushToEngine(app, draft)
                }) {
                    Text("声道居中")
                }
                OutlinedButton(onClick = {
                    draft = AppVolumeState.DEFAULT
                    vm.saveVolume(app, draft)
                    vm.pushToEngine(app, draft)
                }) {
                    Text("恢复默认")
                }
            }

        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(bottom = 4.dp)
    )
}
