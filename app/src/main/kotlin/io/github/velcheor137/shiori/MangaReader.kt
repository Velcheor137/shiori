package io.github.velcheor137.shiori

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.Stable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.velcheor137.shiori.core.common.AppLog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

enum class MangaMode(val label: String) { Vertical("Лента"), LeftToRight("Слева направо"), RightToLeft("Справа налево") }

enum class MangaBackground(val label: String, val argb: Long) { Black("Чёрный", 0xFF000000), Dark("Тёмный", 0xFF2F3136), White("Белый", 0xFFFFFFFF) }

/** Настройки читалки манги (этап B); яркость и «не гасить экран» — общие с текстовой читалкой. */
@Serializable
data class MangaSettings(
    val mode: MangaMode = MangaMode.Vertical,
    val gap: Int = 0,
    val background: MangaBackground = MangaBackground.Black,
    /** Постраничный режим: вписывать страницу по высоте экрана (иначе — по ширине). */
    val fitHeight: Boolean = false,
    val tapZones: Boolean = true,
    val preloadNext: Boolean = true,
)

/** Масштаб щипком: один общий для ленты, свой у каждой страницы в постраничном режиме. */
@Stable
class ZoomState {
    var scale by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)
    fun reset() { scale = 1f; offset = Offset.Zero }
}

/** Щипок — зум 1–4×, при зуме один палец двигает картинку (в ленте — только по горизонтали). */
private fun Modifier.pinchZoom(zoom: ZoomState, panY: Boolean): Modifier = pointerInput(zoom, panY) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        do {
            val event = awaitPointerEvent()
            val pressed = event.changes.count { it.pressed }
            if (pressed >= 2) {
                zoom.scale = (zoom.scale * event.calculateZoom()).coerceIn(1f, 4f)
                val pan = event.calculatePan()
                zoom.offset = clamp(zoom.offset + pan, zoom.scale, size.width.toFloat(), size.height.toFloat(), panY)
                event.changes.forEach { it.consume() }
            } else if (zoom.scale > 1.01f && pressed == 1) {
                val pan = event.calculatePan()
                zoom.offset = clamp(zoom.offset + Offset(pan.x, if (panY) pan.y else 0f), zoom.scale, size.width.toFloat(), size.height.toFloat(), panY)
                if (panY) event.changes.forEach { it.consume() }
            }
            if (zoom.scale <= 1.01f) zoom.offset = Offset.Zero
        } while (event.changes.any { it.pressed })
    }
}

private fun clamp(o: Offset, scale: Float, w: Float, h: Float, panY: Boolean): Offset {
    val mx = (scale - 1f) * w / 2f
    val my = if (panY) (scale - 1f) * h / 2f else 0f
    return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
}

private fun Modifier.zoomed(zoom: ZoomState) = graphicsLayer {
    scaleX = zoom.scale; scaleY = zoom.scale; translationX = zoom.offset.x; translationY = zoom.offset.y
}

/**
 * Глава манги: лента или страницы. [page] — текущая страница (для прогресса и закладок),
 * [jump] — переход к странице из закладки.
 */
@OptIn(FlowPreview::class)
@Composable
fun MangaContent(
    vm: ReaderViewModel,
    d: ReaderViewModel.Data,
    restore: Boolean,
    startBlock: Int,
    page: MutableIntState,
    jump: MutableState<Int?>,
    onTapCenter: () -> Unit,
    onScrollStart: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    val app = LocalContext.current.shiori
    var settings by remember { mutableStateOf(app.ui.manga) }
    LaunchedEffect(Unit) { snapshotFlow { app.mangaSettingsVersion.intValue }.collect { settings = app.ui.manga } }
    val pages = d.chapter.pages
    val bg = Color(settings.background.argb)

    // Стартовая страница: из закладки, из прогресса или с начала.
    val start = remember(d.chapter.id) {
        val p = app.store.progress(d.title.id)
        when {
            startBlock >= 0 -> startBlock
            restore && p != null && p.branchId == vm.branchId && p.volume == vm.volume && p.number == vm.number -> p.block
            else -> 0
        }.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
    }
    LaunchedEffect(d.chapter.id) {
        page.intValue = start
        AppLog.i("MANGA", "Глава т${d.chapter.volume} г${d.chapter.number}: страниц ${pages.size}, старт со страницы ${start + 1}, режим ${settings.mode}")
        // Вся глава грузится постепенно от текущей страницы; затем, если включено, — следующая глава.
        app.mangaImages.preload(pages, start)
        if (settings.preloadNext) vm.nextChapterPages(d)?.let { app.mangaImages.preload(it, 0) }
    }
    LaunchedEffect(d.chapter.id) {
        snapshotFlow { page.intValue }.distinctUntilChanged().debounce(400).collect { vm.saveProgress(d, it, 0) }
    }
    androidx.compose.runtime.DisposableEffect(d.chapter.id) {
        onDispose {
            vm.saveProgress(d, page.intValue, 0)
            vm.syncToAccount(d, page.intValue)
        }
    }

    Box(Modifier.fillMaxSize().background(bg)) {
        when (settings.mode) {
            MangaMode.Vertical -> VerticalStrip(vm, d, settings, start, page, jump, onTapCenter, onScrollStart, onPrev, onNext)
            else -> Paged(vm, d, settings, start, page, jump, onTapCenter, onPrev, onNext)
        }
    }
}

private sealed interface StripItem {
    data class Part(val tile: Tile) : StripItem
    data object Foot : StripItem
}

@Composable
private fun VerticalStrip(
    vm: ReaderViewModel,
    d: ReaderViewModel.Data,
    settings: MangaSettings,
    start: Int,
    page: MutableIntState,
    jump: MutableState<Int?>,
    onTapCenter: () -> Unit,
    onScrollStart: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    val app = LocalContext.current.shiori
    val pages = d.chapter.pages
    // Размеры страниц без width/height в API узнаём после загрузки файла.
    val measured = remember(d.chapter.id) { mutableStateMapOf<Int, Pair<Int, Int>>() }
    val items = remember(pages, measured.toMap()) {
        pages.flatMapIndexed { i, p ->
            val (w, h) = if (p.width > 0 && p.height > 0) p.width to p.height else measured[i] ?: (0 to 0)
            MangaTiles.split(i, w, h).map { StripItem.Part(it) }
        } + StripItem.Foot
    }
    val firstIndexOfPage = remember(items) { items.withIndex().filter { (_, it) -> it is StripItem.Part && it.tile.index == 0 }.associate { (i, it) -> (it as StripItem.Part).tile.page to i } }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = firstIndexOfPage[start] ?: 0)
    val zoom = remember { ZoomState() }
    val scope = rememberCoroutineScope()
    val cc = d.entry?.let { rememberChapterComments(vm.slug, it, vm.branchId, d.chapter.id) }
    var commentsStarted by remember(d.chapter.id) { mutableStateOf(false) }
    val offline by app.offline.collectAsStateWithLifecycle()
    val inline = if (commentsStarted && cc != null && !offline) rememberInlineComments(cc) else null

    LaunchedEffect(list) { snapshotFlow { list.isScrollInProgress }.collect { if (it) onScrollStart() } }
    LaunchedEffect(list, items) {
        snapshotFlow { list.firstVisibleItemIndex }.collect { idx ->
            (items.getOrNull(idx) as? StripItem.Part)?.let { page.intValue = it.tile.page }
        }
    }
    LaunchedEffect(jump.value) {
        val target = jump.value ?: return@LaunchedEffect
        firstIndexOfPage[target]?.let { list.scrollToItem(it) }
        jump.value = null
    }

    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthPx = with(density) { maxWidth.roundToPx() }
        val h = constraints.maxHeight.toFloat()
        Box(
            Modifier.fillMaxSize().pinchZoom(zoom, panY = false).pointerInput(settings.tapZones) {
                detectTapGestures(
                    onDoubleTap = { if (zoom.scale > 1.01f) zoom.reset() else zoom.scale = 2f },
                    onTap = { pos ->
                        when {
                            settings.tapZones && pos.y < h * 0.25f -> scope.launch { list.animateScrollBy(-h * 0.8f) }
                            settings.tapZones && pos.y > h * 0.75f -> scope.launch { list.animateScrollBy(h * 0.8f) }
                            else -> onTapCenter()
                        }
                    },
                )
            },
        ) {
            LazyColumn(state = list, modifier = Modifier.fillMaxSize().zoomed(zoom)) {
                items.forEach { si ->
                    when (si) {
                        is StripItem.Part -> item(key = "p${si.tile.page}t${si.tile.index}") {
                            val p = pages[si.tile.page]
                            val gap = if (si.tile.index == 0 && si.tile.page > 0) settings.gap.dp else 0.dp
                            Column(Modifier.fillMaxWidth().padding(top = gap)) {
                                TileImage(p.path, si.tile, widthPx) { w, hh -> if (p.width <= 0) measured[si.tile.page] = w to hh }
                            }
                        }
                        StripItem.Foot -> item(key = "foot") {
                            LaunchedEffect(Unit) { commentsStarted = true }
                            ChapterEnd(d, settings, onPrev, onNext)
                        }
                    }
                }
                val tint: @Composable (@Composable () -> Unit) -> Unit = { content -> MangaTint(settings) { Box(Modifier.padding(horizontal = 16.dp)) { content() } } }
                if (inline != null) inlineComments(inline, tint)
                else if (offline && cc != null) item(key = "c-offline") { tint { OfflineCommentsNote() } }
            }
        }
    }
}

/** Фрагмент страницы с плейсхолдером нужной высоты (без прыжков ленты) и повтором при ошибке. */
@Composable
private fun TileImage(path: String, tile: Tile, widthPx: Int, onMeasured: (Int, Int) -> Unit) {
    val app = LocalContext.current.shiori
    var attempt by remember { mutableIntStateOf(0) }
    val result by produceState<Result<Bitmap>?>(null, path, tile, attempt) {
        value = runCatching { app.mangaImages.tile(path, tile, widthPx) }
        if (tile.width <= 0) value?.getOrNull()?.let {
            val (w, h) = app.mangaImages.bounds(app.mangaImages.file(path))
            if (w > 0 && h > 0) onMeasured(w, h)
        }
    }
    val aspect = if (tile.width > 0) tile.aspect else result?.getOrNull()?.let { it.width.toFloat() / it.height } ?: 0.7f
    Box(Modifier.fillMaxWidth().aspectRatio(aspect.coerceIn(0.05f, 20f)), contentAlignment = Alignment.Center) {
        val r = result
        when {
            r == null -> CircularProgressIndicator(Modifier.padding(16.dp), color = Color.Gray)
            r.isSuccess -> Image(r.getOrThrow().asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Страница ${tile.page + 1} не загрузилась", color = Color.Gray)
                TextButton(onClick = { attempt++ }) { Text("Повторить") }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Paged(
    vm: ReaderViewModel,
    d: ReaderViewModel.Data,
    settings: MangaSettings,
    start: Int,
    page: MutableIntState,
    jump: MutableState<Int?>,
    onTapCenter: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    val app = LocalContext.current.shiori
    val pages = d.chapter.pages
    val rtl = settings.mode == MangaMode.RightToLeft
    // Последняя «страница» — конец главы с комментариями.
    val pager = rememberPagerState(initialPage = start) { pages.size + 1 }
    val scope = rememberCoroutineScope()
    val zooms = remember(d.chapter.id) { mutableStateMapOf<Int, ZoomState>() }
    val zoomed by remember { derivedStateOf { (zooms[pager.currentPage]?.scale ?: 1f) > 1.01f } }
    LaunchedEffect(pager) { snapshotFlow { pager.currentPage }.collect { if (it < pages.size) page.intValue = it } }
    LaunchedEffect(jump.value) {
        val target = jump.value ?: return@LaunchedEffect
        pager.scrollToPage(target.coerceIn(0, pages.size))
        jump.value = null
    }
    val cc = d.entry?.let { rememberChapterComments(vm.slug, it, vm.branchId, d.chapter.id) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val maxSide = maxOf(constraints.maxWidth, constraints.maxHeight)
        HorizontalPager(
            state = pager,
            reverseLayout = rtl,
            userScrollEnabled = !zoomed,
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize(),
            key = { it },
        ) { i ->
            if (i == pages.size) {
                val list = rememberLazyListState()
                val offline by app.offline.collectAsStateWithLifecycle()
                val inline = if (!offline) cc?.let { rememberInlineComments(it) } else null
                LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
                    item { ChapterEnd(d, settings, onPrev, onNext) }
                    if (inline != null) inlineComments(inline) { content -> MangaTint(settings) { Box(Modifier.padding(horizontal = 16.dp)) { content() } } }
                    else if (offline) item { MangaTint(settings) { Box(Modifier.padding(horizontal = 16.dp)) { OfflineCommentsNote() } } }
                }
            } else {
            val zoom = zooms.getOrPut(i) { ZoomState() }
            Box(
                Modifier.fillMaxSize().pinchZoom(zoom, panY = true).pointerInput(settings.tapZones, rtl) {
                    detectTapGestures(
                        onDoubleTap = { if (zoom.scale > 1.01f) zoom.reset() else zoom.scale = 2f },
                        onTap = { pos ->
                            val left = pos.x < w * 0.3f
                            val right = pos.x > w * 0.7f
                            when {
                                settings.tapZones && (left || right) && zoom.scale <= 1.01f -> {
                                    val forward = if (rtl) left else right
                                    scope.launch { pager.animateScrollToPage((pager.currentPage + if (forward) 1 else -1).coerceIn(0, pages.size)) }
                                }
                                else -> onTapCenter()
                            }
                        },
                    )
                },
                contentAlignment = Alignment.Center,
            ) {
                var attempt by remember { mutableIntStateOf(0) }
                val result by produceState<Result<Bitmap>?>(null, i, attempt) { value = runCatching { app.mangaImages.whole(pages[i].path, maxSide) } }
                val r = result
                when {
                    r == null -> CircularProgressIndicator(color = Color.Gray)
                    r.isSuccess -> Image(
                        r.getOrThrow().asImageBitmap(),
                        "Страница ${i + 1}",
                        Modifier.fillMaxSize().zoomed(zoom),
                        contentScale = if (settings.fitHeight) ContentScale.FillHeight else ContentScale.Fit,
                    )
                    else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Страница ${i + 1} не загрузилась", color = Color.Gray)
                        TextButton(onClick = { attempt++ }) { Text("Повторить") }
                    }
                }
            }
            }
        }
    }
}

/** Цвета ленты под фон читалки. */
@Composable
private fun MangaTint(settings: MangaSettings, content: @Composable () -> Unit) {
    val light = settings.background == MangaBackground.White
    val text = if (light) Color(0xFF202124) else Color(0xFFDCDDDE)
    val secondary = if (light) Color(0xFF5F6368) else Color(0xFF9A9CA0)
    val base = MaterialTheme.colorScheme
    val scheme = remember(settings.background, base) {
        base.copy(
            background = Color(settings.background.argb), surface = Color(settings.background.argb), onSurface = text, onBackground = text,
            onSurfaceVariant = secondary, outline = secondary.copy(alpha = 0.25f), outlineVariant = secondary.copy(alpha = 0.25f),
        )
    }
    MaterialTheme(colorScheme = scheme) {
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides text) { content() }
    }
}

@Composable
private fun ChapterEnd(d: ReaderViewModel.Data, settings: MangaSettings, onPrev: () -> Unit, onNext: () -> Unit) {
    MangaTint(settings) {
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Конец главы ${d.chapter.number}", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                OutlinedButton(enabled = d.prev != null, onClick = onPrev) { Text("← Пред. глава") }
                OutlinedButton(enabled = d.next != null, onClick = onNext) { Text(if (d.next != null) "След. глава →" else "Последняя глава") }
            }
        }
    }
}

/** Настройки читалки манги в нижнем окне. */
@Composable
fun MangaSettingsPanel(readerSettings: ReaderSettings, onReader: (ReaderSettings) -> Unit) {
    val app = LocalContext.current.shiori
    var s by remember { mutableStateOf(app.ui.manga) }
    fun update(n: MangaSettings) {
        s = n
        app.ui.manga = n
        app.mangaSettingsVersion.intValue++
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Чтение манги", style = MaterialTheme.typography.titleMedium)
        Label("Режим")
        ChipRow(MangaMode.entries, s.mode, { it.label }) { update(s.copy(mode = it)) }
        if (s.mode == MangaMode.Vertical) {
            Label("Отступ между страницами")
            ChipRow(listOf(0, 4, 8, 16), s.gap, { if (it == 0) "Нет" else "$it dp" }) { update(s.copy(gap = it)) }
        } else {
            Label("Масштаб страницы")
            ChipRow(listOf(false, true), s.fitHeight, { if (it) "По высоте" else "Целиком" }) { update(s.copy(fitHeight = it)) }
        }
        Label("Фон")
        ChipRow(MangaBackground.entries, s.background, { it.label }) { update(s.copy(background = it)) }
        SwitchRow(if (s.mode == MangaMode.Vertical) "Листать касанием сверху/снизу" else "Листать касанием по краям", s.tapZones) { update(s.copy(tapZones = it)) }
        SwitchRow("Заранее загружать следующую главу", s.preloadNext) { update(s.copy(preloadNext = it)) }
        SwitchRow("Не гасить экран", readerSettings.keepScreenOn) { onReader(readerSettings.copy(keepScreenOn = it)) }
        Label("Яркость" + if (readerSettings.brightness < 0) ": как в системе" else "")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = if (readerSettings.brightness < 0) 0.5f else readerSettings.brightness,
                onValueChange = { onReader(readerSettings.copy(brightness = it.coerceIn(0.02f, 1f))) },
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { onReader(readerSettings.copy(brightness = -1f)) }) { Text("Авто") }
        }
        Text("Двойное касание — увеличить, щипок — масштаб.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Label(text: String) = Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun <T> ChipRow(values: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        values.forEach { v -> FilterChip(selected = v == selected, onClick = { onPick(v) }, label = { Text(label(v)) }) }
    }
}

/** Окно «Выберите страницу»: ползунок с делениями по страницам, переход сразу при перетаскивании. */
@Composable
fun PagePicker(page: Int, total: Int, panel: Color, text: Color, onPick: (Int) -> Unit, onClose: () -> Unit) {
    var value by remember(total) { mutableFloatStateOf(page.toFloat()) }
    Column(
        Modifier.windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.navigationBars)
            .padding(horizontal = 12.dp, vertical = 12.dp).fillMaxWidth()
            .background(panel.copy(alpha = 0.97f), androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
            .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Выберите страницу", Modifier.weight(1f), color = text, style = MaterialTheme.typography.titleMedium)
            Text("${value.toInt() + 1} / $total", color = text.copy(alpha = 0.7f), style = MaterialTheme.typography.labelLarge)
            androidx.compose.material3.IconButton(onClick = onClose) {
                androidx.compose.material3.Icon(Icons.Filled.Close, "Закрыть", tint = text)
            }
        }
        if (total > 1) {
            Slider(
                value = value,
                onValueChange = { v ->
                    val p = kotlin.math.round(v).toInt()
                    if (p != value.toInt()) onPick(p)
                    value = p.toFloat()
                },
                valueRange = 0f..(total - 1).toFloat(),
                steps = (total - 2).coerceIn(0, 200),
                modifier = Modifier.padding(end = 12.dp),
            )
        }
    }
}
