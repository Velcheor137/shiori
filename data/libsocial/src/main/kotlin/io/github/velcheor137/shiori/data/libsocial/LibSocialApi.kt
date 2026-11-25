package io.github.velcheor137.shiori.data.libsocial

import io.github.velcheor137.shiori.core.common.AppLog
import io.github.velcheor137.shiori.core.network.FailureKind
import io.github.velcheor137.shiori.core.network.HttpResult
import io.github.velcheor137.shiori.core.network.LibSocialHttp
import io.github.velcheor137.shiori.engine.document.Attachment
import io.github.velcheor137.shiori.engine.document.DocumentParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.net.URLEncoder

/**
 * Запросы к API LibSocial для RanobeLib (таблица §2.4 плана). Разбор — защитный: поля читаются из
 * `JsonObject`, отсутствие поля не роняет экран.
 */
class LibSocialApi(private val http: LibSocialHttp) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun catalog(query: String, page: Int, sort: CatalogSort): Page<TitleCard> {
        val q = if (query.isBlank()) "" else "&q=" + enc(query.trim())
        val sortPart = if (query.isBlank()) "&sort_by=${sort.apiValue}&sort_type=desc" else ""
        val root = getObject("/api/manga?site_id[]=3&page=$page&limit=30&chapters[min]=1$sortPart$q&fields[]=rate_avg&fields[]=rate")
        return Page(root.arr("data").mapNotNull { (it as? JsonObject)?.toCard() }, root.obj("meta")?.bool("has_next_page") == true)
    }

    suspend fun latestUpdates(page: Int): Page<TitleCard> {
        val root = getObject("/api/latest-updates?page=$page")
        val items = root.arr("data").mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val latest = o.obj("metadata")?.obj("latest_items")
            val count = latest?.int("count")
            val first = latest?.arr("items")?.firstOrNull() as? JsonObject
            val label = first?.let { "Том ${it.str("volume") ?: "?"} Глава ${it.str("number") ?: "?"}" }
            o.toCard()?.copy(subtitle = listOfNotNull(label, count?.takeIf { it > 1 }?.let { "+ ещё ${it - 1}" }).joinToString(" ").ifBlank { null })
        }
        return Page(items, root.obj("meta")?.bool("has_next_page") == true || items.isNotEmpty() && page < 20)
    }

    suspend fun title(slug: String): TitleDetails {
        val fields = listOf(
            "background", "eng_name", "otherNames", "summary", "releaseDate", "type_id", "caution", "views", "rate_avg", "rate",
            "genres", "tags", "teams", "authors", "artists", "status_id", "chap_count", "metadata", "metadata.close_comments",
        ).joinToString("") { "&fields[]=$it" }.removePrefix("&")
        val o = getObject("/api/manga/$slug?$fields").obj("data") ?: throw ApiException(null, "Пустой ответ тайтла")
        val card = o.toCard() ?: throw ApiException(null, "Тайтл без id")
        return TitleDetails(
            card = card,
            summary = richText(o["summary"]),
            genres = o.arr("genres").names(),
            tags = o.arr("tags").names(),
            authors = o.arr("authors").names(),
            artists = o.arr("artists").names(),
            teams = o.arr("teams").mapNotNull { t ->
                val to = t as? JsonObject ?: return@mapNotNull null
                Team(to.long("id") ?: return@mapNotNull null, to.str("name").orEmpty(), to.obj("details")?.long("branch_id"))
            },
            releaseYear = o.str("releaseDateString") ?: o.str("releaseDate"),
            translationStatus = o.obj("scanlateStatus")?.str("label"),
            ageRestriction = o.obj("ageRestriction")?.str("label"),
            chapterCount = o.obj("items_count")?.int("uploaded"),
            commentsClosed = (o.obj("metadata")?.int("close_comments") ?: 0) >= 2,
        )
    }

    suspend fun chapters(slug: String): List<ChapterEntry> =
        getObject("/api/manga/$slug/chapters").arr("data").mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            ChapterEntry(
                apiId = o.long("id") ?: return@mapNotNull null,
                index = o.int("index") ?: o.int("item_number") ?: 0,
                volume = o.str("volume") ?: "0",
                number = o.str("number") ?: "0",
                name = o.str("name"),
                versions = o.arr("branches").mapNotNull { b ->
                    val bo = b as? JsonObject ?: return@mapNotNull null
                    ChapterVersion(
                        versionId = bo.long("id") ?: return@mapNotNull null,
                        branchId = bo.long("branch_id") ?: 0L,
                        createdAt = bo.str("created_at"),
                        isOpen = bo.obj("restricted_view")?.bool("is_open") ?: true,
                        teamNames = bo.arr("teams").names(),
                        uploader = bo.obj("user")?.str("username"),
                    )
                },
            )
        }.sortedBy { it.index }

    /** Ветка, которую сайт считает основной (`/api/branches/{id}?team_defaults=1`). null — если не удалось. */
    suspend fun defaultBranchId(titleId: Long): Long? = runCatching {
        (getAny("/api/branches/$titleId?team_defaults=1").let { it as? JsonObject }?.arr("data")?.firstOrNull() as? JsonObject)?.long("id")
    }.getOrNull()

    suspend fun chapter(slug: String, volume: String, number: String, branchId: Long): ChapterContent {
        val branchPart = if (branchId != 0L) "&branch_id=$branchId" else ""
        val o = getObject("/api/manga/$slug/chapter?volume=${enc(volume)}&number=${enc(number)}$branchPart").obj("data")
            ?: throw ApiException(null, "Пустой ответ главы")
        val restricted = o.obj("restricted_view")?.bool("is_open") == false
        val attachments = o.arr("attachments").mapNotNull { a ->
            val ao = a as? JsonObject ?: return@mapNotNull null
            Attachment(ao.str("name"), ao.str("id"), ao.str("url") ?: return@mapNotNull null)
        }
        val blocks = if (restricted) emptyList() else DocumentParser.parse(o["content"]?.takeIf { it !is JsonNull }, attachments)
        AppLog.i(TAG, "Глава $slug т$volume г$number ветка $branchId: блоков ${blocks.size}, закрыта=$restricted, формат=${if (o["content"] is JsonObject) "ProseMirror" else "HTML"}")
        return ChapterContent(
            id = o.long("id") ?: 0,
            volume = o.str("volume") ?: volume,
            number = o.str("number") ?: number,
            name = o.str("name"),
            branchId = o.long("branch_id") ?: branchId,
            blocks = blocks,
            restricted = restricted,
            price = o.obj("restricted_view")?.int("price"),
        )
    }

    suspend fun folders(userId: Long): List<BookmarkFolder> {
        val any = getAny("/api/bookmarks/folder/$userId")
        val arr = (any as? JsonObject)?.get("data") as? JsonArray ?: (any as? JsonObject)?.obj("data")?.arr("folders") ?: JsonArray(emptyList())
        return arr.mapNotNull { f ->
            val fo = f as? JsonObject ?: return@mapNotNull null
            BookmarkFolder(fo.long("id") ?: return@mapNotNull null, fo.str("name").orEmpty(), fo.int("count") ?: 0)
        }
    }

    suspend fun bookmarks(userId: Long, folderId: Long, page: Int): Page<TitleCard> {
        val root = getObject("/api/bookmarks?user_id=$userId&status=$folderId&page=$page")
        val items = root.arr("data").mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val media = o.obj("media") ?: return@mapNotNull null
            val item = o.obj("item") ?: o.obj("bookmark")?.obj("item")
            val progress = item?.let { "Закладка: том ${it.str("volume") ?: "?"} глава ${it.str("number") ?: "?"}" }
            media.toCard()?.copy(subtitle = progress)
        }
        return Page(items, root.obj("meta")?.bool("has_next_page") == true)
    }

    private suspend fun getObject(path: String): JsonObject = getAny(path) as? JsonObject ?: throw ApiException(null, "Ответ не JSON-объект")

    private suspend fun getAny(path: String): JsonElement {
        val r: HttpResult = http.get(path)
        if (!r.isSuccess) throw ApiException(r.code, describe(r))
        return runCatching { json.parseToJsonElement(r.body) }.getOrElse {
            AppLog.e(TAG, "Не разобрал JSON ответа $path", it)
            throw ApiException(r.code, "Сервис вернул неожиданный ответ")
        }
    }

    private fun describe(r: HttpResult) = when (r.failure) {
        FailureKind.EdgeBlocked -> "Защита сайта не пропустила запрос (HTTP ${r.code})"
        FailureKind.Unauthorized -> "Нужен вход в аккаунт"
        FailureKind.NotFound -> "Не найдено"
        FailureKind.RateLimited -> "Слишком много запросов, подождите"
        FailureKind.Server -> "Ошибка сервера (HTTP ${r.code})"
        else -> "Ошибка HTTP ${r.code}"
    }

    private fun JsonObject.toCard(): TitleCard? {
        val id = long("id") ?: return null
        val slug = str("slug_url") ?: str("slug")?.let { "$id--$it" } ?: return null
        val rating = obj("rating")?.str("average") ?: str("rate_avg") ?: obj("rating")?.str("averageFormated")
        return TitleCard(
            id = id,
            slug = slug,
            name = str("rus_name")?.takeIf { it.isNotBlank() } ?: str("name").orEmpty(),
            altName = str("eng_name")?.takeIf { it.isNotBlank() } ?: str("name"),
            cover = obj("cover")?.let { it.str("default") ?: it.str("thumbnail") },
            type = obj("type")?.str("label") ?: str("type"),
            status = obj("status")?.str("label"),
            rating = rating,
        )
    }

    private fun richText(e: JsonElement?): String = when {
        e == null || e is JsonNull -> ""
        e is JsonPrimitive -> DocumentParser.parseHtml(e.content).joinToString("\n\n") { (it as? io.github.velcheor137.shiori.engine.document.Paragraph)?.text.orEmpty() }
        e is JsonObject -> DocumentParser.parseProseMirror(e, emptyList()).joinToString("\n\n") { (it as? io.github.velcheor137.shiori.engine.document.Paragraph)?.text.orEmpty() }
        else -> ""
    }.trim()

    private fun JsonArray.names() = mapNotNull { (it as? JsonObject)?.str("name") }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private companion object {
        const val TAG = "API"
    }
}

internal fun JsonObject.obj(key: String) = this[key] as? JsonObject
internal fun JsonObject.arr(key: String) = this[key] as? JsonArray ?: JsonArray(emptyList())
internal fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
internal fun JsonObject.long(key: String) = (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }
internal fun JsonObject.int(key: String) = (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }
internal fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull
