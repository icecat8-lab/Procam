package com.procam.gl

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import kotlin.math.abs

object CameraSizeResolver {

    data class PreviewSize(val width: Int, val height: Int) {
        val aspect: Float get() = width.toFloat() / height.toFloat()
    }

    fun resolve(context: Context, cameraId: String = "0"): PreviewSize {
        return try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val chars = cm.getCameraCharacteristics(cameraId)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(SurfaceTexture::class.java) ?: emptyArray()
            if (sizes.isEmpty()) return PreviewSize(1920, 1080)

            val target = 16f / 9f
            val landscape = sizes
                .filter { it.width >= it.height }
                .sortedByDescending { it.width.toLong() * it.height }

            val bestMatch = landscape.minByOrNull {
                val a = it.width.toFloat() / it.height
                abs(a - target)
            } ?: landscape.firstOrNull() ?: sizes[0]

            val capped = landscape
                .filter { it.width <= bestMatch.width && it.height <= bestMatch.height }
                .filter {
                    abs(it.width.toFloat() / it.height -
                        bestMatch.width.toFloat() / bestMatch.height) < 0.01f
                }
                .maxByOrNull { it.width.toLong() * it.height } ?: bestMatch

            PreviewSize(capped.width, capped.height)
        } catch (_: Throwable) {
            PreviewSize(1920, 1080)
        }
    }
}
