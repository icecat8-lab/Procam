package com.procam.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import com.procam.ui.theme.ProcamColors

@Composable
fun GridOverlay(
    showGrid: Boolean,
    modifier: Modifier = Modifier
) {
    if (!showGrid) return
    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val stroke = 1.5f

        drawLine(ProcamColors.GridLine, Offset(w / 3f, 0f), Offset(w / 3f, h), stroke)
        drawLine(ProcamColors.GridLine, Offset(2f * w / 3f, 0f), Offset(2f * w / 3f, h), stroke)
        drawLine(ProcamColors.GridLine, Offset(0f, h / 3f), Offset(w, h / 3f), stroke)
        drawLine(ProcamColors.GridLine, Offset(0f, 2f * h / 3f), Offset(w, 2f * h / 3f), stroke)
    }
}
