package com.procam.ui.components

import android.content.Context
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.procam.gl.CameraSizeResolver
import com.procam.gl.GlPreviewRenderer

@Composable
fun GlCameraPreview(
    onSurfaceReady: (Surface) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    lutEnabled: Boolean,
    lutTextureId: Int,
    lutSize: Float,
    brightness: Float,
    contrast: Float,
    saturation: Float,
    temperature: Float,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val previewInfo = remember(context) { CameraSizeResolver.resolve(context) }
    var rendererRef by remember { mutableStateOf<GlPreviewRenderer?>(null) }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx: Context ->
                GLSurfaceView(ctx).apply {
                    setEGLContextClientVersion(3)
                    preserveEGLContextOnPause = false
                    val renderer = GlPreviewRenderer(
                        onSurfaceReady = { surface ->
                            mainHandler.post { onSurfaceReady(surface) }
                        },
                        onSurfaceDetached = {
                            mainHandler.post { onSurfaceDestroyed() }
                        },
                        bufferWidth = previewInfo.width,
                        bufferHeight = previewInfo.height,
                        sensorOrientation = previewInfo.sensorOrientation,
                        isFrontCamera = previewInfo.isFrontCamera
                    )
                    renderer.bindGlSurfaceView(this)
                    setRenderer(renderer)
                    renderer.setDisplayRotationDegrees(displayRotationDegrees(this))
                    rendererRef = renderer
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { view ->
                rendererRef?.setDisplayRotationDegrees(displayRotationDegrees(view))
            },
            onRelease = { view ->
                rendererRef?.release()
                view.onPause()
                rendererRef = null
                mainHandler.post { onSurfaceDestroyed() }
            }
        )

    }

    LaunchedEffect(lutEnabled, lutTextureId, lutSize, brightness, contrast, saturation, temperature) {
        rendererRef?.apply {
            this.lutEnabled = lutEnabled
            this.lutTextureId = lutTextureId
            this.lutSize = lutSize
            this.brightness = brightness
            this.contrast = contrast
            this.saturation = saturation
            this.temperature = temperature
        }
    }
}

private fun displayRotationDegrees(view: GLSurfaceView): Int = when (view.display?.rotation) {
    Surface.ROTATION_90 -> 90
    Surface.ROTATION_180 -> 180
    Surface.ROTATION_270 -> 270
    else -> 0
}
