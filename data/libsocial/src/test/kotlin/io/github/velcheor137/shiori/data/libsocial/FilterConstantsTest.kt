package io.github.velcheor137.shiori.data.libsocial

import kotlin.test.Test
import kotlin.test.assertEquals

class FilterConstantsTest {
    private val body = """
        {"data":{
          "genres":[{"id":34,"name":"Боевик","adult":false,"site_ids":[1,2,3]},{"id":32,"name":"Арт","adult":false,"site_ids":[1,2]},
                    {"id":90,"name":"Эротика","adult":true,"site_ids":[3]}],
          "tags":[{"id":328,"name":"Магия","alt_name":["Magic"],"site_ids":[3,4]}],
          "types":[{"id":12,"label":"Китай","site_ids":[3]},{"id":1,"label":"Манга","site_ids":[1]}],
          "scanlateStatus":[{"id":2,"label":"Завершён","site_ids":{"0":0,"3":3}},{"id":9,"label":"Чужой","site_ids":{"0":1}}],
          "ageRestriction":[{"id":4,"label":"18+","site_ids":[1,3]}]
        }}
    """.trimIndent()

    @Test
    fun filtersBySiteAndSortsGenres() {
        val c = FilterConstants.parse(body, 3)
        assertEquals(listOf("Боевик", "Эротика"), c.genres.map { it.name })
        assertEquals(true, c.genres.last().adult)
        assertEquals(listOf("Magic"), c.tags.single().altNames)
        assertEquals(listOf(12L), c.types.map { it.id })
        assertEquals(listOf(2L), c.scanlate.map { it.id })
        assertEquals("18+", c.ages.single().name)
        assertEquals(emptyList(), c.formats)
    }

    @Test
    fun brokenBodyGivesEmptyLists() {
        assertEquals(emptyList(), FilterConstants.parse("{}").genres)
    }
}
