package com.byanr.veena.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Palette mirrored from the previous Veena mobile app (theme.ts).
data class VeenaColors(
    val text: Color,
    val background: Color,
    val backgroundElement: Color,
    val backgroundSelected: Color,
    val textSecondary: Color,
)

private val LightColors = VeenaColors(
    text = Color(0xFF000000),
    background = Color(0xFFFFFFFF),
    backgroundElement = Color(0xFFF0F0F3),
    backgroundSelected = Color(0xFFE0E1E6),
    textSecondary = Color(0xFF60646C),
)

private val DarkColors = VeenaColors(
    text = Color(0xFFFFFFFF),
    background = Color(0xFF000000),
    backgroundElement = Color(0xFF212225),
    backgroundSelected = Color(0xFF2E3135),
    textSecondary = Color(0xFFB0B4BA),
)

val StatusRecording = Color(0xFFFF4444)
val StatusReady = Color(0xFF44CC44)
val StatusInitializing = Color(0xFFFFAA00)
val AccentBlue = Color(0xFF208AEF)

private val LightScheme = lightColorScheme(
    primary = AccentBlue,
    onPrimary = Color.White,
    background = LightColors.background,
    onBackground = LightColors.text,
    surface = LightColors.background,
    onSurface = LightColors.text,
    surfaceVariant = LightColors.backgroundElement,
    onSurfaceVariant = LightColors.textSecondary,
    secondaryContainer = LightColors.backgroundElement,
    onSecondaryContainer = LightColors.text,
)

private val DarkScheme = darkColorScheme(
    primary = AccentBlue,
    onPrimary = Color.White,
    background = DarkColors.background,
    onBackground = DarkColors.text,
    surface = DarkColors.background,
    onSurface = DarkColors.text,
    surfaceVariant = DarkColors.backgroundElement,
    onSurfaceVariant = DarkColors.textSecondary,
    secondaryContainer = DarkColors.backgroundElement,
    onSecondaryContainer = DarkColors.text,
)

@Composable
fun VeenaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        content = content,
    )
}

val veenaColors: VeenaColors
    @Composable get() = if (isSystemInDarkTheme()) DarkColors else LightColors
