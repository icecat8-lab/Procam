package com.procam.ui

import android.content.Context
import android.hardware.camera2.CameraManager
import android.view.Surface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.procam.camera.ProcamEngine
import com.procam.ui.components.*
import com.procam.ui.theme.ProcamColors
import kotlin.math.abs

@Composable
fun CameraScreen() {
    val context = LocalContext.current
    var engineState by remember { mutableStateOf(ProcamEngine.State()) }
    val engine = remember {
        ProcamEngine(context) { newState -> engineState = newState }
    }
    var mode by remember { mutableStateOf(CameraMode.VIDEO) }

    val timecode = formatTimecode(engineState.durationMs)

    val histR = remember { FloatArray(64) { i -> (1f - abs(i - 20) / 40f).coerceIn(0f, 1f) * 0.9f } }
    val histG = remember { FloatArray(64) { i -> (1f - abs(i - 30) / 40f).coerceIn(0f, 1f) * 0.85f } }
    val histB = remember { FloatArray(64) { i -> (1f - abs(i - 24) / 40f).coerceIn(0f, 1f) * 0.8f } }

    Row(Modifier.fillMaxSize().background(ProcamColors.Bg)) {
        Box(Modifier.weight(1f).fillMaxHeight()) {
            CameraPreview(
                onSurfaceReady = { holder ->
                    val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                    engine.open(cm, holder.surface)
                },
                modifier = Modifier.fillMaxSize()
            )

            TopStatusBar(
                lens = "26mm",
                fps = 30,
                shutter = formatShutter(engineState.shutterNs),
                iris = "f1.8",
                timecode = timecode,
                iso = engineState.iso,
                wb = engineState.wbKelvin,
                tint = 0,
                resolution = "1080p",
                wbAuto = false,
                modifier = Modifier.align(Alignment.TopStart)
            )

            Row(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Histogram(histR, histG, histB, width = 200, height = 56)
                RecordingInfo(
                    remainingTime = "05:37",
                    storageUsedPct = 1,
                    storageFree = "2GB",
                    batteryPct = 87
                )
                AudioMeter(
                    levelL = if (engineState.isRecording) 0.62f else 0f,
                    levelR = if (engineState.isRecording) 0.58f else 0f,
                    peakL = if (engineState.isRecording) 0.78f else 0f,
                    peakR = if (engineState.isRecording) 0.74f else 0f,
                    width = 200, height = 44
                )
            }

            engineState.error?.let { err ->
                androidx.compose.material3.Text(
                    text = "⚠ $err",
                    color = androidx.compose.ui.graphics.Color.Red,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }

        RightSidebar(
            isRecording = engineState.isRecording,
            selectedMode = mode,
            onModeChange = { mode = it },
            onRecordToggle = {
                if (engineState.isRecording) {
                    engine.stopRecording()
                } else {
                    val dir = context.getExternalFilesDir(null) ?: context.filesDir
                    val file = java.io.File(dir, "PROCAM_${System.currentTimeMillis()}.mp4")
                    engine.startRecording(file)
                }
            },
            onOpenGallery = { },
            onOpenMore = { },
            onToggleLut = { },
            onToggleGrid = { }
        )
    }

    DisposableEffect(Unit) {
        onDispose { engine.close() }
    }
}

private fun formatTimecode(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec / 60) % 60
    val s = totalSec % 60
    val f = ((ms % 1000) * 30 / 1000).toInt()
    return "%02d:%02d:%02d:%02d".format(h, m, s, f)
}

private fun formatShutter(ns: Long): String {
    if (ns <= 0) return "1/30"
    val denom = (1_000_000_000.0 / ns).toInt()
    return "1/$denom"
}
