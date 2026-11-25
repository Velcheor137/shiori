package io.github.velcheor137.shiori

import android.app.Activity
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
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
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
import io.github.velcheor137.shiori.engine.typography.NormalizationMode
import io.github.velcheor137.shiori.engine.typography.NormalizationSettings
import io.github.velcheor137.shiori.engine.typography.ParagraphNormalizer
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import java.io.File

class ReaderViewModel(
    private val app: ShioriApp,
    val slug: String,
    val branchId: Long,
    val volume: String,
    val number: String,
) : ViewModel() {
    data class Data(
        val title: TitleCard,
        val chapter: ChapterContent,
        val entry: ChapterEntry?,
        val branchChapters: List<ChapterEntry>,
        val prev: ChapterEntry?,
        val next: ChapterEntry?,
        val offline: Boolean,
    )

    val state = MutableStateFlow<Load<Data>>(Load.Loading)

    init {
        load()
    }

    fun load() {
        state.value = Load.Loading
        viewModelScope.launch {
            runCatching {
                val titleJob = async { app.titleCache[slug] ?: app.api.title(slug).card.also { app.titleCache[slug] = it } }
                val chapters = app.chapterCache[slug] ?: app.api.chapters(slug).also { app.chapterCache[slug] = it }
                val inBranch = chapters.filter { it.version(branchId) != null }
                val i = inBranch.indexOfFirst { it.volume == volume && it.number == number }
                val entry = inBranch.getOrNull(i)
                val title = titleJob.await()
                val versionId = entry?.version(branchId)?.versionId
                // Сначала скачанная копия (офлайн), иначе сеть.
                val local = versionId?.let { DownloadFiles.chapter(app, title.id, it) }?.takeIf { it.exists() }
                val chapter = if (local != null) {
                    AppLog.i("READER", "Глава открыта из загрузок: ${local.name}")
                    app.api.parseChapter(local.readText(), volume, number, branchId)
                } else {
                    app.api.chapter(slug, volume, number, branchId)
                }
                if (versionId != null && chapter.id != 0L && chapter.id != versionId) {
                    AppLog.w("READER", "H-COM-1: id главы ${chapter.id} ≠ id версии в списке $versionId")
                }
                Data(title, chapter, entry, inBranch, inBranch.getOrNull(i - 1), if (i >= 0) inBranch.getOrNull(i + 1) else null, local != null)
            }.onSuccess { state.value = Load.Ready(it) }
                .onFailure {
                    AppLog.e("READER", "Глава $slug т$volume г$number (перевод $branchId) не загрузилась", it)
                    state.value = Load.Error(it.userMessage())
                }
        }
    }

    fun saveProgress(d: Data, block: Int, offset: Int) {
        app.store.saveProgress(
            ReadingProgress(d.title.id, slug, d.title.name, d.title.cover, branchId, volume, number, d.chapter.name, block, offset, System.currentTimeMillis()),
        )
    }

    /** Синхронизация с закладкой аккаунта (по желанию пользователя, §10.1): глава + номер абзаца, как на сайте. */
    fun syncToAccount(d: Data, block: Int) {
        if (!app.store.syncAccount.value || d.chapter.id == 0L) return
        val paragraph = d.chapter.blocks.take(block + 1).count { it is Paragraph }.coerceAtLeast(1)
        app.appScope.launch {
            runCatching {
                val status = app.accountBookmarks[slug] ?: (app.api.titleBookmark(slug)?.status ?: 1L).also { app.accountBookmarks[slug] = it }
                app.api.saveAccountProgress(slug, status, d.chapter.id, paragraph, (d.entry?.index ?: 0))
            }.onFailure { AppLog.w("READER", "Не удалось сохранить прогресс в аккаунт", it) }
        }
    }

    fun addBookmark(d: Data, block: Int) {
        val excerpt = (d.chapter.blocks.getOrNull(block) as? Paragraph)?.text?.take(120).orEmpty()
        app.store.addBookmark(
            LocalBookmark(System.currentTimeMillis(), d.title.id, slug, d.title.name, branchId, volume, number, block, excerpt, System.currentTimeMillis()),
        )
    }
}

/** Отображаемый блок: абзац уже с применённым оформлением (исходный текст не меняется). */
sealed interface Shown {
    data class Text(val text: AnnotatedString, val heading: Boolean) : Shown
    data class Image(val model: Any) : Shown
    data object Rule : Shown
}

private enum class Sheet { None, Chapters, Comments, Bookmarks, Settings }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(nav: NavController, slug: String, branchId: Long, volume: String, number: String, restore: Boolean, startBlock: Int = -1) {
    val app = LocalContext.current.shiori
    val vm: ReaderViewModel = viewModel(key = "reader-$slug-$branchId-$volume-$number") { ReaderViewModel(app, slug, branchId, volume, number) }
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by app.store.settings.collectAsStateWithLifecycle()
    val colors = settings.colors()
    var bars by remember { mutableStateOf(true) }
    var sheet by remember { mutableStateOf(Sheet.None) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()

    ScreenEffects(settings)
    // Панели прячутся, как только начинается прокрутка; показываются по тапу.
    LaunchedEffect(list) { snapshotFlow { list.isScrollInProgress }.collect { if (it) bars = false } }
    // Системные панели (часы, навигация) показываются и прячутся вместе с панелями читалки — без рывков.
    val view = LocalView.current
    val activity = LocalContext.current as? Activity
    val controller = remember(activity) {
        activity?.window?.let { WindowCompat.getInsetsController(it, view) }?.apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
    LaunchedEffect(bars, sheet) {
        if (bars || sheet != Sheet.None) controller?.show(WindowInsetsCompat.Type.systemBars())
        else controller?.hide(WindowInsetsCompat.Type.systemBars())
    }
    LaunchedEffect(settings.theme, settings.customBackground) {
        val bg = colors.background
        val light = (0.299f * bg.red + 0.587f * bg.green + 0.114f * bg.blue) > 0.5f
        controller?.isAppearanceLightStatusBars = light
        controller?.isAppearanceLightNavigationBars = light
    }
    DisposableEffect(controller) { onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) } }

    fun goTo(c: ChapterEntry, toBranch: Long = branchId) {
        if (toBranch != branchId) {
            (state as? Load.Ready)?.value?.title?.id?.let { app.store.pinBranch(it, toBranch) }
            AppLog.i("READER", "Перевод сменён пользователем: $branchId → $toBranch")
        }
        nav.navigate(Routes.reader(slug, toBranch, c.volume, c.number)) {
            nav.currentBackStackEntry?.destination?.id?.let { id -> popUpTo(id) { inclusive = true } }
        }
    }

    Box(Modifier.fillMaxSize().background(colors.background)) {
        when (val s = state) {
            Load.Loading -> Loading()
            is Load.Error -> ErrorBox(s.message, vm::load)
            is Load.Ready -> ChapterText(
                vm = vm,
                d = s.value,
                settings = settings,
                restore = restore,
                startBlock = startBlock,
                list = list,
                onTapCenter = { bars = !bars },
                onPrev = { s.value.prev?.let(::goTo) },
                onNext = { s.value.next?.let(::goTo) },
            )
        }
        val ready = (state as? Load.Ready)?.value
        AnimatedVisibility(
            bars,
            enter = slideInVertically(tween(220)) { -it } + fadeIn(tween(220)),
            exit = slideOutVertically(tween(200)) { -it } + fadeOut(tween(200)),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Row(
                Modifier.fillMaxWidth().background(colors.panel).windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = colors.text) }
                Column(Modifier.weight(1f)) {
                    Text(ready?.title?.name ?: "", color = colors.text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "Том $volume Глава $number" + if (ready?.offline == true) " · скачано" else "",
                        color = colors.secondary,
                        fontSize = 12.sp,
                        maxLines = 1,
                    )
                }
            }
        }
        AnimatedVisibility(
            bars,
            enter = slideInVertically(tween(220)) { it } + fadeIn(tween(220)),
            exit = slideOutVertically(tween(200)) { it } + fadeOut(tween(200)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Row(
                Modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth()
                    .background(colors.panel.copy(alpha = 0.96f), RoundedCornerShape(16.dp)).padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { ready?.prev?.let(::goTo) }, enabled = ready?.prev != null) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Предыдущая глава", tint = if (ready?.prev != null) colors.text else colors.secondary.copy(alpha = 0.4f))
                }
                IconButton(onClick = { sheet = Sheet.Chapters }) { Icon(AppIcons.Chapters, "Список глав", tint = colors.text) }
                IconButton(onClick = { sheet = Sheet.Comments }, enabled = ready != null) { Icon(AppIcons.Comment, "Комментарии", tint = colors.text) }
                IconButton(onClick = { sheet = Sheet.Bookmarks }, enabled = ready != null) { Icon(AppIcons.Bookmark, "Закладки", tint = colors.text) }
                IconButton(onClick = { sheet = Sheet.Settings }) { Icon(AppIcons.Tune, "Настройки чтения", tint = colors.text) }
                IconButton(onClick = { ready?.next?.let(::goTo) }, enabled = ready?.next != null) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, "Следующая глава", tint = if (ready?.next != null) colors.text else colors.secondary.copy(alpha = 0.4f))
                }
            }
        }
    }

    if (sheet != Sheet.None) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = sheet == Sheet.Comments || sheet == Sheet.Chapters)
        ModalBottomSheet(onDismissRequest = { sheet = Sheet.None }, sheetState = sheetState) {
            val d = (state as? Load.Ready)?.value
            when (sheet) {
                Sheet.Settings -> ReaderSettingsPanel(settings) { t -> app.store.updateSettings(t) }
                Sheet.Chapters -> ChaptersPanel(
                    all = app.chapterCache[slug].orEmpty(),
                    initialBranch = branchId,
                    current = volume to number,
                    downloaded = d?.let { DownloadFiles.downloadedIds(app, it.title.id) }.orEmpty(),
                ) { c, b -> sheet = Sheet.None; goTo(c, b) }
                Sheet.Comments -> if (d?.entry != null) CommentsPanel(slug, d.entry, branchId, d.chapter.id) else Text("Комментарии недоступны", Modifier.padding(24.dp))
                Sheet.Bookmarks -> if (d != null) BookmarksPanel(
                    titleId = d.title.id,
                    onAdd = {
                        vm.addBookmark(d, (list.firstVisibleItemIndex - 1).coerceAtLeast(0))
                        Toast.makeText(app, "Закладка добавлена", Toast.LENGTH_SHORT).show()
                    },
                    onOpen = { b ->
                        sheet = Sheet.None
                        if (b.volume == volume && b.number == number && b.branchId == branchId) {
                            scope.launch { list.scrollToItem(b.block + 1) }
                        } else {
                            nav.navigate(Routes.reader(b.slug, b.branchId, b.volume, b.number, startBlock = b.block))
                        }
                    },
                )
                Sheet.None -> Unit
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Яркость окна и «не гасить экран» — только пока открыта читалка. */
@Composable
private fun ScreenEffects(settings: ReaderSettings) {
    val view = LocalView.current
    val activity = LocalContext.current as? Activity
    DisposableEffect(settings.keepScreenOn) {
        view.keepScreenOn = settings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }
    DisposableEffect(settings.brightness) {
        val window = activity?.window
        val old = window?.attributes?.screenBrightness
        window?.let { w ->
            w.attributes = w.attributes.apply {
                screenBrightness = if (settings.brightness < 0) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else settings.brightness.coerceIn(0.02f, 1f)
            }
        }
        onDispose { window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = old ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE } } }
    }
}

@OptIn(FlowPreview::class)
@Composable
private fun ChapterText(
    vm: ReaderViewModel,
    d: ReaderViewModel.Data,
    settings: ReaderSettings,
    restore: Boolean,
    startBlock: Int,
    list: androidx.compose.foundation.lazy.LazyListState,
    onTapCenter: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    val app = LocalContext.current.shiori
    val colors = settings.colors()
    val norm = when (settings.textMode) {
        TextMode.Original -> NormalizationSettings.Off
        TextMode.Literary -> NormalizationSettings(dialogues = settings.dialogues, thoughts = settings.thoughts, mode = NormalizationMode.Extended)
    }
    val shown = remember(d.chapter, norm) { render(app, d, norm) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(d.chapter.id) {
        val p = app.store.progress(d.title.id)
        when {
            startBlock >= 0 -> list.scrollToItem((startBlock + 1).coerceAtMost(shown.size))
            restore && p != null && p.branchId == vm.branchId && p.volume == vm.volume && p.number == vm.number -> {
                list.scrollToItem((p.block + 1).coerceAtMost(shown.size), p.offset)
                AppLog.i("READER", "Позиция восстановлена: блок ${p.block}, смещение ${p.offset}")
            }
        }
        snapshotFlow { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }
            .debounce(400)
            .collect { (i, off) -> vm.saveProgress(d, (i - 1).coerceAtLeast(0), if (i == 0) 0 else off) }
    }
    val latest by rememberUpdatedState(list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset)
    DisposableEffect(d.chapter.id) {
        onDispose {
            val block = (latest.first - 1).coerceAtLeast(0)
            vm.saveProgress(d, block, if (latest.first == 0) 0 else latest.second)
            vm.syncToAccount(d, block)
        }
    }

    val family = remember(settings.font, settings.fontWeight) { settings.fontFamily() }
    val style = TextStyle(
        color = colors.text,
        fontSize = settings.fontSize.sp,
        fontWeight = FontWeight(settings.fontWeight),
        lineHeight = (settings.fontSize * settings.lineHeight).sp,
        letterSpacing = settings.letterSpacing.em,
        fontFamily = family,
        textAlign = if (settings.align == TextAlignMode.Justify) TextAlign.Justify else TextAlign.Start,
        textIndent = TextIndent(firstLine = settings.indent.em),
        hyphens = if (settings.hyphens) Hyphens.Auto else Hyphens.None,
        lineBreak = LineBreak.Paragraph,
    )
    val progress by remember { derivedStateOf { if (list.layoutInfo.totalItemsCount <= 1) 0 else (list.firstVisibleItemIndex * 100 / (list.layoutInfo.totalItemsCount - 1)).coerceIn(0, 100) } }
    val current by remember { derivedStateOf { list.firstVisibleItemIndex } }

    val clipboard = LocalContext.current.getSystemService(android.content.ClipboardManager::class.java)
    Box(Modifier.fillMaxSize()) {
      SelectionContainer {
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize().padding(horizontal = settings.sideMargin.dp).pointerInput(settings.tapToPage, shown) {
                detectTapGestures(
                    onDoubleTap = { pos ->
                        // Двойной тап — скопировать абзац целиком.
                        val item = list.layoutInfo.visibleItemsInfo.firstOrNull { pos.y.toInt() in it.offset until it.offset + it.size }
                        val block = shown.getOrNull((item?.index ?: 0) - 1) as? Shown.Text
                        if (block != null) {
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Shiori", block.text.text))
                            Toast.makeText(app, "Абзац скопирован", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onTap = { pos ->
                        val h = size.height
                        when {
                            settings.tapToPage && pos.y < h * 0.22f -> scope.launch { list.animateScrollBy(-h * 0.85f) }
                            settings.tapToPage && pos.y > h * 0.78f -> scope.launch { list.animateScrollBy(h * 0.85f) }
                            else -> onTapCenter()
                        }
                    },
                )
            },
        ) {
            item(key = "head") {
                Text(
                    "Том ${d.chapter.volume} Глава ${d.chapter.number}" + (d.chapter.name?.takeIf { it.isNotBlank() }?.let { " - $it" } ?: ""),
                    color = colors.text,
                    fontFamily = family,
                    fontWeight = FontWeight.Bold,
                    fontSize = (settings.fontSize * 1.45f).sp,
                    lineHeight = (settings.fontSize * 1.8f).sp,
                    modifier = Modifier.padding(top = 104.dp, bottom = 16.dp),
                )
            }
            if (d.chapter.restricted) {
                item(key = "locked") {
                    Text("Глава закрыта (платная или ранний доступ${d.chapter.price?.let { ", $it" } ?: ""}). Shiori не обходит платный доступ.", style = style)
                }
            } else if (shown.isEmpty()) {
                item(key = "empty") { Text("В главе нет текста (возможно, удалена правообладателем).", style = style) }
            }
            itemsIndexed(shown, key = { i, _ -> "b$i" }) { i, b ->
                val highlight = settings.highlightParagraph && i + 1 == current
                val mod = Modifier.fillMaxWidth()
                    .then(if (highlight) Modifier.background(colors.panel, RoundedCornerShape(6.dp)) else Modifier)
                    .padding(bottom = (settings.fontSize * settings.paragraphSpacing).dp)
                when (b) {
                    is Shown.Text -> {
                        val st = if (b.heading) style.copy(fontWeight = FontWeight.Bold, textIndent = TextIndent.None) else style
                        if (settings.outlineWidth > 0f) {
                            Box(mod) {
                                Text(b.text, style = st.copy(color = androidx.compose.ui.graphics.Color(settings.outlineColor), drawStyle = Stroke(width = settings.outlineWidth * 2f)))
                                Text(b.text, style = st)
                            }
                        } else {
                            Text(b.text, style = st, modifier = mod)
                        }
                    }
                    is Shown.Image -> AsyncImage(
                        model = b.model,
                        contentDescription = "Иллюстрация",
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    )
                    Shown.Rule -> Text("* * *", color = colors.secondary, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), textAlign = TextAlign.Center)
                }
            }
            item(key = "foot") {
                Row(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 120.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    OutlinedButton(enabled = d.prev != null, onClick = onPrev) { Text("← Пред. глава", color = colors.text) }
                    OutlinedButton(enabled = d.next != null, onClick = onNext) { Text(if (d.next != null) "След. глава →" else "Последняя глава", color = colors.text) }
                }
            }
        }
      }
        if (settings.showProgress) ReadingScrollbar(list, colors.secondary, Modifier.align(Alignment.CenterEnd))
    }
}

/** Полоса прочитанного справа (как на RanobeLib): тонкая дорожка и бегунок по положению в главе. */
@Composable
private fun ReadingScrollbar(list: androidx.compose.foundation.lazy.LazyListState, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    val fraction by remember {
        derivedStateOf {
            val info = list.layoutInfo
            val total = info.totalItemsCount
            val visible = info.visibleItemsInfo
            if (total == 0 || visible.isEmpty()) 0f
            else {
                val first = visible.first()
                val within = if (first.size > 0) (-first.offset).toFloat() / first.size else 0f
                val scrollable = (total - visible.size).coerceAtLeast(1)
                ((list.firstVisibleItemIndex + within) / scrollable).coerceIn(0f, 1f)
            }
        }
    }
    // Компактная дорожка по центру справа (~треть экрана), а не на всю высоту.
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxHeight(0.32f).width(10.dp).padding(end = 2.dp)) {
        val thumb = 28.dp
        Box(Modifier.align(Alignment.TopCenter).fillMaxHeight().width(2.dp).background(color.copy(alpha = 0.18f), RoundedCornerShape(1.dp)))
        Box(
            Modifier.align(Alignment.TopCenter)
                .padding(top = (maxHeight - thumb) * fraction)
                .height(thumb).width(4.dp)
                .background(color.copy(alpha = 0.75f), RoundedCornerShape(2.dp)),
        )
    }
}

private fun render(app: ShioriApp, d: ReaderViewModel.Data, norm: NormalizationSettings): List<Shown> = d.chapter.blocks.map { b ->
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
                            addStyle(SpanStyle(fontWeight = if (sp.bold) FontWeight.Bold else null, fontStyle = if (sp.italic) FontStyle.Italic else null), st, en)
                        }
                    }
                },
                b.heading,
            )
        }
        is ImageBlock -> Shown.Image(DownloadFiles.image(app, d.title.id, b.url).takeIf(File::exists) ?: b.url)
        Separator -> Shown.Rule
    }
}

