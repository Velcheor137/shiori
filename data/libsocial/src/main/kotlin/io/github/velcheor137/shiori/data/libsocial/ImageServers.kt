package io.github.velcheor137.shiori.data.libsocial

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Серверы картинок манги. Порядок: `compress` → `download` → `secondary` → `main`
 * (main за защитой DDoS отвечает 403 приложениям, поэтому последним); `crop` не используется.
 */
object ImageServers {
    private val ORDER = listOf("compress", "download", "secondary", "main")

    /** Запасной список, если справочник не загрузился. */
    val FALLBACK = listOf("https://img3.cdnlibs.org", "https://img3.cdnrulib.link", "https://img2.imglib.info")
    /** HentaiLib (site_id 4): свои серверы, из `/api/constants` (ноябрь 2026). */
    val FALLBACK_HENTAI = listOf("https://img3h.hentaicdn.org", "https://img2h.hentaicdn.org")

    fun fallback(siteId: Int): List<String> = if (siteId == 4) FALLBACK_HENTAI else FALLBACK

    fun parse(body: String, siteId: Int): List<String> {
        val data = (runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonObject)?.get("data") as? JsonObject
        val arr = data?.get("imageServers") as? JsonArray ?: return fallback(siteId)
        val byId = arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val sites = (o["site_ids"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }.orEmpty()
            if (sites.isNotEmpty() && siteId !in sites) return@mapNotNull null
            val id = (o["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val url = (o["url"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            id to url.trimEnd('/')
        }.toMap()
        return ORDER.mapNotNull { byId[it] }.distinct().ifEmpty { fallback(siteId) }
    }

    /** Склейка сервера и пути страницы с одним слэшем (`//manga/...` у API). */
    fun url(server: String, path: String): String = server.trimEnd('/') + "/" + path.trimStart('/')
}
