package com.procamera

import android.view.View
import android.view.TextureView
import android.view.MotionEvent

class TouchFocusHandler(
    context: android.content.Context,
    private val viewFinder: TextureView,
    private val focusRing: View,
    private val cameraController: CameraController
) : View.OnTouchListener {

    private var isFrameLocked = false
    
    private val hideRingRunnable = Runnable {
        if (!isFrameLocked) focusRing.visibility = View.GONE
    }

    private val gestureDetector = android.view.GestureDetector(context, object : android.view.GestureDetector.SimpleOnGestureListener() {
        override fun onLongPress(e: MotionEvent) {
            isFrameLocked = true
            focusRing.removeCallbacks(hideRingRunnable)
            focusRing.visibility = View.VISIBLE
            focusRing.setBackgroundResource(R.drawable.focus_frame_locked)
            
            val ringW = focusRing.width.toFloat()
            val ringH = focusRing.height.toFloat()
            focusRing.x = (e.x - ringW / 2f).coerceIn(0f, viewFinder.width - ringW)
            focusRing.y = (e.y - ringH / 2f).coerceIn(0f, viewFinder.height - ringH)
            
            cameraController.isAELocked = true
            cameraController.applySettings()
        }
    })

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        
        val ringW = if (focusRing.width > 0) focusRing.width.toFloat() else 228f
        val ringH = if (focusRing.height > 0) focusRing.height.toFloat() else 228f
        
        val clampedX = event.x.coerceIn(0f, viewFinder.width.toFloat())
        val clampedY = event.y.coerceIn(0f, viewFinder.height.toFloat())
        
        val ringX = (clampedX - ringW / 2f).coerceIn(0f, maxOf(0f, viewFinder.width - ringW))
        val ringY = (clampedY - ringH / 2f).coerceIn(0f, maxOf(0f, viewFinder.height - ringH))

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (isFrameLocked) {
                    isFrameLocked = false
                    cameraController.isAELocked = false
                    cameraController.applySettings()
                }
                focusRing.removeCallbacks(hideRingRunnable)
                focusRing.visibility = View.VISIBLE
                focusRing.setBackgroundResource(R.drawable.focus_frame)
                focusRing.x = ringX
                focusRing.y = ringY
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isFrameLocked) {
                    focusRing.x = ringX
                    focusRing.y = ringY
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!isFrameLocked) {
                    cameraController.triggerFocusAtPoint(clampedX, clampedY, viewFinder.width, viewFinder.height)
                    focusRing.postDelayed(hideRingRunnable, 2000)
                }
            }
        }
        return true
    }
}
