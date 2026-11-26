package io.github.velcheor137.shiori

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
class CommentsViewModel(
    private val app: ShioriApp,
    private val sources: List<Source>,
    /** Комментарий, к которому нужно прокрутить (из уведомления), и корень его ветки. */
    private val focusComment: Long? = null,
    val focusRoot: Long? = null,
) : ViewModel() {
    private var focusPages = 0
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
    val sort = MutableStateFlow(CommentSort.entries.firstOrNull { it.name == app.ui.string(UiPrefs.COMMENTS_SORT, "") } ?: CommentSort.New)
    private val semaphore = Semaphore(2)

    init {
        loadMore()
    }

    fun setSort(s: CommentSort) {
        if (sort.value == s) return
        sort.value = s
        app.ui.setString(UiPrefs.COMMENTS_SORT, s.name)
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
            seekFocus()
        }
    }

    /**
     * Ищем ветку нужного комментария: при сортировке «Новые» догружаем страницы, пока не дойдём до её корня
     * (не более 15 страниц); если не нашли — подставляем ветку через `GET /api/comments/{id}`.
     */
    private fun seekFocus() {
        val root = focusRoot ?: return
        val states = branches.value
        if (states.any { b -> b.roots.any { it.id == root } }) return
        val open = states.filter { it.hasNext && it.error == null }
        val passed = open.isEmpty() || open.all { b -> (b.roots.lastOrNull()?.id ?: Long.MAX_VALUE) < root }
        if (!passed && sort.value == CommentSort.New && focusPages++ < 15) {
            loadMore()
            return
        }
        val target = focusComment ?: root
        val postId = sources.first().postId
        viewModelScope.launch {
            runCatching { app.api.commentThread(target, postId) }.onSuccess { t ->
                branches.value = branches.value.mapIndexed { i, b ->
                    if (i != 0) b else b.copy(roots = (t.roots + b.roots).distinctBy { it.id }, replies = (b.replies + t.replies).distinctBy { it.id })
                }
            }.onFailure { AppLog.w("COMMENTS", "Ветка комментария $target не загрузилась", it) }
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

/** Источники комментариев главы: все версии (переводы) главы; для текущего перевода — id загруженной главы. */
class ChapterComments(val key: String, val sources: List<CommentsViewModel.Source>, val currentBranch: Long, val currentPostId: Long)

@Composable
fun rememberChapterComments(slug: String, entry: ChapterEntry, currentBranch: Long, currentChapterId: Long): ChapterComments {
    val app = LocalContext.current.shiori
    return remember(slug, entry, currentBranch, currentChapterId) {
        val labels = app.branchLabels[slug] ?: branchInfos(app.chapterCache[slug].orEmpty()).associate { it.branchId to it.name }
        val sources = entry.versions.map { v ->
            val label = labels[v.branchId] ?: v.teamNames.joinToString().ifBlank { v.uploader ?: "Перевод без подписи" }
            val postId = if (v.branchId == currentBranch && currentChapterId != 0L) currentChapterId else v.versionId
            CommentsViewModel.Source(v.branchId, postId, label)
        }
        val currentPostId = sources.firstOrNull { it.branchId == currentBranch }?.postId ?: currentChapterId
        ChapterComments("comments-${entry.apiId}", sources, currentBranch, currentPostId)
    }
}

/** Один и тот же список для окна и для ленты под главой (общий ключ в пределах экрана читалки). */
@Composable
fun chapterCommentsVm(cc: ChapterComments): CommentsViewModel {
    val app = LocalContext.current.shiori
    return viewModel(key = cc.key) { CommentsViewModel(app, cc.sources) }
}

@Composable
fun CommentsPanel(slug: String, entry: ChapterEntry, currentBranch: Long, currentChapterId: Long) {
    val offline by LocalContext.current.shiori.offline.collectAsStateWithLifecycle()
    if (offline) {
        Box(Modifier.padding(16.dp)) { OfflineCommentsNote() }
        return
    }
    val cc = rememberChapterComments(slug, entry, currentBranch, currentChapterId)
    CommentsSheet(chapterCommentsVm(cc), "Комментарии · ${entry.label}", null, cc.sources, currentBranch, cc.currentPostId, null)
}

/** Состояние ленты комментариев под текстом главы (п. 1 этапа A): как в приложении RanobeLib, с подгрузкой. */
class InlineComments(
    val vm: CommentsViewModel,
    val cc: ChapterComments,
    val items: List<CommentsViewModel.Tagged>,
    val more: Boolean,
    val loading: Boolean,
    val sort: CommentSort,
    val branches: List<CommentsViewModel.BranchState>,
    val repliesByRoot: Map<Long, List<Comment>>,
    val filter: androidx.compose.runtime.MutableState<Long?>,
    val expanded: androidx.compose.runtime.snapshots.SnapshotStateMap<Long, Boolean>,
    val replyTo: androidx.compose.runtime.MutableState<Comment?>,
    val authorized: Boolean,
    val toast: (String) -> Unit,
)

@Composable
fun rememberInlineComments(cc: ChapterComments): InlineComments {
    val context = LocalContext.current
    val app = context.shiori
    val vm = chapterCommentsVm(cc)
    val branches by vm.branches.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    val session by app.auth.state.collectAsStateWithLifecycle()
    val filter = remember(cc.key) { mutableStateOf<Long?>(null) }
    val expanded = remember(cc.key) { mutableStateMapOf<Long, Boolean>() }
    val replyTo = remember(cc.key) { mutableStateOf<Comment?>(null) }
    val (items, more) = remember(branches, filter.value, sort) { vm.merged(filter.value) }
    val repliesByRoot = remember(branches) { branches.flatMap { it.replies }.distinctBy { it.id }.groupBy { it.rootId ?: it.parentId ?: 0L } }
    return InlineComments(
        vm, cc, items, more, loading, sort, branches, repliesByRoot, filter, expanded, replyTo,
        session is SessionState.Authorized,
    ) { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
}

/** Лента комментариев элементами общего списка читалки; новая страница грузится, когда низ ленты близко. */
fun androidx.compose.foundation.lazy.LazyListScope.inlineComments(c: InlineComments, tint: @Composable (@Composable () -> Unit) -> Unit) {
    fun vote(x: Comment, v: Int) = if (c.authorized) c.vm.vote(x, v, c.toast) else c.toast("Войдите в аккаунт, чтобы голосовать")
    item(key = "c-head") {
        tint {
            Column(Modifier.fillMaxWidth()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Text("Комментарии", Modifier.padding(top = 16.dp, bottom = 4.dp), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CommentSort.entries.forEach { s -> FilterChip(selected = c.sort == s, onClick = { c.vm.setSort(s) }, label = { Text(s.label) }) }
                    if (c.cc.sources.size > 1) {
                        Spacer(Modifier.width(8.dp))
                        FilterChip(selected = c.filter.value == null, onClick = { c.filter.value = null }, label = { Text("Все переводы") })
                        FilterChip(selected = c.filter.value == c.cc.currentBranch, onClick = { c.filter.value = c.cc.currentBranch }, label = { Text("Текущий") })
                    }
                }
                c.branches.filter { it.error != null && (c.filter.value == null || it.source.branchId == c.filter.value) }.forEach { b ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${b.source.label}: ${b.error}", Modifier.weight(1f), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { c.vm.retry(b.source.branchId) }) { Text("Повторить") }
                    }
                }
                if (c.authorized) {
                    CommentComposer(c.vm, c.replyTo.value, { c.replyTo.value = null }, c.cc.currentPostId, c.toast) { sent ->
                        sent?.let { c.expanded[it.rootId ?: it.id] = true }
                        c.replyTo.value = null
                    }
                } else {
                    Text("Чтобы написать комментарий, войдите в аккаунт (Профиль).", Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    items(c.items, key = { "c${it.comment.id}" }) { t ->
        tint {
            Column(Modifier.fillMaxWidth()) {
                val tag = when {
                    t.branchIds.size > 1 -> "Общий"
                    c.cc.sources.size > 1 -> t.labels.first()
                    else -> null
                }
                CommentItem(t.comment, tag, indent = false, edge = 0.dp, onVote = { v -> vote(t.comment, v) }, onReply = { c.replyTo.value = t.comment })
                val replies = c.repliesByRoot[t.comment.id].orEmpty().sortedBy { it.id }
                if (replies.isNotEmpty()) {
                    if (c.expanded[t.comment.id] == true) {
                        replies.forEach { r -> CommentItem(r, null, indent = true, edge = 0.dp, onVote = { v -> vote(r, v) }, onReply = { c.replyTo.value = r }) }
                    } else {
                        TextButton(onClick = { c.expanded[t.comment.id] = true }, modifier = Modifier.padding(start = 36.dp)) { Text("Показать ответы: ${replies.size}") }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }
        }
    }
    item(key = "c-tail") {
        tint {
            androidx.compose.runtime.LaunchedEffect(c.items.size, c.more, c.loading) { if (c.more && !c.loading) c.vm.loadMore() }
            Column(Modifier.fillMaxWidth().padding(bottom = 120.dp)) {
                when {
                    c.loading -> Loading(Modifier.fillMaxWidth().padding(16.dp))
                    c.items.isEmpty() -> Text("Комментариев пока нет", Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Поле ввода комментария/ответа. Отправка — только по явному нажатию пользователя. */
@Composable
private fun CommentComposer(
    vm: CommentsViewModel,
    replyTo: Comment?,
    cancelReply: () -> Unit,
    currentPostId: Long,
    toast: (String) -> Unit,
    onSent: (Comment?) -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    Column {
        replyTo?.let { r ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Ответ для ${r.author}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                TextButton(onClick = cancelReply) { Text("Отмена") }
            }
        }
        Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
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
                    // Комментарий уходит в версию главы текущего перевода (или к ответу).
                    val target = replyTo
                    vm.send(draft, target, currentPostId) { err ->
                        sending = false
                        if (err == null) {
                            draft = ""
                            onSent(target)
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

/**
 * Окно комментариев. [subtitle] — строка над окном (тайтл · глава), [focusComment] — комментарий, к которому
 * прокрутить и который подсветить (ветка ответов раскрывается).
 */
@Composable
fun CommentsSheet(
    vm: CommentsViewModel,
    header: String,
    subtitle: String?,
    sources: List<CommentsViewModel.Source>,
    currentBranch: Long,
    currentPostId: Long,
    focusComment: Long?,
) {
    val context = LocalContext.current
    val app = context.shiori
    val branches by vm.branches.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    val session by app.auth.state.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf<Long?>(null) }
    val (items, more) = remember(branches, filter, sort) { vm.merged(filter) }
    val repliesByRoot = remember(branches) { branches.flatMap { it.replies }.distinctBy { it.id }.groupBy { it.rootId ?: it.parentId ?: 0L } }
    val expanded = remember { mutableStateMapOf<Long, Boolean>() }
    var replyTo by remember { mutableStateOf<Comment?>(null) }
    val authorized = session is SessionState.Authorized
    val toast: (String) -> Unit = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }

    fun vote(c: Comment, v: Int) = if (authorized) vm.vote(c, v, toast) else toast("Войдите в аккаунт, чтобы голосовать")

    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    var focused by remember { mutableStateOf(false) }
    val focusRoot = vm.focusRoot
    androidx.compose.runtime.LaunchedEffect(items, focusRoot) {
        if (focusRoot == null || focused) return@LaunchedEffect
        val index = items.indexOfFirst { it.comment.id == focusRoot }
        if (index >= 0) {
            expanded[focusRoot] = true
            listState.animateScrollToItem(index)
            focused = true
        }
    }

    Column(Modifier.fillMaxWidth().heightIn(max = 720.dp).imePadding()) {
        subtitle?.let { Text(it, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
        Text(header, Modifier.padding(horizontal = 16.dp), fontWeight = FontWeight.SemiBold)
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
        LazyColumn(Modifier.weight(1f, fill = false), state = listState) {
            if (items.isEmpty() && !loading) item { Text("Комментариев пока нет", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(items, key = { it.comment.id }) { t ->
                val tag = when {
                    t.branchIds.size > 1 -> "Общий"
                    sources.size > 1 -> t.labels.first()
                    else -> null
                }
                CommentItem(t.comment, tag, indent = false, highlight = t.comment.id == focusComment, onVote = { v -> vote(t.comment, v) }, onReply = { replyTo = t.comment })
                val replies = repliesByRoot[t.comment.id].orEmpty().sortedBy { it.id }
                if (replies.isNotEmpty()) {
                    if (expanded[t.comment.id] == true) {
                        replies.forEach { r -> CommentItem(r, null, indent = true, highlight = r.id == focusComment, onVote = { v -> vote(r, v) }, onReply = { replyTo = r }) }
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
            Box(Modifier.padding(horizontal = 12.dp)) {
                CommentComposer(vm, replyTo, { replyTo = null }, currentPostId, toast) { sent ->
                    sent?.let { expanded[it.rootId ?: it.id] = true }
                    replyTo = null
                }
            }
        }
    }
}

@Composable
private fun CommentItem(c: Comment, tag: String?, indent: Boolean, highlight: Boolean = false, edge: androidx.compose.ui.unit.Dp = 16.dp, onVote: (Int) -> Unit, onReply: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (highlight) Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)) else Modifier)
            .padding(start = if (indent) edge + 36.dp else edge, end = if (edge == 0.dp) 0.dp else 8.dp, top = 10.dp),
    ) {
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
            CommentBody(c)
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

/** Тело комментария: абзацы текста и картинки (сайт присылает HTML с <img>, превью — webp). */
@Composable
private fun CommentBody(c: Comment) {
    if (c.blocks.isEmpty()) {
        if (c.text.isNotBlank()) Text(c.text, style = MaterialTheme.typography.bodyMedium)
        return
    }
    var full by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        c.blocks.forEach { b ->
            when (b) {
                is io.github.velcheor137.shiori.engine.document.Paragraph -> if (b.text.isNotBlank()) Text(b.text, style = MaterialTheme.typography.bodyMedium)
                is io.github.velcheor137.shiori.engine.document.ImageBlock -> AsyncImage(
                    model = b.url,
                    contentDescription = "Картинка в комментарии",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                        .clickable { full = b.url },
                )
                io.github.velcheor137.shiori.engine.document.Separator -> HorizontalDivider()
            }
        }
    }
    full?.let { url ->
        androidx.compose.ui.window.Dialog(onDismissRequest = { full = null }) {
            AsyncImage(url, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().clickable { full = null })
        }
    }
}

/** Заглушка вместо комментариев без сети. */
@Composable
fun OfflineCommentsNote() {
    Text(
        "Нет сети — комментарии появятся при подключении.",
        Modifier.fillMaxWidth().padding(vertical = 24.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
    )
}
