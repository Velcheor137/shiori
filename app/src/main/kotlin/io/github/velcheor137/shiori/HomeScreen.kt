package io.github.velcheor137.shiori

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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
import io.github.velcheor137.shiori.data.libsocial.TitleCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeViewModel(private val api: LibSocialApi) : ViewModel() {
    val updates = MutableStateFlow<Load<List<TitleCard>>>(Load.Loading)
    /** Новые главы тайтлов из закладок текущего источника (лента уведомлений о главах). */
    val bookmarkUpdates = MutableStateFlow<List<Notification>>(emptyList())
    val unread = MutableStateFlow(0)
    private var loadedOnline = false

    fun refreshAccount(authorized: Boolean) {
        if (!authorized) { unread.value = 0; bookmarkUpdates.value = emptyList(); return }
        viewModelScope.launch { unread.value = api.unreadNotifications() }
        viewModelScope.launch {
            runCatching { api.notifications("chapter", "all", 1) }
                .onSuccess { p -> bookmarkUpdates.value = p.items.filter { it.slug != null }.distinctBy { it.slug }.take(10) }
                .onFailure { AppLog.w("HOME", "Обновления закладок не загрузились", it) }
        }
    }

    /** Общие обновления; повторно — только если прошлый раз не удался (например, не было сети). */
    fun load(force: Boolean = false) {
        if (loadedOnline && !force) return
        updates.value = Load.Loading
        viewModelScope.launch {
            updates.value = runCatching { Load.Ready(api.latestUpdates(1).items).also { loadedOnline = true } }
                .getOrElse { AppLog.e("HOME", "Обновления не загрузились", it); Load.Error(it.userMessage()) }
        }
    }
}

/** Иконка приложения (как на рабочем столе): фон и буква из ресурсов адаптивной иконки. */
@Composable
fun AppLogo(size: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.28f)), contentAlignment = Alignment.Center) {
        // Видимая часть адаптивной иконки — 72 из 108 dp, поэтому слои рисуются крупнее и обрезаются.
        Image(painterResource(R.drawable.ic_launcher_background), null, Modifier.requiredSize(size * 1.5f))
        Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.requiredSize(size * 1.5f))
    }
}

@Composable
fun HomeScreen(nav: NavController) {
    val app = LocalContext.current.shiori
    val vm: HomeViewModel = viewModel { HomeViewModel(app.api) }
    val allProgress by app.store.progress.collectAsStateWithLifecycle()
    val source by app.source.collectAsStateWithLifecycle()
    val offline by app.offline.collectAsStateWithLifecycle()
    val updates by vm.updates.collectAsStateWithLifecycle()
    val bookmarkUpdates by vm.bookmarkUpdates.collectAsStateWithLifecycle()
    val session by app.auth.state.collectAsStateWithLifecycle()
    val unread by vm.unread.collectAsStateWithLifecycle()
    val authorized = session is SessionState.Authorized
    LaunchedEffect(session, offline) {
        if (offline) return@LaunchedEffect
        vm.load()
        vm.refreshAccount(authorized)
    }

    // Прогресс текущего источника; в офлайне — только главы, которые скачаны.
    val progress by produceState(emptyList<ReadingProgress>(), allProgress, source, offline) {
        val mine = allProgress.filter { it.source == source.key }
        value = if (!offline) mine else withContext(Dispatchers.IO) {
            mine.filter { DownloadFiles.isChapterDownloaded(app, it.titleId, it.branchId, it.volume, it.number) }
        }
    }
    val downloadedTitles by produceState(emptyList<TitleCard>(), offline) {
        if (offline) value = withContext(Dispatchers.IO) { OfflineData.downloadedTitles(DownloadFiles.root(app)) }
    }

    Column(Modifier.fillMaxSize()) {
        // Шапка закреплена: логотип и название, колокольчик; ниже — источник.
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            AppLogo(34.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                "Shiori",
                Modifier.weight(1f),
                fontFamily = TitleFont,
                fontWeight = FontWeight.Bold,
                fontSize = 28.sp,
                color = MaterialTheme.colorScheme.primary,
            )
            if (!offline) {
                IconButton(onClick = { nav.navigate("notifications") }) {
                    BadgedBox(badge = { if (unread > 0) Badge { Text(if (unread > 99) "99+" else "$unread") } }) {
                        Icon(Icons.Filled.Notifications, "Уведомления")
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            SourcePicker()
            if (offline) {
                Spacer(Modifier.width(10.dp))
                Row(
                    Modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.errorContainer)
                        .clickable { nav.navigate("settings") }.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(AppIcons.CloudOff, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onErrorContainer)
                    Text(" Офлайн", color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            if (offline) {
                item {
                    Text(
                        "Нет сети — показаны только скачанные главы.",
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
            val recent = progress.drop(1).take(RECENT_LIMIT)
            if (recent.isNotEmpty()) {
                item { SectionTitle("Недавно читали") }
                // Окно фиксированной высоты (~4 строки) со своей прокруткой — главная не растягивается.
                item {
                    Column(Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                        recent.forEach { p ->
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
                }
            }
            if (offline) {
                item { SectionTitle("Скачанные тайтлы") }
                if (downloadedTitles.isEmpty()) {
                    item { ErrorBox("Скачанных глав нет. Скачать можно в карточке тайтла, когда есть сеть.", null, Modifier.fillMaxWidth()) }
                }
                items(downloadedTitles, key = { "d${it.id}" }) { card -> TitleRow(card) { nav.navigate(Routes.title(card.slug)) } }
                return@LazyColumn
            }
            if (authorized && bookmarkUpdates.isNotEmpty()) {
                item { SectionTitle("Новое в закладках") }
                items(bookmarkUpdates, key = { "b${it.id}" }) { n -> BookmarkUpdateRow(n) { n.slug?.let { nav.navigate(Routes.title(it)) } } }
            }
            item { SectionTitle("Последние обновления") }
            when (val u = updates) {
                Load.Loading -> item { Loading(Modifier.fillMaxWidth().padding(32.dp)) }
                is Load.Error -> item { ErrorBox(u.message, { vm.load(force = true) }, Modifier.fillMaxWidth()) }
                is Load.Ready -> items(u.value, key = { "u${it.id}" }) { card -> TitleRow(card) { nav.navigate(Routes.title(card.slug)) } }
            }
        }
    }
}

private const val RECENT_LIMIT = 20

@Composable
private fun BookmarkUpdateRow(n: Notification, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(n.cover, 40.dp, 58.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(n.title, fontFamily = TitleFont, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val chapter = if (n.volume != null && n.number != null) "Том ${n.volume} Глава ${n.number}" else n.text
                Text(
                    listOfNotNull(chapter, n.createdAt?.let { formatDate(it) }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    }
}
