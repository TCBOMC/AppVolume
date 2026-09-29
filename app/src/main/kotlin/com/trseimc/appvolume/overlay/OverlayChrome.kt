package com.trseimc.appvolume.overlay

import android.content.Context
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 经典 View 侧的悬浮窗装饰件（服务内浮窗用）。
 * 与 ui/WindowKit.kt 的 Compose 版容器同一套视觉参数：
 * 圆角卡片背景 + 悬浮胶囊（半透明、带阴影、宽度随内容、最窄为圆形）。
 */
object OverlayChrome {
    /** 窗口与屏幕边缘的边距（dp），上下=左右。 */
    const val MARGIN_DP = 20

    /** 窗口最大宽度（dp），横屏/宽屏时不拉满。 */
    const val MAX_WIDTH_DP = 460

    /** 卡片圆角（dp），blurBehind 半径也对齐它。 */
    const val CARD_RADIUS_DP = 24
    // 音量键面板没有毛玻璃垫底，背景要比应用内浮窗（0xB3FFFFFF）更实才可读
    const val CARD_BG = 0xE6FFFFFF.toInt()
    private val CAPSULE_BG = 0xF2FFFFFF.toInt()
    private val CAPSULE_STROKE = 0x1F000000.toInt()

    private val Context.density: Float
        get() = resources.displayMetrics.density

    fun dp(context: Context, v: Int): Int = (v * context.density).toInt()

    /** 圆角矩形背景。 */
    fun roundedBg(context: Context, color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = radiusDp * context.density
        }

    /** 圆角胶囊的 outline（elevation 阴影沿这个轮廓投，才会跟着圆角走）。 */
    private fun pillOutline(context: Context, radiusDp: Int) = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, radiusDp * context.density)
        }
    }

    /** 关闭胶囊：圆形，中间 ✕（默认 40dp，小面板可传更小）。 */
    fun closeCapsule(context: Context, sizeDp: Int = 40, onClose: () -> Unit): View {
        val label = TextView(context).apply {
            text = "✕"
            setTextColor(0xE6000000.toInt())
            textSize = if (sizeDp >= 40) 14f else 12f
        }
        return FrameLayout(context).apply {
            // 容器最小尺寸 = sizeDp：WRAP_CONTENT 只会包住 ✕ 文本，之前漏了这
            // 两行导致胶囊缩成一个字符大（应用内 Compose 版有 defaultMinSize 所以正常）
            minimumWidth = dp(context, sizeDp)
            minimumHeight = dp(context, sizeDp)
            background = roundedBg(context, CAPSULE_BG, sizeDp / 2).apply {
                setStroke(dp(context, 1), CAPSULE_STROKE)
            }
            elevation = 4 * context.density
            outlineProvider = pillOutline(context, sizeDp / 2)
            clipToOutline = false
            isClickable = true
            setOnClickListener { onClose() }
            addView(
                label,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            )
        }
    }

    /** 标题胶囊：可选图标 + 文本，宽度随内容。 */
    fun titleCapsule(context: Context, icon: Drawable?, title: String): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBg(context, CAPSULE_BG, 20).apply {
                setStroke(dp(context, 1), CAPSULE_STROKE)
            }
            elevation = 4 * context.density
            outlineProvider = pillOutline(context, 20)
            clipToOutline = false
            setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 8))
        }
        if (icon != null) {
            val iv = ImageView(context).apply {
                setImageDrawable(icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            row.addView(
                iv,
                LinearLayout.LayoutParams(dp(context, 18), dp(context, 18))
                    .also { it.marginEnd = dp(context, 6) }
            )
        }
        val label = TextView(context).apply {
            text = title
            setTextColor(0xEE000000.toInt())
            textSize = 13f
            maxLines = 1
        }
        row.addView(
            label,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        return row
    }
}
