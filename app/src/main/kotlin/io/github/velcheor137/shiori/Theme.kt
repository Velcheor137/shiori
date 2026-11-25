package io.github.velcheor137.shiori

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Светлая тема: тёплая бумага и мягкий фиолетовый акцент (контраст текста ≥ 4.5:1). */
private val Light = lightColorScheme(
    primary = Color(0xFF6B58A8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6DEFA),
    onPrimaryContainer = Color(0xFF2A1F52),
    background = Color(0xFFF7F5F0),
    onBackground = Color(0xFF1F1D1A),
    surface = Color(0xFFF7F5F0),
    onSurface = Color(0xFF1F1D1A),
    surfaceVariant = Color(0xFFECE8F2),
    onSurfaceVariant = Color(0xFF5A564E),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFBFAF7),
    surfaceContainerHigh = Color(0xFFF0EDE6),
    outline = Color(0xFFE2DED5),
    outlineVariant = Color(0xFFE2DED5),
    secondaryContainer = Color(0xFFE6DEFA),
    onSecondaryContainer = Color(0xFF2A1F52),
)

/** Тёмная тема — как читалка по умолчанию («Как на сайте»): #2F3136 / #DCDDDE, мягкий фиолетовый акцент. */
private val Dark = darkColorScheme(
    primary = Color(0xFFB9A8F0),
    onPrimary = Color(0xFF241A45),
    primaryContainer = Color(0xFF4A3D7A),
    onPrimaryContainer = Color(0xFFE6DEFA),
    background = Color(0xFF2F3136),
    onBackground = Color(0xFFDCDDDE),
    surface = Color(0xFF2F3136),
    onSurface = Color(0xFFDCDDDE),
    surfaceVariant = Color(0xFF3A3C42),
    onSurfaceVariant = Color(0xFFA3A6AC),
    surfaceContainer = Color(0xFF36393F),
    surfaceContainerLow = Color(0xFF32353A),
    surfaceContainerHigh = Color(0xFF3A3C42),
    surfaceContainerHighest = Color(0xFF40444B),
    outline = Color(0xFF45484F),
    outlineVariant = Color(0xFF45484F),
    secondaryContainer = Color(0xFF4A3D7A),
    onSecondaryContainer = Color(0xFFE6DEFA),
)

@Composable
fun ShioriTheme(content: @Composable () -> Unit) {
    val theme by LocalContext.current.shiori.store.appTheme.collectAsStateWithLifecycle()
    val dark = when (theme) {
        AppTheme.Dark -> true
        AppTheme.Light -> false
        AppTheme.System -> isSystemInDarkTheme()
    }
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}
