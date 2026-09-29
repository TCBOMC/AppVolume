package com.trseimc.appvolume.ui

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.app.Activity
import android.os.Build
import android.view.MotionEvent
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.drawToBitmap

/** 卡片与屏幕边缘的最小间距，上下=左右。 */
private val WindowMargin = 20.dp

/** 卡片最大宽度：竖屏窄屏时左右留边自然生效，横屏/宽屏时也不会拉满。 */
private val WindowMaxWidth = 460.dp

/** 卡片圆角，blurBehind 半径也对齐它。 */
private val WindowCardRadius = 24.dp

/** 卡片圆角形状。 */
private val WindowCardShape = RoundedCornerShape(WindowCardRadius)

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
        LaunchedEffect(dialogWindow, onDismiss) {
            dialogWindow?.apply {
                setFormat(PixelFormat.TRANSLUCENT)
                // 不做任何全屏变暗。dim=0 且去掉 DIM 标志，窗口外零效果
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setDimAmount(0f)
                addFlags(WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH)
                // 注意：不用 FLAG_BLUR_BEHIND——荣耀新系统会把 blurBehind 强制扩成
                // 全屏模糊（实测，其他应用同样中招）。毛玻璃改由自绘实现：
                // 抓 Activity 内容快照自己模糊垫在卡片底层
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

        // 高度不依赖 Dialog 约束（真机证明那个方向拿到的可能是无界约束，
        // 200dp 测试已验证 configuration 估算链路有效），直接用 configuration 估算，
        // 上下边距与左右一致。
        // 关键一：targetSdk 35 强制 edge-to-edge 后 screenHeightDp 是整块屏幕的高度
        // （含状态栏/导航栏区域），且 Dialog 窗口能落位到 y=0，必须再减掉系统栏 +
        // 刘海的安全区高度，否则上下边距会被系统栏吃掉（真机实证）。
        // 关键二：安全区不能从 Dialog 自己的 WindowInsets 读——Dialog 是 floating
        // window，窗口帧不含系统栏区域，下发给它的 insets 全是 0（真机实证"没区别"）。
        // 必须读 Activity 窗口（edge-to-edge）decorView 的 rootWindowInsets
        val configuration = LocalConfiguration.current
        val density = LocalDensity.current
        val activity = LocalContext.current.findActivity()
        val safeInsets = activity?.window?.decorView
            ?.let { ViewCompat.getRootWindowInsets(it) }
            ?.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
        val safeTopDp = with(density) { (safeInsets?.top ?: 0).toDp() }
        val safeBottomDp = with(density) { (safeInsets?.bottom ?: 0).toDp() }
        val maxCardWidth = minOf(
            WindowMaxWidth.value,
            (configuration.screenWidthDp - WindowMargin.value * 2).coerceAtLeast(0f)
        ).dp
        val maxCardHeight = (
            configuration.screenHeightDp - WindowMargin.value * 2 -
                safeTopDp.value - safeBottomDp.value
            ).coerceAtLeast(0f).dp

        // 毛玻璃背景：抓宿主 Activity 的内容快照，缩小 + Compose blur 自绘。
        // 不依赖系统 blurBehind（荣耀新系统会强制全屏模糊）。API 31 以下
        // Compose blur 是 no-op，直接不放快照，卡片退化为纯半透明
        val backdrop = remember(activity) {
            val decor = activity?.window?.decorView
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                return@remember null
            }
            runCatching {
                if (decor == null || decor.width <= 0 || decor.height <= 0) return@remember null
                val full = decor.drawToBitmap()
                val small = Bitmap.createScaledBitmap(
                    full, (full.width / 6).coerceAtLeast(1), (full.height / 6).coerceAtLeast(1), true
                )
                small.asImageBitmap()
            }.getOrNull()
        }

        MaterialTheme(colorScheme = lightColorScheme()) {
            Box(
                modifier = Modifier
                    .widthIn(max = maxCardWidth)
                    .heightIn(max = maxCardHeight)
                    .clip(WindowCardShape)
                    .border(1.dp, WindowCardBorder, WindowCardShape)
            ) {
                // 底层：自绘毛玻璃。整屏快照按中心裁切铺满卡片，重度模糊下
                // 与实际背景的错位不可见
                if (backdrop != null) {
                    Image(
                        bitmap = backdrop,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .matchParentSize()
                            .blur(24.dp)
                    )
                }
                // 白色半透明白雾叠层（毛玻璃的"雾"），快照缺失时就是纯半透明卡片
                Box(modifier = Modifier.matchParentSize().background(WindowCardBg))

                Column {
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

/** 沿 ContextWrapper.base 链找到宿主 Activity（Dialog 的 context 是主题包装层）。 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext?.findActivity()
    else -> null
}
