package io.github.velcheor137.shiori.data.auth

import io.github.velcheor137.shiori.core.common.AppLog
import io.github.velcheor137.shiori.core.network.FailureKind
import io.github.velcheor137.shiori.core.network.LibSocialHttp
import io.github.velcheor137.shiori.core.network.OfflineException
import io.github.velcheor137.shiori.core.network.TokenProvider
import io.github.velcheor137.shiori.core.security.SecretStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Сессия аккаунта LibSocial. Хранится только в [SecretStore]. */
@Serializable
data class Session(
    val accessToken: String,
    val refreshToken: String?,
    val tokenType: String,
    val expiresAtMillis: Long,
    val userId: Long?,
    val username: String?,
) {
    val authorization: String get() = "$tokenType $accessToken"
}

sealed interface SessionState {
    data object Guest : SessionState
    data class Authorized(val userId: Long?, val username: String?, val expiresAtMillis: Long) : SessionState
    data object ReloginRequired : SessionState
}

/** Результат проверки `GET /api/auth/me`. Токен и личные данные сюда не попадают, кроме имени. */
data class MeCheck(val host: String, val code: Int?, val failure: FailureKind?, val username: String?, val userId: Long?)

/**
 * Вход в аккаунт по §10.0 плана: пользователь входит на странице сайта во WebView, сайт сохраняет
 * токен в `localStorage.auth`, приложение забирает его оттуда и проверяет через `/api/auth/me`.
 */
class AuthRepository(
    private val store: SecretStore,
    private val now: () -> Long = System::currentTimeMillis,
) : TokenProvider {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; isLenient = true }
    private val refreshMutex = Mutex()

    @Volatile
    private var session: Session? = store.read()?.let { runCatching { json.decodeFromString<Session>(it.decodeToString()) }.getOrNull() }

    init {
        AppLog.i(TAG, session?.let { "Сессия загружена: user=${it.username} id=${it.userId}, истекает ${java.util.Date(it.expiresAtMillis)}, refresh=${it.refreshToken != null}" }
            ?: "Сохранённой сессии нет (гость)")
    }

    private val _state = MutableStateFlow(session.toState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    lateinit var http: LibSocialHttp

    override fun authorization(): String? = session?.authorization

    /**
     * Принимает результат `evaluateJavascript("localStorage.getItem('auth')")`: это JSON-строка
     * со значением ключа или `null`. Возвращает true, если в нём был токен.
     */
    fun acceptSiteAuth(evaluateJsResult: String?): Boolean {
        val raw = evaluateJsResult?.let { runCatching { json.decodeFromString<String?>(it) }.getOrNull() }
        if (raw == null) {
            AppLog.i(TAG, "localStorage.auth: пусто (${evaluateJsResult?.take(20)})")
            return false
        }
        val keys = runCatching { (json.parseToJsonElement(raw) as? JsonObject)?.keys }.getOrNull()
        val site = runCatching { json.decodeFromString<SiteAuth>(raw) }.getOrNull()
        if (site == null) {
            AppLog.w(TAG, "localStorage.auth: не разобрал JSON, длина ${raw.length}")
            return false
        }
        val token = site.token
        val access = token?.accessToken?.takeIf { it.isNotBlank() }
        if (token == null || access == null) {
            AppLog.i(TAG, "localStorage.auth: токена нет, ключи=$keys")
            return false
        }
        AppLog.i(TAG, "localStorage.auth: токен получен, ключи=$keys, token_type=${token.tokenType}, expires_in=${token.expiresIn}, refresh=${token.refreshToken != null}, timestamp=${token.timestamp}, user=${site.auth?.username} id=${site.auth?.id}")
        val issuedAt = token.timestamp.takeIf { it > 0 } ?: now()
        save(
            Session(
                accessToken = access,
                refreshToken = token.refreshToken,
                tokenType = token.tokenType.ifBlank { "Bearer" },
                expiresAtMillis = issuedAt + token.expiresIn * 1000,
                userId = site.auth?.id,
                username = site.auth?.username,
            ),
        )
        return true
    }

    suspend fun checkMe(): MeCheck {
        if (session == null) return MeCheck(http.currentHost, null, FailureKind.Unauthorized, null, null)
        if (needsRefresh()) refresh()
        var result = try {
            http.get("/api/auth/me")
        } catch (e: OfflineException) {
            return MeCheck(e.host, null, FailureKind.Offline, null, null)
        }
        if (result.failure == FailureKind.Unauthorized && refresh()) {
            result = runCatching { http.get("/api/auth/me") }.getOrDefault(result)
        }
        if (!result.isSuccess) {
            AppLog.w(TAG, "/auth/me: не успешно, HTTP ${result.code}, ${result.failure}")
            return MeCheck(result.host, result.code, result.failure, null, null)
        }
        val user = parseUser(result.body)
        AppLog.i(TAG, "/auth/me: успешно, user=${user.second} id=${user.first}, домен ${result.host}")
        session?.let { s -> save(s.copy(userId = user.first ?: s.userId, username = user.second ?: s.username)) }
        return MeCheck(result.host, result.code, null, user.second, user.first)
    }

    /** `POST /api/auth/oauth/token` с `grant_type=refresh_token`, как делает сайт. */
    suspend fun refresh(): Boolean = refreshMutex.withLock {
        val current = session ?: return false
        AppLog.i(TAG, "Обновляю токен (refresh)")
        val refreshToken = current.refreshToken ?: run { AppLog.w(TAG, "refresh_token нет — нужен повторный вход"); return markRelogin() }
        val body = json.encodeToString(
            RefreshRequest.serializer(),
            RefreshRequest(grantType = "refresh_token", clientId = "1", refreshToken = refreshToken, scope = ""),
        )
        val result = runCatching { http.postJson("/api/auth/oauth/token", body, withToken = false) }.getOrNull()
            ?: run { AppLog.w(TAG, "refresh: нет сети, сессия сохранена"); return false }
        if (!result.isSuccess) { AppLog.w(TAG, "refresh: HTTP ${result.code} — нужен повторный вход"); return markRelogin() }
        val token = parseToken(result.body) ?: run { AppLog.w(TAG, "refresh: не разобрал ответ — нужен повторный вход"); return markRelogin() }
        AppLog.i(TAG, "refresh: успешно, expires_in=${token.expiresIn}")
        save(
            current.copy(
                accessToken = token.accessToken ?: return markRelogin(),
                refreshToken = token.refreshToken ?: current.refreshToken,
                tokenType = token.tokenType.ifBlank { current.tokenType },
                expiresAtMillis = now() + token.expiresIn * 1000,
            ),
        )
        true
    }

    /** Выход: `DELETE /api/auth/oauth/token` (без гарантии), затем удаление сессии с устройства. */
    suspend fun logout() {
        AppLog.i(TAG, "Выход из аккаунта")
        if (session != null) runCatching { http.delete("/api/auth/oauth/token") }.onFailure { AppLog.w(TAG, "DELETE token не удался", it) }
        session = null
        store.clear()
        _state.value = SessionState.Guest
    }

    private fun needsRefresh(): Boolean {
        val s = session ?: return false
        return s.refreshToken != null && s.expiresAtMillis - now() < REFRESH_AHEAD_MILLIS
    }

    private fun markRelogin(): Boolean {
        session = null
        store.clear()
        _state.value = SessionState.ReloginRequired
        return false
    }

    private fun save(s: Session) {
        store.write(json.encodeToString(Session.serializer(), s).encodeToByteArray())
        session = s
        _state.value = s.toState()
    }

    private fun parseToken(body: String): TokenJson? {
        val element = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return null
        val obj = (element["data"] as? JsonObject)?.takeIf { "access_token" in it } ?: element
        return runCatching { json.decodeFromJsonElement(TokenJson.serializer(), obj) }.getOrNull()
    }

    /** Ответ `/auth/me` бывает `{data:{...}}` или плоским объектом. */
    private fun parseUser(body: String): Pair<Long?, String?> {
        val element = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return null to null
        val obj = element["data"] as? JsonObject ?: element
        return obj.long("id") to obj.string("username")
    }

    private fun JsonObject.long(key: String) = this[key]?.let { runCatching { it.jsonPrimitive.longOrNull }.getOrNull() }
    private fun JsonObject.string(key: String) = this[key]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }

    private fun Session?.toState(): SessionState =
        if (this == null) SessionState.Guest else SessionState.Authorized(userId, username, expiresAtMillis)

    @Serializable
    private data class SiteAuth(val token: TokenJson? = null, val auth: SiteUser? = null)

    @Serializable
    private data class SiteUser(val id: Long? = null, val username: String? = null)

    @Serializable
    private data class TokenJson(
        @SerialName("token_type") val tokenType: String = "Bearer",
        @SerialName("expires_in") val expiresIn: Long = 0,
        @SerialName("access_token") val accessToken: String? = null,
        @SerialName("refresh_token") val refreshToken: String? = null,
        val timestamp: Long = 0,
    )

    @Serializable
    private data class RefreshRequest(
        @SerialName("grant_type") val grantType: String,
        @SerialName("client_id") val clientId: String,
        @SerialName("refresh_token") val refreshToken: String,
        val scope: String,
    )

    private companion object {
        const val TAG = "AUTH"
        const val REFRESH_AHEAD_MILLIS = 60 * 60 * 1000L
    }
}
