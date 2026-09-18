package com.procam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.procam.ui.components.*
import com.procam.ui.theme.ProcamColors
import kotlinx.coroutines.delay
import kotlin.math.abs

@Composable
fun CameraScreen() {
    var isRecording by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(CameraMode.VIDEO) }
    var timecode by remember { mutableStateOf("00:00:00:00") }

    LaunchedEffect(isRecording) {
        if (!isRecording) { timecode = "00:00:00:00"; return@LaunchedEffect }
        var frames = 0
        while (true) {
            val f = frames % 24
            val s = (frames / 24) % 60
            val m = (frames / 24 / 60) % 60
            val h = frames / 24 / 60 / 60
            timecode = "%02d:%02d:%02d:%02d".format(h, m, s, f)
            frames++
            delay(1000L / 24)
        }
    }

    val histR = remember { FloatArray(64) { i -> (1f - abs(i - 20) / 40f).coerceIn(0f, 1f) * 0.9f } }
    val histG = remember { FloatArray(64) { i -> (1f - abs(i - 30) / 40f).coerceIn(0f, 1f) * 0.85f } }
    val histB = remember { FloatArray(64) { i -> (1f - abs(i - 24) / 40f).coerceIn(0f, 1f) * 0.8f } }

    Row(Modifier.fillMaxSize().background(ProcamColors.Bg)) {
        Box(Modifier.weight(1f).fillMaxHeight()) {
            CameraPreview(onSurfaceReady = { }, modifier = Modifier.fillMaxSize())

            TopStatusBar(
                lens = "26mm", fps = 24, shutter = "1/50", iris = "f1.6",
                timecode = timecode, iso = 35, wb = 5580, tint = 9,
                resolution = "4K", wbAuto = true,
                modifier = Modifier.align(Alignment.TopStart)
            )

            Row(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(14.dp),
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
                    levelL = 0.62f, levelR = 0.58f,
                    peakL = 0.78f, peakR = 0.74f,
                    width = 200, height = 44
                )
            }
        }
        RightSidebar(
            isRecording = isRecording,
            selectedMode = mode,
            onModeChange = { mode = it },
            onRecordToggle = { isRecording = !isRecording },
            onOpenGallery = {},
            onOpenMore = {},
            onToggleLut = {},
            onToggleGrid = {}
        )
    }
}
