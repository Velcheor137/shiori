package io.github.velcheor137.shiori

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight

data class ReaderColors(val background: Color, val text: Color, val secondary: Color, val panel: Color)

fun ReaderSettings.colors(): ReaderColors = when (theme) {
    ReaderTheme.Site -> ReaderColors(Color(0xFF2F3136), Color(0xFFDCDDDE), Color(0xFF9A9CA1), Color(0xFF3A3C42))
    ReaderTheme.Light -> ReaderColors(Color(0xFFFAF8F3), Color(0xFF1F1D1A), Color(0xFF6F6A61), Color(0xFFEFEBE3))
    ReaderTheme.Sepia -> ReaderColors(Color(0xFFF3E9D6), Color(0xFF3B2F22), Color(0xFF6B5A45), Color(0xFFE7DAC1))
    ReaderTheme.Gray -> ReaderColors(Color(0xFFE6E6E6), Color(0xFF222222), Color(0xFF5E5E5E), Color(0xFFD6D6D6))
    ReaderTheme.Dark -> ReaderColors(Color(0xFF151412), Color(0xFFE8E5DE), Color(0xFF968F84), Color(0xFF24221F))
    ReaderTheme.Amoled -> ReaderColors(Color(0xFF000000), Color(0xFFD9D5CC), Color(0xFF8C867B), Color(0xFF141414))
    ReaderTheme.Custom -> {
        val bg = Color(customBackground)
        val fg = Color(customText)
        ReaderColors(bg, fg, fg.copy(alpha = 0.6f), lerp(bg, fg, 0.08f))
    }
}

private fun lerp(a: Color, b: Color, t: Float) = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = 1f,
)

/** Семейство шрифта читалки с учётом выбранной насыщенности (вариативные шрифты поддерживают её плавно). */
@OptIn(ExperimentalTextApi::class)
fun ReaderSettings.fontFamily(): FontFamily {
    val w = fontWeight.coerceIn(300, 800)
    val bold = (w + 300).coerceAtMost(900)
    fun variable(res: Int, italicRes: Int? = null): FontFamily = FontFamily(
        listOfNotNull(
            Font(res, FontWeight(w), FontStyle.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(w))),
            Font(res, FontWeight(bold), FontStyle.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(bold))),
            italicRes?.let { Font(it, FontWeight(w), FontStyle.Italic, variationSettings = FontVariation.Settings(FontVariation.weight(w))) },
            italicRes?.let { Font(it, FontWeight(bold), FontStyle.Italic, variationSettings = FontVariation.Settings(FontVariation.weight(bold))) },
        ),
    )
    return when (font) {
        ReaderFont.Merriweather -> variable(R.font.merriweather)
        ReaderFont.Literata -> variable(R.font.literata, R.font.literata_italic)
        ReaderFont.Lora -> variable(R.font.lora)
        ReaderFont.OpenSans -> variable(R.font.opensans)
        ReaderFont.PtSerif -> FontFamily(
            Font(R.font.ptserif_regular, FontWeight.Normal),
            Font(R.font.ptserif_bold, FontWeight.Bold),
            Font(R.font.ptserif_italic, FontWeight.Normal, FontStyle.Italic),
        )
        ReaderFont.PtSans -> FontFamily(Font(R.font.ptsans_regular, FontWeight.Normal), Font(R.font.ptsans_bold, FontWeight.Bold))
        ReaderFont.SystemSerif -> FontFamily.Serif
        ReaderFont.SystemSans -> FontFamily.SansSerif
    }
}

/** Стартовые профили доступности (§10.6): удобные пресеты, не медицинские рекомендации. */
enum class ReaderPreset(val label: String, val apply: (ReaderSettings) -> ReaderSettings) {
    LowVision("Слабое зрение", { it.copy(fontSize = 26f, fontWeight = 500, lineHeight = 1.8f, theme = ReaderTheme.Light, outlineWidth = 0f) }),
    Astigmatism("Астигматизм", { it.copy(theme = ReaderTheme.Dark, letterSpacing = 0.02f, fontWeight = 400, outlineWidth = 0f) }),
    Dyslexia("Дислексия", { it.copy(font = ReaderFont.OpenSans, align = TextAlignMode.Start, lineHeight = 1.8f, letterSpacing = 0.05f, paragraphSpacing = 1.0f, hyphens = false) }),
    LightSensitive("Светочувствительность", { it.copy(theme = ReaderTheme.Sepia, brightness = 0.3f) }),
    Focus("Концентрация", { it.copy(highlightParagraph = true, showProgress = false) }),
}
