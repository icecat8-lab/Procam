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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
    val lifecycleOwner = LocalLifecycleOwner.current

    var engineState by remember { mutableStateOf(ProcamEngine.State()) }
    val engine = remember {
        ProcamEngine(context) { newState -> engineState = newState }
    }
    var screen by remember { mutableStateOf<Screen>(Screen.Camera) }
    var settings by remember { mutableStateOf(VideoSettings()) }
    var isGridOn by remember { mutableStateOf(false) }
    var previewSurface by remember { mutableStateOf<Surface?>(null) }
    var isOpened by remember { mutableStateOf(false) }

    LaunchedEffect(hasPermission, previewSurface) {
        if (hasPermission && previewSurface != null && !isOpened) {
            isOpened = true
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            engine.open(cm, previewSurface!!)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    engine.close()
                    isOpened = false
                    previewSurface = null
                }
                Lifecycle.Event.ON_RESUME -> {
                    isOpened = false
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            engine.close()
        }
    }

    if (screen is Screen.More) {
        MoreScreen(
            settings = settings,
            onBack = { screen = Screen.Camera },
            onResolutionClick = { screen = Screen.Resolution },
            onFpsClick = { screen = Screen.Fps },
            onCodecClick = { screen = Screen.Codec }
        )
        return
    }

    if (screen is Screen.Resolution) {
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

    if (screen is Screen.Fps) {
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

    if (screen is Screen.Codec) {
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

    val timecode = formatTimecode(engineState.durationMs, engineState.isRecording)
    val evText = "%+.1f".format(engineState.ev)

    Row(Modifier.fillMaxSize().background(ProcamColors.Bg)) {
        Box(Modifier.weight(1f).fillMaxHeight()) {
            GlCameraPreview(
                onSurfaceReady = { surface -> previewSurface = surface },
                onSurfaceDestroyed = {
                    previewSurface = null
                    engine.detachSurface()
                },
                lutEnabled = false,
                lutTextureId = 0,
                lutSize = 0f,
                brightness = 0f,
                contrast = 1f,
                saturation = 1f,
                temperature = 0f,
                modifier = Modifier.fillMaxSize()
            )

            GridOverlay(showGrid = isGridOn, modifier = Modifier.fillMaxSize())

            TopStatusBar(
                timecode = timecode,
                shutter = formatShutter(engineState.shutterNs),
                af = "AUTO",
                iso = engineState.iso,
                wb = if (engineState.wbKelvin > 0) "${engineState.wbKelvin}K" else "AUTO",
                ev = evText,
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
                    "ต้องอนุญาตการใช้กล้องและไมโครโฟน",
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
                val uri = engineState.lastUri
                val intent = if (uri != null) {
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "video/mp4")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                } else {
                    Intent(Intent.ACTION_VIEW).apply { type = "video/*" }
                }
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
    return if (ns >= 1_000_000_000L) {
        "%.1fs".format(ns / 1_000_000_000.0)
    } else {
        val denom = (1_000_000_000.0 / ns).roundToInt().coerceAtLeast(1)
        "1/$denom"
    }
}
