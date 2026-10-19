package pl.arma.viewer.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

class TextReaderTest {

    private val cp1250: Charset = Charset.forName("windows-1250")

    @Test
    fun `zwykly tekst UTF-8 czyta sie bez zmian`() {
        val (text, charset) = TextReader.decode("Zażółć gęślą jaźń".toByteArray(Charsets.UTF_8))
        assertEquals("Zażółć gęślą jaźń", text)
        assertEquals(Charsets.UTF_8, charset)
    }

    @Test
    fun `plik w CP1250 nie zamienia sie w znaki zapytania`() {
        // Typowy przypadek: .txt albo .srt z polskiego Windowsa. Zwykle
        // dekodowanie UTF-8 nie zglasza bledu, tylko podmienia bajty -- dlatego
        // proba musi byc scisla.
        val bytes = "Zażółć gęślą jaźń".toByteArray(cp1250)
        val (text, charset) = TextReader.decode(bytes)
        assertEquals("Zażółć gęślą jaźń", text)
        assertEquals(cp1250, charset)
    }

    @Test
    fun `znacznik kolejnosci bajtow jest usuwany z tresci`() {
        val utf8Bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "tekst".toByteArray(Charsets.UTF_8)
        val (text, charset) = TextReader.decode(utf8Bom)
        assertEquals("tekst", text)
        assertEquals(Charsets.UTF_8, charset)
    }

    @Test
    fun `UTF-16 rozpoznawane po znaczniku`() {
        val le = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "abc".toByteArray(Charsets.UTF_16LE)
        val (text, charset) = TextReader.decode(le)
        assertEquals("abc", text)
        assertEquals(Charsets.UTF_16LE, charset)
    }

    @Test
    fun `plik binarny jest rozpoznawany po bajcie zerowym`() {
        assertTrue(TextReader.looksBinary(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00, 0x01)))
        assertFalse(TextReader.looksBinary("zwykly tekst".toByteArray()))
        assertFalse(TextReader.looksBinary(ByteArray(0)))
    }

    @Test
    fun `UTF-16 nie jest brane za plik binarny`() {
        // UTF-16 z natury zawiera bajty zerowe, wiec sam ich test by nie wystarczyl.
        val le = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "abc".toByteArray(Charsets.UTF_16LE)
        assertFalse(TextReader.looksBinary(le))
    }

    @Test
    fun `dokument liczy linie i sygnalizuje obciecie`() {
        val content = "pierwsza\ndruga\ntrzecia"
        val doc = TextReader.read(content.toByteArray(), totalBytes = content.length.toLong())
        assertEquals(3, doc.lineCount)
        assertFalse(doc.truncated)

        val big = TextReader.read(content.toByteArray(), totalBytes = 50L * 1024 * 1024)
        assertTrue(big.truncated)
    }

    @Test
    fun `pusty plik nie wywraca odczytu`() {
        val doc = TextReader.read(ByteArray(0), totalBytes = 0)
        assertEquals("", doc.text)
        assertEquals(0, doc.lineCount)
    }
}
