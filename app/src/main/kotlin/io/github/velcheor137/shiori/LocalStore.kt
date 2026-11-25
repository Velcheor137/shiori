package io.github.velcheor137.shiori

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
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
) {
    val chapterLabel: String get() = "Том $volume Глава $number"
}

enum class ReaderTheme(val label: String) { Light("Светлая"), Sepia("Сепия"), Dark("Тёмная"), Amoled("AMOLED") }

@Serializable
data class ReaderSettings(
    val fontSize: Float = 18f,
    val lineHeight: Float = 1.55f,
    val paragraphSpacing: Float = 0.6f,
    val indent: Float = 1.5f,
    val sideMargin: Int = 20,
    val serif: Boolean = true,
    val justify: Boolean = false,
    val theme: ReaderTheme = ReaderTheme.Light,
    val dialogues: Boolean = true,
    val thoughts: Boolean = true,
)

/** Локальные данные на устройстве: прогресс, закреплённые переводы, настройки читалки. */
class LocalStore(context: Context) {
    private val prefs = context.getSharedPreferences("shiori_local", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _settings = MutableStateFlow(
        prefs.getString(KEY_SETTINGS, null)?.let { runCatching { json.decodeFromString<ReaderSettings>(it) }.getOrNull() } ?: ReaderSettings(),
    )
    val settings: StateFlow<ReaderSettings> = _settings.asStateFlow()

    private val _progress = MutableStateFlow(loadAllProgress())
    val progress: StateFlow<List<ReadingProgress>> = _progress.asStateFlow()

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

    private fun loadAllProgress(): List<ReadingProgress> = prefs.all
        .filterKeys { it.startsWith("progress_") }
        .values.mapNotNull { v -> (v as? String)?.let { runCatching { json.decodeFromString<ReadingProgress>(it) }.getOrNull() } }
        .sortedByDescending { it.updatedAt }

    private companion object {
        const val KEY_SETTINGS = "reader_settings"
    }
}
