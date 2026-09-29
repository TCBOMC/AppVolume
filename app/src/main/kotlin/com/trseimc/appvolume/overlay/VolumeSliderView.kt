package com.trseimc.appvolume.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View

/**
 * 纵向音量滑条（参考荣耀音量面板样式）：
 * 悬浮胶囊样式的竖向轨道 + 自底向上的填充 + 底部应用图标，无 thumb，
 * 手指按住/拖动直接改音量（y 越靠下音量越小）。
 */
class VolumeSliderView(context: Context) : View(context) {

    /** 音量比例 0..1（对齐 AppVolumeState.gainSlider）。 */
    var value = 0f
        set(v) {
            field = v.coerceIn(0f, 1f)
            invalidate()
        }

    /** 画在轨道底部的应用图标。 */
    var icon: Drawable? = null
        set(v) {
            field = v
            invalidate()
        }

    /** 手指按下（开始拖动）回调：挂轮询豁免用。 */
    var onStartDrag: (() -> Unit)? = null

    /** 值变化回调；final=true 表示手指抬起，收尾落盘用。 */
    var onValueChange: ((value: Float, final: Boolean) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xF2FFFFFF.toInt()
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x1F000000.toInt()
        style = Paint.Style.STROKE
        strokeWidth = density
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF7CB8FF.toInt()
        style = Paint.Style.FILL
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val trackPath = Path()
    private val trackRect = RectF()
    private var dragging = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        trackRect.set(0f, 0f, w.toFloat(), h.toFloat())
        trackPath.reset()
        trackPath.addRoundRect(trackRect, w / 2f, w / 2f, Path.Direction.CW)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 轨道（悬浮胶囊样式：白半透明 + 细深边框）
        canvas.drawPath(trackPath, trackPaint)
        // 填充：自底向上，裁进轨道圆角里
        canvas.save()
        canvas.clipPath(trackPath)
        canvas.drawRect(0f, height * (1f - value), width.toFloat(), height.toFloat(), fillPaint)
        canvas.restore()
        canvas.drawPath(trackPath, strokePaint)
        // 应用图标：底部居中
        icon?.let { d ->
            val s = (ICON_DP * density).toInt()
            val left = (width - s) / 2
            val bottom = height - (ICON_MARGIN_DP * density).toInt()
            d.setBounds(left, bottom - s, left + s, bottom)
            d.draw(canvas)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                if (!dragging) {
                    dragging = true
                    onStartDrag?.invoke()
                }
                // 纵向拖动，别让横向翻页容器抢走手势
                parent?.requestDisallowInterceptTouchEvent(true)
                value = 1f - event.y / height
                onValueChange?.invoke(value, false)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    dragging = false
                    onValueChange?.invoke(value, true)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private companion object {
        const val ICON_DP = 26
        const val ICON_MARGIN_DP = 12
    }
}
