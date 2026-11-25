package io.github.velcheor137.shiori

import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import io.github.velcheor137.shiori.data.auth.SessionState
import io.github.velcheor137.shiori.data.libsocial.ChapterEntry
import io.github.velcheor137.shiori.data.libsocial.Comment
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

enum class CommentSort(val label: String, val apiValue: String) { New("Новые", "id"), Popular("Популярные", "votes_up") }

/**
 * Комментарии главы со всех переводов (§10.4). Каждая версия главы грузится отдельно (≤ 2 запроса
 * одновременно), списки сливаются по ключу сортировки. Элемент показывается, только если он не «ниже»
 * последнего загруженного в каждой ещё не исчерпанной ветке — общий порядок верный, а не склейка страниц.
 */
class CommentsViewModel(private val app: ShioriApp, private val sources: List<Source>) : ViewModel() {
    data class Source(val branchId: Long, val postId: Long, val label: String)

    data class BranchState(
        val source: Source,
        val page: Int = 0,
        val hasNext: Boolean = true,
        val roots: List<Comment> = emptyList(),
        val replies: List<Comment> = emptyList(),
        val error: String? = null,
    )

    data class Tagged(val comment: Comment, val labels: List<String>, val branchIds: Set<Long>)

    val branches = MutableStateFlow(sources.map { BranchState(it) })
    val loading = MutableStateFlow(false)
    val sort = MutableStateFlow(CommentSort.New)
    private val semaphore = Semaphore(2)

    init {
        loadMore()
    }

    fun setSort(s: CommentSort) {
        if (sort.value == s) return
        sort.value = s
        branches.value = sources.map { BranchState(it) }
        loading.value = false
        loadMore()
    }

    fun loadMore() {
        if (loading.value) return
        loading.value = true
        viewModelScope.launch {
            val open = branches.value.filter { it.hasNext && it.error == null }
            val first = open.all { it.page == 0 }
            // Догружаем ветку, чья последняя запись сейчас ограничивает показ (или все при первом заходе).
            val limiting = open.filter { it.roots.isNotEmpty() }.maxWithOrNull(compareBy { key(it.roots.last()) })
            val targets = if (first) open else open.filter { it.roots.isEmpty() || it == limiting }
            val sortNow = sort.value
            targets.map { b ->
                async {
                    semaphore.withPermit {
                        runCatching { app.api.comments(b.source.postId, "chapter", b.page + 1, sortNow.apiValue) }
                            .fold(
                                onSuccess = { p -> b.copy(page = b.page + 1, hasNext = p.hasNext && p.roots.isNotEmpty(), roots = b.roots + p.roots, replies = b.replies + p.replies, error = null) },
                                onFailure = { e -> AppLog.w("COMMENTS", "Комментарии перевода ${b.source.label} не загрузились", e); b.copy(error = e.userMessage()) },
                            )
                    }
                }
            }.awaitAll().forEach { u -> if (sort.value == sortNow) branches.value = branches.value.map { if (it.source.branchId == u.source.branchId) u else it } }
            loading.value = false
        }
    }

    fun retry(branchId: Long) {
        branches.value = branches.value.map { if (it.source.branchId == branchId) it.copy(error = null) else it }
        loadMore()
    }

    /** Плюс/минус с мгновенным обновлением; повторное нажатие снимает голос (как на сайте). */
    fun vote(c: Comment, value: Int, onError: (String) -> Unit) {
        val newUser = if (c.userVote == value) null else value
        val up = (c.votesUp ?: 0) - (if (c.userVote == 1) 1 else 0) + (if (newUser == 1) 1 else 0)
        val down = (c.votesDown ?: 0) - (if (c.userVote == 0) 1 else 0) + (if (newUser == 0) 1 else 0)
        replace(c.copy(userVote = newUser, votesUp = up, votesDown = down))
        viewModelScope.launch {
            runCatching { app.api.voteComment(c.id, value) }.onFailure {
                replace(c)
                onError(it.userMessage())
            }
        }
    }

    fun send(text: String, replyTo: Comment?, defaultPostId: Long, onDone: (String?) -> Unit) {
        val postId = replyTo?.postId ?: defaultPostId
        viewModelScope.launch {
            runCatching { app.api.postComment(postId, "chapter", text, replyTo) }
                .onSuccess { created ->
                    if (created != null) {
                        branches.value = branches.value.map { b ->
                            if (b.source.postId != postId) b
                            else if (replyTo == null) b.copy(roots = listOf(created) + b.roots) else b.copy(replies = b.replies + created)
                        }
                    }
                    onDone(null)
                }
                .onFailure { onDone(it.userMessage()) }
        }
    }

    private fun replace(c: Comment) {
        branches.value = branches.value.map { b ->
            b.copy(roots = b.roots.map { if (it.id == c.id) c else it }, replies = b.replies.map { if (it.id == c.id) c else it })
        }
    }

    private fun key(c: Comment): Long = if (sort.value == CommentSort.Popular) (c.votesUp ?: 0).toLong() else c.id

    fun merged(filter: Long?): Pair<List<Tagged>, Boolean> {
        val states = branches.value.filter { filter == null || it.source.branchId == filter }
        val byId = LinkedHashMap<Long, Tagged>()
        states.forEach { b ->
            b.roots.forEach { c ->
                val prev = byId[c.id]
                byId[c.id] = if (prev == null) Tagged(c, listOf(b.source.label), setOf(b.source.branchId))
                else prev.copy(comment = c, labels = prev.labels + b.source.label, branchIds = prev.branchIds + b.source.branchId)
            }
        }
        val open = states.filter { it.hasNext && it.error == null }
        val cutoff = open.mapNotNull { it.roots.lastOrNull()?.let(::key) }.maxOrNull()
        val sorted = byId.values.sortedWith(compareByDescending<Tagged> { key(it.comment) }.thenByDescending { it.comment.id })
        val visible = if (cutoff == null) sorted else sorted.filter { key(it.comment) >= cutoff }
        return visible to open.isNotEmpty()
    }
}

@Composable
fun CommentsPanel(slug: String, entry: ChapterEntry, currentBranch: Long, currentChapterId: Long) {
    val context = LocalContext.current
    val app = context.shiori
    val labels = app.branchLabels[slug].orEmpty()
    val sources = remember(entry) {
        entry.versions.map { v ->
            val label = labels[v.branchId] ?: v.teamNames.joinToString().ifBlank { v.uploader ?: "Перевод без подписи" }
            val postId = if (v.branchId == currentBranch && currentChapterId != 0L) currentChapterId else v.versionId
            CommentsViewModel.Source(v.branchId, postId, label)
        }
    }
    val currentPostId = sources.firstOrNull { it.branchId == currentBranch }?.postId ?: currentChapterId
    val vm: CommentsViewModel = viewModel(key = "comments-${entry.apiId}") { CommentsViewModel(app, sources) }
    val branches by vm.branches.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    val session by app.auth.state.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf<Long?>(null) }
    val (items, more) = remember(branches, filter, sort) { vm.merged(filter) }
    val repliesByRoot = remember(branches) { branches.flatMap { it.replies }.distinctBy { it.id }.groupBy { it.rootId ?: it.parentId ?: 0L } }
    val expanded = remember { mutableStateMapOf<Long, Boolean>() }
    var draft by remember { mutableStateOf("") }
    var replyTo by remember { mutableStateOf<Comment?>(null) }
    var sending by remember { mutableStateOf(false) }
    val authorized = session is SessionState.Authorized
    val toast: (String) -> Unit = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }

    fun vote(c: Comment, v: Int) = if (authorized) vm.vote(c, v, toast) else toast("Войдите в аккаунт, чтобы голосовать")

    Column(Modifier.fillMaxWidth().heightIn(max = 720.dp).imePadding()) {
        Text("Комментарии · ${entry.label}", Modifier.padding(horizontal = 16.dp), fontWeight = FontWeight.SemiBold)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CommentSort.entries.forEach { s -> FilterChip(selected = sort == s, onClick = { vm.setSort(s) }, label = { Text(s.label) }) }
            Spacer(Modifier.width(8.dp))
            FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("Все переводы") })
            if (sources.size > 1) {
                FilterChip(selected = filter == currentBranch, onClick = { filter = currentBranch }, label = { Text("Текущий") })
                sources.forEach { s -> FilterChip(selected = filter == s.branchId, onClick = { filter = s.branchId }, label = { Text(s.label) }) }
            }
        }
        branches.filter { it.error != null && (filter == null || it.source.branchId == filter) }.forEach { b ->
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${b.source.label}: ${b.error}", Modifier.weight(1f), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { vm.retry(b.source.branchId) }) { Text("Повторить") }
            }
        }
        LazyColumn(Modifier.weight(1f, fill = false)) {
            if (items.isEmpty() && !loading) item { Text("Комментариев пока нет", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(items, key = { it.comment.id }) { t ->
                val tag = when {
                    t.branchIds.size > 1 -> "Общий"
                    sources.size > 1 -> t.labels.first()
                    else -> null
                }
                CommentItem(t.comment, tag, indent = false, onVote = { v -> vote(t.comment, v) }, onReply = { replyTo = t.comment })
                val replies = repliesByRoot[t.comment.id].orEmpty().sortedBy { it.id }
                if (replies.isNotEmpty()) {
                    if (expanded[t.comment.id] == true) {
                        replies.forEach { r -> CommentItem(r, null, indent = true, onVote = { v -> vote(r, v) }, onReply = { replyTo = r }) }
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
        HorizontalDivider()
        if (!authorized) {
            Text("Чтобы написать комментарий, войдите в аккаунт (Профиль).", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
        } else {
            replyTo?.let { r ->
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Ответ для ${r.author}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    TextButton(onClick = { replyTo = null }) { Text("Отмена") }
                }
            }
            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = { Text(if (replyTo != null) "Ваш ответ" else "Написать комментарий") },
                    modifier = Modifier.weight(1f),
                    maxLines = 5,
                )
                IconButton(
                    enabled = draft.isNotBlank() && !sending,
                    onClick = {
                        sending = true
                        // Явное действие пользователя: комментарий уходит в версию главы текущего перевода (или к ответу).
                        val target = replyTo
                        vm.send(draft, target, currentPostId) { err ->
                            sending = false
                            if (err == null) {
                                draft = ""
                                target?.let { expanded[it.rootId ?: it.id] = true }
                                replyTo = null
                                toast("Комментарий отправлен")
                            } else {
                                toast("Не отправлено: $err")
                            }
                        }
                    },
                ) { Icon(Icons.AutoMirrored.Filled.Send, "Отправить") }
            }
        }
    }
}

@Composable
private fun CommentItem(c: Comment, tag: String?, indent: Boolean, onVote: (Int) -> Unit, onReply: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = if (indent) 52.dp else 16.dp, end = 8.dp, top = 10.dp)) {
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                val score = (c.votesUp ?: 0) - (c.votesDown ?: 0)
                IconButton(onClick = { onVote(1) }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.KeyboardArrowUp, "Плюс", tint = if (c.userVote == 1) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    "$score",
                    color = when {
                        score > 0 -> Color(0xFF4CAF50)
                        score < 0 -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    style = MaterialTheme.typography.labelLarge,
                )
                IconButton(onClick = { onVote(0) }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.KeyboardArrowDown, "Минус", tint = if (c.userVote == 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onReply) { Text("Ответить") }
            }
        }
    }
}
