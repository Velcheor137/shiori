package io.github.velcheor137.shiori.data.auth

import io.github.velcheor137.shiori.core.network.ApiHostConfig
import io.github.velcheor137.shiori.core.network.LibSocialHttp
import io.github.velcheor137.shiori.core.security.SecretStore
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthRepositoryTest {
    private class MemoryStore : SecretStore {
        var bytes: ByteArray? = null
        override fun write(plain: ByteArray) { bytes = plain }
        override fun read() = bytes
        override fun clear() { bytes = null }
    }

    private val server = MockWebServer()
    private val store = MemoryStore()
    private val now = 1_700_000_000_000L

    @BeforeTest
    fun start() = server.start()

    @AfterTest
    fun stop() = server.close()

    private fun repo() = AuthRepository(store) { now }.also {
        it.http = LibSocialHttp(ApiHostConfig(hosts = listOf(server.url("/").toString().trimEnd('/'))), "Shiori/test", it)
    }

    /** Так WebView возвращает строку из localStorage: JSON-литерал строки. */
    private fun jsResult(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private val siteAuth = """{"token":{"token_type":"Bearer","expires_in":604800,"access_token":"AAA","refresh_token":"RRR","timestamp":$now},"auth":{"id":42,"username":"reader"},"timestamp":$now}"""

    @Test
    fun acceptsTokenFromSiteLocalStorage() {
        val r = repo()
        assertTrue(r.acceptSiteAuth(jsResult(siteAuth)))
        val state = assertIs<SessionState.Authorized>(r.state.value)
        assertEquals(42, state.userId)
        assertEquals("reader", state.username)
        assertEquals(now + 604_800_000, state.expiresAtMillis)
        assertEquals("Bearer AAA", r.authorization())
        // Сессия переживает перезапуск: новый репозиторий читает её из хранилища.
        assertEquals("Bearer AAA", AuthRepository(store) { now }.authorization())
    }

    @Test
    fun guestLocalStorageIsNotASession() {
        val r = repo()
        assertFalse(r.acceptSiteAuth(jsResult("""{"prevUrl":"/ru","timestamp":1}""")))
        assertFalse(r.acceptSiteAuth("null"))
        assertFalse(r.acceptSiteAuth(null))
        assertIs<SessionState.Guest>(r.state.value)
    }

    @Test
    fun meCheckReadsUser() = runTest {
        val r = repo()
        r.acceptSiteAuth(jsResult(siteAuth))
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body("""{"data":{"id":42,"username":"reader2"}}""").build())
        val me = r.checkMe()
        assertEquals(200, me.code)
        assertEquals("reader2", me.username)
        assertNull(me.failure)
    }

    @Test
    fun unauthorizedTriggersRefreshThenRetry() = runTest {
        val r = repo()
        r.acceptSiteAuth(jsResult(siteAuth))
        server.enqueue(MockResponse.Builder().code(401).addHeader("Content-Type", "application/json").body("{}").build())
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body("""{"token_type":"Bearer","expires_in":3600,"access_token":"BBB","refresh_token":"RRR2"}""").build())
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body("""{"data":{"id":42,"username":"reader"}}""").build())
        val me = r.checkMe()
        assertEquals(200, me.code)
        assertEquals("Bearer BBB", r.authorization())
        server.takeRequest()
        val refreshReq = server.takeRequest()
        assertEquals("POST", refreshReq.method)
        assertTrue(refreshReq.body!!.utf8().contains("\"grant_type\":\"refresh_token\""))
        assertNull(refreshReq.headers["Authorization"])
    }

    @Test
    fun failedRefreshRequiresRelogin() = runTest {
        val r = repo()
        r.acceptSiteAuth(jsResult(siteAuth))
        server.enqueue(MockResponse.Builder().code(401).addHeader("Content-Type", "application/json").body("{}").build())
        server.enqueue(MockResponse.Builder().code(400).addHeader("Content-Type", "application/json").body("{}").build())
        r.checkMe()
        assertIs<SessionState.ReloginRequired>(r.state.value)
        assertNull(store.bytes)
    }

    @Test
    fun logoutClearsSession() = runTest {
        val r = repo()
        r.acceptSiteAuth(jsResult(siteAuth))
        server.enqueue(MockResponse.Builder().code(200).build())
        r.logout()
        assertIs<SessionState.Guest>(r.state.value)
        assertNull(r.authorization())
        assertNull(store.bytes)
    }
}
