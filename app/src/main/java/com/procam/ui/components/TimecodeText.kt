package com.procam.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.procam.ui.theme.ProcamColors
import com.procam.ui.theme.ProcamType

@Composable
fun TimecodeText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = ProcamType.Timecode,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(ProcamColors.PanelSoft)
            .padding(horizontal = 14.dp, vertical = 2.dp)
    )
}
