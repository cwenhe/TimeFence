package com.cwenhe.timefence.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF0F766E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7F2EE),
    onPrimaryContainer = Color(0xFF073B37),
    secondary = Color(0xFFB45309),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE1C2),
    onSecondaryContainer = Color(0xFF4A2500),
    tertiary = Color(0xFFC2413B),
    onTertiary = Color.White,
    error = Color(0xFFB3261E),
    background = Color(0xFFFAFAF8),
    onBackground = Color(0xFF1E1F22),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1E1F22),
    surfaceVariant = Color(0xFFE9ECEA),
    onSurfaceVariant = Color(0xFF454846),
    outline = Color(0xFF737774),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF80D5CB),
    onPrimary = Color(0xFF003D38),
    primaryContainer = Color(0xFF075A53),
    secondary = Color(0xFFFFB874),
    tertiary = Color(0xFFFFB4AE),
    background = Color(0xFF151715),
    surface = Color(0xFF1D201E),
    surfaceVariant = Color(0xFF353936),
    onSurfaceVariant = Color(0xFFC5C9C6),
)

/** 应用时界的浅色或深色 Material 3 配色。 */
@Composable
fun TimeFenceTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
