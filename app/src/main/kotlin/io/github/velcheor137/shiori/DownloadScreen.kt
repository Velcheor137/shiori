package io.github.velcheor137.shiori

import android.Manifest
import android.os.Build
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import io.github.velcheor137.shiori.engine.ranges.ChapterRanges

/** Менеджер загрузок тайтла (§10.8): закреплённый перевод, диапазоны «1-100, 155, т3», предпросмотр, очередь. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DownloadScreen(nav: NavController, slug: String) {
    val context = LocalContext.current
    val app = context.shiori
    val title = app.titleCache[slug]
    val chapters = app.chapterCache[slug]
    if (title == null || chapters == null) {
        ErrorBox("Откройте карточку тайтла, затем менеджер загрузок.", null)
        return
    }
    val branchId = app.store.pinnedBranch(title.id) ?: chapters.firstOrNull()?.versions?.firstOrNull()?.branchId ?: 0L
    val branchLabel = app.branchLabels[slug]?.get(branchId) ?: "перевод $branchId"
    val branchChapters = remember(chapters, branchId) { chapters.filter { it.version(branchId) != null } }
    val queue by app.store.downloads.collectAsStateWithLifecycle()
    var refresh by remember { mutableIntStateOf(0) }
    val downloaded = remember(queue, refresh) { DownloadFiles.downloadedIds(context, title.id) }
    var input by rememberSaveable { mutableStateOf("") }
    val ui = LocalContext.current.shiori.ui
    var withImages by remember { mutableStateOf(ui.bool(UiPrefs.DL_IMAGES, true)) }
    var wifiOnly by remember { mutableStateOf(ui.bool(UiPrefs.DL_WIFI, false)) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    val parsed = remember(input) { ChapterRanges.parse(input) }
    val resolved = remember(parsed, branchChapters) {
        ChapterRanges.resolve(parsed, branchChapters.map { ChapterRanges.Entry(it, it.volume, it.number) })
    }
    val selected = resolved.selected
    val paid = selected.count { it.version(branchId)?.isOpen == false }
    val already = selected.count { it.version(branchId)!!.versionId in downloaded }
    val toLoad = selected.filter { val v = it.version(branchId)!!; v.isOpen && v.versionId !in downloaded }
    val progress = app.store.progress(title.id)?.takeIf { it.branchId == branchId }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { nav.popBackStack() }) { Text("← Назад") }
        Text("Загрузка глав", fontFamily = TitleFont, fontWeight = FontWeight.SemiBold, fontSize = 22.sp)
        Text(title.name, style = MaterialTheme.typography.bodyMedium)
        Text("Перевод: $branchLabel · глав ${branchChapters.size} · скачано ${downloaded.size}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text("Главы: 1-100, 120-150, 155, т3, т2:5-10, все") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            isError = parsed.errors.isNotEmpty(),
        )
        parsed.errors.forEach { Text(it.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { input = "все" }) { Text("Все") }
            if (progress != null) {
                val rest = branchChapters.dropWhile { !(it.volume == progress.volume && it.number == progress.number) }
                if (rest.isNotEmpty()) OutlinedButton(onClick = { input = "${rest.first().number}-${rest.last().number}" }) { Text("С текущей до конца") }
                OutlinedButton(onClick = { input = "т${progress.volume}" }) { Text("Текущий том") }
            }
            val notDownloaded = branchChapters.filter { it.version(branchId)!!.versionId !in downloaded }
            if (notDownloaded.isNotEmpty() && notDownloaded.size < branchChapters.size) {
                OutlinedButton(onClick = { input = notDownloaded.joinToString(", ") { "т${it.volume}:${it.number}" } }) { Text("Нескачанные") }
            }
        }
        if (input.isNotBlank()) {
            Text(
                "Выбрано ${selected.size}: к загрузке ${toLoad.size}, уже скачано $already" + (if (paid > 0) ", платных $paid (не скачиваются)" else ""),
                fontWeight = FontWeight.Medium,
            )
            if (resolved.missing.isNotEmpty()) Text("Нет в этом переводе: ${resolved.missing.joinToString()}. Другой перевод не подставляется.", style = MaterialTheme.typography.bodySmall)
            if (resolved.ambiguous.isNotEmpty()) Text("Номера есть в нескольких томах: ${resolved.ambiguous.joinToString()} — взяты все. Уточнить: «т2:5».", style = MaterialTheme.typography.bodySmall)
        }
        SwitchRow("С иллюстрациями", withImages) { withImages = it; ui.setBool(UiPrefs.DL_IMAGES, it) }
        SwitchRow("Только по Wi‑Fi", wifiOnly) { wifiOnly = it; ui.setBool(UiPrefs.DL_WIFI, it) }
        Button(
            enabled = toLoad.isNotEmpty(),
            onClick = {
                if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                Downloader.enqueue(
                    context,
                    toLoad.map { c ->
                        DownloadTask(title.id, slug, title.name, branchId, c.volume, c.number, c.version(branchId)!!.versionId, withImages)
                    },
                    wifiOnly,
                )
                input = ""
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Скачать ${toLoad.size} глав") }

        val mine = queue.filter { it.titleId == title.id }
        val active = mine.count { it.state == DownloadTask.State.Queued || it.state == DownloadTask.State.Running }
        val failed = mine.count { it.state == DownloadTask.State.Failed }
        if (mine.isNotEmpty()) {
            HorizontalDivider()
            Text("Очередь: в работе $active, готово ${mine.count { it.state == DownloadTask.State.Done }}, ошибок $failed")
            if (active > 0) LinearProgressIndicator(progress = { 1f - active.toFloat() / mine.size }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (active > 0) OutlinedButton(onClick = { Downloader.cancel(context) }) { Text("Отменить") }
                if (failed > 0) OutlinedButton(onClick = { Downloader.retryFailed(context) }) { Text("Повторить ошибки") }
                if (active == 0) OutlinedButton(onClick = { Downloader.clearFinished(context) }) { Text("Очистить список") }
            }
            LazyColumn(Modifier.weight(1f, fill = false)) {
                items(mine.filter { it.state != DownloadTask.State.Done }.take(200), key = { it.key }) { t ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Том ${t.volume} Глава ${t.number}", Modifier.weight(1f))
                        Text(
                            when (t.state) {
                                DownloadTask.State.Queued -> "в очереди"
                                DownloadTask.State.Running -> "загрузка…"
                                DownloadTask.State.Done -> "готово"
                                DownloadTask.State.Failed -> t.error ?: "ошибка"
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        if (downloaded.isNotEmpty()) {
            TextButton(onClick = { DownloadFiles.deleteTitle(context, title.id); refresh++ }) {
                Text("Удалить скачанное (${Formatter.formatShortFileSize(context, DownloadFiles.sizeBytes(context, title.id))})")
            }
        }
    }
}

/** Все загрузки: размер по тайтлам и очередь. */
@Composable
fun DownloadsOverviewScreen(nav: NavController) {
    val context = LocalContext.current
    val app = context.shiori
    val queue by app.store.downloads.collectAsStateWithLifecycle()
    var refresh by remember { mutableIntStateOf(0) }
    val titles = remember(queue, refresh) {
        java.io.File(context.filesDir, "downloads").listFiles()?.mapNotNull { dir ->
            val id = dir.name.toLongOrNull() ?: return@mapNotNull null
            val name = queue.firstOrNull { it.titleId == id }?.titleName ?: app.titleCache.values.firstOrNull { it.id == id }?.name ?: "Тайтл $id"
            Triple(id, name, DownloadFiles.downloadedIds(context, id).size to DownloadFiles.sizeBytes(context, id))
        }.orEmpty()
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { nav.popBackStack() }) { Text("← Назад") }
        Text("Загрузки", fontFamily = TitleFont, fontWeight = FontWeight.SemiBold, fontSize = 22.sp)
        val active = queue.count { it.state == DownloadTask.State.Queued || it.state == DownloadTask.State.Running }
        if (active > 0) {
            Text("В очереди: $active")
            OutlinedButton(onClick = { Downloader.cancel(context) }) { Text("Отменить всё") }
        }
        if (titles.isEmpty()) Text("Скачанных глав нет. Скачать можно из карточки тайтла.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        titles.forEach { (id, name, info) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(name, fontWeight = FontWeight.Medium)
                    Text("Глав: ${info.first} · ${Formatter.formatShortFileSize(context, info.second)}", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { DownloadFiles.deleteTitle(context, id); refresh++ }) { Text("Удалить") }
            }
            HorizontalDivider()
        }
    }
}
