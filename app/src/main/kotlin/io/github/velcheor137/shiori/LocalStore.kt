package io.github.velcheor137.shiori

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Позиция чтения тайтла: глава закреплённого перевода + абзац и смещение внутри него. */
@Serializable
data class ReadingProgress(
    val titleId: Long,
    val slug: String,
    val titleName: String,
    val cover: String?,
    val branchId: Long,
    val volume: String,
    val number: String,
    val chapterName: String?,
    val block: Int,
    val offset: Int,
    val updatedAt: Long,
    /** Источник ([Source.key]); у старых записей — ранобэ. */
    val source: String = "ranobe",
) {
    val chapterLabel: String get() = "Том $volume Глава $number"
}

/** Ручная закладка внутри текста (не путать с закладкой тайтла в аккаунте). */
@Serializable
data class LocalBookmark(
    val id: Long,
    val titleId: Long,
    val slug: String,
    val titleName: String,
    val branchId: Long,
    val volume: String,
    val number: String,
    val block: Int,
    val excerpt: String,
    val createdAt: Long,
    val source: String = "ranobe",
)

enum class AppTheme(val label: String) { Dark("Тёмная"), Light("Светлая"), System("Как в системе") }

/** Свои цвета темы приложения (ARGB); null — цвет темы по умолчанию. Свой фон сам задаёт светлую/тёмную схему. */
@Serializable
data class ThemeColors(val accent: Long? = null, val background: Long? = null)

enum class TextMode(val label: String) { Original("Оригинал"), Literary("Литературный") }

enum class ReaderTheme(val label: String) {
    Site("Как на сайте"), Light("Светлая"), Sepia("Сепия"), Gray("Серая"), Dark("Тёмная"), Amoled("AMOLED"), Custom("Своя")
}

enum class ReaderFont(val label: String) {
    Merriweather("Merriweather"), Literata("Literata"), PtSerif("PT Serif"), Lora("Lora"),
    PtSans("PT Sans"), OpenSans("Open Sans"), SystemSerif("Системный с засечками"), SystemSans("Системный без засечек")
}

enum class TextAlignMode(val label: String) { Start("По левому краю"), Justify("По ширине") }

@Serializable
data class ReaderSettings(
    val textMode: TextMode = TextMode.Literary,
    val dialogues: Boolean = true,
    val thoughts: Boolean = true,
    val font: ReaderFont = ReaderFont.Merriweather,
    val fontSize: Float = 19f,
    val fontWeight: Int = 400,
    val lineHeight: Float = 1.6f,
    val letterSpacing: Float = 0f,
    val paragraphSpacing: Float = 0.7f,
    val indent: Float = 1.2f,
    val sideMargin: Int = 22,
    val align: TextAlignMode = TextAlignMode.Start,
    val hyphens: Boolean = true,
    val theme: ReaderTheme = ReaderTheme.Site,
    val customBackground: Long = 0xFF2F3136,
    val customText: Long = 0xFFDCDDDE,
    val outlineWidth: Float = 0f,
    val outlineColor: Long = 0xFF000000,
    val brightness: Float = -1f,
    val keepScreenOn: Boolean = true,
    val tapToPage: Boolean = false,
    val showProgress: Boolean = true,
    val highlightParagraph: Boolean = false,
)

@Serializable
data class DownloadTask(
    val titleId: Long,
    val slug: String,
    val titleName: String,
    val branchId: Long,
    val volume: String,
    val number: String,
    val versionId: Long,
    val withImages: Boolean,
    val state: State = State.Queued,
    val error: String? = null,
) {
    enum class State { Queued, Running, Done, Failed }
    val key: String get() = "$titleId/$versionId"
}

/** Локальные данные на устройстве: прогресс, переводы, настройки, закладки, очередь загрузок. */
class LocalStore(context: Context) {
    private val prefs = context.getSharedPreferences("shiori_local", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }

    private val _settings = MutableStateFlow(read(KEY_SETTINGS) { json.decodeFromString<ReaderSettings>(it) } ?: ReaderSettings())
    val settings: StateFlow<ReaderSettings> = _settings.asStateFlow()

    private val _progress = MutableStateFlow(loadAllProgress())
    val progress: StateFlow<List<ReadingProgress>> = _progress.asStateFlow()

    private val _bookmarks = MutableStateFlow(read(KEY_BOOKMARKS) { json.decodeFromString(ListSerializer(LocalBookmark.serializer()), it) } ?: emptyList())
    val bookmarks: StateFlow<List<LocalBookmark>> = _bookmarks.asStateFlow()

    private val _downloads = MutableStateFlow(read(KEY_DOWNLOADS) { json.decodeFromString(ListSerializer(DownloadTask.serializer()), it) } ?: emptyList())
    val downloads: StateFlow<List<DownloadTask>> = _downloads.asStateFlow()

    private val _appTheme = MutableStateFlow(runCatching { AppTheme.valueOf(prefs.getString(KEY_THEME, null) ?: "Dark") }.getOrDefault(AppTheme.Dark))
    val appTheme: StateFlow<AppTheme> = _appTheme.asStateFlow()

    fun setAppTheme(t: AppTheme) {
        _appTheme.value = t
        prefs.edit().putString(KEY_THEME, t.name).apply()
    }

    private val _themeColors = MutableStateFlow(read(KEY_THEME_COLORS) { json.decodeFromString<ThemeColors>(it) } ?: ThemeColors())
    val themeColors: StateFlow<ThemeColors> = _themeColors.asStateFlow()

    fun setThemeColors(c: ThemeColors) {
        _themeColors.value = c
        prefs.edit().putString(KEY_THEME_COLORS, json.encodeToString(ThemeColors.serializer(), c)).apply()
    }

    private val _syncAccount = MutableStateFlow(prefs.getBoolean(KEY_SYNC, false))
    val syncAccount: StateFlow<Boolean> = _syncAccount.asStateFlow()

    fun setSyncAccount(v: Boolean) {
        _syncAccount.value = v
        prefs.edit().putBoolean(KEY_SYNC, v).apply()
    }

    fun updateSettings(transform: (ReaderSettings) -> ReaderSettings) {
        val s = transform(_settings.value)
        _settings.value = s
        prefs.edit().putString(KEY_SETTINGS, json.encodeToString(ReaderSettings.serializer(), s)).apply()
    }

    fun pinnedBranch(titleId: Long): Long? = if (prefs.contains("branch_$titleId")) prefs.getLong("branch_$titleId", 0) else null

    fun pinBranch(titleId: Long, branchId: Long) {
        prefs.edit().putLong("branch_$titleId", branchId).apply()
    }

    fun progress(titleId: Long): ReadingProgress? = _progress.value.firstOrNull { it.titleId == titleId }

    fun saveProgress(p: ReadingProgress) {
        prefs.edit().putString("progress_${p.titleId}", json.encodeToString(ReadingProgress.serializer(), p)).apply()
        _progress.value = (listOf(p) + _progress.value.filter { it.titleId != p.titleId }).sortedByDescending { it.updatedAt }
    }

    fun addBookmark(b: LocalBookmark) = saveBookmarks(listOf(b) + _bookmarks.value)

    fun removeBookmark(id: Long) = saveBookmarks(_bookmarks.value.filter { it.id != id })

    private fun saveBookmarks(list: List<LocalBookmark>) {
        _bookmarks.value = list
        prefs.edit().putString(KEY_BOOKMARKS, json.encodeToString(ListSerializer(LocalBookmark.serializer()), list)).apply()
    }

    @Synchronized
    fun updateDownloads(transform: (List<DownloadTask>) -> List<DownloadTask>) {
        val list = transform(_downloads.value)
        _downloads.value = list
        prefs.edit().putString(KEY_DOWNLOADS, json.encodeToString(ListSerializer(DownloadTask.serializer()), list)).commit()
    }

    private fun <T> read(key: String, parse: (String) -> T): T? = prefs.getString(key, null)?.let { runCatching { parse(it) }.getOrNull() }

    private fun loadAllProgress(): List<ReadingProgress> = prefs.all
        .filterKeys { it.startsWith("progress_") }
        .values.mapNotNull { v -> (v as? String)?.let { runCatching { json.decodeFromString<ReadingProgress>(it) }.getOrNull() } }
        .sortedByDescending { it.updatedAt }

    private companion object {
        const val KEY_SETTINGS = "reader_settings_v2"
        const val KEY_BOOKMARKS = "local_bookmarks"
        const val KEY_DOWNLOADS = "download_queue"
        const val KEY_SYNC = "sync_account_progress"
        const val KEY_THEME = "app_theme"
        const val KEY_THEME_COLORS = "app_theme_colors"
    }
}
