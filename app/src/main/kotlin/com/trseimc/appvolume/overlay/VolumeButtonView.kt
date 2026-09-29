package com.trseimc.appvolume.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.View
import com.trseimc.appvolume.data.ButtonSettings

/**
 * 音量键悬浮按钮：白底胶囊 + 三个竖着的滑块（调音台样式）图标。
 *
 * 尺寸由 [ButtonSettings.sizeDp] 决定：宽 = sizeDp，高 = sizeDp × 0.62，
 * 圆角取高的一半即是胶囊。三个竖轨各自带一个错位的圆形滑钮，
 * 一眼能看出「这是调每应用音量的」。
 */
class VolumeButtonView(context: Context, sizeDp: Int, densityScale: Float) : View(context) {

    private val stroke = 3f * densityScale
    private val knobRadius = 4f * densityScale

    // 白底胶囊（略带透明，压在音量条旁边不突兀）。圆角 = 高度一半 = 胶囊
    private val capsule = GradientDrawable().apply {
        setColor(0xF7FFFFFF.toInt())
        cornerRadius = sizeDp * 0.62f / 2f * densityScale
    }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3C4356.toInt()
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
    }

    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3C4356.toInt()
        style = Paint.Style.FILL
    }

    // 三个滑钮的纵向位置（占轨高的比例，故意错开成「正在调音」的形态）
    private val knobPositions = floatArrayOf(0.32f, 0.66f, 0.45f)

    init {
        background = capsule
        elevation = 6f * densityScale
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val trackTop = h * 0.24f
        val trackBottom = h * 0.76f
        val trackLen = trackBottom - trackTop

        for (i in 0..2) {
            val cx = w * (0.30f + i * 0.20f)
            canvas.drawLine(cx, trackTop, cx, trackBottom, trackPaint)
            val cy = trackTop + trackLen * knobPositions[i]
            canvas.drawCircle(cx, cy, knobRadius, knobPaint)
        }
    }
}
