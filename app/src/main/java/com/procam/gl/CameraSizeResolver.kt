package com.procam.gl

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import kotlin.math.abs

object CameraSizeResolver {

    data class PreviewInfo(val width: Int, val height: Int)

    fun resolve(context: Context, cameraId: String? = null): PreviewInfo {
        return runCatching {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cameraId ?: cm.cameraIdList.firstOrNull { candidate ->
                cm.getCameraCharacteristics(candidate)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: cm.cameraIdList.first()

            val chars = cm.getCameraCharacteristics(id)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: return@runCatching PreviewInfo(1280, 720)
            val sizes = map.getOutputSizes(SurfaceTexture::class.java)
                ?.filter { it.width > 0 && it.height > 0 }
                .orEmpty()

            if (sizes.isEmpty()) return@runCatching PreviewInfo(1280, 720)

            val target = 16f / 9f
            val candidates = sizes
                .filter { it.width >= it.height }
                .sortedBy { it.width.toLong() * it.height }

            val preview1080p = candidates.filter { it.width <= 1920 && it.height <= 1080 }
            val pool = if (preview1080p.isNotEmpty()) preview1080p else candidates

            val best = pool.minWithOrNull(
                compareBy<android.util.Size> {
                    abs(it.width.toFloat() / it.height.toFloat() - target)
                }.thenByDescending {
                    it.width.toLong() * it.height
                }
            ) ?: sizes.first()

            PreviewInfo(best.width, best.height)
        }.getOrElse {
            PreviewInfo(1280, 720)
        }
    }
}
