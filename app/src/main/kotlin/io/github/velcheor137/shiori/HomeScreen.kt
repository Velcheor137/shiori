package io.github.velcheor137.shiori

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import io.github.velcheor137.shiori.data.libsocial.LibSocialApi
import io.github.velcheor137.shiori.data.libsocial.TitleCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class HomeViewModel(private val api: LibSocialApi) : ViewModel() {
    val updates = MutableStateFlow<Load<List<TitleCard>>>(Load.Loading)

    init {
        load()
    }

    fun load() {
        updates.value = Load.Loading
        viewModelScope.launch {
            updates.value = runCatching { Load.Ready(api.latestUpdates(1).items) }
                .getOrElse { AppLog.e("HOME", "Обновления не загрузились", it); Load.Error(it.userMessage()) }
        }
    }
}

@Composable
fun HomeScreen(nav: NavController) {
    val app = androidx.compose.ui.platform.LocalContext.current.shiori
    val vm: HomeViewModel = viewModel { HomeViewModel(app.api) }
    val progress by app.store.progress.collectAsStateWithLifecycle()
    val updates by vm.updates.collectAsStateWithLifecycle()

    LazyColumn(Modifier.fillMaxWidth()) {
        item {
            Text(
                "Shiori",
                modifier = Modifier.padding(start = 16.dp, top = 16.dp),
                fontFamily = TitleFont,
                fontWeight = FontWeight.SemiBold,
                fontSize = 26.sp,
            )
        }
        val last = progress.firstOrNull()
        if (last != null) {
            item {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Cover(last.cover, 72.dp, 104.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Продолжить чтение", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(last.titleName, fontFamily = TitleFont, fontSize = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(last.chapterLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Button(
                        onClick = { nav.navigate(Routes.reader(last.slug, last.branchId, last.volume, last.number, restore = true)) },
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    ) { Text("Продолжить: ${last.chapterLabel}") }
                }
            }
        }
        if (progress.size > 1) {
            item { SectionTitle("Недавно читали") }
            items(progress.drop(1).take(10), key = { "p${it.titleId}" }) { p ->
                Column(Modifier.fillMaxWidth().clickable { nav.navigate(Routes.reader(p.slug, p.branchId, p.volume, p.number, restore = true)) }) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Cover(p.cover, 40.dp, 58.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.titleName, fontFamily = TitleFont, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(p.chapterLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                }
            }
        }
        item { SectionTitle("Последние обновления") }
        when (val u = updates) {
            Load.Loading -> item { Loading(Modifier.fillMaxWidth().padding(32.dp)) }
            is Load.Error -> item { ErrorBox(u.message, vm::load, Modifier.fillMaxWidth()) }
            is Load.Ready -> items(u.value, key = { "u${it.id}" }) { card -> TitleRow(card) { nav.navigate(Routes.title(card.slug)) } }
        }
    }
}
