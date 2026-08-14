package pl.arma.files.ops

import org.junit.Assert.assertEquals
import org.junit.Test

class ConflictResolverTest {

    @Test
    fun `wolna nazwa zostaje bez zmian`() {
        assertEquals("raport.pdf", ConflictResolver.uniqueName("raport.pdf", setOf("inny.pdf")))
    }

    @Test
    fun `zajeta nazwa dostaje licznik przed rozszerzeniem`() {
        assertEquals(
            "raport (2).pdf",
            ConflictResolver.uniqueName("raport.pdf", setOf("raport.pdf"))
        )
    }

    @Test
    fun `licznik rosnie az do wolnej nazwy`() {
        val taken = setOf("raport.pdf", "raport (2).pdf", "raport (3).pdf")
        assertEquals("raport (4).pdf", ConflictResolver.uniqueName("raport.pdf", taken))
    }

    @Test
    fun `porownanie ignoruje wielkosc liter, bo SMB i FAT tez ja ignoruja`() {
        assertEquals(
            "Raport (2).pdf",
            ConflictResolver.uniqueName("Raport.pdf", setOf("raport.pdf"))
        )
    }

    @Test
    fun `katalog nie dostaje rozszerzenia`() {
        assertEquals(
            "zdjecia.stare (2)",
            ConflictResolver.uniqueName("zdjecia.stare", setOf("zdjecia.stare"), isDirectory = true)
        )
    }

    @Test
    fun `plik ukryty traktowany jest w calosci jako nazwa`() {
        assertEquals(
            ".gitignore (2)",
            ConflictResolver.uniqueName(".gitignore", setOf(".gitignore"))
        )
    }

    @Test
    fun `nazwa konczaca sie kropka nie gubi znakow`() {
        assertEquals("dane. (2)", ConflictResolver.uniqueName("dane.", setOf("dane.")))
    }

    @Test
    fun `podwojne rozszerzenie zachowuje ostatni czlon`() {
        assertEquals(
            "kopia.tar (2).gz",
            ConflictResolver.uniqueName("kopia.tar.gz", setOf("kopia.tar.gz"))
        )
    }
}
