package io.github.velcheor137.shiori

import android.content.Context
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Фильтры каталога — как у сайта (§ план этапа A, п. 6). null у диапазонов = «не задано». */
@Serializable
data class CatalogFilter(
    val genres: Set<Long> = emptySet(),
    val genresExclude: Set<Long> = emptySet(),
    val tags: Set<Long> = emptySet(),
    val tagsExclude: Set<Long> = emptySet(),
    val types: Set<Long> = emptySet(),
    val formats: Set<Long> = emptySet(),
    val statuses: Set<Long> = emptySet(),
    val scanlate: Set<Long> = emptySet(),
    val ages: Set<Long> = emptySet(),
    val chapMin: Int? = null,
    val chapMax: Int? = null,
    val yearMin: Int? = null,
    val yearMax: Int? = null,
    val ratingMin: Int? = null,
    val ratingMax: Int? = null,
    val rateMin: Int? = null,
    val rateMax: Int? = null,
    val longNoTranslation: Boolean = false,
    /** null — любые, 1 — только издательский контент, 0 — без него. */
    val licensed: Int? = null,
    val buy: Boolean = false,
    val bookmarks: Set<Long> = emptySet(),
    val bookmarksExclude: Set<Long> = emptySet(),
) {
    /** Сколько групп фильтров задано — для бейджа на кнопке «Фильтры». */
    val activeCount: Int
        get() = listOf(
            genres.isNotEmpty() || genresExclude.isNotEmpty(),
            tags.isNotEmpty() || tagsExclude.isNotEmpty(),
            types.isNotEmpty(), formats.isNotEmpty(), statuses.isNotEmpty(), scanlate.isNotEmpty(), ages.isNotEmpty(),
            chapMin != null || chapMax != null, yearMin != null || yearMax != null,
            ratingMin != null || ratingMax != null, rateMin != null || rateMax != null,
            longNoTranslation, licensed != null, buy, bookmarks.isNotEmpty() || bookmarksExclude.isNotEmpty(),
        ).count { it }

    /** Часть строки запроса `/api/manga` — имена параметров как у сайта. */
    fun toQuery(): String = buildString {
        fun ids(name: String, set: Set<Long>) = set.sorted().forEach { append("&").append(name).append("[]=").append(it) }
        fun num(name: String, v: Int?) { if (v != null) append("&").append(name).append("=").append(v) }
        ids("genres", genres); ids("genres_exclude", genresExclude)
        ids("tags", tags); ids("tags_exclude", tagsExclude)
        ids("types", types); ids("format", formats); ids("status", statuses)
        ids("scanlate_status", scanlate); ids("caution", ages)
        num("chap_count_min", chapMin); num("chap_count_max", chapMax)
        num("year_min", yearMin); num("year_max", yearMax)
        num("rating_min", ratingMin); num("rating_max", ratingMax)
        num("rate_min", rateMin); num("rate_max", rateMax)
        if (longNoTranslation) append("&long_no_translation=1")
        num("licensed", licensed)
        if (buy) append("&buy=1")
        ids("bookmarks", bookmarks); ids("bookmarks_exclude", bookmarksExclude)
    }
}

@Serializable
data class CatalogPrefs(val sort: String = "Popular", val grid: Boolean = false, val filter: CatalogFilter = CatalogFilter())

/**
 * Запоминаемые настройки экранов (п. 2): всё, что пользователь выбирал — сортировки, фильтры, вкладки, вид.
 * Хранится рядом с остальными локальными данными, на устройстве.
 */
class UiPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("shiori_ui", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }

    private fun <T> read(key: String, serializer: KSerializer<T>, default: T): T =
        prefs.getString(key, null)?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() } ?: default

    private fun <T> write(key: String, serializer: KSerializer<T>, value: T) =
        prefs.edit().putString(key, json.encodeToString(serializer, value)).apply()

    var catalog: CatalogPrefs
        get() = read("catalog", CatalogPrefs.serializer(), CatalogPrefs())
        set(v) = write("catalog", CatalogPrefs.serializer(), v)

    fun bool(key: String, default: Boolean) = prefs.getBoolean(key, default)
    fun setBool(key: String, v: Boolean) = prefs.edit().putBoolean(key, v).apply()
    fun string(key: String, default: String) = prefs.getString(key, default) ?: default
    fun setString(key: String, v: String) = prefs.edit().putString(key, v).apply()
    fun int(key: String, default: Int) = prefs.getInt(key, default)
    fun setInt(key: String, v: Int) = prefs.edit().putInt(key, v).apply()

    /** Фильтр переводчика в списке глав — свой для каждого тайтла; null — все переводы. */
    fun chapterFilter(titleId: Long): Long? = if (prefs.contains("chapters_filter_$titleId")) prefs.getLong("chapters_filter_$titleId", 0) else null
    fun setChapterFilter(titleId: Long, branchId: Long?) =
        prefs.edit().apply { if (branchId == null) remove("chapters_filter_$titleId") else putLong("chapters_filter_$titleId", branchId) }.apply()

    companion object Keys {
        const val CHAPTERS_DESC = "chapters_desc"
        const val COMMENTS_SORT = "comments_sort"
        const val NOTIFY_CATEGORY = "notify_category"
        const val NOTIFY_UNREAD = "notify_unread"
        const val LIBRARY_LOCAL = "library_local"
        const val LIBRARY_FOLDER = "library_folder"
        const val DL_IMAGES = "download_images"
        const val DL_WIFI = "download_wifi"
        const val COVER_CACHE_MB = "cache_cover_mb"
        const val CHAPTER_CACHE_COUNT = "cache_chapter_count"
    }
}
