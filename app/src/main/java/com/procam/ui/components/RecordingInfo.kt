package com.procam.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.procam.ui.theme.ProcamColors
import com.procam.ui.theme.ProcamType

@Composable
fun RecordingInfo(
    remainingTime: String,
    storageUsedPct: Int,
    storageFree: String,
    batteryPct: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(ProcamColors.Panel)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.width(120.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.PhoneAndroid,
                    contentDescription = null,
                    tint = ProcamColors.Text,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(remainingTime, style = ProcamType.ValueLg)
            }
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(ProcamColors.Border)
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(storageUsedPct / 100f)
                        .background(ProcamColors.Accent)
                )
            }
            Spacer(Modifier.height(2.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("$storageUsedPct%", style = ProcamType.Label)
                Text(storageFree, style = ProcamType.Label)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Outlined.BatteryFull,
                contentDescription = null,
                tint = ProcamColors.Text,
                modifier = Modifier.size(18.dp)
            )
            Text("$batteryPct%", style = ProcamType.Label)
        }
    }
}
