package pl.arma.viewer.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.arma.viewer.text.Markdown.Code
import pl.arma.viewer.text.Markdown.Heading
import pl.arma.viewer.text.Markdown.ListBlock
import pl.arma.viewer.text.Markdown.Paragraph
import pl.arma.viewer.text.Markdown.Quote
import pl.arma.viewer.text.Markdown.Rule
import pl.arma.viewer.text.Markdown.Span
import pl.arma.viewer.text.Markdown.Table

class MarkdownTest {

    @Test
    fun headingsParagraphsAndRules() {
        val blocks = Markdown.parse("# Tytuł #\n\nPierwszy\nwiersz\n\nPodkreślony\n---\n\n***\n")
        assertEquals(listOf(Heading(1, "Tytuł"), Paragraph("Pierwszy\nwiersz"), Heading(2, "Podkreślony"), Rule), blocks)
    }

    @Test
    fun fencedCodeKeepsContentVerbatim() {
        val blocks = Markdown.parse("```kotlin\nval a = 1\n# nie nagłówek\n```\npo")
        assertEquals(listOf(Code("kotlin", "val a = 1\n# nie nagłówek"), Paragraph("po")), blocks)
    }

    @Test
    fun nestedListsAndTasks() {
        val blocks = Markdown.parse("- jeden\n  - pod\n- [x] zrobione\n- [ ] do zrobienia\n\n1. a\n2. b\n")
        val bullets = blocks[0] as ListBlock
        assertEquals(3, bullets.items.size)
        assertTrue(bullets.items[0].blocks[1] is ListBlock)
        assertEquals(true, bullets.items[1].checked)
        assertEquals(false, bullets.items[2].checked)
        val ordered = blocks[1] as ListBlock
        assertTrue(ordered.ordered)
        assertEquals(listOf(Paragraph("a")), ordered.items[0].blocks)
    }

    @Test
    fun quoteAndTable() {
        val blocks = Markdown.parse("> cytat\ndalej\n\n| A | B |\n|:-|-:|\n| 1 | 2 \\| 3 |\n")
        assertEquals(Quote(listOf(Paragraph("cytat\ndalej"))), blocks[0])
        val table = blocks[1] as Table
        assertEquals(listOf("A", "B"), table.header)
        assertEquals(listOf(Markdown.Align.START, Markdown.Align.END), table.align)
        assertEquals(listOf(listOf("1", "2 | 3")), table.rows)
    }

    @Test
    fun inlineFormatting() {
        assertEquals(
            listOf(Span("a "), Span("gruby", bold = true), Span(" "), Span("kurs", italic = true), Span(" "), Span("kod", code = true), Span(" "), Span("link", link = "https://x.pl")),
            Markdown.inline("a **gruby** *kurs* `kod` [link](https://x.pl)")
        )
        // Podkreślnik w nazwie pliku nie włącza kursywy.
        assertEquals(listOf(Span("plik_nazwa_v2.md")), Markdown.inline("plik_nazwa_v2.md"))
        assertEquals(listOf(Span("skreśl", strike = true)), Markdown.inline("~~skreśl~~"))
        assertEquals(listOf(Span("*nie*")), Markdown.inline("\\*nie\\*"))
    }

    @Test
    fun boldInsideItalic() {
        assertEquals(
            listOf(Span("a ", italic = true), Span("b", bold = true, italic = true), Span(" c", italic = true)),
            Markdown.inline("*a **b** c*")
        )
    }
}
