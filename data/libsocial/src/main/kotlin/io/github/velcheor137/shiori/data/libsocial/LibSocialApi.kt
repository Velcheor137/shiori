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
class LibSocialApi(private val http: LibSocialHttp, val siteId: Int = 3) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** @param filter готовая часть строки запроса с фильтрами сайта (`&genres[]=…`), см. `CatalogFilter.toQuery`. */
    suspend fun catalog(query: String, page: Int, sort: CatalogSort, filter: String = ""): Page<TitleCard> {
        val q = if (query.isBlank()) "" else "&q=" + enc(query.trim())
        val sortPart = if (query.isBlank()) "&sort_by=${sort.apiValue}&sort_type=desc" else ""
        val root = getObject("/api/manga?site_id[]=$siteId&page=$page&limit=30&chapters[min]=1$sortPart$q$filter&fields[]=rate_avg&fields[]=rate")
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

    suspend fun chapters(slug: String): List<ChapterEntry> = parseChapters(chaptersRaw(slug))

    /** Сырой список глав — сохраняется при скачивании для офлайна. */
    suspend fun chaptersRaw(slug: String): String {
        val r = http.get("/api/manga/$slug/chapters")
        if (!r.isSuccess) throw ApiException(r.code, describe(r))
        return r.body
    }

    fun parseChapters(raw: String): List<ChapterEntry> =
        ((runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonObject) ?: throw ApiException(null, "Список глав не JSON")).arr("data").mapNotNull { e ->
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
                        teamCovers = bo.arr("teams").mapNotNull { t -> (t as? JsonObject)?.obj("cover")?.let { it.str("thumbnail") ?: it.str("default") } },
                    )
                },
            )
        }.sortedBy { it.index }

    /** Ветка, которую сайт считает основной (`/api/branches/{id}?team_defaults=1`). null — если не удалось. */
    suspend fun defaultBranchId(titleId: Long): Long? = runCatching {
        (getAny("/api/branches/$titleId?team_defaults=1").let { it as? JsonObject }?.arr("data")?.firstOrNull() as? JsonObject)?.long("id")
    }.getOrNull()

    suspend fun chapter(slug: String, volume: String, number: String, branchId: Long): ChapterContent =
        parseChapter(chapterRaw(slug, volume, number, branchId), volume, number, branchId)

    /** Сырой JSON главы — его же сохраняем при скачивании (исходник не меняется, R-IMMUTABLE). */
    suspend fun chapterRaw(slug: String, volume: String, number: String, branchId: Long): String {
        val branchPart = if (branchId != 0L) "&branch_id=$branchId" else ""
        val r = http.get("/api/manga/$slug/chapter?volume=${enc(volume)}&number=${enc(number)}$branchPart")
        if (!r.isSuccess) throw ApiException(r.code, describe(r))
        return r.body
    }

    fun parseChapter(raw: String, volume: String, number: String, branchId: Long, slug: String = ""): ChapterContent {
        val o = (runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonObject)?.obj("data")
            ?: throw ApiException(null, "Пустой ответ главы")
        val restricted = o.obj("restricted_view")?.bool("is_open") == false
        val attachments = o.arr("attachments").mapNotNull { a ->
            val ao = a as? JsonObject ?: return@mapNotNull null
            Attachment(ao.str("name"), ao.str("id"), ao.str("url") ?: return@mapNotNull null)
        }
        val blocks = if (restricted || o["content"] == null) emptyList() else DocumentParser.parse(o["content"]?.takeIf { it !is JsonNull }, attachments)
        val pages = o.arr("pages").mapNotNull { p ->
            val po = p as? JsonObject ?: return@mapNotNull null
            MangaPage(po.str("url") ?: return@mapNotNull null, po.int("width") ?: 0, po.int("height") ?: 0)
        }.let { if (restricted) emptyList() else it }
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
            pages = pages,
        )
    }

    /**
     * Комментарии к версии главы (`post_type=chapter`, `post_id` = id главы конкретного перевода, F-16).
     * Ответ: `{data:{root:[...], replies:[...]}, meta:{has_next_page}}`; форма комментария разбирается защитно.
     */
    suspend fun comments(postId: Long, postType: String, page: Int, sortBy: String = "id"): CommentsPage {
        val root = getObject("/api/comments?post_id=$postId&post_type=$postType&sort_by=$sortBy&sort_type=desc&page=$page")
        val data = root.obj("data")
        val roots = data?.arr("root").orEmpty().mapNotNull { (it as? JsonObject)?.toComment(postId) }
        val replies = data?.arr("replies").orEmpty().mapNotNull { (it as? JsonObject)?.toComment(postId) }
        if (page == 1) {
            (data?.arr("root")?.firstOrNull() as? JsonObject)?.let { AppLog.i(TAG, "Ключи комментария: ${it.keys}; user: ${(it["user"] as? JsonObject)?.keys}") }
        }
        return CommentsPage(roots, replies, root.obj("meta")?.bool("has_next_page") == true)
    }

    /** Голос за комментарий, как на сайте: `POST /api/comments/{id}/vote {vote}`; 1 — плюс, 0 — минус (повтор снимает голос). */
    suspend fun voteComment(commentId: Long, vote: Int): Boolean {
        val r = http.postJson("/api/comments/$commentId/vote", "{\"vote\":$vote}")
        AppLog.i(TAG, "Голос $vote за комментарий $commentId: HTTP ${r.code}")
        if (!r.isSuccess) throw ApiException(r.code, describe(r))
        return true
    }

    /**
     * Причины жалобы на комментарий, как в окне жалобы сайта: `GET /api/reports/types?namespaces[]=social_comments`
     * (массив `{name, label, metadata:{comment_required, description}}`). Без сети — запасной список.
     */
    suspend fun commentReportReasons(): List<ReportReason> = runCatching {
        val any = getAny("/api/reports/types?namespaces[]=social_comments")
        val arr = any as? JsonArray ?: (any as? JsonObject)?.get("data") as? JsonArray ?: JsonArray(emptyList())
        arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val name = o.str("name") ?: return@mapNotNull null
            val meta = o.obj("metadata")
            ReportReason(
                name = name,
                label = o.str("label") ?: name,
                commentRequired = meta?.bool("comment_required") == true,
                description = meta?.obj("description")?.get("content")
                    ?.let { DocumentParser.plainText(DocumentParser.parse(it, emptyList(), rich = true)) }?.takeIf { it.isNotBlank() },
            )
        }
    }.onFailure { AppLog.w(TAG, "Причины жалобы не загрузились, беру запасной список", it) }
        .getOrNull()?.takeIf { it.isNotEmpty() } ?: ReportReason.FALLBACK

    /**
     * Жалоба на комментарий, как на сайте: `POST /api/comments/{id}/report`
     * `{reason, metadata:{comment}, reportable_id, reportable_type:"comment", namespaces:"social_comments"}`.
     */
    suspend fun reportComment(commentId: Long, reason: String, comment: String) {
        val body = buildString {
            append("{\"reason\":").append(kotlinx.serialization.json.JsonPrimitive(reason))
            append(",\"metadata\":{\"comment\":").append(kotlinx.serialization.json.JsonPrimitive(comment.trim())).append('}')
            append(",\"reportable_id\":").append(commentId)
            append(",\"reportable_type\":\"comment\",\"namespaces\":\"social_comments\"}")
        }
        val r = http.postJson("/api/comments/$commentId/report", body)
        AppLog.i(TAG, "Жалоба на комментарий $commentId (причина $reason): HTTP ${r.code}")
        if (!r.isSuccess) throw ApiException(r.code, describe(r) + (if (r.isJson) ": " + r.body.take(200) else ""))
    }

    /**
     * Новый комментарий или ответ (`POST /api/comments`). Текст отправляется в формате редактора сайта
     * (ProseMirror doc): абзацы по строкам.
     */
    suspend fun postComment(postId: Long, postType: String, text: String, replyTo: Comment?): Comment? {
        val paragraphs = text.trim().split(Regex("\\n+")).joinToString(",") { line ->
            "{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":" + kotlinx.serialization.json.JsonPrimitive(line).toString() + "}]}"
        }
        val body = buildString {
            append("{\"comment\":{\"type\":\"doc\",\"content\":[").append(paragraphs).append("]},")
            append("\"attachments\":[],\"post_type\":\"").append(postType).append("\",\"post_id\":").append(postId).append(',')
            append("\"parent_comment\":").append(replyTo?.id ?: "null").append(',')
            append("\"root_id\":").append(replyTo?.let { it.rootId ?: it.id } ?: "null").append(',')
            append("\"comment_level\":").append(replyTo?.let { it.level + 1 } ?: 0)
            if (postType == "chapter") append(",\"post_page\":1")
            append('}')
        }
        val r = http.postJson("/api/comments", body)
        AppLog.i(TAG, "Комментарий отправлен: HTTP ${r.code}")
        if (!r.isSuccess) throw ApiException(r.code, describe(r) + (if (r.isJson) ": " + r.body.take(200) else ""))
        return (runCatching { json.parseToJsonElement(r.body) }.getOrNull() as? JsonObject)?.let { (it["data"] as? JsonObject ?: it).toComment(postId) }
    }

    /**
     * Уведомления (`GET /api/notifications`, как страница уведомлений сайта): `type` = `all` | `chapter` | `comments`,
     * `read` = `unread` | `read` | `all`.
     */
    suspend fun notifications(type: String, read: String, page: Int): Page<Notification> {
        val root = getObject("/api/notifications?notification_type=$type&read_type=$read&sort_type=desc&page=$page")
        val items = root.arr("data").mapNotNull { (it as? JsonObject)?.toNotification() }
        if (page == 1) (root.arr("data").firstOrNull() as? JsonObject)?.let { AppLog.i(TAG, "Ключи уведомления: ${it.keys}; data: ${(it["data"] as? JsonObject)?.keys}") }
        return Page(items, root.obj("meta")?.bool("has_next_page") == true || root.obj("links")?.str("next") != null)
    }

    /** Число непрочитанных уведомлений (сумма по категориям `GET /api/notifications/count`). */
    suspend fun unreadNotifications(): Int = runCatching {
        val unread = getObject("/api/notifications/count").obj("data")?.obj("unread") ?: return@runCatching 0
        unread["all"]?.let { (it as? JsonPrimitive)?.intOrNull } ?: unread.values.sumOf { (it as? JsonPrimitive)?.intOrNull ?: 0 }
    }.getOrDefault(0)

    suspend fun markNotificationRead(id: Long): Boolean = runCatching { http.putJson("/api/notifications/$id", "{}").isSuccess }.getOrDefault(false)

    suspend fun markAllNotificationsRead(type: String): Boolean =
        runCatching { http.putJson("/api/notifications/bulk", "{\"notification_type\":\"$type\"}").isSuccess }.getOrDefault(false)

    /** Ветка обсуждения вокруг комментария (`GET /api/comments/{id}`): корень и ответы. */
    suspend fun commentThread(commentId: Long, postId: Long, postType: String = "chapter"): CommentsPage {
        val data = getObject("/api/comments/$commentId?post_id=$postId&post_type=$postType&sort_by=id&sort_type=desc").obj("data")
        return CommentsPage(
            data?.arr("root").orEmpty().mapNotNull { (it as? JsonObject)?.toComment(postId) },
            data?.arr("replies").orEmpty().mapNotNull { (it as? JsonObject)?.toComment(postId) },
            false,
        )
    }

    private fun JsonObject.toNotification(): Notification? {
        val id = long("id") ?: return null
        val type = str("type").orEmpty()
        val d = obj("data") ?: JsonObject(emptyMap())
        val comment = d.obj("comment")
        val relation = comment?.obj("relation")
        val media = d.obj("media") ?: d.obj("manga") ?: relation?.obj("media") ?: relation?.obj("manga")
        val chapter = d.obj("chapter") ?: relation
        val mediaName = media?.let { it.str("rus_name")?.takeIf { n -> n.isNotBlank() } ?: it.str("name") }.orEmpty()
        val slug = media?.str("slug_url") ?: media?.let { m -> m.long("id")?.let { i -> m.str("slug")?.let { "$i--$it" } } }
        val cover = media?.obj("cover")?.let { it.str("thumbnail") ?: it.str("default") }
            ?: media?.str("cover")?.let { c -> media.str("slug")?.let { "https://cover.imglib.info/uploads/cover/$it/cover/${c}_thumb.jpg" } }
        val chapterLabel = chapter?.let { "Том ${it.str("volume") ?: "?"} Глава ${it.str("number") ?: "?"}" }
        val kind = when {
            type == "chapter" -> Notification.Kind.Chapter
            type.startsWith("comment") && comment != null -> Notification.Kind.Comment
            else -> Notification.Kind.Other
        }
        val text = when (kind) {
            Notification.Kind.Chapter -> {
                val count = d.int("count") ?: 1
                "Новая глава: ${chapterLabel.orEmpty()}" + if (count > 1) " и ещё ${count - 1}" else ""
            }
            Notification.Kind.Comment -> {
                val author = comment?.obj("user")?.str("username") ?: "Кто-то"
                val body = richText(comment?.get("comment")).replace('\n', ' ').take(160)
                "$author ответил(а)${chapterLabel?.let { " · $it" } ?: ""}: $body"
            }
            Notification.Kind.Other -> d.str("message") ?: d.str("text") ?: d.str("title") ?: type
        }
        return Notification(
            id = id,
            type = type,
            isRead = bool("is_read") ?: (str("read_at") != null),
            createdAt = str("created_at"),
            kind = kind,
            title = mediaName,
            text = text,
            cover = cover,
            slug = slug,
            volume = chapter?.str("volume"),
            number = chapter?.str("number"),
            branchId = chapter?.long("branch_id"),
            commentId = comment?.long("id"),
            commentRootId = comment?.long("root_id"),
            chapterVersionId = if (comment?.str("relation_type") == "chapter") comment.long("relation_id") ?: relation?.long("id") else null,
        )
    }

    /** Закладка аккаунта на тайтл (`GET /api/manga/{slug}/bookmark`). null — закладки нет или не вошли. */
    suspend fun titleBookmark(slug: String): AccountBookmark? = runCatching {
        val o = getObject("/api/manga/$slug/bookmark").obj("data") ?: return@runCatching null
        AppLog.i(TAG, "Закладка аккаунта: ключи ${o.keys}")
        AccountBookmark(o.long("id"), o.long("status") ?: 1, o.long("item_id") ?: o.obj("item")?.long("id"), o.int("progress"))
    }.getOrNull()

    /** Сохранить прогресс в закладке аккаунта (`POST /api/bookmarks`, как сайт): глава + номер абзаца. */
    suspend fun saveAccountProgress(slug: String, status: Long, chapterId: Long, paragraph: Int, itemNumber: Int): Boolean {
        val body = buildString {
            append("{\"media_type\":\"manga\",\"media_slug\":\"").append(slug).append("\",")
            append("\"bookmark\":{\"item_id\":").append(chapterId).append(",\"progress\":").append(paragraph).append(",\"status\":").append(status).append("},")
            append("\"meta\":{\"item_number\":").append(itemNumber).append("}}")
        }
        val r = http.postJson("/api/bookmarks", body)
        AppLog.i(TAG, "Прогресс в аккаунт: HTTP ${r.code}")
        return r.isSuccess
    }

    private fun JsonObject.toComment(postId: Long): Comment? {
        val id = long("id") ?: return null
        val user = obj("user")
        val avatar = user?.obj("avatar")?.let { it.str("url") ?: it.str("default") ?: it.str("thumbnail") } ?: user?.str("avatar")
        val votes = obj("votes")
        return Comment(
            id = id,
            postId = postId,
            parentId = long("parent_comment"),
            rootId = long("root_id"),
            author = user?.str("username") ?: "—",
            avatar = avatar,
            createdAt = str("created_at"),
            blocks = DocumentParser.parse(this["comment"]?.takeIf { it !is JsonNull }, emptyList(), rich = true),
            text = richText(this["comment"]),
            votesUp = votes?.int("up") ?: votes?.int("likes") ?: int("votes_up"),
            votesDown = votes?.int("down") ?: votes?.int("dislikes"),
            repliesCount = int("replies_count") ?: 0,
            userVote = votes?.int("user"),
            level = int("comment_level") ?: 0,
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
        // Для «Нового в закладках»: есть ли у тайтла в ответе дата последней главы (поле проверяется по журналу).
        if (page == 1) ((root.arr("data").firstOrNull() as? JsonObject)?.obj("media"))?.let { AppLog.i(TAG, "Ключи тайтла в закладках: ${it.keys}") }
        val items = root.arr("data").mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val media = o.obj("media") ?: return@mapNotNull null
            val item = o.obj("item") ?: o.obj("bookmark")?.obj("item")
            val progress = item?.let { "Закладка: том ${it.str("volume") ?: "?"} глава ${it.str("number") ?: "?"}" }
            media.toCard()?.copy(subtitle = progress)
        }
        return Page(items, root.obj("meta")?.bool("has_next_page") == true)
    }

    /** Профиль пользователя: ник, аватар, уровень, дата регистрации (`GET /api/user/{id}`). */
    suspend fun user(id: Long): UserProfile {
        val o = getObject("/api/user/$id?fields[]=background&fields[]=points&fields[]=created_at&fields[]=about").obj("data")
            ?: throw ApiException(null, "Пустой профиль")
        val points = o.obj("points_info")
        return UserProfile(
            id = o.long("id") ?: id,
            username = o.str("username").orEmpty(),
            avatar = o.obj("avatar")?.str("url"),
            background = o.obj("background")?.takeIf { it.str("filename") != null }?.str("url"),
            about = o.str("about")?.takeIf { it.isNotBlank() },
            createdAt = o.str("created_at"),
            level = points?.int("level"),
            totalPoints = points?.long("total_points"),
            levelPercent = points?.str("point_percent_progress")?.toFloatOrNull(),
            loginStreak = o.obj("login_streak")?.int("login_streak"),
        )
    }

    /** Любимые жанры из статистики профиля (`GET /api/user/{id}/stats`); пусто, если статистика скрыта. */
    suspend fun userTopGenres(id: Long): List<Pair<String, Float>> = runCatching {
        getObject("/api/user/$id/stats").obj("data")?.arr("genres").orEmpty().mapNotNull { e ->
            val g = e as? JsonObject ?: return@mapNotNull null
            (g.str("label") ?: return@mapNotNull null) to (g.str("percent")?.toFloatOrNull() ?: 0f)
        }
    }.getOrDefault(emptyList())

    /** Серверы картинок манги (`/api/constants?fields[]=imageServers`) в порядке перебора. */
    suspend fun imageServers(): List<String> {
        val r = http.get("/api/constants?fields[]=imageServers")
        if (!r.isSuccess) throw ApiException(r.code, describe(r))
        return ImageServers.parse(r.body, siteId)
    }

    /** Справочники фильтров каталога (жанры, теги, типы…) — сырой JSON, чтобы приложение кэшировало его как есть. */
    suspend fun constantsRaw(): String {
        val r = http.get("/api/constants?" + FilterConstants.FIELDS.joinToString("&") { "fields[]=$it" })
        if (!r.isSuccess) throw ApiException(r.code, describe(r))
        return r.body
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

    /** Плоский текст комментария (превью, уведомления): спойлеры скрыты. */
    private fun richText(e: JsonElement?): String = when {
        e == null || e is JsonNull -> ""
        e is JsonPrimitive -> DocumentParser.plainText(DocumentParser.parseHtml(e.content, rich = true))
        e is JsonObject -> DocumentParser.plainText(DocumentParser.parseProseMirror(e, emptyList(), rich = true))
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
