package io.github.velcheor137.shiori

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Badge
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
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
import coil3.compose.AsyncImage
import io.github.velcheor137.shiori.core.common.AppLog
import io.github.velcheor137.shiori.data.libsocial.CatalogSort
import io.github.velcheor137.shiori.data.libsocial.LibSocialApi
import io.github.velcheor137.shiori.data.libsocial.TitleCard
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class CatalogViewModel(private val api: LibSocialApi, private val ui: UiPrefs, private val source: Source) : ViewModel() {
    data class State(
        val query: String = "",
        val sort: CatalogSort = CatalogSort.Popular,
        val filter: CatalogFilter = CatalogFilter(),
        val grid: Boolean = false,
        val items: List<TitleCard> = emptyList(),
        val page: Int = 0,
        val hasNext: Boolean = true,
        val loading: Boolean = false,
        val error: String? = null,
    )

    val state: MutableStateFlow<State>
    private var job: Job? = null

    init {
        val saved = ui.catalog(source)
        state = MutableStateFlow(
            State(sort = CatalogSort.entries.firstOrNull { it.name == saved.sort } ?: CatalogSort.Popular, filter = saved.filter, grid = saved.grid),
        )
        reload()
    }

    private fun persist() {
        val s = state.value
        ui.setCatalog(source, CatalogPrefs(sort = s.sort.name, grid = s.grid, filter = s.filter))
    }

    fun setQuery(q: String) {
        state.value = state.value.copy(query = q)
        job?.cancel()
        job = viewModelScope.launch {
            delay(400)
            reloadNow()
        }
    }

    fun setSort(sort: CatalogSort) {
        state.value = state.value.copy(sort = sort)
        persist()
        reload()
    }

    fun setFilter(filter: CatalogFilter) {
        if (filter == state.value.filter) return
        state.value = state.value.copy(filter = filter)
        persist()
        reload()
    }

    fun toggleGrid() {
        state.value = state.value.copy(grid = !state.value.grid)
        persist()
    }

    fun reload() {
        job?.cancel()
        job = viewModelScope.launch { reloadNow() }
    }

    private suspend fun reloadNow() {
        state.value = state.value.copy(items = emptyList(), page = 0, hasNext = true, error = null)
        loadPage()
    }

    fun loadMore() {
        val s = state.value
        if (s.loading || !s.hasNext || s.error != null) return
        job = viewModelScope.launch { loadPage() }
    }

    private suspend fun loadPage() {
        val s = state.value
        state.value = s.copy(loading = true)
        runCatching { api.catalog(s.query, s.page + 1, s.sort, s.filter.toQuery()) }
            .onSuccess { p ->
                val known = state.value.items.map { it.id }.toSet()
                state.value = state.value.copy(
                    items = state.value.items + p.items.filter { it.id !in known },
                    page = s.page + 1,
                    hasNext = p.hasNext,
                    loading = false,
                )
            }
            .onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                AppLog.e("CATALOG", "Каталог не загрузился", it)
                state.value = state.value.copy(loading = false, error = it.userMessage())
            }
    }
}

@Composable
fun CatalogScreen(nav: NavController) {
    val app = LocalContext.current.shiori
    val vm: CatalogViewModel = viewModel { CatalogViewModel(app.api, app.ui, app.source.value) }
    val s by vm.state.collectAsStateWithLifecycle()
    var sortMenu by remember { mutableStateOf(false) }
    var filtersOpen by rememberSaveable { mutableStateOf(false) }

    if (filtersOpen) {
        BackHandler { filtersOpen = false }
        CatalogFiltersScreen(initial = s.filter, onClose = { filtersOpen = false }, onApply = { vm.setFilter(it); filtersOpen = false })
        return
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        OutlinedTextField(
            value = s.query,
            onValueChange = vm::setQuery,
            placeholder = { Text("Поиск по названию") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                TextButton(onClick = { sortMenu = true }, enabled = s.query.isBlank()) { Text("${s.sort.label} ▾", maxLines = 1) }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    CatalogSort.entries.forEach { sort ->
                        DropdownMenuItem(text = { Text(sort.label) }, onClick = { sortMenu = false; vm.setSort(sort) })
                    }
                }
            }
            TextButton(onClick = { filtersOpen = true }) {
                BadgedBox(badge = { if (s.filter.activeCount > 0) Badge { Text("${s.filter.activeCount}") } }) {
                    Icon(AppIcons.Tune, null)
                }
                Text("  Фильтры")
            }
            IconButton(onClick = vm::toggleGrid) {
                Icon(if (s.grid) AppIcons.ListView else AppIcons.Grid, if (s.grid) "Списком" else "Сеткой")
            }
        }
        when {
            s.items.isEmpty() && s.loading -> Loading()
            s.items.isEmpty() && s.error != null -> ErrorBox(s.error!!, vm::reload)
            s.items.isEmpty() && !s.hasNext -> ErrorBox("Ничего не найдено", null)
            s.grid -> CatalogGrid(s, vm) { nav.navigate(Routes.title(it.slug)) }
            else -> CatalogList(s, vm) { nav.navigate(Routes.title(it.slug)) }
        }
    }
}

@Composable
private fun CatalogList(s: CatalogViewModel.State, vm: CatalogViewModel, open: (TitleCard) -> Unit) {
    val list = rememberLazyListState()
    val nearEnd by remember { derivedStateOf { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= list.layoutInfo.totalItemsCount - 5 } == true } }
    LaunchedEffect(nearEnd, s.items.size) { if (nearEnd) vm.loadMore() }
    LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
        items(s.items, key = { it.id }) { card -> TitleRow(card) { open(card) } }
        if (s.loading) item { Loading(Modifier.fillMaxWidth().padding(16.dp)) }
        s.error?.let { e -> item { ErrorBox(e, vm::reload, Modifier.fillMaxWidth()) } }
    }
}

@Composable
private fun CatalogGrid(s: CatalogViewModel.State, vm: CatalogViewModel, open: (TitleCard) -> Unit) {
    val grid = rememberLazyGridState()
    val nearEnd by remember { derivedStateOf { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= grid.layoutInfo.totalItemsCount - 9 } == true } }
    LaunchedEffect(nearEnd, s.items.size) { if (nearEnd) vm.loadMore() }
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        state = grid,
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(s.items, key = { it.id }) { card -> GridCard(card) { open(card) } }
        if (s.loading) item(span = { GridItemSpan(maxLineSpan) }) { Loading(Modifier.fillMaxWidth().padding(16.dp)) }
        s.error?.let { e -> item(span = { GridItemSpan(maxLineSpan) }) { ErrorBox(e, vm::reload, Modifier.fillMaxWidth()) } }
    }
}

@Composable
private fun GridCard(card: TitleCard, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.7f).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            if (card.cover != null) AsyncImage(card.cover, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            card.rating?.let {
                Text(
                    "★ $it",
                    Modifier.align(Alignment.TopStart).padding(4.dp).background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                    color = androidx.compose.ui.graphics.Color.White,
                    fontSize = 11.sp,
                )
            }
        }
        card.type?.let { Text(it, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
        Text(card.name, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
