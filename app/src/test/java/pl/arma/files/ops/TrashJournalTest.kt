package pl.arma.files.ops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrashJournalTest {

    private fun entry(name: String, id: String = "1") = TrashEntry(
        id = id,
        name = name,
        trashUri = "file:///storage/.armafiles_trash/$id",
        originalUri = "file:///storage/Dokumenty/$name",
        originalParent = "file:///storage/Dokumenty",
        size = 1234L,
        isDirectory = false,
        deletedAt = 1_700_000_000_000L
    )

    @Test
    fun `zapis i odczyt zachowuja wpis`() {
        val entries = listOf(entry("raport.pdf"), entry("zdjecie.jpg", id = "2"))
        assertEquals(entries, TrashJournal.decode(TrashJournal.encode(entries)))
    }

    @Test
    fun `nazwa z tabulatorem i nowa linia przezywa cykl`() {
        val nasty = entry("dziwna\tnazwa\nz enterem.txt")
        val restored = TrashJournal.decode(TrashJournal.encode(listOf(nasty)))
        assertEquals(listOf(nasty), restored)
        assertEquals("dziwna\tnazwa\nz enterem.txt", restored.single().name)
    }

    @Test
    fun `nazwa z backslashem nie rozjezdza sie przy escape`() {
        val nasty = entry("kopia\\\\backup\\t.txt")
        assertEquals(listOf(nasty), TrashJournal.decode(TrashJournal.encode(listOf(nasty))))
    }

    @Test
    fun `uszkodzona linia jest pomijana, reszta kosza ocalala`() {
        val good = entry("dobry.txt")
        val text = TrashJournal.encode(listOf(good)) + "popsuta linia bez pol\n"
        assertEquals(listOf(good), TrashJournal.decode(text))
    }

    @Test
    fun `pusty dziennik daje pusta liste`() {
        assertTrue(TrashJournal.decode(TrashJournal.encode(emptyList())).isEmpty())
        assertTrue(TrashJournal.decode("").isEmpty())
    }
}
