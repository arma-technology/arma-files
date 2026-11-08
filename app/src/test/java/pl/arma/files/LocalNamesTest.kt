package pl.arma.files

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalNamesTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val repo by lazy { FileRepository(ApplicationProvider.getApplicationContext()) }

    private fun item(file: File) = FileItem(Uri.fromFile(file), file.name, "text/plain", file.length(), file.lastModified(), false)

    @Test
    fun `nazwy z serwera nie wychodza poza folder`() {
        listOf("..", ".", "../x", "a/b", "a\\b", "", " ").forEach { assertFalse(it, LocalNames.isSafe(it)) }
        assertTrue(LocalNames.isSafe("Plan ..parteru.dwg"))
        assertTrue(LocalNames.isSafe(".nomedia"))
        try {
            LocalNames.child(temp.root, "../poza")
            fail()
        } catch (_: IOException) {
        }
    }

    @Test
    fun `zmiana nazwy nie nadpisuje istniejacego pliku`() = runTest {
        val a = temp.newFile("a.txt").apply { writeText("A") }
        val b = temp.newFile("b.txt").apply { writeText("B") }
        try {
            repo.rename(item(a), "b.txt")
            fail()
        } catch (_: IOException) {
        }
        assertEquals("A", a.readText())
        assertEquals("B", b.readText())
    }

    @Test
    fun `zmiana samej wielkosci liter dziala`() = runTest {
        val a = temp.newFile("raport.txt").apply { writeText("R") }
        val renamed = repo.rename(item(a), "Raport.txt")
        assertEquals("Raport.txt", renamed?.lastPathSegment)
        assertEquals("R", File(temp.root, "Raport.txt").readText())
    }

    @Test
    fun `nowy plik z nazwa z ukosnikiem jest odrzucany`() = runTest {
        try {
            repo.createFile(Uri.fromFile(temp.root), "../poza.txt", "text/plain")
            fail()
        } catch (_: IOException) {
        }
        assertFalse(File(temp.root.parentFile, "poza.txt").exists())
    }
}
