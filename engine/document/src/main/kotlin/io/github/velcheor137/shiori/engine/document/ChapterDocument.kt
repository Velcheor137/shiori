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

data class Span(val start: Int, val end: Int, val bold: Boolean, val italic: Boolean)

data class Attachment(val name: String?, val id: String?, val url: String)

/**
 * Разбор поля `content` главы: HTML-строка или ProseMirror JSON (F-07 плана). Скрипты, стили и
 * атрибуты отбрасываются; ссылки на картинки — только https.
 */
object DocumentParser {
    fun parse(content: JsonElement?, attachments: List<Attachment>, siteOrigin: String = "https://ranobelib.me"): List<Block> =
        when {
            content == null -> emptyList()
            content is JsonPrimitive && content.isString -> parseHtml(content.content, siteOrigin)
            content is JsonObject -> parseProseMirror(content, attachments, siteOrigin)
            else -> emptyList()
        }.filterNot { it is Paragraph && it.text.isBlank() && it.spans.isEmpty() && !it.heading }

    fun parseHtml(html: String, siteOrigin: String = "https://ranobelib.me"): List<Block> {
        val body = Jsoup.parseBodyFragment(html).body()
        body.select("script, style, iframe, object, embed, form, svg").remove()
        val out = ArrayList<Block>()
        val inline = InlineBuilder()
        fun flush(heading: Boolean = false) {
            inline.build(heading)?.let(out::add)
        }
        fun walkBlock(node: Node, bold: Boolean, italic: Boolean) {
            when (node) {
                is TextNode -> inline.append(node.text(), bold, italic)
                is Element -> when (val tag = node.normalName()) {
                    "br" -> inline.append("\n", bold, italic)
                    "hr" -> { flush(); out += Separator }
                    "img" -> {
                        flush()
                        absolute(node.attr("src"), siteOrigin)?.let { out += ImageBlock(it) }
                    }
                    "p", "div", "li", "blockquote", "section", "td", "tr", "ul", "ol", "table", "tbody" -> {
                        flush()
                        node.childNodes().forEach { walkBlock(it, bold, italic) }
                        flush()
                    }
                    "h1", "h2", "h3", "h4", "h5", "h6" -> {
                        flush()
                        node.childNodes().forEach { walkBlock(it, true, italic) }
                        flush(heading = true)
                    }
                    else -> {
                        val b = bold || tag == "b" || tag == "strong"
                        val i = italic || tag == "i" || tag == "em"
                        node.childNodes().forEach { walkBlock(it, b, i) }
                    }
                }
            }
        }
        body.childNodes().forEach { walkBlock(it, bold = false, italic = false) }
        flush()
        return out
    }

    fun parseProseMirror(doc: JsonObject, attachments: List<Attachment>, siteOrigin: String = "https://ranobelib.me"): List<Block> {
        val out = ArrayList<Block>()
        fun inlineOf(nodes: JsonArray?, builder: InlineBuilder) {
            nodes?.forEach { n ->
                val o = n as? JsonObject ?: return@forEach
                when (o.str("type")) {
                    "text" -> {
                        val marks = (o["marks"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.str("type") }.orEmpty()
                        builder.append(o.str("text").orEmpty(), "bold" in marks, "italic" in marks)
                    }
                    "hardBreak" -> builder.append("\n", false, false)
                    else -> inlineOf(o["content"] as? JsonArray, builder)
                }
            }
        }
        fun blocks(nodes: JsonArray?) {
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
                    else -> blocks(o["content"] as? JsonArray)
                }
            }
        }
        blocks(doc["content"] as? JsonArray)
        return out
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

        fun append(raw: String, bold: Boolean, italic: Boolean) {
            if (raw.isEmpty()) return
            val text = if (raw == "\n") "\n" else raw.replace(Regex("[ \\t\\r\\n]+"), " ")
            val start = sb.length
            sb.append(text)
            if (bold || italic) spans += Span(start, sb.length, bold, italic)
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
