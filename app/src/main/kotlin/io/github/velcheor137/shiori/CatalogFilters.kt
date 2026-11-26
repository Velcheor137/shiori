package io.github.velcheor137.shiori

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.velcheor137.shiori.core.common.AppLog
import io.github.velcheor137.shiori.data.auth.SessionState
import io.github.velcheor137.shiori.data.libsocial.BookmarkFolder
import io.github.velcheor137.shiori.data.libsocial.FilterConstants
import io.github.velcheor137.shiori.data.libsocial.FilterOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Справочники фильтров: файл на диске, обновляется раз в 7 дней; при ошибке сети — старая копия. */
class FilterConstantsRepo(private val app: ShioriApp, private val source: Source) {
    private val file = File(app.filesDir, "constants_site${source.siteId}.json")
    private val lock = Mutex()
    @Volatile private var memory: FilterConstants? = null

    suspend fun get(): FilterConstants = lock.withLock {
        memory?.let { return it }
        withContext(Dispatchers.IO) {
            val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < 7L * 24 * 3600 * 1000
            val body = if (fresh) file.readText() else runCatching { app.apiFor(source).constantsRaw().also { file.writeText(it) } }
                .getOrElse { if (file.exists()) { AppLog.w("FILTERS", "Справочники не обновились, беру старые", it); file.readText() } else throw it }
            FilterConstants.parse(body, source.siteId).also { memory = it }
        }
    }
}

private enum class Pick { None, Include, Exclude }

/** Экран «Фильтры» каталога — набор групп как у сайта; изменения применяются кнопкой «Применить». */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CatalogFiltersScreen(initial: CatalogFilter, onClose: () -> Unit, onApply: (CatalogFilter) -> Unit) {
    val app = LocalContext.current.shiori
    var f by remember { mutableStateOf(initial) }
    var sub by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableStateOf(0) }
    val constants by produceState<Load<FilterConstants>>(Load.Loading, reloadKey) {
        value = runCatching { Load.Ready(app.filterConstants.get()) }
            .getOrElse { AppLog.e("FILTERS", "Справочники не загрузились", it); Load.Error(it.userMessage()) }
    }
    val session by app.auth.state.collectAsStateWithLifecycle()
    val userId = (session as? SessionState.Authorized)?.userId
    val folders by produceState<List<BookmarkFolder>>(emptyList(), userId) {
        if (userId != null) value = runCatching { app.api.folders(userId) }.getOrDefault(emptyList())
    }

    val c = (constants as? Load.Ready)?.value
    if (sub != null && c != null) {
        BackHandler { sub = null }
        val genres = sub == "genres"
        TagPicker(
            title = if (genres) "Жанры" else "Теги",
            options = if (genres) c.genres else c.tags,
            include = if (genres) f.genres else f.tags,
            exclude = if (genres) f.genresExclude else f.tagsExclude,
            onChange = { inc, exc -> f = if (genres) f.copy(genres = inc, genresExclude = exc) else f.copy(tags = inc, tagsExclude = exc) },
            onBack = { sub = null },
        )
        return
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
            Text("Фильтры", Modifier.weight(1f), fontFamily = TitleFont, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
            TextButton(onClick = { f = CatalogFilter() }, enabled = f != CatalogFilter()) { Text("Сбросить") }
        }
        when (val k = constants) {
            Load.Loading -> Loading(Modifier.weight(1f).fillMaxWidth())
            is Load.Error -> ErrorBox(k.message, { reloadKey++ }, Modifier.weight(1f).fillMaxWidth())
            is Load.Ready -> LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                val cc = k.value
                item { PickerRow("Жанры", summary(cc.genres, f.genres, f.genresExclude)) { sub = "genres" } }
                item { PickerRow("Теги", summary(cc.tags, f.tags, f.tagsExclude)) { sub = "tags" } }
                item { RangeRow("Количество глав", f.chapMin, f.chapMax) { a, b -> f = f.copy(chapMin = a, chapMax = b) } }
                item { RangeRow("Год релиза", f.yearMin, f.yearMax) { a, b -> f = f.copy(yearMin = a, yearMax = b) } }
                item { RangeRow("Оценка", f.ratingMin, f.ratingMax, hint = "0–10") { a, b -> f = f.copy(ratingMin = a, ratingMax = b) } }
                item { RangeRow("Количество оценок", f.rateMin, f.rateMax) { a, b -> f = f.copy(rateMin = a, rateMax = b) } }
                item { ChipGroup("Возрастной рейтинг", cc.ages, f.ages) { f = f.copy(ages = it) } }
                item { ChipGroup("Тип", cc.types, f.types) { f = f.copy(types = it) } }
                item { ChipGroup("Формат выпуска", cc.formats, f.formats) { f = f.copy(formats = it) } }
                item { ChipGroup("Статус тайтла", cc.statuses, f.statuses) { f = f.copy(statuses = it) } }
                item { ChipGroup("Статус перевода", cc.scanlate, f.scanlate) { f = f.copy(scanlate = it) } }
                item {
                    GroupTitle("Другое")
                    CheckRow("Нет перевода больше 2 месяцев", f.longNoTranslation) { f = f.copy(longNoTranslation = it) }
                    CheckRow("Издательский контент", f.licensed == 1) { f = f.copy(licensed = if (it) 1 else null) }
                    CheckRow("Можно приобрести", f.buy) { f = f.copy(buy = it) }
                }
                if (folders.isNotEmpty()) item {
                    Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        GroupTitle("Мои списки", Modifier.weight(1f))
                        val all = folders.map { it.id }.toSet()
                        TextButton(onClick = { f = f.copy(bookmarks = if (f.bookmarks == all) emptySet() else all, bookmarksExclude = emptySet()) }) {
                            Text(if (f.bookmarks == all) "Снять все" else "Выделить все")
                        }
                    }
                    Text(
                        "Нажатие — показать только эти списки, второе — исключить их.",
                        Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        folders.forEach { folder ->
                            val pick = when (folder.id) { in f.bookmarks -> Pick.Include; in f.bookmarksExclude -> Pick.Exclude; else -> Pick.None }
                            TriChip(folder.name, pick) {
                                f = when (pick) {
                                    Pick.None -> f.copy(bookmarks = f.bookmarks + folder.id)
                                    Pick.Include -> f.copy(bookmarks = f.bookmarks - folder.id, bookmarksExclude = f.bookmarksExclude + folder.id)
                                    Pick.Exclude -> f.copy(bookmarksExclude = f.bookmarksExclude - folder.id)
                                }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.padding(12.dp)) }
            }
        }
        Button(onClick = { onApply(f) }, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(if (f.activeCount > 0) "Применить (${f.activeCount})" else "Применить")
        }
    }
}

private fun summary(options: List<FilterOption>, include: Set<Long>, exclude: Set<Long>): String {
    if (include.isEmpty() && exclude.isEmpty()) return "Любые"
    val names = options.associate { it.id to it.name }
    return buildList {
        if (include.isNotEmpty()) add(include.mapNotNull { names[it] }.joinToString(", "))
        if (exclude.isNotEmpty()) add("без: " + exclude.mapNotNull { names[it] }.joinToString(", "))
    }.joinToString(" · ")
}

@Composable
private fun GroupTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun PickerRow(title: String, value: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
}

@Composable
private fun RangeRow(title: String, min: Int?, max: Int?, hint: String? = null, onChange: (Int?, Int?) -> Unit) {
    GroupTitle(if (hint != null) "$title ($hint)" else title)
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        NumberField("от", min, Modifier.weight(1f)) { onChange(it, max) }
        Text("—", Modifier.padding(horizontal = 8.dp))
        NumberField("до", max, Modifier.weight(1f)) { onChange(min, it) }
    }
}

@Composable
private fun NumberField(label: String, value: Int?, modifier: Modifier, onChange: (Int?) -> Unit) {
    var text by remember { mutableStateOf(value?.toString().orEmpty()) }
    LaunchedEffect(value) { if (text.toIntOrNull() != value) text = value?.toString().orEmpty() }
    OutlinedTextField(
        value = text,
        onValueChange = { t -> val digits = t.filter(Char::isDigit).take(7); text = digits; onChange(digits.toIntOrNull()) },
        placeholder = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipGroup(title: String, options: List<FilterOption>, selected: Set<Long>, onChange: (Set<Long>) -> Unit) {
    if (options.isEmpty()) return
    GroupTitle(title)
    FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o ->
            val on = o.id in selected
            FilterChip(selected = on, onClick = { onChange(if (on) selected - o.id else selected + o.id) }, label = { Text(o.name) })
        }
    }
}

@Composable
private fun CheckRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun TriChip(label: String, pick: Pick, onClick: () -> Unit) {
    FilterChip(
        selected = pick != Pick.None,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = when (pick) {
            Pick.Include -> ({ Icon(Icons.Filled.Check, null) })
            Pick.Exclude -> ({ Icon(Icons.Filled.Close, null) })
            Pick.None -> null
        },
        colors = if (pick == Pick.Exclude) FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.errorContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onErrorContainer,
            selectedLeadingIconColor = MaterialTheme.colorScheme.onErrorContainer,
        ) else FilterChipDefaults.filterChipColors(),
    )
}

/** Выбор жанров/тегов: поиск, нажатие — включить, второе — исключить, третье — снять. */
@Composable
private fun TagPicker(
    title: String,
    options: List<FilterOption>,
    include: Set<Long>,
    exclude: Set<Long>,
    onChange: (Set<Long>, Set<Long>) -> Unit,
    onBack: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val shown = remember(query, options) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) options else options.filter { o -> o.name.lowercase().contains(q) || o.altNames.any { it.lowercase().contains(q) } }
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
            Text(title, Modifier.weight(1f), fontFamily = TitleFont, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
            TextButton(onClick = { onChange(emptySet(), emptySet()) }, enabled = include.isNotEmpty() || exclude.isNotEmpty()) { Text("Сбросить") }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Поиск") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        Text(
            "Нажатие — включить, второе — исключить, третье — снять.",
            Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(shown, key = { it.id }) { o ->
                val pick = when (o.id) { in include -> Pick.Include; in exclude -> Pick.Exclude; else -> Pick.None }
                Row(
                    Modifier.fillMaxWidth().clickable {
                        when (pick) {
                            Pick.None -> onChange(include + o.id, exclude)
                            Pick.Include -> onChange(include - o.id, exclude + o.id)
                            Pick.Exclude -> onChange(include, exclude - o.id)
                        }
                    }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(o.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    if (o.adult) Text("18+", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(8.dp))
                    when (pick) {
                        Pick.Include -> Icon(Icons.Filled.Check, "Включён", tint = MaterialTheme.colorScheme.primary)
                        Pick.Exclude -> Icon(Icons.Filled.Close, "Исключён", tint = MaterialTheme.colorScheme.error)
                        Pick.None -> Spacer(Modifier.width(24.dp))
                    }
                }
            }
        }
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            val n = include.size + exclude.size
            Text(if (n > 0) "Готово ($n)" else "Готово")
        }
    }
}
