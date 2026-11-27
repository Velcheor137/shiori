package io.github.velcheor137.shiori

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UpdaterTest {
    @Test
    fun versionCodeMatchesGradle() {
        assertEquals(50_064, AppVersion.code("0.5.0-rc.4"))
        assertEquals(50_065, AppVersion.code("v0.5.0-rc.5"))
        assertEquals(50_099, AppVersion.code("0.5.0"))
        assertEquals(1_020_331, AppVersion.code("1.2.3-beta.1"))
        assertNull(AppVersion.code("0.4.0-dev"))
        assertNull(AppVersion.code("0.5.0-rc.30"))
    }

    @Test
    fun parsesReleasesAndPicksNewest() {
        val json = """
            [
              {"tag_name":"v0.5.0-rc.5","draft":false,"prerelease":true,"body":"Что нового\r\n- жалобы\n---\nПроверка файла: sha256sum -c",
               "assets":[{"name":"shiori-0.5.0-rc.5.apk","size":23000000,"browser_download_url":"https://github.com/x/a.apk"},
                         {"name":"shiori-0.5.0-rc.5.apk.sha256","size":90,"browser_download_url":"https://github.com/x/a.apk.sha256"}]},
              {"tag_name":"v0.6.0-rc.1","draft":true,"assets":[{"name":"shiori-0.6.0-rc.1.apk","browser_download_url":"https://github.com/x/b.apk"}]},
              {"tag_name":"v0.5.0-rc.4","draft":false,"prerelease":true,"assets":[{"name":"shiori-0.5.0-rc.4.apk","size":1,"browser_download_url":"https://github.com/x/c.apk"}]},
              {"tag_name":"v0.5.1","draft":false,"prerelease":false,"assets":[]}
            ]
        """.trimIndent()
        val list = ReleaseParser.parse(json)
        assertEquals(listOf("v0.5.0-rc.5", "v0.5.0-rc.4"), list.map { it.tag })
        val r = list.first()
        assertEquals("0.5.0-rc.5", r.version)
        assertEquals("https://github.com/x/a.apk.sha256", r.shaUrl)
        assertEquals("Что нового\n- жалобы", r.notes)
        assertEquals(null, list[1].shaUrl)

        assertEquals("v0.5.0-rc.5", ReleaseParser.newest(list, 50_064)?.tag)
        assertNull(ReleaseParser.newest(list, 50_065))
    }

    @Test
    fun parsesSha256File() {
        val hex = "a".repeat(64)
        assertEquals(hex, ReleaseParser.parseSha("$hex  shiori-0.5.0-rc.5.apk\n"))
        assertEquals(hex, ReleaseParser.parseSha(hex.uppercase()))
        assertNull(ReleaseParser.parseSha("not a hash"))
    }
}
