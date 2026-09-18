package com.procam.ui

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.view.Surface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.procam.camera.ProcamEngine
import com.procam.ui.components.*
import com.procam.ui.theme.ProcamColors

@Composable
fun CameraScreen(hasPermission: Boolean) {
    val context = LocalContext.current
    var engineState by remember { mutableStateOf(ProcamEngine.State()) }
    val engine = remember {
        ProcamEngine(context) { newState -> engineState = newState }
    }
    var mode by remember { mutableStateOf(CameraMode.VIDEO) }
    var surfaceReady by remember { mutableStateOf(false) }
    var lastSurface by remember { mutableStateOf<Surface?>(null) }
    var isGridOn by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }

    val timecode = formatTimecode(engineState.durationMs, engineState.isRecording)

    LaunchedEffect(hasPermission, surfaceReady) {
        if (hasPermission && surfaceReady && lastSurface != null) {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            engine.open(cm, lastSurface!!)
        }
    }

    if (showMore) {
        MoreScreen(onBack = { showMore = false })
        return
    }

    Row(Modifier.fillMaxSize().background(ProcamColors.Bg)) {
        Box(Modifier.weight(1f).fillMaxHeight()) {
            CameraPreview(
                onSurfaceReady = { holder ->
                    lastSurface = holder.surface
                    surfaceReady = true
                },
                modifier = Modifier.fillMaxSize()
            )

            GridOverlay(showGrid = isGridOn, modifier = Modifier.fillMaxSize())

            TopStatusBar(
                timecode = timecode,
                shutter = formatShutter(engineState.shutterNs),
                iris = "f1.8",
                iso = engineState.iso,
                wb = if (engineState.wbKelvin > 0) "${engineState.wbKelvin}K" else "AUTO",
                resolution = "1080p",
                isRecording = engineState.isRecording,
                modifier = Modifier.align(Alignment.TopStart)
            )

            if (engineState.isRecording) {
                AudioMeter(
                    levelL = engineState.audioLevelL,
                    levelR = engineState.audioLevelR,
                    width = 100,
                    height = 16,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 24.dp)
                )
            }

            if (!hasPermission) {
                Text(
                    "需要กล้องและไมค์",
                    color = Color.Yellow,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                engineState.error?.let { err ->
                    Text(
                        text = "⚠ $err",
                        color = Color.Red,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }
        }

        RightSidebar(
            isRecording = engineState.isRecording,
            selectedMode = mode,
            onRecordToggle = {
                if (engineState.isRecording) {
                    engine.stopRecording()
                } else {
                    val dir = context.getExternalFilesDir(null) ?: context.filesDir
                    val file = java.io.File(dir, "PROCAM_${System.currentTimeMillis()}.mp4")
                    engine.startRecording(file)
                }
            },
            onOpenGallery = {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    type = "video/*"
                }
                runCatching { context.startActivity(intent) }
            },
            onOpenMore = { showMore = true },
            onToggleLut = {},
            onToggleGrid = { isGridOn = !isGridOn },
            isGridOn = isGridOn
        )
    }

    DisposableEffect(Unit) {
        onDispose { engine.close() }
    }
}

private fun formatTimecode(ms: Long, isRecording: Boolean): String {
    if (!isRecording && ms == 0L) return "00:00"
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return "%02d:%02d".format(m, s)
}

private fun formatShutter(ns: Long): String {
    if (ns <= 0) return "AUTO"
    val denom = (1_000_000_000.0 / ns).toInt()
    return "1/$denom"
}
