package com.procam.ui

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
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
import com.procam.camera.VideoSettings
import com.procam.ui.components.*
import com.procam.ui.theme.ProcamColors

private sealed class Screen {
    object Camera : Screen()
    object More : Screen()
    object Resolution : Screen()
    object Fps : Screen()
    object Codec : Screen()
}

private fun bitrateFor(width: Int, height: Int, fps: Int): Int {
    val pixels = width.toLong() * height.toLong() * fps.toLong()
    return (pixels * 0.10).toInt().coerceIn(5_000_000, 120_000_000)
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
    var previewSurface by remember { mutableStateOf<Surface?>(null) }
    var opened by remember { mutableStateOf(false) }

    LaunchedEffect(hasPermission, previewSurface) {
        if (hasPermission && previewSurface != null && !opened) {
            opened = true
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            engine.open(cm, previewSurface!!)
        }
    }

    DisposableEffect(Unit) {
        onDispose { engine.close() }
    }

    when (screen) {
        Screen.More -> {
            MoreScreen(
                settings = settings,
                onBack = { screen = Screen.Camera },
                onResolutionClick = { screen = Screen.Resolution },
                onFpsClick = { screen = Screen.Fps },
                onCodecClick = { screen = Screen.Codec }
            )
            return
        }
        Screen.Resolution -> {
            val options = listOf(
                SelectOption(
                    "3840×2160 (4K)",
                    VideoSettings(3840, 2160, 30, bitrateFor(3840, 2160, 30),
                        settings.codec, settings.codecLabel)
                ),
                SelectOption(
                    "1920×1080 (FHD)",
                    VideoSettings(1920, 1080, 30, bitrateFor(1920, 1080, 30),
                        settings.codec, settings.codecLabel)
                ),
                SelectOption(
                    "1280×720 (HD)",
                    VideoSettings(1280, 720, 30, bitrateFor(1280, 720, 30),
                        settings.codec, settings.codecLabel)
                )
            )
            SelectScreen(
                title = "Resolution",
                options = options,
                currentMatch = { it.settings.width == settings.width && it.settings.height == settings.height },
                onBack = { screen = Screen.More },
                onSelect = { option ->
                    val newFps = if (option.settings.height == 2160) 30 else settings.fps
                    settings = settings.copy(
                        width = option.settings.width,
                        height = option.settings.height,
                        fps = newFps,
                        bitrate = bitrateFor(option.settings.width, option.settings.height, newFps)
                    )
                    screen = Screen.More
                }
            )
            return
        }
        Screen.Fps -> {
            val available = if (settings.height == 2160) listOf(30) else listOf(30, 60)
            val options = available.map { fps ->
                SelectOption(
                    "$fps fps",
                    settings.copy(fps = fps, bitrate = bitrateFor(settings.width, settings.height, fps))
                )
            }
            SelectScreen(
                title = "FPS",
                options = options,
                currentMatch = { it.settings.fps == settings.fps },
                onBack = { screen = Screen.More },
                onSelect = { option ->
                    settings = settings.copy(
                        fps = option.settings.fps,
                        bitrate = option.settings.bitrate
                    )
                    screen = Screen.More
                }
            )
            return
        }
        Screen.Codec -> {
            val options = listOf(
                SelectOption("H.264", settings.copy(codec = "video/avc", codecLabel = "H.264")),
                SelectOption("H.265 (HEVC)", settings.copy(codec = "video/hevc", codecLabel = "H.265"))
            )
            SelectScreen(
                title = "Codec",
                options = options,
                currentMatch = { it.settings.codec == settings.codec },
                onBack = { screen = Screen.More },
                onSelect = { option ->
                    settings = settings.copy(
                        codec = option.settings.codec,
                        codecLabel = option.settings.codecLabel
                    )
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
                    previewSurface = holder.surface
                },
                onSurfaceDestroyed = {
                    previewSurface = null
                    engine.detachSurface()
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
                resolution = when (settings.height) {
                    2160 -> "4K"
                    1080 -> "1080p"
                    720 -> "720p"
                    else -> "${settings.width}×${settings.height}"
                },
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
                    engine.startRecording(settings)
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
