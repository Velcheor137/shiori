package io.github.velcheor137.shiori

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MangaTilesTest {
    @Test
    fun normalPageIsOneTile() {
        val t = MangaTiles.split(0, 800, 1200)
        assertEquals(1, t.size)
        assertEquals(1200, t[0].bottom)
    }

    @Test
    fun longStripIsCutWithoutGaps() {
        val t = MangaTiles.split(3, 690, 17662)
        assertTrue(t.size > 1)
        assertEquals(0, t.first().top)
        assertEquals(17662, t.last().bottom)
        t.zipWithNext().forEach { (a, b) -> assertEquals(a.bottom, b.top) }
        assertTrue(t.all { it.bottom - it.top <= 3000 && it.page == 3 })
    }

    @Test
    fun unknownSizeGivesPlaceholder() {
        assertEquals(0, MangaTiles.split(0, 0, 0).single().width)
    }
}
