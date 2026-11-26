package io.github.velcheor137.shiori

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import io.github.velcheor137.shiori.core.common.AppLog
import io.github.velcheor137.shiori.data.auth.AuthRepository
import io.github.velcheor137.shiori.data.auth.SessionState
import io.github.velcheor137.shiori.data.libsocial.BookmarkFolder
import io.github.velcheor137.shiori.data.libsocial.LibSocialApi
import io.github.velcheor137.shiori.data.libsocial.TitleCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Списки аккаунта RanobeLib: папки закладок («Читаю», «В планах», …) и тайтлы в них. */
class LibraryViewModel(private val api: LibSocialApi, private val auth: AuthRepository, private val ui: UiPrefs) : ViewModel() {
    data class State(
        val folders: Load<List<BookmarkFolder>> = Load.Loading,
        val selected: Int = 0,
        val items: List<TitleCard> = emptyList(),
        val page: Int = 0,
        val hasNext: Boolean = true,
        val loading: Boolean = false,
        val error: String? = null,
    )

    val state = MutableStateFlow(State())
    private var userId: Long? = null

    fun start() {
        val s = auth.state.value as? SessionState.Authorized ?: return
        if (userId == s.userId && state.value.folders is Load.Ready) return
        userId = s.userId
        loadFolders()
    }

    fun loadFolders() {
        val uid = userId ?: return
        state.value = State()
        viewModelScope.launch {
            runCatching { api.folders(uid) }
                .onSuccess { f ->
                    AppLog.i("LIBRARY", "Папок: ${f.size} (${f.joinToString { "${it.name}=${it.count}" }})")
                    state.value = state.value.copy(folders = Load.Ready(f))
                    val saved = ui.int(UiPrefs.LIBRARY_FOLDER, -1).toLong()
                    if (f.isNotEmpty()) select(f.indexOfFirst { it.id == saved }.coerceAtLeast(0))
                }
                .onFailure {
                    AppLog.e("LIBRARY", "Папки не загрузились", it)
                    state.value = state.value.copy(folders = Load.Error(it.userMessage()))
                }
        }
    }

    fun select(index: Int) {
        state.value = state.value.copy(selected = index, items = emptyList(), page = 0, hasNext = true, error = null)
        (state.value.folders as? Load.Ready)?.value?.getOrNull(index)?.let { ui.setInt(UiPrefs.LIBRARY_FOLDER, it.id.toInt()) }
        loadMore()
    }

    fun loadMore() {
        val s = state.value
        val folder = (s.folders as? Load.Ready)?.value?.getOrNull(s.selected) ?: return
        val uid = userId ?: return
        if (s.loading || !s.hasNext) return
        state.value = s.copy(loading = true, error = null)
        viewModelScope.launch {
            runCatching { api.bookmarks(uid, folder.id, s.page + 1) }
                .onSuccess { p ->
                    if (state.value.selected != s.selected) return@onSuccess
                    val known = state.value.items.map { it.id }.toSet()
                    state.value = state.value.copy(items = state.value.items + p.items.filter { it.id !in known }, page = s.page + 1, hasNext = p.hasNext, loading = false)
                }
                .onFailure {
                    AppLog.e("LIBRARY", "Список «${folder.name}» не загрузился", it)
                    state.value = state.value.copy(loading = false, error = it.userMessage())
                }
        }
    }
}

@Composable
fun LibraryScreen(nav: NavController) {
    val app = LocalContext.current.shiori
    val session by app.auth.state.collectAsStateWithLifecycle()
    val vm: LibraryViewModel = viewModel { LibraryViewModel(app.api, app.auth, app.ui) }
    LaunchedEffect(session) { vm.start() }
    val s by vm.state.collectAsStateWithLifecycle()

    var local by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(app.ui.bool(UiPrefs.LIBRARY_LOCAL, false)) }
    Column(Modifier.fillMaxSize()) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            listOf("Списки аккаунта", "Закладки в тексте").forEachIndexed { i, label ->
                SegmentedButton(
                    selected = local == (i == 1),
                    onClick = { local = i == 1; app.ui.setBool(UiPrefs.LIBRARY_LOCAL, local) },
                    shape = SegmentedButtonDefaults.itemShape(i, 2),
                ) { Text(label) }
            }
        }
        if (local) LocalBookmarks(nav) else AccountLists(nav, session, vm, s)
    }
}

@Composable
private fun LocalBookmarks(nav: NavController) {
    val app = LocalContext.current.shiori
    val stored by app.store.bookmarks.collectAsStateWithLifecycle()
    val all = androidx.compose.runtime.remember(stored) { stored.filter { it.source == app.source.value.key } }
    if (all.isEmpty()) {
        ErrorBox("Закладок пока нет. В читалке нажмите значок закладки на нижней панели.", null)
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(all, key = { it.id }) { b ->
            Column(
                Modifier.fillMaxWidth().clickable { nav.navigate(Routes.reader(b.slug, b.branchId, b.volume, b.number, startBlock = b.block)) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(b.titleName, fontFamily = TitleFont, maxLines = 1)
                Text("Том ${b.volume} Глава ${b.number}", style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                if (b.excerpt.isNotBlank()) Text(b.excerpt, style = androidx.compose.material3.MaterialTheme.typography.bodySmall, maxLines = 2)
            }
            androidx.compose.material3.HorizontalDivider()
        }
    }
}

@Composable
private fun AccountLists(nav: NavController, session: SessionState, vm: LibraryViewModel, s: LibraryViewModel.State) {
    if (session !is SessionState.Authorized) {
        ErrorBox("Ваши списки RanobeLib появятся после входа в аккаунт (вкладка «Профиль»).", null)
        return
    }
    when (val f = s.folders) {
        Load.Loading -> Loading()
        is Load.Error -> ErrorBox(f.message, vm::loadFolders)
        is Load.Ready -> Column(Modifier.fillMaxSize()) {
            if (f.value.isEmpty()) {
                ErrorBox("В аккаунте нет списков", vm::loadFolders)
                return@Column
            }
            PrimaryScrollableTabRow(selectedTabIndex = s.selected.coerceIn(0, f.value.lastIndex), edgePadding = 8.dp) {
                f.value.forEachIndexed { i, folder ->
                    Tab(selected = i == s.selected, onClick = { vm.select(i) }, text = { Text("${folder.name} ${folder.count}") })
                }
            }
            val list = rememberLazyListState()
            val nearEnd by remember { derivedStateOf { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= list.layoutInfo.totalItemsCount - 5 } == true } }
            LaunchedEffect(nearEnd, s.items.size) { if (nearEnd) vm.loadMore() }
            when {
                s.items.isEmpty() && s.loading -> Loading()
                s.items.isEmpty() && s.error != null -> ErrorBox(s.error!!, { vm.select(s.selected) })
                s.items.isEmpty() -> ErrorBox("Список пуст", null)
                else -> LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
                    items(s.items, key = { it.id }) { card -> TitleRow(card) { nav.navigate(Routes.title(card.slug)) } }
                    if (s.loading) item { Loading(Modifier.fillMaxWidth().padding(16.dp)) }
                }
            }
        }
    }
}
