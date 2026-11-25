package io.github.velcheor137.shiori.core.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class RedactorTest {
    @Test
    fun masksTokensInJson() {
        val s = """{"token":{"access_token":"AAA.BBB","refresh_token":"RRR","expires_in":604800},"auth":{"id":1}}"""
        val r = Redactor.redact(s)
        assertFalse("AAA.BBB" in r)
        assertFalse("RRR" in r)
        assertEquals(true, "\"expires_in\":604800" in r)
    }

    @Test
    fun masksOauthParamsInUrls() {
        val r = Redactor.redact("https://ranobelib.me/ru/front/auth/oauth/callback?code=SECRET&state=XYZ&foo=1")
        assertEquals("https://ranobelib.me/ru/front/auth/oauth/callback?code=***&state=***&foo=1", r)
    }

    @Test
    fun masksHeaders() {
        assertEquals("Authorization: ***", Redactor.redact("Authorization: Bearer abc.def"))
        assertEquals("token Bearer ***", Redactor.redact("token Bearer abc.def"))
    }
}
