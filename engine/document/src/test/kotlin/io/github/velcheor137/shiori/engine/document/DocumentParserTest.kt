package io.github.velcheor137.shiori.engine.document

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DocumentParserTest {
    @Test
    fun htmlParagraphsWithMarksAndImages() {
        val html = """<p data-paragraph-index="1">Первый <em>курсив</em> и <b>жирный</b>.</p><script>alert(1)</script>""" +
            """<p>Второй<br>строка</p><hr><img src="/uploads/x.jpg"><img src="javascript:alert(1)"><h3>Заголовок</h3>"""
        val blocks = DocumentParser.parse(JsonPrimitive(html), emptyList())
        val p1 = assertIs<Paragraph>(blocks[0])
        assertEquals("Первый курсив и жирный.", p1.text)
        assertEquals(listOf(Span(7, 13, bold = false, italic = true), Span(16, 22, bold = true, italic = false)), p1.spans)
        assertEquals("Второй\nстрока", assertIs<Paragraph>(blocks[1]).text)
        assertEquals(Separator, blocks[2])
        assertEquals(ImageBlock("https://ranobelib.me/uploads/x.jpg"), blocks[3])
        assertTrue(assertIs<Paragraph>(blocks[4]).heading)
        assertEquals(5, blocks.size)
    }

    @Test
    fun proseMirrorWithAttachments() {
        val doc = Json.parseToJsonElement(
            """{"type":"doc","content":[
              {"type":"paragraph","content":[{"type":"text","text":"Привет, "},{"type":"text","marks":[{"type":"italic"}],"text":"мир"}]},
              {"type":"paragraph"},
              {"type":"image","attrs":{"images":[{"image":"abc"}]}},
              {"type":"horizontalRule"}
            ]}""",
        )
        val blocks = DocumentParser.parse(doc, listOf(Attachment("abc", null, "/uploads/ranobe/a.png")))
        val p = assertIs<Paragraph>(blocks[0])
        assertEquals("Привет, мир", p.text)
        assertEquals(listOf(Span(8, 11, bold = false, italic = true)), p.spans)
        assertEquals(ImageBlock("https://ranobelib.me/uploads/ranobe/a.png"), blocks[1])
        assertEquals(Separator, blocks[2])
    }

    /** Разметка комментариев сайта (образцы из реальных ответов `/api/comments`). */
    @Test
    fun commentQuoteSpoilersAndMarks() {
        val html = "<blockquote><p>— Да. Думаю, это <strong>остановка</strong>.</p></blockquote>" +
            "<p>мне казалось, <span class=\"spoiler-node\" data-spoiler-type=\"inline\" data-spoiler-text=\"тк\">" +
            "<span class=\"spoiler-node__text\">я больше 3 букв</span></span> , но <u>тут</u> <s>так</s> интересно</p>" +
            "<div class=\"spoiler-node\" data-spoiler-type=\"block\" data-spoiler-text=\"спойлер\"><div class=\"spoiler-node__text\"><p>Тайна</p></div></div>" +
            "<div class=\"comment__spoiler\"><span class=\"spoiler-title\">старый</span><span class=\"spoiler-text\">точнее</span></div>"
        val blocks = DocumentParser.parse(JsonPrimitive(html), emptyList(), rich = true)
        assertEquals(4, blocks.size)
        val quote = assertIs<QuoteBlock>(blocks[0])
        val q = assertIs<Paragraph>(quote.blocks.single())
        assertEquals("— Да. Думаю, это остановка.", q.text)
        assertEquals(listOf(Span(17, 26, bold = true, italic = false)), q.spans)

        val p = assertIs<Paragraph>(blocks[1])
        assertEquals("мне казалось, я больше 3 букв , но тут так интересно", p.text)
        val spoiler = p.spans.single { it.spoiler != 0 }
        assertEquals("я больше 3 букв", p.text.substring(spoiler.start, spoiler.end))
        assertEquals("тут", p.text.substring(p.spans.single { it.underline }.let { it.start until it.end }))
        assertEquals("так", p.text.substring(p.spans.single { it.strike }.let { it.start until it.end }))

        val block = assertIs<SpoilerBlock>(blocks[2])
        assertEquals("спойлер", block.title)
        assertEquals("Тайна", assertIs<Paragraph>(block.blocks.single()).text)
        val old = assertIs<SpoilerBlock>(blocks[3])
        assertEquals("старый", old.title)
        assertEquals("точнее", assertIs<Paragraph>(old.blocks.single()).text)

        assertEquals("«— Да. Думаю, это остановка.»\n\nмне казалось, [спойлер] , но тут так интересно\n\n[спойлер]\n\n[спойлер]", DocumentParser.plainText(blocks))
    }

    /** Без `rich` (главы) цитата остаётся обычным абзацем — нумерация абзацев не меняется. */
    @Test
    fun chapterBlockquoteStaysFlat() {
        val blocks = DocumentParser.parse(JsonPrimitive("<blockquote><p>Цитата</p></blockquote><p><u>Текст</u></p>"), emptyList())
        assertEquals(listOf<Block>(Paragraph("Цитата", emptyList()), Paragraph("Текст", emptyList())), blocks)
    }
}
