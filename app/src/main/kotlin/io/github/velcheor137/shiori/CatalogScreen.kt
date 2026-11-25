package io.github.velcheor137.shiori

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import io.github.velcheor137.shiori.core.common.AppLog
import io.github.velcheor137.shiori.data.libsocial.CatalogSort
import io.github.velcheor137.shiori.data.libsocial.LibSocialApi
import io.github.velcheor137.shiori.data.libsocial.TitleCard
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class CatalogViewModel(private val api: LibSocialApi) : ViewModel() {
    data class State(
        val query: String = "",
        val sort: CatalogSort = CatalogSort.Popular,
        val items: List<TitleCard> = emptyList(),
        val page: Int = 0,
        val hasNext: Boolean = true,
        val loading: Boolean = false,
        val error: String? = null,
    )

    val state = MutableStateFlow(State())
    private var job: Job? = null

    init {
        reload()
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
        reload()
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
        runCatching { api.catalog(s.query, s.page + 1, s.sort) }
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
    val vm: CatalogViewModel = viewModel { CatalogViewModel(app.api) }
    val s by vm.state.collectAsStateWithLifecycle()
    val list = rememberLazyListState()
    val nearEnd by remember { derivedStateOf { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= list.layoutInfo.totalItemsCount - 5 } == true } }
    LaunchedEffect(nearEnd, s.items.size) { if (nearEnd) vm.loadMore() }
    var sortMenu by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = s.query,
            onValueChange = vm::setQuery,
            placeholder = { Text("Поиск по названию") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                TextButton(onClick = { sortMenu = true }, enabled = s.query.isBlank()) { Text("Сортировка: ${s.sort.label} ▾") }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    CatalogSort.entries.forEach { sort ->
                        DropdownMenuItem(text = { Text(sort.label) }, onClick = { sortMenu = false; vm.setSort(sort) })
                    }
                }
            }
        }
        when {
            s.items.isEmpty() && s.loading -> Loading()
            s.items.isEmpty() && s.error != null -> ErrorBox(s.error!!, vm::reload)
            s.items.isEmpty() && !s.hasNext -> ErrorBox("Ничего не найдено", null)
            else -> LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
                items(s.items, key = { it.id }) { card -> TitleRow(card) { nav.navigate(Routes.title(card.slug)) } }
                if (s.loading) item { Loading(Modifier.fillMaxWidth().padding(16.dp)) }
                s.error?.let { e -> item { ErrorBox(e, vm::reload, Modifier.fillMaxWidth()) } }
            }
        }
    }
}
