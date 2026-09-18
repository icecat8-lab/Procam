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
import com.procam.ui.theme.ProcamColors
import com.procam.ui.theme.ProcamType

@Composable
fun MoreScreen(
    onBack: () -> Unit,
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
                modifier = Modifier.clickable(onClick = onBack).padding(end = 16.dp)
            )
            Text("More", color = ProcamColors.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(24.dp))

        SettingRow("FPS", "30")
        SettingRow("Resolution", "1080p")
        SettingRow("Bitrate", "20 Mbps")
        SettingRow("Codec", "H.264")

        Spacer(Modifier.height(32.dp))

        Text("About", color = ProcamColors.Accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
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
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/icecat8-lab/Procam"))
                context.startActivity(intent)
            }
        )

        Spacer(Modifier.height(24.dp))

        Text("Procam v0.1.3", color = ProcamColors.TextMuted, fontSize = 12.sp)
    }
}

@Composable
private fun SettingRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = ProcamColors.TextDim, fontSize = 14.sp)
        Text(value, color = ProcamColors.Text, fontSize = 14.sp)
    }
}
