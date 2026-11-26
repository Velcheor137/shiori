package io.github.velcheor137.shiori

import io.github.velcheor137.shiori.data.libsocial.TitleCard
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OfflineDataTest {
    @Test
    fun savesAndReadsTitleForOffline() {
        val root = Files.createTempDirectory("downloads").toFile()
        val dir = File(root, "42")
        assertFalse(OfflineData.hasTitle(dir))
        val card = TitleCard(42, "42--book", "Книга", null, "https://c/x.jpg", "Китай", null, "9.1")
        OfflineData.save(dir, card, """{"data":[]}""")
        assertTrue(OfflineData.hasTitle(dir))
        assertEquals(card, OfflineData.card(dir))
        // В списке скачанных — только тайтлы с главами.
        assertEquals(emptyList(), OfflineData.downloadedTitles(root))
        File(dir, "1001.json").writeText("{}")
        assertEquals(listOf(card), OfflineData.downloadedTitles(root))
        root.deleteRecursively()
    }

    @Test
    fun titleIdFromSlug() {
        assertEquals(26690L, OfflineData.titleId("26690--omniscient"))
        assertNull(OfflineData.titleId("abc"))
    }
}
