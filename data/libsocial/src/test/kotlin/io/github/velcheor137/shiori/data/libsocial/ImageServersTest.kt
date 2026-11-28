package io.github.velcheor137.shiori.data.libsocial

import kotlin.test.Test
import kotlin.test.assertEquals

class ImageServersTest {
    private val body = """
        {"data":{"imageServers":[
          {"id":"main","url":"https://img2.imglib.info","site_ids":[1,3]},
          {"id":"secondary","url":"https://img3.cdnrulib.link/","site_ids":[1,3]},
          {"id":"compress","url":"https://img3.cdnlibs.org","site_ids":[1,3]},
          {"id":"download","url":"https://img3.cdnlibs.org","site_ids":[1,3]},
          {"id":"crop","url":"https://crops.mangalib.me","site_ids":[1,3]},
          {"id":"compress","url":"https://other.example","site_ids":[5]}
        ]}}
    """.trimIndent()

    @Test
    fun ordersServersAndSkipsCrop() {
        assertEquals(listOf("https://img3.cdnlibs.org", "https://img3.cdnrulib.link", "https://img2.imglib.info"), ImageServers.parse(body, 1))
    }

    @Test
    fun fallbackOnGarbage() {
        assertEquals(ImageServers.FALLBACK, ImageServers.parse("<html>", 1))
    }

    @Test
    fun joinsWithSingleSlash() {
        assertEquals("https://img3.cdnlibs.org/manga/x/chapters/1/001.png", ImageServers.url("https://img3.cdnlibs.org/", "//manga/x/chapters/1/001.png"))
    }

    /** HentaiLib (site_id 4) — свои серверы, без «download» (из ответа `/api/constants`, ноябрь 2026). */
    @Test
    fun hentaiLibServers() {
        val hentai = """
            {"data":{"imageServers":[
              {"id":"main","url":"https://img2.imglib.info","site_ids":[1,3]},
              {"id":"compress","url":"https://img3.cdnlibs.org","site_ids":[1,3]},
              {"id":"main","url":"https://img2h.hentaicdn.org","site_ids":[4]},
              {"id":"secondary","url":"https://img3h.hentaicdn.org","site_ids":[4]},
              {"id":"compress","url":"https://img3h.hentaicdn.org","site_ids":[4]},
              {"id":"crop","url":"https://crops.hentailib.me","site_ids":[4]}
            ]}}
        """.trimIndent()
        assertEquals(listOf("https://img3h.hentaicdn.org", "https://img2h.hentaicdn.org"), ImageServers.parse(hentai, 4))
        assertEquals(ImageServers.FALLBACK_HENTAI, ImageServers.parse("<html>", 4))
    }
}
