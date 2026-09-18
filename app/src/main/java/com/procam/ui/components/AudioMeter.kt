package com.procam.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import com.procam.ui.theme.ProcamColors

@Composable
fun AudioMeter(
    levelL: Float,
    levelR: Float,
    modifier: Modifier = Modifier,
    width: Int = 100,
    height: Int = 14
) {
    Row(
        modifier = modifier.width(width.dp).height(height.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Bar(levelL, Modifier.weight(1f).fillMaxHeight())
        Bar(levelR, Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun Bar(level: Float, modifier: Modifier = Modifier) {
    Canvas(
        modifier
            .clip(RoundedCornerShape(1.dp))
            .background(ProcamColors.AudioTrack)
    ) {
        val w = size.width
        val h = size.height
        val lv = level.coerceIn(0f, 1f)
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
    }
}
