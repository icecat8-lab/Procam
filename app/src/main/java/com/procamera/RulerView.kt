package com.procamera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.roundToInt

class RulerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var max = 100
        set(value) {
            field = value.coerceAtLeast(1)
            position = position.coerceIn(0f, field.toFloat())
            invalidate()
        }

    var autoPosition = 0
        set(value) {
            field = value
            invalidate()
        }

    var autoLabel = "AUTO"
        set(value) {
            field = value
            invalidate()
        }

    var onProgressChanged: ((Int) -> Unit)? = null
    var onTrackingStopped: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val stepPx = 9f * density
    private val flingScale = 100f
    private var position = 0f
    private var lastReported = 0
    private val scroller = OverScroller(context)

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 1.5f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFD60A")
        strokeWidth = 2.5f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 10f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
    }

    var progress: Int
        get() = position.roundToInt().coerceIn(0, max)
        set(value) {
            scroller.forceFinished(true)
            position = value.coerceIn(0, max).toFloat()
            lastReported = progress
            invalidate()
        }

    private val detector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                scroller.forceFinished(true)
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                moveTo(position + distanceX / stepPx)
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                val start = (position * flingScale).toInt()
                val velocity = (-velocityX / stepPx * flingScale).toInt()
                scroller.fling(start, 0, velocity, 0, 0, (max * flingScale).toInt(), 0, 0)
                postInvalidateOnAnimation()
                return true
            }
        }
    )

    private fun moveTo(target: Float) {
        position = target.coerceIn(0f, max.toFloat())
        val current = progress
        if (current != lastReported) {
            lastReported = current
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            onProgressChanged?.invoke(current)
        }
        invalidate()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            moveTo(scroller.currX / flingScale)
            if (scroller.isFinished) {
                onTrackingStopped?.invoke()
            } else {
                postInvalidateOnAnimation()
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = detector.onTouchEvent(event)
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            parent?.requestDisallowInterceptTouchEvent(false)
            if (scroller.isFinished) onTrackingStopped?.invoke()
        }
        return handled || super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val baseline = h * 0.62f

        val first = ((position - cx / stepPx).toInt() - 1).coerceAtLeast(0)
        val last = ((position + cx / stepPx).toInt() + 1).coerceAtMost(max)

        for (i in first..last) {
            val x = cx + (i - position) * stepPx
            val tickHeight = when {
                i % 10 == 0 -> 22f
                i % 5 == 0 -> 15f
                else -> 9f
            } * density
            val fade = 1f - (abs(x - cx) / cx).coerceIn(0f, 1f) * 0.75f
            val strength = if (i % 10 == 0) 0.9f else 0.55f
            tickPaint.alpha = (255 * strength * fade).toInt()
            canvas.drawLine(x, baseline - tickHeight, x, baseline, tickPaint)

            if (i == autoPosition) {
                textPaint.alpha = (255 * fade).toInt()
                canvas.drawText(autoLabel, x, h - 4f * density, textPaint)
            }
        }

        canvas.drawLine(cx, baseline - 28f * density, cx, baseline + 2f * density, centerPaint)
    }
}
