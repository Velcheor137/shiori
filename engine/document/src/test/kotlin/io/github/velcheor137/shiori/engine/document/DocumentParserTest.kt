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
}
