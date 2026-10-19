package pl.arma.files.archive

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import pl.arma.libarchive.LibArchive
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * Formaty czytane przez libarchive. Biblioteka natywna jest zbudowana dla
 * Androida, więc na komputerze test działa tylko z wersją dla hosta podaną
 * przez `-Djava.library.path` (np. w JAVA_TOOL_OPTIONS); bez niej jest pomijany.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NativeArchiveTest {

    private lateinit var dir: File
    private lateinit var archives: ArchiveRepository

    @Before
    fun setUp() {
        assumeTrue("brak libarchivejni dla hosta", runCatching { LibArchive }.isSuccess)
        dir = Files.createTempDirectory("natywne").toFile()
        archives = ArchiveRepository(
            ApplicationProvider.getApplicationContext(),
            openSource = { uri -> File(uri.path!!).inputStream() },
            sourceSignature = { uri -> File(uri.path!!).let { "${it.length()}|${it.lastModified()}" } },
            localFile = { uri -> File(uri.path!!) }
        )
    }

    @After
    fun tearDown() {
        if (::dir.isInitialized) dir.deleteRecursively()
    }

    private fun fixture(name: String): Uri {
        val file = File(dir, name)
        javaClass.getResourceAsStream("/archives/$name")!!.use { input -> file.outputStream().use { input.copyTo(it) } }
        return archives.rootOf(Uri.fromFile(file), name)
    }

    private fun read(root: Uri, path: String) =
        archives.openInput(root.buildUpon().path("/$path").build()).use { it.readBytes().toString(Charsets.UTF_8) }

    private fun assertProject(root: Uri) {
        archives.ensureReadable(root)
        assertEquals(setOf("projekt", "notatka.txt"), archives.listChildren(root).map { it.name }.toSet())
        // Od końca i od początku: kursor musi umieć wrócić.
        assertEquals("plan", read(root, "projekt/rysunki/plan.dxf"))
        assertEquals("Opis projektu — zażółć gęślą jaźń", read(root, "projekt/opis.txt"))
        assertEquals("krótka", read(root, "notatka.txt"))
    }

    @Test
    fun `rar5`() = assertProject(fixture("test5.rar"))

    @Test
    fun `obraz iso`() = assertProject(fixture("test.iso"))

    @Test
    fun `szyfrowany rar daje czytelny blad zamiast prosby o haslo`() {
        val root = fixture("szyfrowany.rar")
        try {
            // Spis jest jawny; szyfrowanie wychodzi przy spisie albo przy odczycie.
            archives.ensureReadable(root)
            read(root, "notatka.txt")
        } catch (e: ArchivePasswordException) {
            throw AssertionError("hasło nic tu nie pomoże", e)
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("RAR"))
            return
        }
        throw AssertionError("oczekiwany błąd")
    }
}
