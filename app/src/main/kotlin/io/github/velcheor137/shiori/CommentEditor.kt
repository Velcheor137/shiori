package io.github.velcheor137.shiori

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.velcheor137.shiori.engine.document.CommentDraft
import io.github.velcheor137.shiori.engine.document.DraftStyle

/**
 * Редактор комментария на весь экран, как окно «Написать комментарий» сайта: жирный, курсив, подчёркнутый,
 * зачёркнутый, спойлер в строке, цитата и блок-спойлер с заголовком. Без выделения кнопка включает стиль
 * для следующего набранного текста.
 */
@Composable
fun CommentEditorDialog(
    title: String,
    initial: CommentDraft,
    sendLabel: String,
    onDismiss: (CommentDraft) -> Unit,
    onSend: (CommentDraft, (String?) -> Unit) -> Unit,
) {
    var draft by remember { mutableStateOf(initial) }
    var value by remember { mutableStateOf(TextFieldValue(initial.text, TextRange(initial.text.length))) }
    var pending by remember { mutableStateOf<Map<DraftStyle, Boolean>>(emptyMap()) }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var askTitle by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    val colors = EditorColors(
        spoiler = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.22f),
        quote = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
        quoteText = MaterialTheme.colorScheme.onSurfaceVariant,
        block = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.14f),
    )
    val transformation = remember(draft, colors) {
        VisualTransformation { t -> TransformedText(if (t.text == draft.text) styled(draft, colors) else t, OffsetMapping.Identity) }
    }

    fun active(style: DraftStyle): Boolean = pending[style] ?: draft.isActive(style, value.selection.start, value.selection.end)

    fun press(style: DraftStyle, title: String? = null) {
        val sel = value.selection
        val (ls, le) = draft.lineBounds(sel.min, sel.max)
        val target = if (style.block) ls to le else sel.min to sel.max
        if (target.first == target.second) {
            pending = pending + (style to !active(style))
        } else {
            draft = draft.toggle(style, sel.min, sel.max, title)
        }
    }

    Dialog(onDismissRequest = { if (!sending) onDismiss(draft) }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (!sending) onDismiss(draft) }) { Icon(Icons.Filled.Close, "Закрыть") }
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Button(
                        enabled = !draft.isBlank && !sending,
                        onClick = {
                            sending = true
                            error = null
                            onSend(draft) { err ->
                                sending = false
                                error = err
                            }
                        },
                        modifier = Modifier.padding(end = 8.dp),
                    ) { Text(if (sending) "Отправляю…" else sendLabel) }
                }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ToolButton("B", active(DraftStyle.Bold), "Жирный", weight = FontWeight.Bold) { press(DraftStyle.Bold) }
                    ToolButton("I", active(DraftStyle.Italic), "Курсив", italic = true) { press(DraftStyle.Italic) }
                    ToolButton("U", active(DraftStyle.Underline), "Подчёркнутый", deco = TextDecoration.Underline) { press(DraftStyle.Underline) }
                    ToolButton("S", active(DraftStyle.Strike), "Зачёркнутый", deco = TextDecoration.LineThrough) { press(DraftStyle.Strike) }
                    ToolButton("Спойлер", active(DraftStyle.Spoiler), "Спойлер в тексте") { press(DraftStyle.Spoiler) }
                    ToolButton("❝ Цитата", active(DraftStyle.Quote), "Цитата") { press(DraftStyle.Quote) }
                    ToolButton("Блок-спойлер", active(DraftStyle.SpoilerBlock), "Спойлер блоком с заголовком") {
                        if (active(DraftStyle.SpoilerBlock)) press(DraftStyle.SpoilerBlock) else askTitle = true
                    }
                }
                HorizontalDivider()
                error?.let {
                    Text("Не отправлено: $it", Modifier.padding(horizontal = 16.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Box(Modifier.weight(1f).fillMaxWidth().padding(16.dp)) {
                    BasicTextField(
                        value = value,
                        onValueChange = { v ->
                            if (v.text != value.text) {
                                draft = draft.edit(v.text, pending)
                            } else if (v.selection != value.selection) {
                                pending = emptyMap()
                            }
                            value = v
                        },
                        visualTransformation = transformation,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxSize().heightIn(min = 120.dp).focusRequester(focus),
                        decorationBox = { inner ->
                            if (value.text.isEmpty()) {
                                Text("Текст комментария", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            inner()
                        },
                    )
                }
                Text(
                    "Выделите текст и нажмите кнопку, или включите стиль и печатайте. Цитата и блок-спойлер — на всю строку.",
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (askTitle) {
        var name by remember { mutableStateOf("спойлер") }
        AlertDialog(
            onDismissRequest = { askTitle = false },
            title = { Text("Заголовок спойлера") },
            text = {
                OutlinedTextField(name, { name = it.take(32) }, singleLine = true, supportingText = { Text("${name.length}/32") })
            },
            confirmButton = {
                TextButton(onClick = {
                    askTitle = false
                    press(DraftStyle.SpoilerBlock, name.trim().ifEmpty { "спойлер" })
                }) { Text("Применить") }
            },
            dismissButton = { TextButton(onClick = { askTitle = false }) { Text("Отмена") } },
        )
    }
}

private data class EditorColors(val spoiler: Color, val quote: Color, val quoteText: Color, val block: Color)

private fun styled(d: CommentDraft, c: EditorColors): AnnotatedString = buildAnnotatedString {
    append(d.text)
    d.ranges.forEach { r ->
        val st = r.start.coerceIn(0, d.text.length)
        val en = r.end.coerceIn(st, d.text.length)
        if (en <= st) return@forEach
        val style = when (r.style) {
            DraftStyle.Bold -> SpanStyle(fontWeight = FontWeight.Bold)
            DraftStyle.Italic -> SpanStyle(fontStyle = FontStyle.Italic)
            DraftStyle.Underline -> SpanStyle(textDecoration = TextDecoration.Underline)
            DraftStyle.Strike -> SpanStyle(textDecoration = TextDecoration.LineThrough)
            DraftStyle.Spoiler -> SpanStyle(background = c.spoiler)
            DraftStyle.Quote -> SpanStyle(background = c.quote, color = c.quoteText)
            DraftStyle.SpoilerBlock -> SpanStyle(background = c.block)
        }
        addStyle(style, st, en)
    }
    // Подчёркнутый и зачёркнутый вместе.
    val u = d.ranges.filter { it.style == DraftStyle.Underline }
    d.ranges.filter { it.style == DraftStyle.Strike }.forEach { s ->
        u.forEach { x ->
            val a = maxOf(s.start, x.start)
            val b = minOf(s.end, x.end)
            if (b > a) addStyle(SpanStyle(textDecoration = TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))), a, b)
        }
    }
}

@Composable
private fun ToolButton(
    label: String,
    active: Boolean,
    description: String,
    weight: FontWeight? = null,
    italic: Boolean = false,
    deco: TextDecoration? = null,
    onClick: () -> Unit,
) {
    Box(
        Modifier.clip(RoundedCornerShape(8.dp))
            .background(if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .clickable(onClickLabel = description, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontWeight = weight,
            fontStyle = if (italic) FontStyle.Italic else null,
            textDecoration = deco,
            fontSize = 15.sp,
            color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        )
    }
}
