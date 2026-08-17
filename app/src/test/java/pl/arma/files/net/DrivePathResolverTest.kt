package pl.arma.files.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DrivePathResolverTest {

    private var calls = 0

    private fun folder(id: String, name: String) =
        DriveFile(id, name, DriveFile.FOLDER_MIME, 0, 0)

    private fun file(id: String, name: String) =
        DriveFile(id, name, "text/plain", 10, 0)

    /** Drzewo: /Zdjecia/2026/raport.pdf oraz /Dokumenty */
    private val tree = mapOf(
        "root" to listOf(folder("id-zdjecia", "Zdjecia"), folder("id-dok", "Dokumenty")),
        "id-zdjecia" to listOf(folder("id-2026", "2026")),
        "id-2026" to listOf(file("id-raport", "raport.pdf"))
    )

    private fun resolver() = DrivePathResolver { parentId ->
        calls++
        tree[parentId].orEmpty()
    }

    @Test
    fun `korzen nie wymaga zapytania`() {
        val resolver = resolver()
        assertEquals(DrivePathResolver.ROOT_ID, resolver.resolve("/"))
        assertEquals(0, calls)
    }

    @Test
    fun `sciezka zamienia sie na identyfikator`() {
        val resolver = resolver()
        assertEquals("id-raport", resolver.resolve("/Zdjecia/2026/raport.pdf"))
    }

    @Test
    fun `powtorne pytanie nie odpytuje API ponownie`() {
        val resolver = resolver()
        resolver.resolve("/Zdjecia/2026/raport.pdf")
        val afterFirst = calls
        resolver.resolve("/Zdjecia/2026/raport.pdf")
        assertEquals("drugie wywolanie ma isc z pamieci", afterFirst, calls)
    }

    @Test
    fun `czesciowo znana sciezka odpytuje tylko brakujacy poziom`() {
        val resolver = resolver()
        resolver.resolve("/Zdjecia/2026")
        val afterFirst = calls
        resolver.resolve("/Zdjecia/2026/raport.pdf")
        assertEquals("brakowal tylko jeden poziom", afterFirst + 1, calls)
    }

    @Test
    fun `nieistniejaca sciezka daje null`() {
        assertNull(resolver().resolve("/Zdjecia/1999"))
    }

    @Test
    fun `uniewaznienie usuwa takze poddrzewo`() {
        val resolver = resolver()
        resolver.resolve("/Zdjecia/2026/raport.pdf")
        val before = resolver.cachedCount()
        resolver.invalidate("/Zdjecia")
        // Znikaja /Zdjecia, /Zdjecia/2026 i /Zdjecia/2026/raport.pdf
        assertEquals(before - 3, resolver.cachedCount())
    }

    @Test
    fun `uniewaznienie jednego katalogu nie rusza rodzenstwa`() {
        val resolver = resolver()
        resolver.resolve("/Zdjecia/2026")
        resolver.resolve("/Dokumenty")
        resolver.invalidate("/Zdjecia")
        val callsBefore = calls
        resolver.resolve("/Dokumenty")
        assertEquals("rodzenstwo mialo zostac w pamieci", callsBefore, calls)
    }

    @Test
    fun `zapamietanie recznie pomija odpytywanie`() {
        val resolver = resolver()
        resolver.remember("/Nowy/Folder", "id-nowy")
        val before = calls
        assertEquals("id-nowy", resolver.resolve("/Nowy/Folder"))
        assertEquals(before, calls)
    }

    @Test
    fun `czyszczenie zostawia sam korzen`() {
        val resolver = resolver()
        resolver.resolve("/Zdjecia/2026/raport.pdf")
        resolver.clear()
        assertEquals(1, resolver.cachedCount())
        assertEquals(DrivePathResolver.ROOT_ID, resolver.resolve("/"))
    }

    @Test
    fun `katalog to plik o specjalnym typie MIME`() {
        val folder = folder("id", "Zdjecia")
        assert(folder.isFolder)
        assert(!folder.isGoogleDoc)
        val doc = DriveFile("id", "Notatka", "application/vnd.google-apps.document", 0, 0)
        assert(doc.isGoogleDoc)
        assert(!doc.isFolder)
    }
}
