package pl.arma.files.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextProfileTest {

    @Test
    fun `rozpoznaje typy po rozszerzeniu`() {
        assertEquals(TextProfile.Kind.MARKDOWN, TextProfile.of("README.md").kind)
        assertEquals(TextProfile.Kind.DATA, TextProfile.of("dane.json").kind)
        assertEquals(TextProfile.Kind.CODE, TextProfile.of("Main.kt").kind)
        assertEquals(TextProfile.Kind.PLAIN, TextProfile.of("notatka.txt").kind)
    }

    @Test
    fun `rozpoznaje pliki bez rozszerzenia po nazwie`() {
        assertTrue(TextProfile.of("Dockerfile").code)
        assertEquals("DOCKERFILE", TextProfile.of("Dockerfile").label)
        assertEquals("GITIGNORE", TextProfile.of(".gitignore").label)
    }

    @Test
    fun `spis tresci tylko tam, gdzie ma sens`() {
        assertTrue(TextProfile.of("README.md").outlineable)
        assertTrue(TextProfile.of("Main.kt").outlineable)
        assertFalse(TextProfile.of("notatka.txt").outlineable)
    }

    @Test
    fun `naglowki Markdown trafiaja do spisu z poziomem`() {
        val text = """
            # Tytuł
            tekst
            ## Rozdział
            ### Podrozdział
        """.trimIndent()
        val outline = TextOutline.of(text, TextProfile.of("a.md"))
        assertEquals(listOf("Tytuł", "Rozdział", "Podrozdział"), outline.map { it.title })
        assertEquals(listOf(1, 2, 3), outline.map { it.level })
        assertEquals(listOf(0, 2, 3), outline.map { it.line })
    }

    @Test
    fun `krzyzyk w bloku kodu nie jest naglowkiem`() {
        val text = """
            # Prawdziwy
            ```bash
            # to jest komentarz w skrypcie
            ```
            ## Drugi
        """.trimIndent()
        assertEquals(listOf("Prawdziwy", "Drugi"), TextOutline.of(text, TextProfile.of("a.md")).map { it.title })
    }

    @Test
    fun `deklaracje w kodzie trafiaja do spisu`() {
        val text = """
            package test
            class Narzedzie {
                fun policz() {}
                private fun pomocnicza() {}
            }
        """.trimIndent()
        val outline = TextOutline.of(text, TextProfile.of("a.kt"))
        assertEquals(listOf("class Narzedzie", "fun policz", "fun pomocnicza"), outline.map { it.title })
    }

    @Test
    fun `zwykly tekst nie dostaje spisu`() {
        assertTrue(TextOutline.of("# to nie markdown\ntresc", TextProfile.of("a.txt")).isEmpty())
    }

    @Test
    fun `rozpoznaje pliki tekstowe takze bez typu MIME`() {
        // FTP i SMB nie podaja typu, wiec wszystko przychodzi jako octet-stream.
        val octet = "application/octet-stream"
        assertTrue(TextProfile.isTextFile("dziennik.log", octet))
        assertTrue(TextProfile.isTextFile("zestawienie.csv", octet))
        assertTrue(TextProfile.isTextFile("napisy.srt", octet))
        assertTrue(TextProfile.isTextFile("smb.conf", octet))
        assertTrue(TextProfile.isTextFile("Dockerfile", octet))
        assertTrue(TextProfile.isTextFile("Main.java", octet))
    }

    @Test
    fun `nie bierze plikow binarnych za tekst`() {
        val octet = "application/octet-stream"
        assertFalse(TextProfile.isTextFile("zdjecie.jpg", octet))
        assertFalse(TextProfile.isTextFile("aplikacja.apk", octet))
        assertFalse(TextProfile.isTextFile("archiwum.zip", octet))
        assertFalse(TextProfile.isTextFile("film.mp4", octet))
    }

    @Test
    fun `typ MIME wystarczy, gdy jest sensowny`() {
        assertTrue(TextProfile.isTextFile("cokolwiek", "text/plain"))
        assertTrue(TextProfile.isTextFile("dane", "application/json"))
    }
}
