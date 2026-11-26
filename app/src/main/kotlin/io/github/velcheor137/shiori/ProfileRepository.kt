package io.github.velcheor137.shiori

import io.github.velcheor137.shiori.core.common.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class ProfileUser(
    val id: Long,
    val username: String,
    val avatar: String? = null,
    val background: String? = null,
    val about: String? = null,
    val createdAt: String? = null,
    val level: Int? = null,
    val totalPoints: Long? = null,
    val levelPercent: Float? = null,
    val loginStreak: Int? = null,
)

@Serializable
data class ProfileFolder(val id: Long, val name: String, val count: Int)

@Serializable
data class ProfileGenre(val name: String, val percent: Float)

/** Профиль для экрана: сохраняется на диск и показывается сразу, сеть только обновляет. */
@Serializable
data class ProfileData(
    val userId: Long,
    val user: ProfileUser? = null,
    val folders: List<ProfileFolder> = emptyList(),
    val genres: List<ProfileGenre> = emptyList(),
    val error: String? = null,
    val updatedAt: Long = 0,
) {
    /** Совпадает ли содержимое (время обновления не считается). */
    fun sameAs(other: ProfileData?) = other != null && copy(updatedAt = 0) == other.copy(updatedAt = 0)
}

/**
 * Кэш профиля одного источника (п. 2): `profile_{source}.json`. Экран подписан на [data] и перерисовывается,
 * только когда содержимое действительно поменялось. Обновление из сети — не чаще раза в [MIN_AGE_MS].
 */
class ProfileRepository(private val dir: File, private val source: Source) {
    private val file = File(dir, "profile_${source.key}.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Mutex()
    private val _data = MutableStateFlow(read())
    val data: StateFlow<ProfileData?> = _data.asStateFlow()

    private fun read(): ProfileData? = runCatching { json.decodeFromString(ProfileData.serializer(), file.readText()) }.getOrNull()

    /** Публикует [new], только если оно отличается от текущего; на диск пишет всегда (время обновления). */
    fun publish(new: ProfileData) {
        if (!new.sameAs(_data.value)) _data.value = new
        runCatching { file.writeText(json.encodeToString(ProfileData.serializer(), new)) }
    }

    suspend fun refresh(app: ShioriApp, userId: Long, force: Boolean = false) = lock.withLock {
        val cur = _data.value
        if (!force && cur != null && cur.userId == userId && cur.error == null && System.currentTimeMillis() - cur.updatedAt < MIN_AGE_MS) return@withLock
        if (app.offline.value) return@withLock
        val api = app.apiFor(source)
        withContext(Dispatchers.IO) {
            val user = runCatching { api.user(userId) }.onFailure { AppLog.w("PROFILE", "Профиль $userId не загрузился", it) }
            val folders = runCatching { api.folders(userId) }.getOrNull()
            val genres = api.userTopGenres(userId).take(5)
            val old = cur?.takeIf { it.userId == userId }
            publish(
                ProfileData(
                    userId = userId,
                    user = user.getOrNull()?.let { u ->
                        ProfileUser(u.id, u.username, u.avatar, u.background, u.about, u.createdAt, u.level, u.totalPoints, u.levelPercent, u.loginStreak)
                    } ?: old?.user,
                    folders = folders?.map { ProfileFolder(it.id, it.name, it.count) } ?: old?.folders.orEmpty(),
                    genres = genres.map { ProfileGenre(it.first, it.second) }.ifEmpty { old?.genres.orEmpty() },
                    // Ошибку показываем, только если и в кэше ничего нет.
                    error = if (user.isFailure && old?.user == null) "Профиль не загрузился: ${user.exceptionOrNull()?.userMessage()}" else null,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    fun clear() {
        _data.value = null
        file.delete()
    }

    private companion object {
        const val MIN_AGE_MS = 10 * 60 * 1000L
    }
}
