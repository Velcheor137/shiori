package io.github.velcheor137.shiori

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChapterCacheTest {
    @Test
    fun keepsMostRecentlyOpened() {
        val dir = Files.createTempDirectory("chapters").toFile()
        var limit = 3
        val cache = ChapterCache(dir) { limit }
        val t = System.currentTimeMillis() - 100_000
        (1..3).forEach { n ->
            cache.put("s", 1, "1", "$n", "raw$n")
            dir.listFiles()!!.maxBy { it.lastModified() }.setLastModified(t + n * 1000L)
        }
        // Глава 1 открыта последней — вытесняется самая старая из остальных (2).
        assertEquals("raw1", cache.get("s", 1, "1", "1"))
        cache.put("s", 1, "1", "4", "raw4")
        assertEquals(3, cache.count())
        assertNull(cache.get("s", 1, "1", "2"))
        assertEquals("raw3", cache.get("s", 1, "1", "3"))

        limit = 2
        cache.trim()
        assertEquals(2, cache.count())
        cache.clear()
        assertEquals(0, cache.count())
        dir.deleteRecursively()
    }
}
