package com.phudy.redline.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Design tokens — every screen pulls color and spacing from here. */
object R {
    val Bg = Color(0xFF0A0A0C)
    val Surface = Color(0xFF141418)
    val Surface2 = Color(0xFF1C1C22)
    val Stroke = Color(0xFF2A2A33)
    val Red = Color(0xFFFF1E3C)
    val RedDeep = Color(0xFFB0001E)
    val RedGlow = Color(0x66FF1E3C)
    val Text = Color(0xFFF4F4F6)
    val Muted = Color(0xFF8B8B96)
    val Ok = Color(0xFF2EE59D)
    val Warn = Color(0xFFFFB020)

    val gap = 16.dp
    val gapS = 8.dp
    val radius = 20.dp
    val radiusS = 12.dp

    val redGradient = Brush.linearGradient(listOf(Red, RedDeep))
    val cardGradient = Brush.verticalGradient(listOf(Surface2, Surface))
}

private val scheme = darkColorScheme(
    primary = R.Red, onPrimary = Color.White,
    secondary = R.RedDeep, onSecondary = Color.White,
    background = R.Bg, onBackground = R.Text,
    surface = R.Surface, onSurface = R.Text,
    surfaceVariant = R.Surface2, onSurfaceVariant = R.Muted,
    outline = R.Stroke, error = R.Red,
)

@Composable
fun RedlineTheme(content: @Composable () -> Unit) {
    val base = MaterialTheme.typography
    MaterialTheme(
        colorScheme = scheme,
        typography = base.copy(
            headlineLarge = TextStyle(fontWeight = FontWeight.Black, fontSize = 30.sp, letterSpacing = (-0.5).sp),
            titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp),
            titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
            labelSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.2.sp),
        ),
        content = content,
    )
}
