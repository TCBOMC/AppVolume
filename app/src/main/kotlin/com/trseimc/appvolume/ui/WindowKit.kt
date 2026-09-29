package com.trseimc.appvolume.ui

import android.graphics.PixelFormat
import android.os.Build
import android.view.MotionEvent
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

/** 卡片与屏幕边缘的最小间距，上下=左右。 */
private val WindowMargin = 20.dp

/** 卡片最大宽度：竖屏窄屏时左右留边自然生效，横屏/宽屏时也不会拉满。 */
private val WindowMaxWidth = 460.dp

/** 卡片圆角。 */
private val WindowCardShape = RoundedCornerShape(24.dp)

/** 卡片背景：白色半透明，配合背后的模糊形成毛玻璃（透明度只属于卡片本身）。 */
private val WindowCardBg = Color(0xD9FFFFFF)

/** 卡片边框：细深线，把半透明卡片从背后内容里衬出来。 */
private val WindowCardBorder = Color(0x1F000000)

/** 悬浮胶囊背景：更实的白色半透明（叠在白卡片上略亮一档）。 */
private val CapsuleBg = Color(0xF2FFFFFF)

/** 悬浮胶囊边框。 */
private val CapsuleBorder = Color(0x1F000000)

/**
 * 悬浮胶囊：半透明 + 阴影（悬浮感），宽度随内容变化，最窄时是圆形。
 * 用于窗口右上角的关闭按钮和左上角的标题。
 */
@Composable
fun WindowCapsule(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit
) {
    val shape = RoundedCornerShape(50)
    var m = modifier
        .shadow(elevation = 4.dp, shape = shape, clip = false)
        .clip(shape)
        .background(CapsuleBg)
        .border(1.dp, CapsuleBorder, shape)
        .defaultMinSize(minWidth = 40.dp, minHeight = 40.dp)
    if (onClick != null) {
        m = m.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick
        )
    }
    Row(
        modifier = m.padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        content = content
    )
}

/**
 * 统一悬浮窗口容器（应用内三个弹窗共用：应用音量 / 设置 / 其他）。
 *
 * 与参考的桌面窗口一致的行为：
 * - 窗口 = 卡片本身（wrap-content 居中），没有任何全屏遮罩层；
 * - 窗口外背景绝对全透明、不变暗，背后内容原样可见 —— 变暗与模糊效果只属于
 *   卡片矩形：Android 12+ 用 FLAG_BLUR_BEHIND 模糊窗口背后（窗口=卡片矩形，
 *   blurBehindRadius=卡片圆角），ROM 不支持时退化为纯半透明卡片；
 * - 点窗口外关闭：窗口不再是全屏收不到外部触摸，靠 FLAG_WATCH_OUTSIDE_TOUCH
 *   的 ACTION_OUTSIDE 事件；
 * - 胶囊全部在卡片内部：左上角可选标题胶囊，右上角关闭胶囊；
 * - 高度上限 = 屏幕可用高 - 上下边距，正文区超出后滚动。
 *
 * 内容强制跑在亮色配色下：卡片背景固定白色，暗色主题下正文也可读。
 */
@Composable
fun FloatingWindow(
    titleContent: (@Composable () -> Unit)? = null,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        val blurRadiusPx = with(LocalDensity.current) { 24.dp.toPx().toInt() }
        LaunchedEffect(dialogWindow, onDismiss) {
            dialogWindow?.apply {
                setFormat(PixelFormat.TRANSLUCENT)
                // 关键：不做任何全屏变暗。dim=0 且去掉 DIM 标志，窗口外零效果
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setDimAmount(0f)
                addFlags(WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH)
                if (Build.VERSION.SDK_INT >= 31) {
                    runCatching {
                        addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                        attributes = attributes.apply { blurBehindRadius = blurRadiusPx }
                    }
                }
                decorView.setOnTouchListener { _, event ->
                    if (event.action == MotionEvent.ACTION_OUTSIDE) {
                        onDismiss()
                        true
                    } else {
                        false
                    }
                }
            }
        }

        // 卡片尺寸上限直接取 Dialog 实际拿到的约束（BoxWithConstraints 的 max 即
        // window 可用帧），减去上下/左右边距 —— 不用 screenHeightDp 估算：那套数字
        // 和 window 可用帧对不上时，上限比可用高还大，卡片会撑到贴满屏幕，
        // 上下边距消失、模糊区域也跟着盖住大半屏
        BoxWithConstraints(contentAlignment = Alignment.Center) {
            val maxCardWidth = if (maxWidth != Dp.Infinity) {
                minOf(WindowMaxWidth.value, (maxWidth - WindowMargin * 2).value).dp
            } else {
                WindowMaxWidth
            }
            val maxCardHeight = if (maxHeight != Dp.Infinity) maxHeight - WindowMargin * 2 else maxHeight

            MaterialTheme(colorScheme = lightColorScheme()) {
                Column(
                    modifier = Modifier
                        .widthIn(max = maxCardWidth)
                        .heightIn(max = maxCardHeight)
                        .clip(WindowCardShape)
                        .background(WindowCardBg)
                        .border(1.dp, WindowCardBorder, WindowCardShape)
                ) {
                    // 顶行：左上角标题胶囊（可选）+ 右上角关闭胶囊，都在卡片内部
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 12.dp, top = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (titleContent != null) {
                            WindowCapsule {
                                titleContent()
                            }
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        WindowCapsule(onClick = onDismiss) {
                            Text("✕", color = Color(0xE6000000))
                        }
                    }

                    // 滚动主画布：正文放这里；fill=false 内容不满时按内容高，超上限才滚动
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp)
                    ) {
                        content()
                    }
                }
            }
        }
    }
}
