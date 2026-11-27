package io.github.velcheor137.shiori.engine.document

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/** Промежуточная модель главы (§9.2 плана): одинакова для HTML и ProseMirror. */
sealed interface Block

data class Paragraph(val text: String, val spans: List<Span>, val heading: Boolean = false) : Block

data class ImageBlock(val url: String) : Block

data object Separator : Block

/** Цитата (только в комментариях, режим `rich`). */
data class QuoteBlock(val blocks: List<Block>) : Block

/** Скрытый блок «спойлер» с заголовком (только в комментариях, режим `rich`). */
data class SpoilerBlock(val title: String, val blocks: List<Block>) : Block

/** [spoiler] — номер скрытого фрагмента внутри текста (0 — не спойлер); фрагменты открываются по отдельности. */
data class Span(
    val start: Int,
    val end: Int,
    val bold: Boolean,
    val italic: Boolean,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val spoiler: Int = 0,
)

data class Attachment(val name: String?, val id: String?, val url: String)

/**
 * Разбор поля `content` главы: HTML-строка или ProseMirror JSON (F-07 плана). Скрипты, стили и
 * атрибуты отбрасываются; ссылки на картинки — только https.
 */
object DocumentParser {
    /**
     * [rich] — разметка комментариев: цитаты, спойлеры (блоком и в строке), подчёркнутый и зачёркнутый текст.
     * Для глав выключено: там нумерация абзацев должна оставаться плоской.
     */
    fun parse(content: JsonElement?, attachments: List<Attachment>, siteOrigin: String = "https://ranobelib.me", rich: Boolean = false): List<Block> =
        when {
            content == null -> emptyList()
            content is JsonPrimitive && content.isString -> parseHtml(content.content, siteOrigin, rich)
            content is JsonObject -> parseProseMirror(content, attachments, siteOrigin, rich)
            else -> emptyList()
        }.clean()

    private fun List<Block>.clean(): List<Block> = mapNotNull { b ->
        when (b) {
            is Paragraph -> b.takeUnless { it.text.isBlank() && it.spans.isEmpty() && !it.heading }
            is QuoteBlock -> b.blocks.clean().takeIf { it.isNotEmpty() }?.let(::QuoteBlock)
            is SpoilerBlock -> b.copy(blocks = b.blocks.clean())
            else -> b
        }
    }

    fun parseHtml(html: String, siteOrigin: String = "https://ranobelib.me", rich: Boolean = false): List<Block> {
        val body = Jsoup.parseBodyFragment(html).body()
        body.select("script, style, iframe, object, embed, form, svg").remove()
        var spoilers = 0
        fun parseNodes(nodes: List<Node>, base: Style): List<Block> {
            val out = ArrayList<Block>()
            val inline = InlineBuilder()
            fun flush(heading: Boolean = false) {
                inline.build(heading)?.let(out::add)
            }
            fun walkBlock(node: Node, st: Style) {
                when (node) {
                    is TextNode -> inline.append(node.text(), st)
                    is Element -> {
                        val tag = node.normalName()
                        if (rich) {
                            val cls = node.classNames()
                            when {
                                tag == "blockquote" -> {
                                    flush()
                                    out += QuoteBlock(parseNodes(node.childNodes(), st))
                                    return
                                }
                                "spoiler-node" in cls && node.attr("data-spoiler-type") == "block" -> {
                                    flush()
                                    val inner = node.selectFirst(".spoiler-node__text") ?: node
                                    out += SpoilerBlock(node.attr("data-spoiler-text").trim().ifEmpty { "Спойлер" }, parseNodes(inner.childNodes(), st))
                                    return
                                }
                                "comment__spoiler" in cls -> {
                                    flush()
                                    val title = node.selectFirst(".spoiler-title")?.text()?.trim().orEmpty()
                                    val inner = node.selectFirst(".spoiler-text") ?: node
                                    out += SpoilerBlock(title.ifEmpty { "Спойлер" }, parseNodes(inner.childNodes(), st))
                                    return
                                }
                                "spoiler-node" in cls || "mark-spoiler" in cls || "spoiler" in cls -> {
                                    val inner = node.selectFirst(".spoiler-node__text") ?: node
                                    val id = ++spoilers
                                    inner.childNodes().forEach { walkBlock(it, st.copy(spoiler = id)) }
                                    return
                                }
                            }
                        }
                        when (tag) {
                            "br" -> inline.append("\n", st)
                            "hr" -> { flush(); out += Separator }
                            "img" -> {
                                flush()
                                absolute(node.attr("src"), siteOrigin)?.let { out += ImageBlock(it) }
                            }
                            "p", "div", "li", "blockquote", "section", "td", "tr", "ul", "ol", "table", "tbody" -> {
                                flush()
                                node.childNodes().forEach { walkBlock(it, st) }
                                flush()
                            }
                            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                                flush()
                                node.childNodes().forEach { walkBlock(it, st.copy(bold = true)) }
                                flush(heading = true)
                            }
                            else -> {
                                val next = st.copy(
                                    bold = st.bold || tag == "b" || tag == "strong",
                                    italic = st.italic || tag == "i" || tag == "em",
                                    underline = st.underline || (rich && tag == "u"),
                                    strike = st.strike || (rich && (tag == "s" || tag == "strike" || tag == "del")),
                                )
                                node.childNodes().forEach { walkBlock(it, next) }
                            }
                        }
                    }
                }
            }
            nodes.forEach { walkBlock(it, base) }
            flush()
            return out
        }
        return parseNodes(body.childNodes(), Style())
    }

    fun parseProseMirror(doc: JsonObject, attachments: List<Attachment>, siteOrigin: String = "https://ranobelib.me", rich: Boolean = false): List<Block> {
        var spoilers = 0
        fun inlineOf(nodes: JsonArray?, builder: InlineBuilder) {
            nodes?.forEach { n ->
                val o = n as? JsonObject ?: return@forEach
                when (o.str("type")) {
                    "text" -> {
                        val marks = (o["marks"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.str("type") }.orEmpty()
                        builder.append(
                            o.str("text").orEmpty(),
                            Style(
                                bold = "bold" in marks,
                                italic = "italic" in marks,
                                underline = rich && "underline" in marks,
                                strike = rich && "strike" in marks,
                                spoiler = if (rich && "spoiler" in marks) ++spoilers else 0,
                            ),
                        )
                    }
                    "hardBreak" -> builder.append("\n", Style())
                    else -> inlineOf(o["content"] as? JsonArray, builder)
                }
            }
        }
        fun blocks(nodes: JsonArray?): List<Block> {
            val out = ArrayList<Block>()
            nodes?.forEach { n ->
                val o = n as? JsonObject ?: return@forEach
                when (o.str("type")) {
                    "paragraph", "heading" -> {
                        val b = InlineBuilder()
                        inlineOf(o["content"] as? JsonArray, b)
                        out += b.build(o.str("type") == "heading") ?: Paragraph("", emptyList())
                    }
                    "horizontalRule" -> out += Separator
                    "image" -> {
                        val attrs = o["attrs"] as? JsonObject
                        val ids = (attrs?.get("images") as? JsonArray)?.mapNotNull { (it as? JsonObject)?.get("image")?.let { e -> (e as? JsonPrimitive)?.contentOrNull } }.orEmpty()
                        ids.forEach { id ->
                            attachments.firstOrNull { it.name == id || it.id == id }?.let { a -> absolute(a.url, siteOrigin)?.let { out += ImageBlock(it) } }
                        }
                        attrs?.str("src")?.let { absolute(it, siteOrigin)?.let { u -> out += ImageBlock(u) } }
                    }
                    "blockquote" -> if (rich) out += QuoteBlock(blocks(o["content"] as? JsonArray)) else out += blocks(o["content"] as? JsonArray)
                    "spoilerBlock", "spoiler" -> if (rich) {
                        val title = (o["attrs"] as? JsonObject)?.let { it.str("text") ?: it.str("title") }?.trim().orEmpty()
                        out += SpoilerBlock(title.ifEmpty { "Спойлер" }, blocks(o["content"] as? JsonArray))
                    } else {
                        out += blocks(o["content"] as? JsonArray)
                    }
                    else -> out += blocks(o["content"] as? JsonArray)
                }
            }
            return out
        }
        return blocks(doc["content"] as? JsonArray)
    }

    /** Плоский текст для превью: спойлеры скрыты, цитаты в «ёлочках». */
    fun plainText(blocks: List<Block>): String = blocks.mapNotNull { b ->
        when (b) {
            is Paragraph -> {
                val sb = StringBuilder()
                var i = 0
                b.spans.filter { it.spoiler != 0 }.sortedBy { it.start }.forEach { sp ->
                    if (sp.start >= i) {
                        sb.append(b.text, i, sp.start).append("[спойлер]")
                        i = sp.end
                    }
                }
                sb.append(b.text, i.coerceAtMost(b.text.length), b.text.length)
                sb.toString().replace("[спойлер][спойлер]", "[спойлер]")
            }
            is QuoteBlock -> "«" + plainText(b.blocks) + "»"
            is SpoilerBlock -> "[спойлер]"
            else -> null
        }
    }.filter { it.isNotBlank() }.joinToString("\n\n")

    private data class Style(
        val bold: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
        val strike: Boolean = false,
        val spoiler: Int = 0,
    ) {
        val any get() = bold || italic || underline || strike || spoiler != 0
    }

    private fun absolute(src: String, siteOrigin: String): String? {
        val s = src.trim()
        return when {
            s.startsWith("https://") -> s
            s.startsWith("//") -> "https:$s"
            s.startsWith("/") -> siteOrigin + s
            else -> null
        }
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }

    /** Собирает текст абзаца со спанами жирного/курсива, схлопывая пробелы как HTML. */
    private class InlineBuilder {
        private val sb = StringBuilder()
        private val spans = ArrayList<Span>()

        fun append(raw: String, st: Style) {
            if (raw.isEmpty()) return
            val text = if (raw == "\n") "\n" else raw.replace(Regex("[ \\t\\r\\n]+"), " ")
            val start = sb.length
            sb.append(text)
            if (st.any) spans += Span(start, sb.length, st.bold, st.italic, st.underline, st.strike, st.spoiler)
        }

        fun build(heading: Boolean = false): Paragraph? {
            if (sb.isEmpty()) return null
            val raw = sb.toString()
            val lead = raw.length - raw.trimStart(' ', ' ').length
            val text = raw.trim(' ', ' ')
            val shifted = spans.mapNotNull { s ->
                val st = (s.start - lead).coerceIn(0, text.length)
                val en = (s.end - lead).coerceIn(0, text.length)
                if (en > st) s.copy(start = st, end = en) else null
            }
            sb.clear()
            spans.clear()
            return Paragraph(text, shifted, heading)
        }
    }
}
