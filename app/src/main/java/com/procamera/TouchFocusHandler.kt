package com.procamera

import android.annotation.SuppressLint
import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View

class TouchFocusHandler(
    context: Context,
    private val viewFinder: View,
    private val focusRing: View,
    private val cameraController: CameraController
) : View.OnTouchListener {

    private var isFrameLocked = false
    
    
    private val hideRingRunnable = Runnable {
        if (!isFrameLocked) focusRing.visibility = View.GONE
    }

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onLongPress(e: MotionEvent) {
            isFrameLocked = true
            focusRing.removeCallbacks(hideRingRunnable)
            focusRing.visibility = View.VISIBLE
            focusRing.setBackgroundResource(R.drawable.focus_frame_locked)
            focusRing.x = (e.x + viewFinder.x) - (focusRing.width / 2f)
            focusRing.y = (e.y + viewFinder.y) - (focusRing.height / 2f)
            
            cameraController.isAELocked = true
            cameraController.applySettings()
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        
        val touchX = event.x + viewFinder.x
        val touchY = event.y + viewFinder.y
        
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
                focusRing.x = touchX - (focusRing.width / 2f)
                focusRing.y = touchY - (focusRing.height / 2f)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isFrameLocked) {
                    focusRing.x = touchX - (focusRing.width / 2f)
                    focusRing.y = touchY - (focusRing.height / 2f)
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!isFrameLocked) {
                    cameraController.triggerFocusAtPoint(event.x, event.y, viewFinder.width, viewFinder.height)
                    
                    focusRing.postDelayed(hideRingRunnable, 2000)
                }
            }
        }
        return true
    }
}
