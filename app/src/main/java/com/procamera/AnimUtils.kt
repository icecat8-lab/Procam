package com.procamera

import android.annotation.SuppressLint
import android.app.Activity
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator

object AnimUtils {
    val smooth = PathInterpolator(0.22f, 1f, 0.36f, 1f)
    val spring = OvershootInterpolator(3f)

    @SuppressLint("ClickableViewAccessibility")
    fun pressable(view: View, pressedScale: Float = 0.92f) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().cancel()
                    v.animate()
                        .scaleX(pressedScale)
                        .scaleY(pressedScale)
                        .setStartDelay(0)
                        .setDuration(90)
                        .setInterpolator(smooth)
                        .start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setStartDelay(0)
                        .setDuration(300)
                        .setInterpolator(spring)
                        .start()
                }
            }
            false
        }
    }

    @Suppress("DEPRECATION")
    fun slideOpen(activity: Activity) {
        activity.overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
    }

    @Suppress("DEPRECATION")
    fun slideClose(activity: Activity) {
        activity.overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
    }
}
