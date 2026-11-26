package io.github.velcheor137.shiori

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil3.SingletonImageLoader
import io.github.velcheor137.shiori.core.common.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private data class StorageInfo(
    val images: Long,
    val chapters: Long,
    val chapterCount: Int,
    val downloads: Long,
    val log: Long,
    val other: Long,
)

/** «Хранилище» (п. 8): сколько места занято, лимиты кэша и очистка по категориям. */
@Composable
fun StorageScreen(nav: NavController) {
    val app = LocalContext.current.shiori
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var coverMb by remember { mutableIntStateOf(app.ui.int(UiPrefs.COVER_CACHE_MB, 150)) }
    var chapterLimit by remember { mutableIntStateOf(app.ui.int(UiPrefs.CHAPTER_CACHE_COUNT, 30)) }
    val info by produceState<StorageInfo?>(null, refresh) {
        value = withContext(Dispatchers.IO) {
            val loader = SingletonImageLoader.get(app)
            StorageInfo(
                images = loader.diskCache?.size ?: 0L,
                chapters = app.chapterTextCache.sizeBytes(),
                chapterCount = app.chapterTextCache.count(),
                downloads = File(app.filesDir, "downloads").sizeDeep(),
                log = File(app.filesDir, "logs").sizeDeep(),
                other = File(app.filesDir, "constants_site3.json").length() + File(app.cacheDir, "WebView").sizeDeep(),
            )
        }
    }

    fun clear(what: String, action: () -> Unit) {
        scope.launch {
            withContext(Dispatchers.IO) { action() }
            AppLog.i("STORAGE", "Очищено: $what")
            refresh++
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
            Text("Хранилище", Modifier.weight(1f), fontFamily = TitleFont, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
        }
        val i = info
        if (i == null) {
            Loading(Modifier.fillMaxWidth().padding(32.dp))
            return@Column
        }
        Text(
            "Всего занято: ${bytes(i.images + i.chapters + i.downloads + i.log + i.other)}",
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.titleMedium,
        )

        Block(
            title = "Обложки и картинки комментариев",
            value = "${bytes(i.images)} из $coverMb МБ",
            note = "Новый лимит действует после перезапуска приложения.",
        ) {
            Chips(listOf(50, 150, 300, 500), coverMb, { "$it МБ" }) { coverMb = it; app.ui.setInt(UiPrefs.COVER_CACHE_MB, it) }
            OutlinedButton(onClick = {
                clear("картинки") {
                    SingletonImageLoader.get(app).let { it.diskCache?.clear(); it.memoryCache?.clear() }
                }
            }) { Text("Очистить") }
        }
        Block(
            title = "Кэш чтения глав",
            value = "${i.chapterCount} гл. · ${bytes(i.chapters)}",
            note = "Текущая и следующая глава плюс последние прочитанные — открываются сразу и без сети.",
        ) {
            Chips(listOf(10, 30, 50, 100), chapterLimit, { "$it" }) {
                chapterLimit = it
                app.ui.setInt(UiPrefs.CHAPTER_CACHE_COUNT, it)
                clear("лишние главы") { app.chapterTextCache.trim() }
            }
            OutlinedButton(onClick = { clear("кэш глав") { app.chapterTextCache.clear() } }) { Text("Очистить") }
        }
        Block(title = "Загрузки", value = bytes(i.downloads), note = "Скачанные главы для чтения без сети; удаляются по тайтлам.") {
            OutlinedButton(onClick = { nav.navigate("downloads") }) { Text("Открыть загрузки") }
        }
        if (i.log > 0) {
            Block(title = "Журнал", value = bytes(i.log), note = null) {
                OutlinedButton(onClick = { clear("журнал") { AppLog.clear() } }) { Text("Очистить") }
            }
        }
        Block(title = "Прочее", value = bytes(i.other), note = "Справочники фильтров и данные окна входа.") {
            OutlinedButton(onClick = { clear("справочники") { File(app.filesDir, "constants_site3.json").delete() } }) { Text("Очистить справочники") }
        }
    }
}

@Composable
private fun Block(title: String, value: String, note: String?, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        content()
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
}

@Composable
private fun Chips(values: List<Int>, selected: Int, label: (Int) -> String, onPick: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        values.forEach { v -> FilterChip(selected = v == selected, onClick = { onPick(v) }, label = { Text(label(v)) }) }
    }
}

private fun File.sizeDeep(): Long = if (!exists()) 0L else walkTopDown().filter { it.isFile }.sumOf { it.length() }

private fun bytes(b: Long): String = when {
    b >= 1024L * 1024 * 1024 -> "%.1f ГБ".format(b / (1024.0 * 1024 * 1024))
    b >= 1024L * 1024 -> "%.1f МБ".format(b / (1024.0 * 1024))
    b >= 1024 -> "${b / 1024} КБ"
    else -> "$b Б"
}
