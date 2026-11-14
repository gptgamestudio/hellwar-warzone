package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = AmberWarning,
    onPrimary = Color.Black,
    primaryContainer = ArmorCard,
    onPrimaryContainer = AmberGlow,
    secondary = CyberCyan,
    onSecondary = Color.Black,
    secondaryContainer = CarbonSurface,
    onSecondaryContainer = CyanGlow,
    tertiary = ThreatRed,
    background = GunmetalBlack,
    surface = CarbonSurface,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    outline = TacticalBorder
)

@Composable
fun MyApplicationTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
