package io.github.velcheor137.shiori

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
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

/** Тёмная ли тема сейчас: свой фон решает по яркости, иначе — выбранный режим. */
@Composable
fun shioriDark(): Boolean {
    val store = LocalContext.current.shiori.store
    val theme by store.appTheme.collectAsStateWithLifecycle()
    val colors by store.themeColors.collectAsStateWithLifecycle()
    colors.background?.let { return ThemePalette.isDark(ThemePalette.color(it)) }
    return when (theme) {
        AppTheme.Dark -> true
        AppTheme.Light -> false
        AppTheme.System -> isSystemInDarkTheme()
    }
}

@Composable
fun ShioriTheme(content: @Composable () -> Unit) {
    val colors by LocalContext.current.shiori.store.themeColors.collectAsStateWithLifecycle()
    val dark = shioriDark()
    val scheme = remember(dark, colors) { ThemePalette.scheme(if (dark) Dark else Light, dark, colors.accent?.let(ThemePalette::color), colors.background?.let(ThemePalette::color)) }
    MaterialTheme(colorScheme = scheme, content = content)
}

/**
 * Схема из своих цветов: акцент даёт primary и контейнеры, фон — поверхности, вторичный текст и рамки
 * (смешением фона с цветом текста). Контраст акцента с фоном подтягивается до ≥ 3:1.
 */
object ThemePalette {
    fun color(argb: Long): Color = Color((argb and 0xFFFFFFFFL).toInt())

    fun argb(c: Color): Long = c.toArgb().toLong() and 0xFFFFFFFFL

    fun isDark(bg: Color): Boolean = bg.luminance() < 0.35f

    fun contrast(a: Color, b: Color): Float {
        val la = a.luminance()
        val lb = b.luminance()
        return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
    }

    fun scheme(base: ColorScheme, dark: Boolean, accent: Color?, background: Color?): ColorScheme {
        if (accent == null && background == null) return base
        val bg = background ?: base.background
        val fg = if (background == null) base.onBackground else if (dark) lerp(bg, Color.White, 0.86f) else lerp(bg, Color.Black, 0.88f)
        var primary = accent ?: base.primary
        // Акцент читается на фоне: высветляем на тёмном, затемняем на светлом.
        var guard = 0
        while (contrast(primary, bg) < 3f && guard++ < 10) primary = lerp(primary, if (dark) Color.White else Color.Black, 0.15f)
        val onPrimary = if (primary.luminance() > 0.45f) Color(0xFF14121A) else Color.White
        val container = if (accent == null) base.primaryContainer else lerp(bg, primary, if (dark) 0.38f else 0.20f)
        val onContainer = if (accent == null) base.onPrimaryContainer else if (dark) lerp(primary, Color.White, 0.75f) else lerp(primary, Color.Black, 0.6f)
        val mixed = background != null
        return base.copy(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = container,
            onPrimaryContainer = onContainer,
            secondary = primary,
            onSecondary = onPrimary,
            secondaryContainer = container,
            onSecondaryContainer = onContainer,
            tertiary = primary,
            onTertiary = onPrimary,
            tertiaryContainer = container,
            onTertiaryContainer = onContainer,
            background = bg,
            onBackground = fg,
            surface = bg,
            onSurface = fg,
            surfaceVariant = lerp(if (mixed) lerp(bg, fg, 0.07f) else base.surfaceVariant, primary, 0.06f),
            onSurfaceVariant = if (mixed) lerp(bg, fg, 0.62f) else base.onSurfaceVariant,
            surfaceContainerLowest = if (mixed) bg else base.surfaceContainerLowest,
            surfaceContainerLow = if (mixed) lerp(bg, fg, 0.025f) else base.surfaceContainerLow,
            surfaceContainer = if (mixed) lerp(bg, fg, 0.045f) else base.surfaceContainer,
            surfaceContainerHigh = if (mixed) lerp(bg, fg, 0.075f) else base.surfaceContainerHigh,
            surfaceContainerHighest = if (mixed) lerp(bg, fg, 0.11f) else base.surfaceContainerHighest,
            surfaceBright = if (mixed) lerp(bg, fg, 0.11f) else base.surfaceBright,
            surfaceDim = if (mixed) bg else base.surfaceDim,
            inverseSurface = fg,
            inverseOnSurface = bg,
            inversePrimary = lerp(primary, bg, 0.4f),
            outline = if (mixed) lerp(bg, fg, 0.16f) else base.outline,
            outlineVariant = if (mixed) lerp(bg, fg, 0.16f) else base.outlineVariant,
            scrim = Color.Black,
        )
    }
}
