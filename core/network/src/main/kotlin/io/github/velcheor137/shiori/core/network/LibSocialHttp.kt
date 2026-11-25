package io.github.velcheor137.shiori.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** Домены API LibSocial (§6.2 плана). Порядок: основной, затем запасные. */
data class ApiHostConfig(
    val hosts: List<String> = listOf(
        "https://api.mangalib.me",
        "https://api.cdnlibs.org",
        "https://api.cdnrulib.link",
        "https://api2.mangalib.me",
    ),
    val siteOrigin: String = "https://ranobelib.me",
    val siteId: Int = 3,
)

/** Только на эти хосты может уйти заголовок Authorization. */
object HostPolicy {
    private val apiHosts = setOf(
        "api.mangalib.me",
        "api.cdnlibs.org",
        "api.cdnrulib.link",
        "api2.mangalib.me",
        "api.lib.social",
    )

    fun mayReceiveToken(host: String, scheme: String): Boolean = scheme == "https" && host in apiHosts
}

fun interface TokenProvider {
    /** Значение заголовка Authorization (например «Bearer …») или null для гостя. */
    fun authorization(): String?
}

enum class FailureKind { Offline, EdgeBlocked, Unauthorized, NotFound, RateLimited, Server, Client }

data class HttpResult(
    val host: String,
    val code: Int,
    val body: String,
    val isJson: Boolean,
    val retryAfterSeconds: Long?,
) {
    val isSuccess: Boolean get() = code in 200..299

    val failure: FailureKind?
        get() = when {
            isSuccess -> null
            code == 403 && !isJson -> FailureKind.EdgeBlocked
            code == 401 || code == 403 || code == 419 -> FailureKind.Unauthorized
            code == 404 -> FailureKind.NotFound
            code == 429 -> FailureKind.RateLimited
            code >= 500 -> FailureKind.Server
            else -> FailureKind.Client
        }
}

class OfflineException(val host: String, cause: Throwable) : IOException("Нет связи с $host", cause)

/**
 * HTTP-клиент для API LibSocial. Честный User-Agent (подмена на браузерный запрещена, DEC-008),
 * `Referer` сайта, `Site-Id`, `Accept: application/json`. Переключение на запасной домен — только
 * при сетевой ошибке или HTML-403 гейта, не при JSON-ответах API.
 */
class LibSocialHttp(
    private val config: ApiHostConfig,
    userAgent: String,
    private val tokenProvider: TokenProvider,
    baseClient: OkHttpClient = OkHttpClient(),
) {
    @Volatile
    private var preferredHost: String = config.hosts.first()

    private val client: OkHttpClient = baseClient.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(HeaderInterceptor(config, userAgent, tokenProvider))
        .build()

    val currentHost: String get() = preferredHost

    suspend fun get(path: String, withToken: Boolean = true): HttpResult = execute("GET", path, null, withToken)

    suspend fun postJson(path: String, json: String, withToken: Boolean = true): HttpResult =
        execute("POST", path, json.toRequestBody(JSON), withToken)

    suspend fun delete(path: String, withToken: Boolean = true): HttpResult = execute("DELETE", path, null, withToken)

    private suspend fun execute(method: String, path: String, body: RequestBody?, withToken: Boolean): HttpResult =
        withContext(Dispatchers.IO) {
            val order = listOf(preferredHost) + config.hosts.filter { it != preferredHost }
            var lastError: IOException? = null
            for (host in order) {
                val request = Request.Builder()
                    .url(host + path)
                    .method(method, body)
                    .tag(TokenUse::class.java, TokenUse(withToken))
                    .build()
                val result = try {
                    client.newCall(request).execute().use { it.toResult(host) }
                } catch (e: IOException) {
                    lastError = e
                    continue
                }
                // Гейт не пустил — пробуем другой домен; ответы самого API (JSON) не переключают домен.
                if (result.failure == FailureKind.EdgeBlocked && method == "GET") continue
                preferredHost = host
                return@withContext result
            }
            throw OfflineException(order.last(), lastError ?: IOException("Все домены отклонили запрос"))
        }

    private fun Response.toResult(host: String) = HttpResult(
        host = host,
        code = code,
        body = body.string(),
        isJson = header("Content-Type")?.contains("json", ignoreCase = true) == true,
        retryAfterSeconds = header("Retry-After")?.trim()?.toLongOrNull(),
    )

    private class TokenUse(val enabled: Boolean)

    private class HeaderInterceptor(
        private val config: ApiHostConfig,
        private val userAgent: String,
        private val tokenProvider: TokenProvider,
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val original = chain.request()
            val builder = original.newBuilder()
                .header("Accept", "application/json")
                .header("Site-Id", config.siteId.toString())
                .header("Referer", config.siteOrigin + "/")
                .header("User-Agent", userAgent)
                .header("client-time-zone", TimeZone.getDefault().id)
            val useToken = original.tag(TokenUse::class.java)?.enabled ?: true
            val url = original.url
            if (useToken && HostPolicy.mayReceiveToken(url.host, url.scheme)) {
                tokenProvider.authorization()?.let { builder.header("Authorization", it) }
            } else {
                builder.removeHeader("Authorization")
            }
            return chain.proceed(builder.build())
        }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /** «Shiori/0.0.1 (Android 16; +https://github.com/Velcheor137/shiori)». */
        fun userAgent(versionName: String, androidRelease: String) =
            "Shiori/$versionName (Android $androidRelease; +https://github.com/Velcheor137/shiori)"
    }
}
