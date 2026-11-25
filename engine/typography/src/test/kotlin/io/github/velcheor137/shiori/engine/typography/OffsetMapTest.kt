package io.github.velcheor137.shiori.engine.typography

import kotlin.test.Test
import kotlin.test.assertEquals

class OffsetMapTest {
    @Test
    fun mapsAroundReplacedQuotes() {
        val src = "«Иду», - сказал он."
        val r = ParagraphNormalizer.normalize(src, NormalizationSettings.DialoguesAndThoughts)
        assertEquals("— Иду, — сказал он.", r.text)
        // «И» в исходнике на позиции 1, в отображении — 2 (кавычка заменена на тире и пробел).
        assertEquals(2, r.offsets.toDisplay(1))
        assertEquals(1, r.offsets.toSource(2))
        // «с» из «сказал»: исходник 9, отображение 9 (разделитель «», - » заменён на «,⍽— »).
        assertEquals('с', src[9])
        assertEquals('с', r.text[r.offsets.toDisplay(9)])
    }

    @Test
    fun identityWhenNothingChanged() {
        val r = ParagraphNormalizer.normalize("Просто текст.", NormalizationSettings.DialoguesAndThoughts)
        assertEquals(true, r.offsets.isIdentity)
        assertEquals(5, r.offsets.toDisplay(5))
    }

    @Test
    fun newGuillemetsFromThoughtsAreNotTreatedAsDialogue() {
        val r = ParagraphNormalizer.normalize("'Беги', — подумал он.", NormalizationSettings.DialoguesAndThoughts)
        assertEquals("«Беги», — подумал он.", r.text)
        assertEquals(listOf("T"), r.rules)
    }
}
