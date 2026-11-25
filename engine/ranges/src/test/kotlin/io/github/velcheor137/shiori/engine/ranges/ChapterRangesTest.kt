package io.github.velcheor137.shiori.engine.ranges

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChapterRangesTest {
    private val entries = listOf(
        ChapterRanges.Entry("1/1", "1", "1"), ChapterRanges.Entry("1/2", "1", "2"), ChapterRanges.Entry("1/2.5", "1", "2.5"),
        ChapterRanges.Entry("2/1", "2", "1"), ChapterRanges.Entry("2/3", "2", "3"), ChapterRanges.Entry("2/120", "2", "120"),
        ChapterRanges.Entry("2/155", "2", "155"), ChapterRanges.Entry("3/160", "3", "160"), ChapterRanges.Entry("3/170", "3", "170"),
    )

    private fun pick(s: String) = ChapterRanges.resolve(ChapterRanges.parse(s), entries)

    @Test
    fun mixedRangesAgainstRealCatalog() {
        val r = pick("120-150, 155, 160-170")
        assertEquals(listOf("2/120", "2/155", "3/160", "3/170"), r.selected)
        assertTrue(r.missing.isEmpty())
    }

    @Test
    fun fractionalVolumesAndAll() {
        assertEquals(listOf("1/2", "1/2.5"), pick("2-2.5").selected.filter { it.startsWith("1/") })
        assertEquals(listOf("2/1", "2/3", "2/120", "2/155"), pick("т2").selected)
        assertEquals(listOf("2/3"), pick("т2:2-3").selected)
        assertEquals(entries.map { it.key }, pick("все").selected)
    }

    @Test
    fun missingAndAmbiguousAndErrors() {
        val r = pick("1, 999")
        assertEquals(listOf("1/1", "2/1"), r.selected)
        assertEquals(listOf("1"), r.ambiguous)
        assertEquals(listOf("999"), r.missing)
        assertEquals(1, ChapterRanges.parse("1-5, абв").errors.size)
    }
}
