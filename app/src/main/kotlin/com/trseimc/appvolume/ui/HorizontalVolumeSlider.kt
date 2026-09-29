package com.trseimc.appvolume.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * 横向主音量条（主页应用卡片顶部用，样式对齐音量面板的 VolumeSliderView，横置版）：
 * 悬浮胶囊样式的横向轨道 + 自左向右的填充 + 左侧应用图标，无 thumb，
 * 手指按住/拖动直接改音量（x 越靠左音量越小，拖到最左进入静音区）。
 *
 * 填充只靠外层 clip 裁剪（不自己 clip）：左端跟随轨道圆角，右端保持垂直切面，
 * 与面板纵向版的 drawRect + clipPath 行为一致。
 */
@Composable
fun HorizontalVolumeSlider(
    value: Float,
    packageName: String,
    onValueChange: (Float) -> Unit,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier = modifier
            .height(52.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(CapsuleBgPublic)
            .border(1.dp, CapsuleBorderPublic, shape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    // 按下即定位（与面板滑条 ACTION_DOWN 行为一致）
                    val down = awaitFirstDown(requireUnconsumed = false)
                    onDragStart()
                    onValueChange((down.position.x / size.width).coerceIn(0f, 1f))
                    var dragging = true
                    while (dragging) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (change.pressed) {
                            onValueChange((change.position.x / size.width).coerceIn(0f, 1f))
                            change.consume()
                        } else {
                            dragging = false
                        }
                    }
                    onDragEnd()
                }
            }
    ) {
        // 填充：自左向右
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(value.coerceIn(0f, 1f))
                .background(Color(0xFF7CB8FF))
        )
        // 应用图标：左侧居中
        AppIcon(
            packageName = packageName,
            size = 26.dp,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 12.dp)
        )
    }
}

// 胶囊配色与 WindowKit.kt 的 CapsuleBg / CapsuleBorder 一致（那边是 private，这里镜像一份）
private val CapsuleBgPublic = Color(0xF2FFFFFF)
private val CapsuleBorderPublic = Color(0x1F000000)
