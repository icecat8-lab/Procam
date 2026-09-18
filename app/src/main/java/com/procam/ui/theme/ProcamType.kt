package com.procam.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object ProcamType {
    private val mono = FontFamily.Monospace

    val Label = TextStyle(
        fontFamily = mono,
        fontSize = 9.sp,
        letterSpacing = 1.sp,
        color = ProcamColors.TextMuted
    )
    val Value = TextStyle(
        fontFamily = mono,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = ProcamColors.Text
    )
    val Timecode = TextStyle(
        fontFamily = mono,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.sp,
        color = ProcamColors.Text
    )
    val Badge = TextStyle(
        fontFamily = mono,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = ProcamColors.Text
    )
}
