package io.github.velcheor137.shiori

/**
 * Источник контента LibSocial (этап B, п. 7). Аккаунт общий (тот же токен), различаются сайт (`Site-Id`),
 * `Referer` и тип контента: текст (ранобэ) или картинки (манга).
 */
enum class Source(val key: String, val label: String, val siteId: Int, val origin: String, val images: Boolean) {
    Ranobe("ranobe", "RanobeLib", 3, "https://ranobelib.me", images = false),
    Manga("manga", "MangaLib", 1, "https://mangalib.me", images = true),
    ;

    /** Тип контента для подписи в списке источников. */
    val kind: String get() = if (images) "Манга" else "Ранобэ"
    val icon: androidx.compose.ui.graphics.vector.ImageVector get() = if (images) AppIcons.Image else AppIcons.Book

    /** Скачивание глав для офлайна — пока только текст. */
    val downloads: Boolean get() = !images

    companion object {
        fun of(key: String?): Source = entries.firstOrNull { it.key == key } ?: Ranobe
    }
}
