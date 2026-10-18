package com.procamera

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.animation.DecelerateInterpolator

class TouchFocusHandler(
    context: Context,
    private val viewFinder: TextureView,
    private val focusRing: View,
    private val cameraController: CameraController
) : View.OnTouchListener {
    private var isFrameLocked = false

    private val hideRingRunnable = Runnable {
        if (!isFrameLocked) {
            focusRing.animate().alpha(0f).setDuration(220).withEndAction {
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
                isFrameLocked = true
                focusRing.removeCallbacks(hideRingRunnable)
                showFocusRingAt(e.x, e.y, locked = true)
                cameraController.triggerFocusAtPoint(e.x, e.y, viewFinder.width, viewFinder.height)
                focusRing.removeCallbacks(lockRunnable)
                focusRing.postDelayed(lockRunnable, 500)
            }
        }
    )

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        return true
    }

    private fun showFocusRingAt(touchX: Float, touchY: Float, locked: Boolean) {
        val fallbackSize = 92f * viewFinder.resources.displayMetrics.density
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

        val scaleX = ObjectAnimator.ofFloat(focusRing, View.SCALE_X, 0.6f, 1f)
        val scaleY = ObjectAnimator.ofFloat(focusRing, View.SCALE_Y, 0.6f, 1f)
        AnimatorSet().apply {
            playTogether(scaleX, scaleY)
            duration = 180
            interpolator = DecelerateInterpolator()
            start()
        }
    }
}
