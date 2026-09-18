package com.procam.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import com.procam.ui.theme.ProcamColors

@Composable
fun Histogram(
    red: FloatArray,
    green: FloatArray,
    blue: FloatArray,
    modifier: Modifier = Modifier,
    width: Int = 200,
    height: Int = 56
) {
    Box(
        modifier = modifier
            .size(width.dp, height.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Color(0xAA000000))
            .border(0.5.dp, ProcamColors.Border, RoundedCornerShape(3.dp))
    ) {
        Canvas(Modifier.size(width.dp, height.dp)) {
            val w = size.width
            val h = size.height
            for (i in 1..3) {
                val x = w * i / 4f
                drawLine(ProcamColors.HistoGrid, Offset(x, 0f), Offset(x, h), 1f)
                val y = h * i / 4f
                drawLine(ProcamColors.HistoGrid, Offset(0f, y), Offset(w, y), 1f)
            }
            fun channel(data: FloatArray, color: Color) {
                if (data.isEmpty()) return
                val step = w / (data.size - 1).coerceAtLeast(1)
                val path = Path().apply {
                    moveTo(0f, h)
                    data.forEachIndexed { i, v ->
                        lineTo(i * step, h - (v.coerceIn(0f, 1f) * h))
                    }
                    lineTo(w, h)
                    close()
                }
                drawPath(path, color.copy(alpha = 0.45f))
            }
            channel(red, ProcamColors.HistoR)
            channel(green, ProcamColors.HistoG)
            channel(blue, ProcamColors.HistoB)
        }
    }
}
