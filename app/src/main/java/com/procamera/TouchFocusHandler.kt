package com.procamera

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.animation.OvershootInterpolator

class TouchFocusHandler(
    context: Context,
    private val viewFinder: TextureView,
    private val focusRing: View,
    private val cameraController: CameraController
) : View.OnTouchListener {
    private var isFrameLocked = false

    private val hideRingRunnable = Runnable {
        if (!isFrameLocked) {
            focusRing.animate().alpha(0f).setStartDelay(0).setDuration(220).setInterpolator(AnimUtils.smooth).withEndAction {
                if (!isFrameLocked) focusRing.visibility = View.GONE
            }.start()
        }
    }

    private val lockRunnable = Runnable {
        if (isFrameLocked) {
            cameraController.isAELocked = true
            cameraController.applySettings()
        }
    }

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (!insideFrame(e)) return true
                focusRing.removeCallbacks(lockRunnable)
                if (isFrameLocked) {
                    isFrameLocked = false
                    cameraController.isAELocked = false
                }
                showFocusRingAt(e.x, e.y, locked = false)

                cameraController.triggerFocusAtPoint(e.x, e.y, viewFinder.width, viewFinder.height)
                focusRing.removeCallbacks(hideRingRunnable)
                focusRing.postDelayed(hideRingRunnable, 2000)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (!insideFrame(e)) return
                isFrameLocked = true
                focusRing.removeCallbacks(hideRingRunnable)
                showFocusRingAt(e.x, e.y, locked = true)
                cameraController.triggerFocusAtPoint(e.x, e.y, viewFinder.width, viewFinder.height)
                focusRing.removeCallbacks(lockRunnable)
                focusRing.postDelayed(lockRunnable, 500)
            }
        }
    )

    private fun insideFrame(e: MotionEvent): Boolean {
        return e.x >= 0f && e.y >= 0f && e.x <= viewFinder.width && e.y <= viewFinder.height
    }

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        return true
    }

    private fun showFocusRingAt(touchX: Float, touchY: Float, locked: Boolean) {
        val fallbackSize = 64f * viewFinder.resources.displayMetrics.density
        val ringW = if (focusRing.width > 0) focusRing.width.toFloat() else fallbackSize
        val ringH = if (focusRing.height > 0) focusRing.height.toFloat() else fallbackSize

        val clampedX = touchX.coerceIn(0f, viewFinder.width.toFloat())
        val clampedY = touchY.coerceIn(0f, viewFinder.height.toFloat())

        val ringX = viewFinder.left + (clampedX - ringW / 2f)
            .coerceIn(0f, maxOf(0f, viewFinder.width - ringW))
        val ringY = viewFinder.top + (clampedY - ringH / 2f)
            .coerceIn(0f, maxOf(0f, viewFinder.height - ringH))

        focusRing.removeCallbacks(hideRingRunnable)
        focusRing.animate().cancel()
        focusRing.alpha = 1f
        focusRing.visibility = View.VISIBLE
        focusRing.setBackgroundResource(
            if (locked) R.drawable.focus_frame_locked else R.drawable.focus_frame
        )
        focusRing.x = ringX
        focusRing.y = ringY

        focusRing.scaleX = 1.4f
        focusRing.scaleY = 1.4f
        focusRing.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setStartDelay(0)
            .setDuration(240)
            .setInterpolator(OvershootInterpolator(1.4f))
            .start()
    }
}
