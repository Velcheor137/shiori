package io.github.velcheor137.shiori.engine.document

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Стиль в черновике комментария. [block] — применяется к строкам целиком (цитата, блок-спойлер). */
enum class DraftStyle(val block: Boolean, val mark: String?) {
    Bold(false, "bold"),
    Italic(false, "italic"),
    Underline(false, "underline"),
    Strike(false, "strike"),
    Spoiler(false, null),
    Quote(true, null),
    SpoilerBlock(true, null),
}

/** Диапазон стиля [start, end). [title] — заголовок блок-спойлера (на сайте до 32 символов). */
data class DraftRange(val style: DraftStyle, val start: Int, val end: Int, val title: String? = null)

/**
 * Черновик комментария для редактора: обычный текст и диапазоны стилей, которые сдвигаются при наборе.
 * Строка текста = абзац; цитата и блок-спойлер покрывают строки целиком. Сборка — в ProseMirror-документ
 * редактора сайта (`bold`/`italic`/`underline`/`strike`, `blockquote`, `spoilerInline`, `spoilerBlock {visibleText}`).
 */
data class CommentDraft(val text: String = "", val ranges: List<DraftRange> = emptyList()) {

    /** Новый текст после правки в поле ввода; [pending] — стили, включённые/выключенные кнопкой без выделения. */
    fun edit(newText: String, pending: Map<DraftStyle, Boolean> = emptyMap()): CommentDraft {
        if (newText == text) return this
        val old = text
        var p = 0
        val maxP = minOf(old.length, newText.length)
        while (p < maxP && old[p] == newText[p]) p++
        var s = 0
        while (s < minOf(old.length, newText.length) - p && old[old.length - 1 - s] == newText[newText.length - 1 - s]) s++
        val remEnd = old.length - s
        val ins = newText.length - s - p
        val delta = ins - (remEnd - p)
        // Набор в конце диапазона продолжает стиль, в начале — нет; заменённый внутри текст стиль сохраняет.
        fun map(pos: Int): Int = when {
            pos < p -> pos
            pos >= remEnd && pos > p -> pos + delta
            else -> p + ins
        }
        var out = ranges.mapNotNull { r ->
            val a = map(r.start)
            val b = map(r.end)
            if (b > a) r.copy(start = a, end = b) else null
        }
        if (ins > 0 && pending.isNotEmpty()) {
            pending.forEach { (style, on) ->
                out = if (on) out + DraftRange(style, p, p + ins, defaultTitle(style)) else subtract(out, style, p, p + ins)
            }
        }
        return CommentDraft(newText, normalize(out, newText.length))
    }

    /** Есть ли стиль на выделении (для курсора — на символе слева от него). */
    fun isActive(style: DraftStyle, selStart: Int, selEnd: Int): Boolean {
        if (style.block) {
            val (s, e) = lineBounds(selStart, selEnd)
            return lineHas(style, s, e) != null
        }
        val s = minOf(selStart, selEnd)
        val e = maxOf(selStart, selEnd)
        return if (s == e) ranges.any { it.style == style && it.start < s && s <= it.end } else covered(style, s, e)
    }

    /** Диапазон блочного стиля, который касается строки [st, en] (для пустой строки достаточно касания). */
    private fun lineHas(style: DraftStyle, st: Int, en: Int): DraftRange? = ranges.firstOrNull {
        it.style == style && if (en > st) it.start < en && it.end > st else it.start <= en && it.end >= st
    }

    /** Включить/выключить стиль на выделении; для блочных — на строках, которых касается выделение. */
    fun toggle(style: DraftStyle, selStart: Int, selEnd: Int, title: String? = null): CommentDraft {
        val (s, e) = if (style.block) lineBounds(selStart, selEnd) else minOf(selStart, selEnd) to maxOf(selStart, selEnd)
        if (s == e) return this
        val next = if (covered(style, s, e)) subtract(ranges, style, s, e) else ranges + DraftRange(style, s, e, title ?: defaultTitle(style))
        return copy(ranges = normalize(next, text.length))
    }

    fun lineBounds(selStart: Int, selEnd: Int): Pair<Int, Int> {
        val a = minOf(selStart, selEnd).coerceIn(0, text.length)
        val b = maxOf(selStart, selEnd).coerceIn(0, text.length)
        val s = text.lastIndexOf('\n', a - 1) + 1
        val e = text.indexOf('\n', b).let { if (it < 0) text.length else it }
        return s to e
    }

    private fun covered(style: DraftStyle, s: Int, e: Int): Boolean {
        var pos = s
        for (r in ranges.filter { it.style == style }.sortedBy { it.start }) {
            if (r.start > pos) break
            if (r.end > pos) pos = r.end
            if (pos >= e) return true
        }
        return pos >= e
    }

    val isBlank: Boolean get() = text.isBlank()

    /** ProseMirror-документ `{type:"doc", content:[…]}` для `POST /api/comments` и `PUT /api/comments/{id}`. */
    fun toDoc(): JsonObject {
        val lines = text.split('\n')
        var first = 0
        var last = lines.lastIndex
        while (first <= last && lines[first].isBlank()) first++
        while (last >= first && lines[last].isBlank()) last--
        data class Line(val start: Int, val end: Int, val block: DraftStyle?, val title: String?)
        val all = ArrayList<Line>()
        var off = 0
        lines.forEachIndexed { i, l ->
            val st = off
            val en = off + l.length
            off = en + 1
            if (i < first || i > last) return@forEachIndexed
            val spoiler = lineHas(DraftStyle.SpoilerBlock, st, en)
            val quote = lineHas(DraftStyle.Quote, st, en) != null
            all += Line(st, en, if (spoiler != null) DraftStyle.SpoilerBlock else if (quote) DraftStyle.Quote else null, spoiler?.title)
        }
        val content = buildJsonArray {
            var i = 0
            while (i < all.size) {
                val l = all[i]
                if (l.block == null) {
                    add(paragraph(l.start, l.end))
                    i++
                    continue
                }
                var j = i
                while (j + 1 < all.size && all[j + 1].block == l.block && all[j + 1].title == l.title) j++
                val inner = buildJsonArray { for (k in i..j) add(paragraph(all[k].start, all[k].end)) }
                add(
                    buildJsonObject {
                        if (l.block == DraftStyle.SpoilerBlock) {
                            put("type", "spoilerBlock")
                            putJsonObject("attrs") { put("visibleText", l.title?.takeIf { it.isNotBlank() } ?: "спойлер") }
                        } else {
                            put("type", "blockquote")
                        }
                        put("content", inner)
                    },
                )
                i = j + 1
            }
        }
        return buildJsonObject {
            put("type", "doc")
            put("content", content)
        }
    }

    private fun paragraph(start: Int, end: Int): JsonObject = buildJsonObject {
        put("type", "paragraph")
        val inline = inlineNodes(start, end)
        if (inline.isNotEmpty()) put("content", inline)
    }

    private fun inlineNodes(start: Int, end: Int): JsonArray {
        if (end <= start) return JsonArray(emptyList())
        val inl = ranges.filter { !it.style.block && it.start < end && it.end > start }
        val cuts = (inl.flatMap { listOf(it.start, it.end) } + start + end).filter { it in start..end }.toSortedSet().toList()
        data class Seg(val a: Int, val b: Int, val marks: List<String>, val spoiler: Boolean)
        val segs = cuts.zipWithNext().filter { (a, b) -> b > a }.map { (a, b) ->
            val active = inl.filter { it.start <= a && it.end >= b }.map { it.style }.toSet()
            Seg(a, b, DraftStyle.entries.filter { it in active && it.mark != null }.map { it.mark!! }, DraftStyle.Spoiler in active)
        }
        fun textNode(sg: Seg) = buildJsonObject {
            put("type", "text")
            put("text", text.substring(sg.a, sg.b))
            if (sg.marks.isNotEmpty()) putJsonArray("marks") { sg.marks.forEach { m -> add(buildJsonObject { put("type", m) }) } }
        }
        return buildJsonArray {
            var i = 0
            while (i < segs.size) {
                if (!segs[i].spoiler) {
                    add(textNode(segs[i]))
                    i++
                    continue
                }
                var j = i
                while (j + 1 < segs.size && segs[j + 1].spoiler) j++
                val group = segs.subList(i, j + 1)
                add(
                    buildJsonObject {
                        put("type", "spoilerInline")
                        putJsonObject("attrs") { put("visibleText", "спойлер") }
                        putJsonArray("content") { group.forEach { add(textNode(it)) } }
                    },
                )
                i = j + 1
            }
        }
    }

    companion object {
        fun defaultTitle(style: DraftStyle): String? = if (style == DraftStyle.SpoilerBlock) "спойлер" else null

        /** Склеивает пересекающиеся и соседние диапазоны одного стиля (блок-спойлеры — только с тем же заголовком). */
        fun normalize(list: List<DraftRange>, length: Int): List<DraftRange> {
            val out = ArrayList<DraftRange>()
            list.map { it.copy(start = it.start.coerceIn(0, length), end = it.end.coerceIn(0, length)) }
                .filter { it.end > it.start }
                .groupBy { it.style to (if (it.style == DraftStyle.SpoilerBlock) it.title else null) }
                .forEach { (_, rs) ->
                    var cur: DraftRange? = null
                    rs.sortedBy { it.start }.forEach { r ->
                        val c = cur
                        cur = if (c != null && r.start <= c.end) c.copy(end = maxOf(c.end, r.end)) else { c?.let(out::add); r }
                    }
                    cur?.let(out::add)
                }
            return out.sortedWith(compareBy({ it.start }, { it.style.ordinal }))
        }

        fun subtract(list: List<DraftRange>, style: DraftStyle, s: Int, e: Int): List<DraftRange> = list.flatMap { r ->
            if (r.style != style || r.end <= s || r.start >= e) listOf(r)
            else listOfNotNull(r.copy(end = s).takeIf { s > r.start }, r.copy(start = e).takeIf { e < r.end })
        }

        /** Черновик из разобранного комментария (для редактирования своего комментария). */
        fun fromBlocks(blocks: List<Block>): CommentDraft {
            val sb = StringBuilder()
            val ranges = ArrayList<DraftRange>()
            fun para(p: Paragraph) {
                val base = sb.length
                sb.append(p.text)
                p.spans.forEach { sp ->
                    val a = base + sp.start
                    val b = base + sp.end
                    if (sp.bold) ranges += DraftRange(DraftStyle.Bold, a, b)
                    if (sp.italic) ranges += DraftRange(DraftStyle.Italic, a, b)
                    if (sp.underline) ranges += DraftRange(DraftStyle.Underline, a, b)
                    if (sp.strike) ranges += DraftRange(DraftStyle.Strike, a, b)
                    if (sp.spoiler != 0) ranges += DraftRange(DraftStyle.Spoiler, a, b)
                }
            }
            fun walk(list: List<Block>, wrap: DraftStyle?, title: String?) {
                list.forEach { b ->
                    when (b) {
                        is Paragraph -> {
                            if (sb.isNotEmpty()) sb.append('\n')
                            val a = sb.length
                            para(b)
                            if (wrap != null) ranges += DraftRange(wrap, a, sb.length, title)
                        }
                        is QuoteBlock -> walk(b.blocks, DraftStyle.Quote, null)
                        is SpoilerBlock -> walk(b.blocks, DraftStyle.SpoilerBlock, b.title)
                        else -> Unit
                    }
                }
            }
            walk(blocks, null, null)
            return CommentDraft(sb.toString(), normalize(ranges, sb.length))
        }
    }
}
