package io.github.velcheor137.shiori

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import io.github.velcheor137.shiori.data.auth.SessionState
import io.github.velcheor137.shiori.data.libsocial.LibSocialApi
import io.github.velcheor137.shiori.data.libsocial.Notification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Уведомления аккаунта: новые главы и ответы на комментарии (вкладки как на сайте). */
class NotificationsViewModel(private val api: LibSocialApi, private val ui: UiPrefs) : ViewModel() {
    enum class Category(val apiValue: String, val label: String) { All("all", "Все"), Chapters("chapter", "Главы"), Replies("comments", "Ответы") }

    data class State(
        val category: Category = Category.All,
        val unreadOnly: Boolean = true,
        val items: List<Notification> = emptyList(),
        val page: Int = 0,
        val hasNext: Boolean = true,
        val loading: Boolean = false,
        val error: String? = null,
    )

    val state = MutableStateFlow(
        State(
            category = Category.entries.firstOrNull { it.name == ui.string(UiPrefs.NOTIFY_CATEGORY, "") } ?: Category.All,
            unreadOnly = ui.bool(UiPrefs.NOTIFY_UNREAD, true),
        ),
    )

    init {
        loadMore()
    }

    fun select(c: Category) {
        ui.setString(UiPrefs.NOTIFY_CATEGORY, c.name)
        reset(state.value.copy(category = c))
    }

    fun setUnreadOnly(v: Boolean) {
        ui.setBool(UiPrefs.NOTIFY_UNREAD, v)
        reset(state.value.copy(unreadOnly = v))
    }

    private fun reset(s: State) {
        state.value = s.copy(items = emptyList(), page = 0, hasNext = true, error = null, loading = false)
        loadMore()
    }

    fun loadMore() {
        val s = state.value
        if (s.loading || !s.hasNext) return
        state.value = s.copy(loading = true, error = null)
        viewModelScope.launch {
            runCatching { api.notifications(s.category.apiValue, if (s.unreadOnly) "unread" else "all", s.page + 1) }
                .onSuccess { p ->
                    if (state.value.category != s.category || state.value.unreadOnly != s.unreadOnly) return@onSuccess
                    val known = state.value.items.map { it.id }.toSet()
                    state.value = state.value.copy(items = state.value.items + p.items.filter { it.id !in known }, page = s.page + 1, hasNext = p.hasNext, loading = false)
                }
                .onFailure {
                    AppLog.e("NOTIFY", "Уведомления не загрузились", it)
                    state.value = state.value.copy(loading = false, error = it.userMessage())
                }
        }
    }

    fun markRead(n: Notification) {
        if (n.isRead) return
        state.value = state.value.copy(items = state.value.items.map { if (it.id == n.id) it.copy(isRead = true) else it })
        viewModelScope.launch { api.markNotificationRead(n.id) }
    }

    fun markAllRead() {
        val cat = state.value.category
        viewModelScope.launch {
            if (api.markAllNotificationsRead(cat.apiValue)) reset(state.value)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(nav: NavController) {
    val app = LocalContext.current.shiori
    val session by app.auth.state.collectAsStateWithLifecycle()
    if (session !is SessionState.Authorized) {
        ErrorBox("Уведомления доступны после входа в аккаунт (вкладка «Профиль»).", null)
        return
    }
    val offline by app.offline.collectAsStateWithLifecycle()
    if (offline) {
        ErrorBox("Нет сети — уведомления появятся при подключении.", null)
        return
    }
    val vm: NotificationsViewModel = viewModel { NotificationsViewModel(app.api, app.ui) }
    val s by vm.state.collectAsStateWithLifecycle()
    var commentsFor by remember { mutableStateOf<Notification?>(null) }
    val list = rememberLazyListState()
    val nearEnd by remember { derivedStateOf { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= list.layoutInfo.totalItemsCount - 4 } == true } }
    LaunchedEffect(nearEnd, s.items.size) { if (nearEnd) vm.loadMore() }

    fun open(n: Notification) {
        vm.markRead(n)
        when (n.kind) {
            // Ответ на комментарий: окно комментариев главы поверх уведомлений, сама глава не открывается.
            Notification.Kind.Comment -> if (n.chapterVersionId != null) commentsFor = n
            Notification.Kind.Chapter -> {
                val slug = n.slug ?: return
                val titleId = slug.substringBefore("--").toLongOrNull()
                val branch = n.branchId ?: titleId?.let { app.store.pinnedBranch(it) }
                if (branch != null && n.volume != null && n.number != null) nav.navigate(Routes.reader(slug, branch, n.volume!!, n.number!!))
                else nav.navigate(Routes.title(slug))
            }
            Notification.Kind.Other -> n.slug?.let { nav.navigate(Routes.title(it)) }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
            Text("Уведомления", Modifier.weight(1f), fontFamily = TitleFont, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
            TextButton(onClick = vm::markAllRead) { Text("Прочитать все") }
        }
        PrimaryTabRow(selectedTabIndex = s.category.ordinal) {
            NotificationsViewModel.Category.entries.forEach { c -> Tab(selected = s.category == c, onClick = { vm.select(c) }, text = { Text(c.label) }) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Только непрочитанные", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = s.unreadOnly, onCheckedChange = vm::setUnreadOnly)
        }
        when {
            s.items.isEmpty() && s.loading -> Loading()
            s.items.isEmpty() && s.error != null -> ErrorBox(s.error!!, { vm.setUnreadOnly(s.unreadOnly) })
            s.items.isEmpty() -> ErrorBox(if (s.unreadOnly) "Новых уведомлений нет" else "Уведомлений нет", null)
            else -> LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
                items(s.items, key = { it.id }) { n -> NotificationRow(n) { open(n) } }
                if (s.loading) item { Loading(Modifier.fillMaxWidth().padding(16.dp)) }
            }
        }
    }

    commentsFor?.let { n ->
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { commentsFor = null }, sheetState = sheetState) {
            val postId = n.chapterVersionId!!
            val sources = remember(n.id) { listOf(CommentsViewModel.Source(0L, postId, "")) }
            val root = n.commentRootId ?: n.commentId
            val vm2: CommentsViewModel = viewModel(key = "notif-comments-${n.id}") { CommentsViewModel(app, sources, n.commentId, root) }
            val chapter = if (n.volume != null) "Том ${n.volume} Глава ${n.number}" else null
            CommentsSheet(
                vm = vm2,
                header = "Комментарии к главе",
                subtitle = listOfNotNull(n.title.ifBlank { null }, chapter).joinToString(" · ").ifBlank { null },
                sources = sources,
                currentBranch = 0L,
                currentPostId = postId,
                focusComment = n.commentId,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun NotificationRow(n: Notification, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                Cover(n.cover, 44.dp, 62.dp)
                if (!n.isRead) Box(Modifier.size(10.dp).background(MaterialTheme.colorScheme.primary, CircleShape).align(Alignment.TopEnd))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                if (n.title.isNotBlank()) Text(n.title, fontFamily = TitleFont, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(n.text, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                n.createdAt?.let { Text(formatDate(it) + " " + it.drop(11).take(5), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    }
}
