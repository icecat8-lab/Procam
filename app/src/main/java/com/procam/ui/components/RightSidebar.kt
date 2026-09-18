package com.procam.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.procam.ui.theme.ProcamColors
import com.procam.ui.theme.ProcamType

@Composable
fun RightSidebar(
    isRecording: Boolean,
    onRecordToggle: () -> Unit,
    onOpenGallery: () -> Unit,
    onOpenMore: () -> Unit,
    onToggleGrid: () -> Unit,
    isGridOn: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .width(76.dp)
            .fillMaxHeight()
            .background(ProcamColors.PanelSoft)
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconPill(Icons.Outlined.Videocam, "Video", true) {}
        }

        RecordButton(isRecording, onRecordToggle)

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            IconPill(Icons.Outlined.GridOn, "Grid", isGridOn, onToggleGrid)
            IconPill(Icons.Outlined.PhotoLibrary, "Media", false, onOpenGallery)
            IconPill(Icons.Outlined.MoreHoriz, "More", false, onOpenMore)
        }
    }
}

@Composable
private fun IconPill(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val tint = if (selected) ProcamColors.Accent else ProcamColors.Text
    val bg = if (selected) ProcamColors.AccentSoft else Color.Transparent
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            style = ProcamType.Label,
            color = if (selected) ProcamColors.Accent else ProcamColors.TextDim
        )
    }
}

@Composable
private fun RecordButton(isRecording: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(ProcamColors.Panel)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (isRecording) Icons.Filled.Stop
                          else Icons.Filled.FiberManualRecord,
            contentDescription = "Record",
            tint = ProcamColors.Record,
            modifier = Modifier.size(if (isRecording) 32.dp else 56.dp)
        )
    }
}
