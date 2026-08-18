package pl.arma.files.index

import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FileIndexDbTest {

    private lateinit var db: FileIndexDb

    @Before
    fun setUp() {
        db = FileIndexDb(ApplicationProvider.getApplicationContext())
        db.clear()
    }

    @After
    fun tearDown() = db.close()

    private fun file(
        path: String,
        size: Long = 100,
        modified: Long = 1_700_000_000_000L,
        directory: Boolean = false
    ): IndexedFile {
        val name = path.substringAfterLast('/')
        val mime = if (directory) "" else pl.arma.files.guessMime(name)
        return IndexedFile(
            uri = "file://$path",
            parentUri = "file://" + path.substringBeforeLast('/'),
            name = name,
            size = size,
            modified = modified,
            isDirectory = directory,
            mime = mime,
            category = if (directory) IndexCategory.OTHER else IndexCategory.of(name, mime)
        )
    }

    @Test
    fun `wyszukiwanie znajduje po poczatku nazwy`() {
        db.upsert(listOf(file("/storage/Dokumenty/raport.pdf"), file("/storage/Dokumenty/inne.txt")))
        val found = db.search("rap")
        assertEquals(listOf("raport.pdf"), found.map { it.name })
    }

    @Test
    fun `wyszukiwanie znajduje tez fragment ze srodka nazwy`() {
        // FTS dopasowuje poczatki slow, wiec sam indeks nie wystarczy --
        // dlatego zapytanie dokłada dopasowanie LIKE.
        db.upsert(listOf(file("/storage/Dokumenty/zestawienie-2026.xlsx")))
        assertEquals(1, db.search("2026").size)
        assertEquals(1, db.search("stawien").size)
    }

    @Test
    fun `wyszukiwanie nie rozroznia wielkosci liter`() {
        db.upsert(listOf(file("/storage/Dokumenty/Raport.PDF")))
        assertEquals(1, db.search("raport").size)
        assertEquals(1, db.search("RAPORT").size)
    }

    @Test
    fun `ponowne wstawienie nie duplikuje wpisu`() {
        val entry = file("/storage/Dokumenty/raport.pdf", size = 100)
        db.upsert(listOf(entry))
        db.upsert(listOf(entry.copy(size = 900)))
        val found = db.search("raport")
        assertEquals(1, found.size)
        assertEquals(900L, found.single().size)
    }

    @Test
    fun `usuniecie poddrzewa czysci katalog i jego zawartosc`() {
        db.upsert(
            listOf(
                file("/storage/Projekt", directory = true),
                file("/storage/Projekt/a.txt"),
                file("/storage/Projekt/pod/b.txt"),
                file("/storage/Inne/c.txt")
            )
        )
        db.deleteSubtree("file:///storage/Projekt")
        assertEquals(listOf("c.txt"), db.search("txt").map { it.name } + db.search("c").map { it.name }.minus("c.txt"))
        assertTrue(db.search("a.txt").isEmpty())
        assertTrue(db.search("b").isEmpty())
    }

    @Test
    fun `kategorie licza sie przy indeksowaniu`() {
        db.upsert(
            listOf(
                file("/storage/DCIM/zdjecie.jpg"),
                file("/storage/Filmy/film.mp4"),
                file("/storage/Muzyka/utwor.mp3"),
                file("/storage/Pobrane/apka.apk"),
                file("/storage/Pobrane/paczka.zip"),
                file("/storage/Dokumenty/raport.pdf")
            )
        )
        assertEquals(listOf("zdjecie.jpg"), db.byCategory(IndexCategory.IMAGE).map { it.name })
        assertEquals(listOf("film.mp4"), db.byCategory(IndexCategory.VIDEO).map { it.name })
        assertEquals(listOf("utwor.mp3"), db.byCategory(IndexCategory.AUDIO).map { it.name })
        assertEquals(listOf("apka.apk"), db.byCategory(IndexCategory.APK).map { it.name })
        assertEquals(listOf("paczka.zip"), db.byCategory(IndexCategory.ARCHIVE).map { it.name })
        assertEquals(listOf("raport.pdf"), db.byCategory(IndexCategory.DOCUMENT).map { it.name })
    }

    @Test
    fun `najwieksze pliki sortowane malejaco`() {
        db.upsert(
            listOf(
                file("/storage/a.bin", size = 10),
                file("/storage/b.bin", size = 5_000),
                file("/storage/c.bin", size = 300)
            )
        )
        assertEquals(listOf("b.bin", "c.bin", "a.bin"), db.largestFiles().map { it.name })
    }

    @Test
    fun `katalogi sumuja rozmiar swoich plikow`() {
        db.upsert(
            listOf(
                file("/storage/Filmy/a.mp4", size = 1_000),
                file("/storage/Filmy/b.mp4", size = 2_000),
                file("/storage/Male/c.txt", size = 10)
            )
        )
        val largest = db.largestFolders()
        assertEquals("file:///storage/Filmy", largest.first().parentUri)
        assertEquals(3_000L, largest.first().totalBytes)
        assertEquals(2, largest.first().fileCount)
    }

    @Test
    fun `stare pliki wybierane po dacie modyfikacji`() {
        db.upsert(
            listOf(
                file("/storage/stary.txt", modified = 1_000L),
                file("/storage/nowy.txt", modified = 9_000_000_000_000L)
            )
        )
        assertEquals(listOf("stary.txt"), db.olderThan(1_000_000L).map { it.name })
    }

    @Test
    fun `duplikaty grupowane sa po identycznym rozmiarze`() {
        db.upsert(
            listOf(
                file("/storage/A/kopia.jpg", size = 2_048),
                file("/storage/B/kopia.jpg", size = 2_048),
                file("/storage/C/inny.jpg", size = 999)
            )
        )
        val groups = db.duplicateCandidates()
        assertEquals(1, groups.size)
        assertEquals(2_048L, groups.single().size)
        assertEquals(2, groups.single().files.size)
    }

    @Test
    fun `pliki zerowej dlugosci nie sa uznawane za duplikaty`() {
        // Pustych plikow bywa w pamieci mnostwo i nie sa kopiami niczego.
        db.upsert(listOf(file("/storage/a", size = 0), file("/storage/b", size = 0)))
        assertTrue(db.duplicateCandidates().isEmpty())
    }

    @Test
    fun `statystyki licza pliki, katalogi i sume rozmiarow`() {
        db.upsert(
            listOf(
                file("/storage/Projekt", directory = true),
                file("/storage/Projekt/a.txt", size = 100),
                file("/storage/Projekt/b.txt", size = 250)
            )
        )
        val stats = db.stats()
        assertEquals(2, stats.files)
        assertEquals(1, stats.folders)
        assertEquals(350L, stats.totalBytes)
    }

    @Test
    fun `puste zapytanie nie zwraca calego indeksu`() {
        db.upsert(listOf(file("/storage/a.txt")))
        assertTrue(db.search("").isEmpty())
        assertTrue(db.search("   ").isEmpty())
        // Sama gwiazdka po oczyszczeniu daje pusty token, wiec tez nic nie zwraca.
        assertTrue(db.search("*").isEmpty())
    }

    @Test
    fun `zajetosc rozbita na kategorie`() {
        db.upsert(
            listOf(
                file("/storage/DCIM/a.jpg", size = 1_000),
                file("/storage/DCIM/b.jpg", size = 2_000),
                file("/storage/Filmy/c.mp4", size = 9_000),
                file("/storage/Projekt", directory = true)
            )
        )
        val usage = db.usageByCategory()
        assertEquals(3_000L, usage[IndexCategory.IMAGE])
        assertEquals(9_000L, usage[IndexCategory.VIDEO])
        // Katalogi nie wchodza do rozbicia, bo ich rozmiar to zawsze zero.
        assertEquals(null, usage[IndexCategory.OTHER])
    }

    @Test
    fun `liczenie plikow obejmuje podkatalogi`() {
        db.upsert(
            listOf(
                file("/storage/Pobrane/a.txt", size = 10),
                file("/storage/Pobrane/pod/b.txt", size = 20),
                file("/storage/Pobrane/pod", directory = true),
                file("/storage/Inne/c.txt", size = 30)
            )
        )
        assertEquals(2, db.countUnder("file:///storage/Pobrane"))
        assertEquals(30L, db.bytesUnder("file:///storage/Pobrane"))
        assertEquals(1, db.countUnder("file:///storage/Inne"))
    }

    @Test
    fun `pusty katalog daje zero, a nie blad`() {
        assertEquals(0, db.countUnder("file:///storage/Nieistnieje"))
        assertEquals(0L, db.bytesUnder("file:///storage/Nieistnieje"))
    }
}
