package com.procam.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.procam.ui.theme.ProcamColors
import com.procam.ui.theme.ProcamType

@Composable
fun AudioMeter(
    levelL: Float, levelR: Float,
    peakL: Float = levelL, peakR: Float = levelR,
    modifier: Modifier = Modifier,
    width: Int = 200, height: Int = 44
) {
    Column(modifier = modifier.width(width.dp)) {
        Row(
            modifier = Modifier.height(height.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                Modifier.width(14.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text("L", style = ProcamType.Label)
                Text("R", style = ProcamType.Label)
            }
            Spacer(Modifier.width(4.dp))
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Bar(levelL, peakL)
                Bar(levelR, peakR)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 18.dp, top = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            listOf("-45","-30","-20","-10","-6","-3","0").forEach {
                Text(it, style = ProcamType.Label)
            }
        }
    }
}

@Composable
private fun Bar(level: Float, peak: Float) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(11.dp)
            .clip(RoundedCornerShape(1.dp))
            .background(ProcamColors.AudioTrack)
    ) {
        val w = size.width
        val h = size.height
        val lv = level.coerceIn(0f, 1f)
        val pk = peak.coerceIn(0f, 1f)
        val greenEnd = w * 0.70f
        val yellowEnd = w * 0.88f

        drawRect(
            color = ProcamColors.AudioGreen,
            topLeft = Offset(0f, 0f),
            size = Size(w * lv * 0.70f, h)
        )
        if (lv > 0.70f) {
            val seg = (lv - 0.70f).coerceAtMost(0.18f)
            drawRect(
                color = ProcamColors.AudioYellow,
                topLeft = Offset(greenEnd, 0f),
                size = Size(w * seg, h)
            )
        }
        if (lv > 0.88f) {
            val seg = (lv - 0.88f).coerceAtMost(0.12f)
            drawRect(
                color = ProcamColors.AudioRed,
                topLeft = Offset(yellowEnd, 0f),
                size = Size(w * seg, h)
            )
        }
        drawRect(Color.White, Offset(w * pk - 1f, 0f), Size(2f, h))
    }
}
