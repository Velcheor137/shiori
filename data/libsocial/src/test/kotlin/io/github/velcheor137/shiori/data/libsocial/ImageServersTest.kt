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
}
