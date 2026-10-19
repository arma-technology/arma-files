package pl.arma.viewer.cad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DxfReaderTest {

    private fun reader(text: String) = DxfReader(text.replace("\n", "\r\n").byteInputStream())

    @Test
    fun `napis zlamany surowym znakiem nowej linii z LibreDWG wraca w calosci`() {
        // Tak LibreDWG 0.14 zapisał MTEXT z rysunku „WYKONAWCZY 18MAJA 2027”.
        val r = reader("  0\nMTEXT\n  1\n\\A1;STAL MALOWANA PROSZKOWO^JKOLOR RAL 8019walka nablatowa\nbateria z blatu}\n  7\nStandard\n  0\nEOF\n")
        assertTrue(r.next()); assertEquals(0, r.code)
        assertTrue(r.next()); assertEquals(1, r.code)
        assertEquals("\\A1;STAL MALOWANA PROSZKOWO^JKOLOR RAL 8019walka nablatowa\\Pbateria z blatu}", r.string)
        assertTrue(r.next()); assertEquals(7, r.code); assertEquals("Standard", r.string)
        assertTrue(r.next()); assertEquals(0, r.code); assertEquals("EOF", r.string)
        assertFalse(r.next())
    }

    @Test
    fun `zwykly napis nie zjada nastepnego kodu`() {
        val r = reader("  1\nTekst\n 10\n5.0\n")
        assertTrue(r.next()); assertEquals("Tekst", r.string)
        assertTrue(r.next()); assertEquals(10, r.code); assertEquals(5.0, r.double, 0.0)
        assertFalse(r.next())
    }
}
