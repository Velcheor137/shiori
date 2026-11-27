package io.github.velcheor137.shiori.data.libsocial

import io.github.velcheor137.shiori.engine.document.Block

/** Карточка тайтла в списках (каталог, обновления, закладки). */
data class TitleCard(
    val id: Long,
    val slug: String,
    val name: String,
    val altName: String?,
    val cover: String?,
    val type: String?,
    val status: String?,
    val rating: String?,
    val subtitle: String? = null,
)

data class Team(val id: Long, val name: String, val branchId: Long?)

data class TitleDetails(
    val card: TitleCard,
    val summary: String,
    val genres: List<String>,
    val tags: List<String>,
    val authors: List<String>,
    val artists: List<String>,
    val teams: List<Team>,
    val releaseYear: String?,
    val translationStatus: String?,
    val ageRestriction: String?,
    val chapterCount: Int?,
    val commentsClosed: Boolean,
)

/** Линия перевода. `branchId = 0` — ветка без идентификатора (у API `branch_id: null`). */
data class Branch(val branchId: Long, val label: String, val chapterCount: Int)

data class ChapterVersion(
    val versionId: Long,
    val branchId: Long,
    val createdAt: String?,
    val isOpen: Boolean,
    val teamNames: List<String>,
    val uploader: String?,
    val teamCovers: List<String> = emptyList(),
)

data class ChapterEntry(
    val apiId: Long,
    val index: Int,
    val volume: String,
    val number: String,
    val name: String?,
    val versions: List<ChapterVersion>,
) {
    val label: String get() = "Том $volume Глава $number" + (name?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: "")
    fun version(branchId: Long): ChapterVersion? = versions.firstOrNull { it.branchId == branchId }
}

data class ChapterContent(
    val id: Long,
    val volume: String,
    val number: String,
    val name: String?,
    val branchId: Long,
    val blocks: List<Block>,
    val restricted: Boolean,
    val price: Int?,
    /** Страницы главы манги (у ранобэ пусто). */
    val pages: List<MangaPage> = emptyList(),
)

/** Страница манги: путь от корня сервера картинок и размер исходника (0 — неизвестен). */
data class MangaPage(val path: String, val width: Int, val height: Int)

data class Comment(
    val id: Long,
    val postId: Long,
    val parentId: Long?,
    val rootId: Long?,
    val author: String,
    val avatar: String?,
    val createdAt: String?,
    val text: String,
    /** Текст и картинки комментария (HTML или ProseMirror → общая модель). */
    val blocks: List<io.github.velcheor137.shiori.engine.document.Block> = emptyList(),
    val votesUp: Int?,
    val votesDown: Int?,
    val repliesCount: Int,
    /** Голос текущего пользователя: 1 — плюс, 0 — минус, null — нет. */
    val userVote: Int? = null,
    val level: Int = 0,
)

/** Причина жалобы ([name] — id для запроса, [label] — текст для выбора). */
data class ReportReason(val name: String, val label: String, val commentRequired: Boolean = false, val description: String? = null) {
    companion object {
        /** Список с сайта (ноябрь 2026) — на случай, если `/api/reports/types` не ответил. */
        val FALLBACK = listOf(
            ReportReason("3", "Оскорбление пользователей"),
            ReportReason("4", "Флуд / Оффтоп / Комментарий без смысла"),
            ReportReason("5", "Реклама / Спам"),
            ReportReason("1", "Спойлер", description = "Вы можете дополнительно указать для модератора, в какой главе находится раскрытие сюжета."),
            ReportReason("7", "Провокации / Конфликты"),
            ReportReason("2", "Ненормативная лексика"),
            ReportReason("8", "Запрещенный / непотребный контент"),
        )
    }
}

data class CommentsPage(val roots: List<Comment>, val replies: List<Comment>, val hasNext: Boolean)

data class AccountBookmark(val id: Long?, val status: Long, val chapterId: Long?, val progress: Int?)

/** Уведомление аккаунта. [kind] определяет действие при нажатии. */
data class Notification(
    val id: Long,
    val type: String,
    val isRead: Boolean,
    val createdAt: String?,
    val kind: Kind,
    val title: String,
    val text: String,
    val cover: String?,
    val slug: String?,
    val volume: String?,
    val number: String?,
    val branchId: Long?,
    /** Для ответа на комментарий: комментарий, версия главы (post_id) и корень ветки. */
    val commentId: Long?,
    val commentRootId: Long?,
    val chapterVersionId: Long?,
) {
    enum class Kind { Chapter, Comment, Other }
}

data class UserProfile(
    val id: Long,
    val username: String,
    val avatar: String?,
    val background: String?,
    val about: String?,
    val createdAt: String?,
    val level: Int?,
    val totalPoints: Long?,
    val levelPercent: Float?,
    val loginStreak: Int?,
)

data class BookmarkFolder(val id: Long, val name: String, val count: Int)

data class Page<T>(val items: List<T>, val hasNext: Boolean)

enum class CatalogSort(val apiValue: String, val label: String) {
    Popular("rating_score", "По популярности"),
    Rating("rate_avg", "По рейтингу"),
    Views("views", "По просмотрам"),
    Updated("last_chapter_at", "По обновлению"),
    Created("created_at", "По дате добавления"),
    Chapters("chap_count", "По количеству глав"),
}

class ApiException(val code: Int?, message: String) : Exception(message)
