package io.github.velcheor137.shiori

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import io.github.velcheor137.shiori.core.common.AppLog
import io.github.velcheor137.shiori.data.libsocial.ChapterEntry
import io.github.velcheor137.shiori.data.libsocial.LibSocialApi
import io.github.velcheor137.shiori.data.libsocial.TitleCard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File

/**
 * Офлайн-режим: включается сам без сети или вручную в «Настройках».
 * [offline] — итоговое состояние, по нему экраны показывают скачанное вместо сетевого.
 */
class OfflineState(context: Context, private val ui: UiPrefs, scope: CoroutineScope) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val online = MutableStateFlow(isOnlineNow())
    val manual = MutableStateFlow(ui.bool(UiPrefs.OFFLINE_MANUAL, false))
    val offline: StateFlow<Boolean> = combine(online, manual) { o, m -> m || !o }
        .stateIn(scope, SharingStarted.Eagerly, manual.value || !online.value)

    init {
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val ok = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    if (online.value != ok) AppLog.i("NET", if (ok) "Сеть появилась" else "Сеть без интернета")
                    online.value = ok
                }

                override fun onLost(network: Network) {
                    AppLog.i("NET", "Сеть пропала")
                    online.value = false
                }
            })
        }.onFailure { AppLog.w("NET", "Не удалось следить за сетью", it) }
    }

    private fun isOnlineNow(): Boolean = runCatching {
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }.getOrDefault(true)

    fun setManual(v: Boolean) {
        ui.setBool(UiPrefs.OFFLINE_MANUAL, v)
        manual.value = v
        AppLog.i("NET", "Офлайн-режим вручную: $v")
    }
}

/**
 * Данные для чтения без сети: при скачивании рядом с главами сохраняются карточка тайтла (`title.json`)
 * и список глав (`chapters.json`, ответ API как есть).
 */
object OfflineData {
    private val json = Json { ignoreUnknownKeys = true }

    fun titleId(slug: String): Long? = slug.substringBefore("--").toLongOrNull()

    private fun cardFile(dir: File) = File(dir, "title.json")
    private fun chaptersFile(dir: File) = File(dir, "chapters.json")

    fun save(dir: File, card: TitleCard, chaptersRaw: String) {
        dir.mkdirs()
        val o = buildJsonObject {
            put("id", card.id); put("slug", card.slug); put("name", card.name)
            card.altName?.let { put("altName", it) }; card.cover?.let { put("cover", it) }
            card.type?.let { put("type", it) }; card.status?.let { put("status", it) }; card.rating?.let { put("rating", it) }
        }
        DownloadFiles.writeAtomic(cardFile(dir), o.toString().toByteArray())
        DownloadFiles.writeAtomic(chaptersFile(dir), chaptersRaw.toByteArray())
    }

    fun hasTitle(dir: File) = cardFile(dir).exists() && chaptersFile(dir).exists()

    fun card(dir: File): TitleCard? = runCatching {
        val o = json.parseToJsonElement(cardFile(dir).readText()) as JsonObject
        fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
        TitleCard(
            id = (o["id"] as JsonPrimitive).longOrNull ?: return null,
            slug = s("slug") ?: return null,
            name = s("name").orEmpty(),
            altName = s("altName"), cover = s("cover"), type = s("type"), status = s("status"), rating = s("rating"),
        )
    }.getOrNull()

    fun chapters(dir: File, api: LibSocialApi): List<ChapterEntry>? =
        runCatching { api.parseChapters(chaptersFile(dir).readText()) }.getOrNull()

    /** Тайтлы, у которых есть хотя бы одна скачанная глава и сохранённая карточка. */
    fun downloadedTitles(root: File): List<TitleCard> =
        root.listFiles()?.filter { d -> d.isDirectory && d.listFiles()?.any { it.name.endsWith(".json") && it.name.first().isDigit() } == true }
            ?.mapNotNull { card(it) }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()
}
