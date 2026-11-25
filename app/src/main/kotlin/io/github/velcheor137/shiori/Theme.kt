package io.github.velcheor137.shiori

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Цвета Literary Minimalism из §8.4 плана (контраст текста ≥ 4.5:1). */
private val Light = lightColorScheme(
    primary = Color(0xFF8C3A2B),
    onPrimary = Color.White,
    background = Color(0xFFF7F5F0),
    onBackground = Color(0xFF1F1D1A),
    surface = Color(0xFFF7F5F0),
    onSurface = Color(0xFF1F1D1A),
    surfaceVariant = Color(0xFFEDE9E1),
    onSurfaceVariant = Color(0xFF5A564E),
    surfaceContainer = Color(0xFFFFFFFF),
    outline = Color(0xFFE2DED5),
    outlineVariant = Color(0xFFE2DED5),
    secondaryContainer = Color(0xFFEFDDD7),
    onSecondaryContainer = Color(0xFF5A2318),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFE0A08C),
    onPrimary = Color(0xFF2A120C),
    background = Color(0xFF151412),
    onBackground = Color(0xFFE8E5DE),
    surface = Color(0xFF151412),
    onSurface = Color(0xFFE8E5DE),
    surfaceVariant = Color(0xFF26241F),
    onSurfaceVariant = Color(0xFFB0AA9F),
    surfaceContainer = Color(0xFF1D1C19),
    outline = Color(0xFF2F2D29),
    outlineVariant = Color(0xFF2F2D29),
    secondaryContainer = Color(0xFF3A2620),
    onSecondaryContainer = Color(0xFFF0D5CC),
)

@Composable
fun ShioriTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
