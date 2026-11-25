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
