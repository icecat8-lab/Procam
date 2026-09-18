package com.procam.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.procam.camera.VideoSettings
import com.procam.ui.theme.ProcamColors

@Composable
fun MoreScreen(
    settings: VideoSettings,
    onBack: () -> Unit,
    onResolutionClick: () -> Unit,
    onFpsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ProcamColors.Bg)
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "<",
                color = ProcamColors.Text,
                fontSize = 24.sp,
                modifier = Modifier
                    .clickable(onClick = onBack)
                    .padding(end = 16.dp)
            )
            Text(
                "More",
                color = ProcamColors.Text,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(Modifier.height(24.dp))

        ClickRow(
            label = "Resolution",
            value = "${settings.width}×${settings.height}",
            onClick = onResolutionClick
        )
        ClickRow(
            label = "FPS",
            value = "${settings.fps}",
            onClick = onFpsClick
        )
        InfoRow(label = "Bitrate", value = "${settings.bitrate / 1_000_000} Mbps")
        InfoRow(label = "Codec", value = settings.codecLabel)

        Spacer(Modifier.height(32.dp))

        Text(
            "About",
            color = ProcamColors.Accent,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("@", color = ProcamColors.TextDim, fontSize = 14.sp)
            Spacer(Modifier.width(4.dp))
            Text(
                "@ICECAT",
                color = ProcamColors.Text,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(Modifier.height(12.dp))

        Text(
            "github.com/icecat8-lab/Procam",
            color = ProcamColors.Accent,
            fontSize = 14.sp,
            modifier = Modifier.clickable {
                val intent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/icecat8-lab/Procam")
                )
                runCatching { context.startActivity(intent) }
            }
        )

        Spacer(Modifier.height(24.dp))

        Text("Procam v0.2.0", color = ProcamColors.TextMuted, fontSize = 12.sp)
    }
}

@Composable
private fun ClickRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = ProcamColors.TextDim, fontSize = 14.sp)
        Text(value, color = ProcamColors.Text, fontSize = 14.sp)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = ProcamColors.TextDim, fontSize = 14.sp)
        Text(value, color = ProcamColors.Text, fontSize = 14.sp)
    }
}

@Composable
fun SelectScreen(
    title: String,
    options: List<Pair<String, VideoSettings>>,
    current: VideoSettings,
    onBack: () -> Unit,
    onSelect: (VideoSettings) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ProcamColors.Bg)
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "<",
                color = ProcamColors.Text,
                fontSize = 24.sp,
                modifier = Modifier
                    .clickable(onClick = onBack)
                    .padding(end = 16.dp)
            )
            Text(
                title,
                color = ProcamColors.Text,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(Modifier.height(24.dp))

        options.forEach { (label, setting) ->
            val selected = when (title) {
                "Resolution" -> setting.width == current.width && setting.height == current.height
                "FPS" -> setting.fps == current.fps
                else -> false
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(setting) }
                    .padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    label,
                    color = if (selected) ProcamColors.Accent else ProcamColors.Text,
                    fontSize = 15.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                )
                if (selected) {
                    Text("✓", color = ProcamColors.Accent, fontSize = 15.sp)
                }
            }
        }
    }
}
