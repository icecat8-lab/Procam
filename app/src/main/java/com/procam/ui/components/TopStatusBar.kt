package com.procam.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.procam.ui.theme.ProcamColors
import com.procam.ui.theme.ProcamType

@Composable
fun TopStatusBar(
    timecode: String,
    fps: Int,
    shutter: String,
    iris: String,
    iso: Int,
    wb: String,
    resolution: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(ProcamColors.PanelSoft)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top
    ) {
        // LEFT: timecode (small) + fps + shutter + iris
        Row(
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.Top
        ) {
            // small timecode where LENS used to be
            Column(horizontalAlignment = Alignment.Start) {
                Text("TIME", style = ProcamType.Label)
                Spacer(Modifier.height(2.dp))
                Text(timecode, style = ProcamType.Value, color = ProcamColors.Record)
            }
            Stat("FPS", fps.toString())
            Stat("SHUTTER", shutter)
            Stat("IRIS", iris)
        }

        Spacer(Modifier.weight(1f))

        // RIGHT: iso + wb + resolution
        Row(
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.Top
        ) {
            Stat("ISO", iso.toString())
            Stat("WB", wb)
            Badge(resolution)
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.widthIn(min = 44.dp)
    ) {
        Text(label, style = ProcamType.Label, textAlign = TextAlign.Center)
        Spacer(Modifier.height(2.dp))
        Text(value, style = ProcamType.Value)
    }
}

@Composable
private fun Badge(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .border(1.dp, ProcamColors.Text, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(text, style = ProcamType.Badge)
    }
}
