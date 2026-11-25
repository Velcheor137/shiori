package io.github.velcheor137.shiori.engine.typography

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Property-тесты движка (§9.7 плана) на случайных абзацах с фиксированным seed. */
class NormalizerPropertyTest {
    private val alphabet = ("абвгдеёжзийклмнопрстуфхцчшщъыьэюяАБВГДЕЖЗИКЛМНОПРСТЯ" +
        "abcxyzABC0123456789      .,!?…:;-–—«»„“”\"'‘’() ").toList()
    private val settingsVariants = listOf(
        NormalizationSettings.DialoguesAndThoughts,
        NormalizationSettings(dialogues = true),
        NormalizationSettings(thoughts = true),
        NormalizationSettings(dialogues = true, thoughts = true, straightQuotesAsSpeech = true, nbspAroundDialogueDash = false),
        NormalizationSettings(dialogues = true, thoughts = true, mode = NormalizationMode.Extended),
    )

    private fun randomParagraph(rnd: Random): String {
        val len = rnd.nextInt(0, 120)
        return buildString(len) { repeat(len) { append(alphabet[rnd.nextInt(alphabet.size)]) } }
    }

    /** Абзацы, похожие на реальные: реплики и мысли из фрагментов, чтобы правила срабатывали часто. */
    private fun dialogueLikeParagraph(rnd: Random): String {
        val words = listOf("Я", "не", "знаю", "сказал", "он", "она", "куда", "ты", "идёшь", "мысль", "вот", "и", "всё")
        fun phrase() = (1..rnd.nextInt(1, 5)).joinToString(" ") { words[rnd.nextInt(words.size)] }
        val punct = listOf("", ".", "?", "!", "…", ",", "...")
        val dashes = listOf("-", "–", "—")
        val parts = listOf(
            { "«${phrase()}${punct.random(rnd)}»" },
            { "'${phrase()}${punct.random(rnd)}'" },
            { ", ${dashes.random(rnd)} ${phrase()}" },
            { " ${dashes.random(rnd)} ${phrase()}" },
            { "${punct.random(rnd)} " },
            { " ${phrase()}" },
        )
        return (1..rnd.nextInt(1, 6)).joinToString("") { parts.random(rnd)() }
    }

    private fun forEachCase(block: (String, NormalizationSettings) -> Unit) {
        val rnd = Random(20261125)
        repeat(10_000) { i ->
            val text = if (i % 2 == 0) randomParagraph(rnd) else dialogueLikeParagraph(rnd)
            block(text, settingsVariants[i % settingsVariants.size])
        }
    }

    @Test
    fun lettersAndDigitsNeverChange() = forEachCase { text, s ->
        val r = ParagraphNormalizer.normalize(text, s)
        assertEquals(Chars.lettersAndDigits(text), Chars.lettersAndDigits(r.text), "Вход: $text")
    }

    @Test
    fun invariantIsNeverViolatedByRules() = forEachCase { text, s ->
        assertFalse(ParagraphNormalizer.normalize(text, s).fallback, "Правила нарушили инвариант на: $text")
    }

    @Test
    fun deterministic() = forEachCase { text, s ->
        val a = ParagraphNormalizer.normalize(text, s)
        val b = ParagraphNormalizer.normalize(text, s)
        assertEquals(a.text, b.text)
        assertEquals(a.rules, b.rules)
        for (i in 0..text.length) assertEquals(a.offsets.toDisplay(i), b.offsets.toDisplay(i))
    }

    @Test
    fun disabledSettingsAreIdentity() = forEachCase { text, _ ->
        val r = ParagraphNormalizer.normalize(text, NormalizationSettings.Off)
        assertEquals(text, r.text)
        assertTrue(r.offsets.isIdentity)
    }

    @Test
    fun offsetMapIsMonotonicAndInBounds() = forEachCase { text, s ->
        val r = ParagraphNormalizer.normalize(text, s)
        var prev = -1
        for (i in 0..text.length) {
            val d = r.offsets.toDisplay(i)
            assertTrue(d in 0..r.text.length, "toDisplay($i)=$d вне границ для: $text")
            assertTrue(d >= prev, "toDisplay не монотонна в $i для: $text")
            prev = d
        }
        var prevSrc = -1
        for (j in 0..r.text.length) {
            val src = r.offsets.toSource(j)
            assertTrue(src in 0..text.length, "toSource($j)=$src вне границ для: $text")
            assertTrue(src >= prevSrc, "toSource не монотонна в $j для: $text")
            prevSrc = src
        }
    }

    @Test
    fun letterPositionsRoundTrip() = forEachCase { text, s ->
        val r = ParagraphNormalizer.normalize(text, s)
        for (i in text.indices) {
            if (!text[i].isLetterOrDigit()) continue
            val d = r.offsets.toDisplay(i)
            assertEquals(text[i], r.text[d], "Буква $i сместилась неверно в: $text")
            assertEquals(i, r.offsets.toSource(d))
        }
    }
}
