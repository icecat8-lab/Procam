package com.procam.ui

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
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
import com.procam.camera.VideoSettings
import com.procam.ui.components.*
import com.procam.ui.theme.ProcamColors

private sealed class Screen {
    object Camera : Screen()
    object More : Screen()
    object Resolution : Screen()
    object Fps : Screen()
}

@Composable
fun CameraScreen(hasPermission: Boolean) {
    val context = LocalContext.current
    var engineState by remember { mutableStateOf(ProcamEngine.State()) }
    val engine = remember {
        ProcamEngine(context) { newState -> engineState = newState }
    }
    var screen by remember { mutableStateOf<Screen>(Screen.Camera) }
    var settings by remember { mutableStateOf(VideoSettings()) }
    var isGridOn by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { engine.close() }
    }

    when (screen) {
        Screen.More -> {
            MoreScreen(
                settings = settings,
                onBack = { screen = Screen.Camera },
                onResolutionClick = { screen = Screen.Resolution },
                onFpsClick = { screen = Screen.Fps }
            )
            return
        }
        Screen.Resolution -> {
            val options = listOf(
                "3840×2160 (4K)" to VideoSettings(3840, 2160, 30, 50_000_000, "video/avc", "H.264", "4K 30"),
                "1920×1080 (FHD)" to VideoSettings(1920, 1080, 30, 20_000_000, "video/avc", "H.264", "1080p 30"),
                "1280×720 (HD)" to VideoSettings(1280, 720, 30, 10_000_000, "video/avc", "H.264", "720p 30")
            )
            SelectScreen(
                title = "Resolution",
                options = options,
                current = settings,
                onBack = { screen = Screen.More },
                onSelect = { selected ->
                    settings = settings.copy(
                        width = selected.width,
                        height = selected.height,
                        bitrate = selected.bitrate,
                        label = selected.label
                    )
                    screen = Screen.More
                }
            )
            return
        }
        Screen.Fps -> {
            val options = listOf(
                "30 fps" to VideoSettings(settings.width, settings.height, 30, settings.bitrate, "video/avc", "H.264", settings.label),
                "60 fps" to VideoSettings(settings.width, settings.height, 60, settings.bitrate * 2, "video/avc", "H.264", settings.label)
            )
            SelectScreen(
                title = "FPS",
                options = options,
                current = settings,
                onBack = { screen = Screen.More },
                onSelect = { selected ->
                    settings = settings.copy(fps = selected.fps, bitrate = selected.bitrate)
                    screen = Screen.More
                }
            )
            return
        }
        Screen.Camera -> {}
    }

    val timecode = formatTimecode(engineState.durationMs, engineState.isRecording)

    Row(Modifier.fillMaxSize().background(ProcamColors.Bg)) {
        Box(Modifier.weight(1f).fillMaxHeight()) {
            CameraPreview(
                onSurfaceReady = { holder ->
                    val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                    if (engineState.isOpen) {
                        engine.attachSurface(holder.surface)
                    } else {
                        engine.open(cm, holder.surface)
                    }
                },
                onSurfaceDestroyed = { engine.detachSurface() },
                modifier = Modifier.fillMaxSize()
            )

            GridOverlay(showGrid = isGridOn, modifier = Modifier.fillMaxSize())

            TopStatusBar(
                timecode = timecode,
                shutter = formatShutter(engineState.shutterNs),
                iris = "f1.8",
                iso = engineState.iso,
                wb = if (engineState.wbKelvin > 0) "${engineState.wbKelvin}K" else "AUTO",
                resolution = "${settings.width}×${settings.height}",
                isRecording = engineState.isRecording,
                modifier = Modifier.align(Alignment.TopStart)
            )

            if (engineState.isRecording) {
                AudioMeter(
                    levelL = engineState.audioLevelL,
                    levelR = engineState.audioLevelR,
                    width = 100,
                    height = 14,
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
            onRecordToggle = {
                if (engineState.isRecording) {
                    engine.stopRecording()
                } else {
                    val dir = context.getExternalFilesDir(null) ?: context.filesDir
                    val file = java.io.File(dir, "PROCAM_${System.currentTimeMillis()}.mp4")
                    engine.startRecording(file, settings)
                }
            },
            onOpenGallery = {
                val intent = Intent(Intent.ACTION_VIEW).apply { type = "video/*" }
                runCatching { context.startActivity(intent) }
            },
            onOpenMore = { screen = Screen.More },
            onToggleGrid = { isGridOn = !isGridOn },
            isGridOn = isGridOn
        )
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
