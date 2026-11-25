package io.github.velcheor137.shiori

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import io.github.velcheor137.shiori.core.common.AppLog
import io.github.velcheor137.shiori.data.libsocial.ChapterContent
import io.github.velcheor137.shiori.data.libsocial.ChapterEntry
import io.github.velcheor137.shiori.data.libsocial.TitleCard
import io.github.velcheor137.shiori.engine.document.ImageBlock
import io.github.velcheor137.shiori.engine.document.Paragraph
import io.github.velcheor137.shiori.engine.document.Separator
import io.github.velcheor137.shiori.engine.typography.NormalizationSettings
import io.github.velcheor137.shiori.engine.typography.ParagraphNormalizer
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

class ReaderViewModel(
    private val app: ShioriApp,
    val slug: String,
    val branchId: Long,
    val volume: String,
    val number: String,
) : ViewModel() {
    data class Data(val title: TitleCard, val chapter: ChapterContent, val prev: ChapterEntry?, val next: ChapterEntry?)

    val state = MutableStateFlow<Load<Data>>(Load.Loading)

    init {
        load()
    }

    fun load() {
        state.value = Load.Loading
        viewModelScope.launch {
            runCatching {
                val titleJob = async { app.titleCache[slug] ?: app.api.title(slug).card.also { app.titleCache[slug] = it } }
                val chaptersJob = async { app.chapterCache[slug] ?: app.api.chapters(slug).also { app.chapterCache[slug] = it } }
                val chapter = app.api.chapter(slug, volume, number, branchId)
                // Соседние главы — только того же перевода (§10.3: без автоподмены).
                val inBranch = chaptersJob.await().filter { it.version(branchId) != null }
                val i = inBranch.indexOfFirst { it.volume == volume && it.number == number }
                Data(titleJob.await(), chapter, inBranch.getOrNull(i - 1), if (i >= 0) inBranch.getOrNull(i + 1) else null)
            }.onSuccess { state.value = Load.Ready(it) }
                .onFailure {
                    AppLog.e("READER", "Глава $slug т$volume г$number (перевод $branchId) не загрузилась", it)
                    state.value = Load.Error(it.userMessage())
                }
        }
    }

    fun saveProgress(title: TitleCard, chapterName: String?, block: Int, offset: Int) {
        app.store.saveProgress(
            ReadingProgress(title.id, slug, title.name, title.cover, branchId, volume, number, chapterName, block, offset, System.currentTimeMillis()),
        )
    }
}

/** Отображаемый блок: абзац уже с применённым оформлением (исходный текст не меняется). */
private sealed interface Shown {
    data class Text(val text: AnnotatedString, val heading: Boolean) : Shown
    data class Image(val url: String) : Shown
    data object Rule : Shown
}

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun ReaderScreen(nav: NavController, slug: String, branchId: Long, volume: String, number: String, restore: Boolean) {
    val app = LocalContext.current.shiori
    val vm: ReaderViewModel = viewModel(key = "reader-$slug-$branchId-$volume-$number") { ReaderViewModel(app, slug, branchId, volume, number) }
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by app.store.settings.collectAsStateWithLifecycle()
    var original by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf(false) }
    val colors = settings.theme.colors()

    Column(Modifier.fillMaxSize().background(colors.background)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { nav.popBackStack() }) { Text("←", color = colors.text, fontSize = 20.sp) }
            Text(
                "Том $volume Глава $number",
                modifier = Modifier.weight(1f),
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = { original = !original }) { Text(if (original) "Оригинал" else "Оформлено", color = colors.secondary, fontSize = 12.sp) }
            TextButton(onClick = { sheet = true }) { Text("Аа", color = colors.text, fontSize = 18.sp) }
        }
        HorizontalDivider(color = colors.secondary.copy(alpha = 0.2f))
        when (val s = state) {
            Load.Loading -> Loading()
            is Load.Error -> ErrorBox(s.message, vm::load)
            is Load.Ready -> ChapterText(nav, vm, s.value, settings, original, restore)
        }
    }
    if (sheet) {
        ModalBottomSheet(onDismissRequest = { sheet = false }) {
            ReaderSettingsPanel(settings) { t -> app.store.updateSettings(t) }
        }
    }
}

@OptIn(FlowPreview::class)
@Composable
private fun ChapterText(nav: NavController, vm: ReaderViewModel, d: ReaderViewModel.Data, settings: ReaderSettings, original: Boolean, restore: Boolean) {
    val app = LocalContext.current.shiori
    val colors = settings.theme.colors()
    val norm = if (original) NormalizationSettings.Off else NormalizationSettings(dialogues = settings.dialogues, thoughts = settings.thoughts)
    val shown = remember(d.chapter, norm) { render(d.chapter, norm) }
    val list = rememberLazyListState()

    // Восстановление позиции: блок + смещение (смена шрифта/темы не сбрасывает место, т.к. блоки не меняются).
    LaunchedEffect(d.chapter.id) {
        val p = app.store.progress(d.title.id)
        if (restore && p != null && p.branchId == vm.branchId && p.volume == vm.volume && p.number == vm.number) {
            list.scrollToItem((p.block + 1).coerceAtMost(shown.size), p.offset)
            AppLog.i("READER", "Позиция восстановлена: блок ${p.block}, смещение ${p.offset}")
        }
        snapshotFlow { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }
            .debounce(400)
            .collect { (i, off) -> vm.saveProgress(d.title, d.chapter.name, (i - 1).coerceAtLeast(0), if (i == 0) 0 else off) }
    }
    val latest by rememberUpdatedState(list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset)
    DisposableEffect(d.chapter.id) {
        onDispose { vm.saveProgress(d.title, d.chapter.name, (latest.first - 1).coerceAtLeast(0), if (latest.first == 0) 0 else latest.second) }
    }

    val style = TextStyle(
        color = colors.text,
        fontSize = settings.fontSize.sp,
        lineHeight = (settings.fontSize * settings.lineHeight).sp,
        fontFamily = if (settings.serif) FontFamily.Serif else FontFamily.SansSerif,
        textAlign = if (settings.justify) TextAlign.Justify else TextAlign.Start,
        textIndent = TextIndent(firstLine = settings.indent.em),
        hyphens = Hyphens.Auto,
        lineBreak = LineBreak.Paragraph,
    )

    LazyColumn(state = list, modifier = Modifier.fillMaxSize().padding(horizontal = settings.sideMargin.dp)) {
        item(key = "head") {
            Column(Modifier.padding(top = 16.dp, bottom = 12.dp)) {
                Text(d.title.name, color = colors.secondary, fontSize = 13.sp)
                Text(
                    "Том ${d.chapter.volume} Глава ${d.chapter.number}" + (d.chapter.name?.takeIf { it.isNotBlank() }?.let { "\n$it" } ?: ""),
                    color = colors.text,
                    fontFamily = TitleFont,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = (settings.fontSize + 4).sp,
                )
            }
        }
        if (d.chapter.restricted) {
            item(key = "locked") {
                Text(
                    "Глава закрыта (платная или ранний доступ${d.chapter.price?.let { ", $it" } ?: ""}). Shiori не обходит платный доступ.",
                    color = colors.text,
                )
            }
        } else if (shown.isEmpty()) {
            item(key = "empty") { Text("В главе нет текста (возможно, удалена правообладателем).", color = colors.text) }
        }
        itemsIndexed(shown, key = { i, _ -> "b$i" }) { _, b ->
            when (b) {
                is Shown.Text -> Text(
                    b.text,
                    style = if (b.heading) style.copy(fontWeight = FontWeight.SemiBold, textIndent = TextIndent.None) else style,
                    modifier = Modifier.fillMaxWidth().padding(bottom = (settings.fontSize * settings.paragraphSpacing).dp),
                )
                is Shown.Image -> AsyncImage(
                    model = b.url,
                    contentDescription = "Иллюстрация",
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
                Shown.Rule -> Text("* * *", color = colors.secondary, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), textAlign = TextAlign.Center)
            }
        }
        item(key = "foot") {
            Row(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                OutlinedButton(enabled = d.prev != null, onClick = { d.prev?.let { nav.navigate(Routes.reader(vm.slug, vm.branchId, it.volume, it.number)) { nav.currentBackStackEntry?.destination?.id?.let { id -> popUpTo(id) { inclusive = true } } } } }) {
                    Text("← Пред.")
                }
                OutlinedButton(enabled = d.next != null, onClick = { d.next?.let { nav.navigate(Routes.reader(vm.slug, vm.branchId, it.volume, it.number)) { nav.currentBackStackEntry?.destination?.id?.let { id -> popUpTo(id) { inclusive = true } } } } }) {
                    Text(if (d.next != null) "След. глава →" else "Последняя глава")
                }
            }
            Spacer(Modifier.height(48.dp))
        }
    }
}

private fun render(chapter: ChapterContent, norm: NormalizationSettings): List<Shown> = chapter.blocks.map { b ->
    when (b) {
        is Paragraph -> {
            val n = ParagraphNormalizer.normalize(b.text, norm)
            if (n.fallback) AppLog.w("READER", "Абзац показан без оформления (сработала защита инварианта)")
            Shown.Text(
                buildAnnotatedString {
                    append(n.text)
                    b.spans.forEach { sp ->
                        val st = n.offsets.toDisplay(sp.start).coerceIn(0, n.text.length)
                        val en = n.offsets.toDisplay(sp.end).coerceIn(st, n.text.length)
                        if (en > st) {
                            addStyle(
                                SpanStyle(
                                    fontWeight = if (sp.bold) FontWeight.Bold else null,
                                    fontStyle = if (sp.italic) FontStyle.Italic else null,
                                ),
                                st,
                                en,
                            )
                        }
                    }
                },
                b.heading,
            )
        }
        is ImageBlock -> Shown.Image(b.url)
        Separator -> Shown.Rule
    }
}

@Composable
private fun ReaderSettingsPanel(s: ReaderSettings, update: ((ReaderSettings) -> ReaderSettings) -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Оформление текста", fontWeight = FontWeight.SemiBold)
        SwitchRow("Реплики в кавычках → тире", s.dialogues) { v -> update { it.copy(dialogues = v) } }
        SwitchRow("Мысли в 'кавычках' → «ёлочки»", s.thoughts) { v -> update { it.copy(thoughts = v) } }
        Spacer(Modifier.height(8.dp))
        Text("Тема", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ReaderTheme.entries.forEach { t -> FilterChip(selected = s.theme == t, onClick = { update { it.copy(theme = t) } }, label = { Text(t.label) }) }
        }
        SliderRow("Размер шрифта", s.fontSize, 12f..40f, "${s.fontSize.toInt()} sp") { v -> update { it.copy(fontSize = v) } }
        SliderRow("Межстрочный интервал", s.lineHeight, 1.0f..2.4f, "%.2f".format(s.lineHeight)) { v -> update { it.copy(lineHeight = v) } }
        SliderRow("Отступ между абзацами", s.paragraphSpacing, 0f..2f, "%.1f".format(s.paragraphSpacing)) { v -> update { it.copy(paragraphSpacing = v) } }
        SliderRow("Красная строка", s.indent, 0f..3f, "%.1f em".format(s.indent)) { v -> update { it.copy(indent = v) } }
        SliderRow("Поля", s.sideMargin.toFloat(), 0f..48f, "${s.sideMargin} dp") { v -> update { it.copy(sideMargin = v.toInt()) } }
        SwitchRow("Шрифт с засечками", s.serif) { v -> update { it.copy(serif = v) } }
        SwitchRow("Выравнивание по ширине", s.justify) { v -> update { it.copy(justify = v) } }
        TextButton(onClick = { update { ReaderSettings() } }) { Text("Сбросить всё") }
    }
}

@Composable
private fun SwitchRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}

@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, shown: String, onChange: (Float) -> Unit) {
    Column {
        Row { Text(label, Modifier.weight(1f)); Text(shown, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

