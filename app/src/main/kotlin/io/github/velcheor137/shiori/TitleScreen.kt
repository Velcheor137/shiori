package io.github.velcheor137.shiori

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import io.github.velcheor137.shiori.core.common.AppLog
import io.github.velcheor137.shiori.data.libsocial.Branch
import io.github.velcheor137.shiori.data.libsocial.ChapterEntry
import io.github.velcheor137.shiori.data.libsocial.TitleDetails
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class TitleViewModel(private val app: ShioriApp, private val slug: String) : ViewModel() {
    data class Data(val title: TitleDetails, val chapters: List<ChapterEntry>, val branches: List<Branch>, val branchId: Long)

    val state = MutableStateFlow<Load<Data>>(Load.Loading)

    init {
        load()
    }

    fun load() {
        state.value = Load.Loading
        viewModelScope.launch {
            runCatching {
                val titleJob = async { app.api.title(slug) }
                val chapters = app.api.chapters(slug).also { app.chapterCache[slug] = it }
                val title = titleJob.await().also { app.titleCache[slug] = it.card }
                val branches = branchesOf(title, chapters)
                val branchId = resolveBranch(title, branches)
                app.branchLabels[slug] = branchInfos(chapters, title).associate { it.branchId to it.name }
                AppLog.i("TITLE", "Тайтл ${title.card.id} «${title.card.name}»: глав ${chapters.size}, переводов ${branches.size}, выбран перевод $branchId")
                Data(title, chapters, branches, branchId)
            }.onSuccess { state.value = Load.Ready(it) }
                .onFailure {
                    AppLog.e("TITLE", "Тайтл $slug не загрузился", it)
                    state.value = Load.Error(it.userMessage())
                }
        }
    }

    /** Порядок (§10.3 плана): явный выбор → перевод из прогресса → основной у сайта → где больше глав. Без автосмены потом. */
    private suspend fun resolveBranch(title: TitleDetails, branches: List<Branch>): Long {
        val ids = branches.map { it.branchId }
        app.store.pinnedBranch(title.card.id)?.takeIf { it in ids }?.let { return it }
        app.store.progress(title.card.id)?.branchId?.takeIf { it in ids }?.let { return it }
        if (branches.size > 1) app.api.defaultBranchId(title.card.id)?.takeIf { it in ids }?.let { return it }
        return branches.maxByOrNull { it.chapterCount }?.branchId ?: 0L
    }

    fun pin(branchId: Long) {
        val d = (state.value as? Load.Ready)?.value ?: return
        app.store.pinBranch(d.title.card.id, branchId)
        AppLog.i("TITLE", "Перевод закреплён: $branchId")
        state.value = Load.Ready(d.copy(branchId = branchId))
    }

    private fun branchesOf(title: TitleDetails, chapters: List<ChapterEntry>): List<Branch> {
        val counts = chapters.flatMap { c -> c.versions.map { it.branchId } }.groupingBy { it }.eachCount()
        return counts.map { (id, count) ->
            val teams = title.teams.filter { (it.branchId ?: 0L) == id }.map { it.name }
            val fromChapters = chapters.asSequence().flatMap { it.versions.asSequence() }.firstOrNull { it.branchId == id }
            val label = teams.ifEmpty { fromChapters?.teamNames.orEmpty() }.joinToString(", ")
                .ifBlank { fromChapters?.uploader?.let { "Загрузил $it" } ?: "Перевод без подписи" }
            Branch(id, label, count)
        }.sortedByDescending { it.chapterCount }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TitleScreen(nav: NavController, slug: String) {
    val app = LocalContext.current.shiori
    val vm: TitleViewModel = viewModel(key = "title-$slug") { TitleViewModel(app, slug) }
    val state by vm.state.collectAsStateWithLifecycle()
    val allProgress by app.store.progress.collectAsStateWithLifecycle()

    when (val s = state) {
        Load.Loading -> Loading()
        is Load.Error -> ErrorBox(s.message, vm::load)
        is Load.Ready -> {
            val d = s.value
            val progress = allProgress.firstOrNull { it.titleId == d.title.card.id && it.branchId == d.branchId }
            val branchChapters = remember(d.chapters, d.branchId) { d.chapters.filter { it.version(d.branchId) != null } }
            val infos = remember(d.chapters) { branchInfos(d.chapters, d.title) }
            val infoMap = remember(infos) { infos.associateBy { it.branchId } }
            var tab by rememberSaveable { mutableStateOf(0) }
            var descending by remember { mutableStateOf(app.ui.bool(UiPrefs.CHAPTERS_DESC, true)) }
            var query by rememberSaveable { mutableStateOf("") }
            var filter by remember(d.title.card.id) { mutableStateOf(app.ui.chapterFilter(d.title.card.id)) }
            val context = LocalContext.current
            val queue by app.store.downloads.collectAsStateWithLifecycle()
            val downloaded = remember(d.title.card.id, allProgress, queue) { DownloadFiles.downloadedIds(context, d.title.card.id) }
            val base = remember(d.chapters, filter) { if (filter == null) d.chapters else d.chapters.filter { it.version(filter!!) != null } }
            val ordered = if (descending) base.reversed() else base
            val shown = remember(ordered, query) { filterChapters(ordered, query) }
            val list = rememberLazyListState()
            val scope = rememberCoroutineScope()
            val currentIndex = progress?.let { p -> shown.indexOfFirst { it.volume == p.volume && it.number == p.number } } ?: -1

            fun open(c: ChapterEntry, branch: Long = d.branchId, restore: Boolean = false) {
                // Другой перевод — только по явному выбору пользователя: он становится закреплённым.
                if (branch != d.branchId) vm.pin(branch)
                app.store.pinBranch(d.title.card.id, branch)
                nav.navigate(Routes.reader(slug, branch, c.volume, c.number, restore))
            }

            LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
                item { Header(d, onBack = { nav.popBackStack() }) }
                item {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        if (progress != null) {
                            Button(onClick = { nav.navigate(Routes.reader(slug, progress.branchId, progress.volume, progress.number, restore = true)) }, modifier = Modifier.fillMaxWidth()) {
                                Text("Продолжить: ${progress.chapterLabel}")
                            }
                        } else if (branchChapters.isNotEmpty()) {
                            Button(onClick = { open(branchChapters.first()) }, modifier = Modifier.fillMaxWidth()) { Text("Начать читать") }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Читаю перевод: ${infoMap[d.branchId]?.name ?: "—"}",
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            TextButton(onClick = { app.store.pinBranch(d.title.card.id, d.branchId); nav.navigate(Routes.download(slug)) }) {
                                Icon(AppIcons.Download, null, Modifier.size(18.dp))
                                Text(" Скачать")
                            }
                        }
                    }
                }
                item {
                    PrimaryTabRow(selectedTabIndex = tab) {
                        Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("О тайтле") })
                        Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Главы ${d.chapters.size}") })
                    }
                }
                if (tab == 0) {
                    item { About(d.title) }
                } else {
                    item { TeamChips(infos, pinned = d.branchId, filter = filter) { filter = it; app.ui.setChapterFilter(d.title.card.id, it) } }
                    item {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = { descending = !descending; app.ui.setBool(UiPrefs.CHAPTERS_DESC, descending) }) { Text(if (descending) "Сначала новые ↓" else "Сначала старые ↑") }
                            if (currentIndex >= 0) {
                                TextButton(onClick = { scope.launch { list.animateScrollToItem(currentIndex + 6) } }) { Text("К текущей главе") }
                            }
                        }
                    }
                    item {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text("Поиск главы: номер или название") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                    chapterTree(
                        chapters = shown,
                        infos = infoMap,
                        pinned = d.branchId,
                        filter = filter,
                        current = progress?.let { it.volume to it.number },
                        downloaded = downloaded,
                        onOpen = { c, b -> open(c, b, restore = progress != null && b == progress.branchId && c.volume == progress.volume && c.number == progress.number) },
                        onDownload = { c, b ->
                            val v = c.version(b) ?: return@chapterTree
                            Downloader.enqueue(context, listOf(DownloadTask(d.title.card.id, slug, d.title.card.name, b, c.volume, c.number, v.versionId, withImages = true)), wifiOnly = false)
                            android.widget.Toast.makeText(context, "Глава добавлена в загрузки", android.widget.Toast.LENGTH_SHORT).show()
                        },
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun Header(d: TitleViewModel.Data, onBack: () -> Unit) {
    val t = d.title
    Column {
        TextButton(onClick = onBack, modifier = Modifier.padding(start = 4.dp)) { Text("← Назад") }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Cover(t.card.cover, 110.dp, 158.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(t.card.name, fontFamily = TitleFont, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 27.sp)
                t.card.altName?.takeIf { it != t.card.name }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(6.dp))
                val meta = listOfNotNull(t.card.type, t.releaseYear, t.card.rating?.let { "★ $it" }).joinToString(" · ")
                if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodyMedium)
                t.card.status?.let { Text("Статус: $it", style = MaterialTheme.typography.bodySmall) }
                t.translationStatus?.let { Text("Перевод: $it", style = MaterialTheme.typography.bodySmall) }
                t.ageRestriction?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
private fun BranchPicker(branches: List<Branch>, selected: Long, onPick: (Long) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = branches.firstOrNull { it.branchId == selected }
    Box(Modifier.padding(vertical = 6.dp)) {
        TextButton(onClick = { open = true }, enabled = branches.size > 1) {
            Text("Перевод: ${current?.label ?: "—"}" + if (branches.size > 1) " ▾" else "")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            branches.forEach { b ->
                DropdownMenuItem(
                    text = { Text("${b.label} · ${b.chapterCount} гл." + if (b.branchId == selected) " ✓" else "") },
                    onClick = { open = false; onPick(b.branchId) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun About(t: TitleDetails) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (t.summary.isNotBlank()) Text(t.summary, style = MaterialTheme.typography.bodyMedium, lineHeight = 21.sp)
        if (t.genres.isNotEmpty() || t.tags.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (t.genres + t.tags).forEach { AssistChip(onClick = {}, label = { Text(it, fontSize = 12.sp) }) }
            }
        }
        if (t.authors.isNotEmpty()) Text("Автор: " + t.authors.joinToString(), style = MaterialTheme.typography.bodySmall)
        if (t.artists.isNotEmpty()) Text("Художник: " + t.artists.joinToString(), style = MaterialTheme.typography.bodySmall)
        if (t.teams.isNotEmpty()) Text("Переводчики: " + t.teams.joinToString { it.name }, style = MaterialTheme.typography.bodySmall)
        t.chapterCount?.let { Text("Глав на сайте: $it", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun ChapterRow(c: ChapterEntry, createdAt: String?, current: Boolean, locked: Boolean, downloaded: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    c.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
            if (downloaded) Icon(AppIcons.Download, "Скачано", Modifier.size(16.dp).padding(end = 4.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            if (locked) Text("платная ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            createdAt?.take(10)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    }
}

