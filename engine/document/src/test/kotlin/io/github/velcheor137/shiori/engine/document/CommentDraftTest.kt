package io.github.velcheor137.shiori.engine.document

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CommentDraftTest {
    @Test
    fun typingAtRangeEndExtendsStyleAndShiftsLater() {
        val d = CommentDraft("ab cd", listOf(DraftRange(DraftStyle.Bold, 0, 2), DraftRange(DraftStyle.Italic, 3, 5)))
        val e = d.edit("abX cd")
        assertEquals(listOf(DraftRange(DraftStyle.Bold, 0, 3), DraftRange(DraftStyle.Italic, 4, 6)), e.ranges)
        // Удаление внутри диапазона сжимает его; удаление всего — убирает.
        assertEquals(listOf(DraftRange(DraftStyle.Bold, 0, 2), DraftRange(DraftStyle.Italic, 3, 5)), e.edit("ab cd").ranges)
        assertEquals(listOf(DraftRange(DraftStyle.Italic, 1, 3)), d.edit(" cd").ranges)
    }

    @Test
    fun pendingStyleAppliesToTypedText() {
        val d = CommentDraft("ab").edit("abcd", mapOf(DraftStyle.Spoiler to true))
        assertEquals(listOf(DraftRange(DraftStyle.Spoiler, 2, 4)), d.ranges)
        val off = d.edit("abcdef", mapOf(DraftStyle.Spoiler to false))
        assertEquals(listOf(DraftRange(DraftStyle.Spoiler, 2, 4)), off.ranges)
    }

    @Test
    fun toggleAddsMergesAndRemoves() {
        val d = CommentDraft("hello world").toggle(DraftStyle.Bold, 0, 5).toggle(DraftStyle.Bold, 3, 8)
        assertEquals(listOf(DraftRange(DraftStyle.Bold, 0, 8)), d.ranges)
        assertTrue(d.isActive(DraftStyle.Bold, 2, 6))
        val cut = d.toggle(DraftStyle.Bold, 2, 4)
        assertEquals(listOf(DraftRange(DraftStyle.Bold, 0, 2), DraftRange(DraftStyle.Bold, 4, 8)), cut.ranges)
        assertFalse(cut.isActive(DraftStyle.Bold, 2, 4))
    }

    @Test
    fun quoteCoversWholeLines() {
        val d = CommentDraft("one\ntwo\nthree").toggle(DraftStyle.Quote, 5, 5 + 1)
        assertEquals(listOf(DraftRange(DraftStyle.Quote, 4, 7)), d.ranges)
        assertTrue(d.isActive(DraftStyle.Quote, 6, 6))
        assertFalse(d.isActive(DraftStyle.Quote, 1, 1))
    }

    @Test
    fun docMatchesSiteEditorSchema() {
        val d = CommentDraft(
            "Привет мир\nцитата\nтайна",
            listOf(
                DraftRange(DraftStyle.Bold, 0, 6),
                DraftRange(DraftStyle.Spoiler, 7, 10),
                DraftRange(DraftStyle.Quote, 11, 17),
                DraftRange(DraftStyle.SpoilerBlock, 18, 23, "кто убийца"),
            ),
        )
        val expected = """{"type":"doc","content":[""" +
            """{"type":"paragraph","content":[{"type":"text","text":"Привет","marks":[{"type":"bold"}]},{"type":"text","text":" "},""" +
            """{"type":"spoilerInline","attrs":{"visibleText":"спойлер"},"content":[{"type":"text","text":"мир"}]}]},""" +
            """{"type":"blockquote","content":[{"type":"paragraph","content":[{"type":"text","text":"цитата"}]}]},""" +
            """{"type":"spoilerBlock","attrs":{"visibleText":"кто убийца"},"content":[{"type":"paragraph","content":[{"type":"text","text":"тайна"}]}]}]}"""
        assertEquals(expected, d.toDoc().toString())
    }

    @Test
    fun roundTripThroughParser() {
        val d = CommentDraft(
            "a b\nq",
            listOf(DraftRange(DraftStyle.Italic, 0, 1), DraftRange(DraftStyle.Spoiler, 2, 3), DraftRange(DraftStyle.Quote, 4, 5)),
        )
        val back = CommentDraft.fromBlocks(DocumentParser.parse(d.toDoc(), emptyList(), rich = true))
        assertEquals(d, back)
    }
}
