package com.procam.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.procam.ui.theme.ProcamColors
import com.procam.ui.theme.ProcamType

@Composable
fun TopStatusBar(
    timecode: String,
    shutter: String,
    af: String,
    iso: Int,
    wb: String,
    ev: String,
    isRecording: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(horizontalAlignment = Alignment.Start) {
                Text("TIME", style = ProcamType.Label)
                Spacer(Modifier.height(2.dp))
                Text(
                    timecode,
                    style = ProcamType.Timecode,
                    color = if (isRecording) ProcamColors.Record else ProcamColors.Text
                )
            }
            Stat("SHUTTER", shutter)
            AfStat(af)
        }

        Spacer(Modifier.weight(1f))

        Row(
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.Top
        ) {
            Stat("ISO", iso.toString())
            Stat("WB", wb)
            EvBadge(ev)
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
private fun AfStat(value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("AF", style = ProcamType.Label, textAlign = TextAlign.Center)
        Spacer(Modifier.height(2.dp))
        Text(
            value,
            color = ProcamColors.Text,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun EvBadge(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .border(1.dp, ProcamColors.Text, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(text, style = ProcamType.Badge)
    }
}
