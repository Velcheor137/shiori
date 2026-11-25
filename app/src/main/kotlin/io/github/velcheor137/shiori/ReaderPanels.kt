package io.github.velcheor137.shiori

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.velcheor137.shiori.data.libsocial.ChapterEntry

/** Список глав как на RanobeLib: команды с аватарами, под каждой главой — её переводы; поиск по номеру/названию. */
@Composable
fun ChaptersPanel(
    all: List<ChapterEntry>,
    initialBranch: Long,
    current: Pair<String, String>,
    downloaded: Set<Long>,
    onPick: (ChapterEntry, Long) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf<Long?>(null) }
    var descending by remember { mutableStateOf(false) }
    val infos = remember(all) { branchInfos(all) }
    val infoMap = remember(infos) { infos.associateBy { it.branchId } }
    val base = remember(all, filter, descending) {
        val f = if (filter == null) all else all.filter { it.version(filter!!) != null }
        if (descending) f.reversed() else f
    }
    val filtered = remember(query, base) { filterChapters(base, query) }
    val list = rememberLazyListState()
    LaunchedEffect(filtered.size, descending) {
        val i = filtered.indexOfFirst { it.volume == current.first && it.number == current.second }
        if (i > 1) list.scrollToItem(i - 1)
    }
    Column(Modifier.fillMaxWidth().heightIn(max = 680.dp)) {
        Text("Список глав", Modifier.padding(horizontal = 16.dp), fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
        TeamChips(infos, pinned = initialBranch, filter = filter) { filter = it }
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Поиск главы") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { descending = !descending }) { Text(if (descending) "↓ новые" else "↑ старые") }
        }
        LazyColumn(state = list) {
            chapterTree(
                chapters = filtered,
                infos = infoMap,
                pinned = initialBranch,
                filter = filter,
                current = current,
                downloaded = downloaded,
                onOpen = onPick,
                onDownload = null,
            )
        }
    }
}

fun filterChapters(chapters: List<ChapterEntry>, query: String): List<ChapterEntry> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return chapters
    val asNumber = q.replace(',', '.').toBigDecimalOrNull()
    return chapters.filter { c ->
        (asNumber != null && c.number.toBigDecimalOrNull()?.compareTo(asNumber) == 0) ||
            c.number.startsWith(q) || c.name?.lowercase()?.contains(q) == true || c.label.lowercase().contains(q)
    }
}

/** Ручные закладки тайтла: добавить в текущем месте, открыть, удалить. */
@Composable
fun BookmarksPanel(titleId: Long, onAdd: () -> Unit, onOpen: (LocalBookmark) -> Unit) {
    val app = LocalContext.current.shiori
    val all by app.store.bookmarks.collectAsStateWithLifecycle()
    val mine = all.filter { it.titleId == titleId }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Text("Добавить закладку здесь") }
        if (mine.isEmpty()) Text("Закладок в этом тайтле пока нет", Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.heightIn(max = 480.dp)) {
            items(mine, key = { it.id }) { b ->
                Row(Modifier.fillMaxWidth().clickable { onOpen(b) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Том ${b.volume} Глава ${b.number}", fontWeight = FontWeight.Medium)
                        Text(b.excerpt.ifBlank { "Абзац ${b.block + 1}" }, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { app.store.removeBookmark(b.id) }) { Icon(Icons.Filled.Delete, "Удалить закладку") }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

private val swatches = listOf(
    0xFF000000, 0xFF151412, 0xFF2F3136, 0xFF3A3C42, 0xFF5E5E5E, 0xFF9A9CA1, 0xFFDCDDDE, 0xFFFFFFFF,
    0xFFFAF8F3, 0xFFF3E9D6, 0xFFE7DAC1, 0xFF3B2F22, 0xFF1F2A36, 0xFF0F2A1F, 0xFFE8F0E3, 0xFFFFF4C2,
    0xFF8C3A2B, 0xFFE0A08C, 0xFF3D5A80, 0xFF98C1D9, 0xFF2E7D32, 0xFFFFB300, 0xFFC2185B, 0xFF6A1B9A,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReaderSettingsPanel(s: ReaderSettings, update: ((ReaderSettings) -> ReaderSettings) -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Section("Оформление текста")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            TextMode.entries.forEachIndexed { i, m ->
                SegmentedButton(selected = s.textMode == m, onClick = { update { it.copy(textMode = m) } }, shape = SegmentedButtonDefaults.itemShape(i, TextMode.entries.size)) { Text(m.label) }
            }
        }
        Text(
            if (s.textMode == TextMode.Literary) "Реплики в кавычках оформляются тире, мысли — «ёлочками». Исходный текст не меняется." else "Текст показан как у переводчика.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (s.textMode == TextMode.Literary) {
            SwitchRow("Диалоги → тире", s.dialogues) { v -> update { it.copy(dialogues = v) } }
            SwitchRow("Мысли → «ёлочки»", s.thoughts) { v -> update { it.copy(thoughts = v) } }
        }

        Section("Шрифт")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ReaderFont.entries.forEach { f -> FilterChip(selected = s.font == f, onClick = { update { it.copy(font = f) } }, label = { Text(f.label) }) }
        }
        SliderRow("Размер", s.fontSize, 12f..40f, "${s.fontSize.toInt()}") { v -> update { it.copy(fontSize = v.toInt().toFloat()) } }
        SliderRow("Насыщенность", s.fontWeight.toFloat(), 300f..700f, "${s.fontWeight}") { v -> update { it.copy(fontWeight = (v / 50).toInt() * 50) } }
        SliderRow("Межбуквенный интервал", s.letterSpacing, -0.05f..0.15f, "%.2f".format(s.letterSpacing)) { v -> update { it.copy(letterSpacing = v) } }

        Section("Абзацы и поля")
        SliderRow("Межстрочный интервал", s.lineHeight, 1.0f..2.4f, "%.2f".format(s.lineHeight)) { v -> update { it.copy(lineHeight = v) } }
        SliderRow("Отступ между абзацами", s.paragraphSpacing, 0f..2f, "%.1f".format(s.paragraphSpacing)) { v -> update { it.copy(paragraphSpacing = v) } }
        SliderRow("Красная строка", s.indent, 0f..3f, "%.1f".format(s.indent)) { v -> update { it.copy(indent = v) } }
        SliderRow("Поля по бокам", s.sideMargin.toFloat(), 0f..48f, "${s.sideMargin}") { v -> update { it.copy(sideMargin = v.toInt()) } }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            TextAlignMode.entries.forEachIndexed { i, a ->
                SegmentedButton(selected = s.align == a, onClick = { update { it.copy(align = a) } }, shape = SegmentedButtonDefaults.itemShape(i, TextAlignMode.entries.size)) { Text(a.label) }
            }
        }
        SwitchRow("Переносы слов", s.hyphens) { v -> update { it.copy(hyphens = v) } }

        Section("Цвета")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ReaderTheme.entries.forEach { t -> FilterChip(selected = s.theme == t, onClick = { update { it.copy(theme = t) } }, label = { Text(t.label) }) }
        }
        if (s.theme == ReaderTheme.Custom) {
            ColorPicker("Фон", s.customBackground) { c -> update { it.copy(customBackground = c) } }
            ColorPicker("Текст", s.customText) { c -> update { it.copy(customText = c) } }
            ContrastHint(s.customBackground, s.customText)
        }
        SliderRow("Обводка текста", s.outlineWidth, 0f..3f, if (s.outlineWidth == 0f) "нет" else "%.1f".format(s.outlineWidth)) { v -> update { it.copy(outlineWidth = (v * 4).toInt() / 4f) } }
        if (s.outlineWidth > 0f) ColorPicker("Цвет обводки", s.outlineColor) { c -> update { it.copy(outlineColor = c) } }

        Section("Экран и управление")
        SwitchRow("Своя яркость", s.brightness >= 0f) { v -> update { it.copy(brightness = if (v) 0.5f else -1f) } }
        if (s.brightness >= 0f) SliderRow("Яркость", s.brightness, 0.02f..1f, "${(s.brightness * 100).toInt()}%") { v -> update { it.copy(brightness = v) } }
        SwitchRow("Не гасить экран", s.keepScreenOn) { v -> update { it.copy(keepScreenOn = v) } }
        SwitchRow("Листать касанием сверху/снизу", s.tapToPage) { v -> update { it.copy(tapToPage = v) } }
        SwitchRow("Показывать % прочитанного", s.showProgress) { v -> update { it.copy(showProgress = v) } }
        SwitchRow("Подсветка текущего абзаца", s.highlightParagraph) { v -> update { it.copy(highlightParagraph = v) } }

        Section("Профили")
        Text("Стартовые настройки для удобства, не медицинская рекомендация.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ReaderPreset.entries.forEach { p -> OutlinedButton(onClick = { update(p.apply) }) { Text(p.label) } }
        }
        TextButton(onClick = { update { ReaderSettings() } }) { Text("Сбросить все настройки") }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, Modifier.padding(top = 12.dp), fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = MaterialTheme.colorScheme.primary)
}

@Composable
fun SwitchRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}

@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, shown: String, onChange: (Float) -> Unit) {
    Column {
        Row { Text(label, Modifier.weight(1f)); Text(shown, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorPicker(label: String, value: Long, onPick: (Long) -> Unit) {
    var hex by remember(value) { mutableStateOf("%06X".format(value and 0xFFFFFF)) }
    Column {
        Text(label)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            swatches.forEach { c ->
                Box(
                    Modifier.size(30.dp).background(Color(c), CircleShape)
                        .border(if (c == value) 3.dp else 1.dp, if (c == value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape)
                        .clickable { onPick(c) },
                )
            }
        }
        OutlinedTextField(
            value = hex,
            onValueChange = { v ->
                hex = v.uppercase().filter { it in "0123456789ABCDEF" }.take(6)
                if (hex.length == 6) onPick(0xFF000000 or hex.toLong(16))
            },
            label = { Text("HEX") },
            singleLine = true,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun ContrastHint(bg: Long, fg: Long) {
    fun lum(c: Long): Double {
        fun ch(v: Long): Double { val x = v / 255.0; return if (x <= 0.04045) x / 12.92 else Math.pow((x + 0.055) / 1.055, 2.4) }
        return 0.2126 * ch((c shr 16) and 0xFF) + 0.7152 * ch((c shr 8) and 0xFF) + 0.0722 * ch(c and 0xFF)
    }
    val a = lum(bg); val b = lum(fg)
    val ratio = (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    Text(
        "Контраст %.1f:1".format(ratio) + if (ratio < 4.5) " — низкий, может быть трудно читать" else "",
        style = MaterialTheme.typography.bodySmall,
        color = if (ratio < 4.5) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

