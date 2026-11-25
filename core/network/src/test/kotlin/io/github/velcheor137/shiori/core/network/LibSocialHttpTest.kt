package io.github.velcheor137.shiori.core.network

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibSocialHttpTest {
    private val server = MockWebServer()

    @BeforeTest
    fun start() = server.start()

    @AfterTest
    fun stop() = server.close()

    private fun http(token: String? = "Bearer secret") = LibSocialHttp(
        ApiHostConfig(hosts = listOf(server.url("/").toString().trimEnd('/'))),
        userAgent = "Shiori/test (Android 16; +https://github.com/Velcheor137/shiori)",
        tokenProvider = { token },
    )

    @Test
    fun sendsHonestHeadersAndNoTokenToNonAllowlistedHost() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body("{}").build())
        val result = http().get("/api/auth/me")
        val req = server.takeRequest()
        assertEquals(200, result.code)
        assertEquals("application/json", req.headers["Accept"])
        assertEquals("3", req.headers["Site-Id"])
        assertEquals("https://ranobelib.me/", req.headers["Referer"])
        assertTrue(req.headers["User-Agent"]!!.startsWith("Shiori/"))
        // Тестовый сервер — localhost, его нет в allowlist: токен не уходит.
        assertNull(req.headers["Authorization"])
    }

    @Test
    fun classifiesEdgeAndApiErrors() {
        fun r(code: Int, json: Boolean) = HttpResult("h", code, "", json, null)
        assertEquals(FailureKind.EdgeBlocked, r(403, json = false).failure)
        assertEquals(FailureKind.Unauthorized, r(403, json = true).failure)
        assertEquals(FailureKind.Unauthorized, r(401, json = true).failure)
        assertEquals(FailureKind.RateLimited, r(429, json = true).failure)
        assertNull(r(200, json = true).failure)
    }

    @Test
    fun tokenOnlyForAllowlistedHttpsHosts() {
        assertTrue(HostPolicy.mayReceiveToken("api.mangalib.me", "https"))
        assertTrue(!HostPolicy.mayReceiveToken("api.mangalib.me", "http"))
        assertTrue(!HostPolicy.mayReceiveToken("cover.imglib.info", "https"))
        assertTrue(!HostPolicy.mayReceiveToken("evil.example", "https"))
    }
}
