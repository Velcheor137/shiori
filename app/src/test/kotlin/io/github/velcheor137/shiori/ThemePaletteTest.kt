package io.github.velcheor137.shiori

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ThemePaletteTest {
    @Test
    fun noCustomColorsKeepsBase() {
        val base = darkColorScheme()
        assertSame(base, ThemePalette.scheme(base, true, null, null))
    }

    @Test
    fun darkAccentOnDarkBackgroundIsLiftedToReadable() {
        val bg = Color(0xFF121212)
        val s = ThemePalette.scheme(darkColorScheme(), true, Color(0xFF1A237E), bg)
        assertEquals(bg, s.background)
        assertTrue(ThemePalette.contrast(s.primary, bg) >= 3f)
        assertTrue(s.onBackground.luminance() > 0.6f)
        assertTrue(ThemePalette.contrast(s.onBackground, bg) >= 7f)
    }

    @Test
    fun lightBackgroundGetsDarkText() {
        val bg = Color(0xFFF3EAD7)
        assertTrue(!ThemePalette.isDark(bg))
        val s = ThemePalette.scheme(lightColorScheme(), false, Color(0xFFFFEB3B), bg)
        assertTrue(s.onBackground.luminance() < 0.1f)
        assertTrue(ThemePalette.contrast(s.primary, bg) >= 3f)
    }

    @Test
    fun argbRoundTrip() {
        assertEquals(0xFF7C4DFF, ThemePalette.argb(ThemePalette.color(0xFF7C4DFF)))
    }
}
