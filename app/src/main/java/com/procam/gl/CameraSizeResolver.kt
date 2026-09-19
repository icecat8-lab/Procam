package com.procam.gl

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import kotlin.math.abs

object CameraSizeResolver {
    data class PreviewInfo(
        val width: Int,
        val height: Int,
        val sensorOrientation: Int,
        val isFrontCamera: Boolean
    )

    fun resolve(context: Context): PreviewInfo = try {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = cm.cameraIdList.firstOrNull { id ->
            cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: cm.cameraIdList.firstOrNull()
        if (cameraId == null) return PreviewInfo(1920, 1080, 0, false)
        val chars = cm.getCameraCharacteristics(cameraId)
        val facing = chars.get(CameraCharacteristics.LENS_FACING)
        val sensor = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val sizes = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(SurfaceTexture::class.java) ?: emptyArray()
        if (sizes.isEmpty()) return PreviewInfo(1920, 1080, sensor, facing == CameraCharacteristics.LENS_FACING_FRONT)
        val target = 16f / 9f
        val landscape = sizes.filter { it.width >= it.height }
        val pool = if (landscape.isNotEmpty()) landscape else sizes.toList()
        val best = pool.minByOrNull { abs(it.width.toFloat() / it.height - target) } ?: sizes[0]
        val chosen = pool.filter { it.width <= best.width && it.height <= best.height }
            .filter { abs(it.width.toFloat() / it.height - best.width.toFloat() / best.height) < 0.01f }
            .maxByOrNull { it.width.toLong() * it.height } ?: best
        PreviewInfo(chosen.width, chosen.height, sensor, facing == CameraCharacteristics.LENS_FACING_FRONT)
    } catch (_: Throwable) {
        PreviewInfo(1920, 1080, 0, false)
    }
}
