package io.github.velcheor137.shiori

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class CatalogFilterTest {
    @Test
    fun emptyFilterAddsNothing() {
        assertEquals("", CatalogFilter().toQuery())
        assertEquals(0, CatalogFilter().activeCount)
    }

    @Test
    fun buildsSiteParams() {
        val f = CatalogFilter(
            genres = setOf(34, 2), genresExclude = setOf(32), tags = setOf(328), types = setOf(12), ages = setOf(4),
            chapMin = 100, yearMax = 2021, longNoTranslation = true, licensed = 0, bookmarks = setOf(1),
        )
        assertEquals(
            "&genres[]=2&genres[]=34&genres_exclude[]=32&tags[]=328&types[]=12&caution[]=4&chap_count_min=100" +
                "&year_max=2021&long_no_translation=1&licensed=0&bookmarks[]=1",
            f.toQuery(),
        )
        assertEquals(9, f.activeCount)
    }

    @Test
    fun prefsSurviveJsonRoundTrip() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val p = CatalogPrefs(sort = "Rating", grid = true, filter = CatalogFilter(tags = setOf(5), rateMin = 10))
        assertEquals(p, json.decodeFromString(CatalogPrefs.serializer(), json.encodeToString(CatalogPrefs.serializer(), p)))
        assertEquals(CatalogPrefs(), json.decodeFromString(CatalogPrefs.serializer(), """{"unknown":1}"""))
    }
}
