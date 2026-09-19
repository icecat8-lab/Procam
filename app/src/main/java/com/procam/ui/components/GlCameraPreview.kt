package com.procam.ui.components

import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
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
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var rendererRef by remember { mutableStateOf<GlPreviewRenderer?>(null) }

    AndroidView(
        factory = { ctx ->
            GLSurfaceView(ctx).apply {
                setEGLContextClientVersion(2)
                val renderer = GlPreviewRenderer(
                    onSurfaceReady = { surface ->
                        mainHandler.post { onSurfaceReady(surface) }
                    }
                )
                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                rendererRef = renderer
            }
        },
        modifier = modifier.fillMaxSize(),
        onRelease = {
            rendererRef?.release()
            rendererRef = null
            onSurfaceDestroyed()
        }
    )

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
