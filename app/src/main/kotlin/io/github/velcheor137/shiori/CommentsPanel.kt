package io.github.velcheor137.shiori

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import io.github.velcheor137.shiori.core.common.AppLog
import io.github.velcheor137.shiori.data.libsocial.ChapterEntry
import io.github.velcheor137.shiori.data.libsocial.Comment
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Комментарии главы со всех переводов (§10.4). Комментарии привязаны к версии главы конкретного перевода,
 * поэтому грузим каждую версию отдельно (не более 2 запросов одновременно) и сливаем по id (новые сверху).
 * Элемент показывается, только если он не старше «головы» каждой ещё не исчерпанной ветки — так общий
 * порядок верный, а не склейка первых страниц.
 */
class CommentsViewModel(private val app: ShioriApp, private val sources: List<Source>) : ViewModel() {
    data class Source(val branchId: Long, val postId: Long, val label: String)

    data class BranchState(val source: Source, val page: Int = 0, val hasNext: Boolean = true, val roots: List<Comment> = emptyList(), val replies: List<Comment> = emptyList(), val error: String? = null)

    data class Tagged(val comment: Comment, val labels: List<String>, val branchIds: Set<Long>)

    val branches = MutableStateFlow(sources.map { BranchState(it) })
    val loading = MutableStateFlow(false)
    private val semaphore = Semaphore(2)

    init {
        loadMore()
    }

    fun loadMore(onlyBranch: Long? = null) {
        if (loading.value) return
        loading.value = true
        viewModelScope.launch {
            val current = branches.value
            val cutoffIds = current.filter { it.hasNext && it.error == null && (onlyBranch == null || it.source.branchId == onlyBranch) }
            // Догружаем ветки, чья «голова» сейчас ограничивает показ (или все при первом заходе).
            val targets = if (current.all { it.page == 0 }) cutoffIds else cutoffIds.filter { it.roots.lastOrNull()?.id == cutoffIds.mapNotNull { b -> b.roots.lastOrNull()?.id }.maxOrNull() || it.roots.isEmpty() }
            targets.map { b ->
                async {
                    semaphore.withPermit {
                        runCatching { app.api.comments(b.source.postId, "chapter", b.page + 1) }
                            .fold(
                                onSuccess = { p -> b.copy(page = b.page + 1, hasNext = p.hasNext && p.roots.isNotEmpty(), roots = b.roots + p.roots, replies = b.replies + p.replies, error = null) },
                                onFailure = { e -> AppLog.w("COMMENTS", "Комментарии перевода ${b.source.label} не загрузились", e); b.copy(error = e.userMessage()) },
                            )
                    }
                }
            }.awaitAll().forEach { updated -> branches.value = branches.value.map { if (it.source.branchId == updated.source.branchId) updated else it } }
            AppLog.i("COMMENTS", branches.value.joinToString { "${it.source.label}: ${it.roots.size}${if (it.hasNext) "+" else ""}" })
            loading.value = false
        }
    }

    fun retry(branchId: Long) {
        branches.value = branches.value.map { if (it.source.branchId == branchId) it.copy(error = null) else it }
        loadMore(branchId)
    }

    companion object {
        /** Слияние с дедупликацией: один и тот же комментарий в нескольких ветках помечается как общий. */
        fun merge(states: List<BranchState>, filter: Long?): Pair<List<Tagged>, Boolean> {
            val used = states.filter { filter == null || it.source.branchId == filter }
            val byId = LinkedHashMap<Long, Tagged>()
            used.forEach { b ->
                b.roots.forEach { c ->
                    val prev = byId[c.id]
                    byId[c.id] = if (prev == null) Tagged(c, listOf(b.source.label), setOf(b.source.branchId))
                    else prev.copy(labels = prev.labels + b.source.label, branchIds = prev.branchIds + b.source.branchId)
                }
            }
            val open = used.filter { it.hasNext && it.error == null }
            val cutoff = open.mapNotNull { it.roots.lastOrNull()?.id }.maxOrNull()
            val sorted = byId.values.sortedByDescending { it.comment.id }
            val visible = if (cutoff == null) sorted else sorted.filter { it.comment.id >= cutoff }
            return visible to open.isNotEmpty()
        }
    }
}

@Composable
fun CommentsPanel(slug: String, entry: ChapterEntry, currentBranch: Long, currentChapterId: Long) {
    val app = LocalContext.current.shiori
    val labels = app.branchLabels[slug].orEmpty()
    val sources = remember(entry) {
        entry.versions.map { v ->
            val label = labels[v.branchId] ?: v.teamNames.joinToString().ifBlank { v.uploader ?: "Перевод без подписи" }
            val postId = if (v.branchId == currentBranch && currentChapterId != 0L) currentChapterId else v.versionId
            CommentsViewModel.Source(v.branchId, postId, label)
        }
    }
    val vm: CommentsViewModel = viewModel(key = "comments-${entry.apiId}") { CommentsViewModel(app, sources) }
    val branches by vm.branches.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    var filter by remember { androidx.compose.runtime.mutableStateOf<Long?>(null) }
    val (items, more) = remember(branches, filter) { CommentsViewModel.merge(branches, filter) }
    val repliesByRoot = remember(branches) { branches.flatMap { it.replies }.distinctBy { it.id }.groupBy { it.rootId ?: it.parentId ?: 0L } }
    val expanded = remember { mutableStateMapOf<Long, Boolean>() }

    Column(Modifier.fillMaxWidth().heightIn(max = 680.dp)) {
        Text("Комментарии · ${entry.label}", Modifier.padding(horizontal = 16.dp), fontWeight = FontWeight.SemiBold)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("Все переводы") })
            if (sources.size > 1) FilterChip(selected = filter == currentBranch, onClick = { filter = currentBranch }, label = { Text("Текущий") })
            if (sources.size > 1) sources.forEach { s -> FilterChip(selected = filter == s.branchId, onClick = { filter = s.branchId }, label = { Text(s.label) }) }
        }
        branches.filter { it.error != null && (filter == null || it.source.branchId == filter) }.forEach { b ->
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${b.source.label}: ${b.error}", Modifier.weight(1f), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { vm.retry(b.source.branchId) }) { Text("Повторить") }
            }
        }
        LazyColumn {
            if (items.isEmpty() && !loading) item { Text("Комментариев пока нет", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(items, key = { it.comment.id }) { t ->
                val tag = when {
                    t.branchIds.size > 1 -> "Общий"
                    sources.size > 1 -> t.labels.first()
                    else -> null
                }
                CommentItem(t.comment, tag, indent = false)
                val replies = repliesByRoot[t.comment.id].orEmpty().sortedBy { it.id }
                if (replies.isNotEmpty()) {
                    if (expanded[t.comment.id] == true) {
                        replies.forEach { r -> CommentItem(r, null, indent = true) }
                    } else {
                        TextButton(onClick = { expanded[t.comment.id] = true }, modifier = Modifier.padding(start = 52.dp)) { Text("Показать ответы: ${replies.size}") }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }
            item {
                if (loading) Loading(Modifier.fillMaxWidth().padding(16.dp))
                else if (more) OutlinedButton(onClick = { vm.loadMore() }, modifier = Modifier.fillMaxWidth().padding(16.dp)) { Text("Загрузить ещё") }
            }
        }
    }
}

@Composable
private fun CommentItem(c: Comment, tag: String?, indent: Boolean) {
    Row(Modifier.fillMaxWidth().padding(start = if (indent) 52.dp else 16.dp, end = 16.dp, top = 10.dp, bottom = 6.dp)) {
        if (c.avatar != null) {
            AsyncImage(c.avatar, null, Modifier.size(32.dp).clip(CircleShape), contentScale = ContentScale.Crop)
        } else {
            Text(c.author.take(1).uppercase(), Modifier.size(32.dp).padding(top = 6.dp), fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.author, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                tag?.let { Text("  · $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
            }
            c.createdAt?.let { Text(it.take(16).replace('T', ' '), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(c.text, style = MaterialTheme.typography.bodyMedium)
            val up = c.votesUp; val down = c.votesDown
            if (up != null || down != null) Text("▲ ${up ?: 0}   ▼ ${down ?: 0}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
