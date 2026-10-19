package pl.arma.files.ops

import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import pl.arma.files.DIRECTORY_MIME
import pl.arma.files.FileItem

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OperationEngineTest {

    private val target = Uri.parse("file:///cel")

    private fun file(ops: FakeOps, uri: String, content: String): FileItem {
        ops.addFile(uri, content)
        return FileItem(Uri.parse(uri), uri.substringAfterLast('/'), "text/plain", content.length.toLong(), 0L, false)
    }

    private fun dir(ops: FakeOps, uri: String): FileItem {
        ops.addDir(uri)
        return FileItem(Uri.parse(uri), uri.substringAfterLast('/'), DIRECTORY_MIME, 0L, 0L, true)
    }

    @Test
    fun `kopiowanie przenosi zawartosc i konczy sie sukcesem`() = runTest {
        val ops = FakeOps().also { it.addDir("file:///cel") }
        val source = file(ops, "file:///zrodlo/raport.pdf", "tresc raportu")
        val engine = OperationEngine(ops, CoroutineScope(StandardTestDispatcher(testScheduler) + Job()))

        engine.enqueue(OperationKind.COPY, listOf(source), target)
        advanceUntilIdle()

        val operation = engine.operations.value.single()
        assertEquals(OperationStatus.COMPLETED, operation.status)
        assertEquals("tresc raportu", ops.content("file:///cel/raport.pdf"))
        assertTrue("zrodlo musi zostac", ops.exists("file:///zrodlo/raport.pdf"))
        assertEquals(1, operation.progress.filesDone)
        assertEquals(operation.progress.bytesTotal, operation.progress.bytesDone)
    }

    @Test
    fun `kopiowanie katalogu odtwarza strukture`() = runTest {
        val ops = FakeOps().also { it.addDir("file:///cel") }
        val folder = dir(ops, "file:///zrodlo/projekt")
        ops.addFile("file:///zrodlo/projekt/a.txt", "aaa")
        ops.addDir("file:///zrodlo/projekt/pod")
        ops.addFile("file:///zrodlo/projekt/pod/b.txt", "bb")
        val engine = OperationEngine(ops, CoroutineScope(StandardTestDispatcher(testScheduler) + Job()))

        engine.enqueue(OperationKind.COPY, listOf(folder), target)
        advanceUntilIdle()

        assertEquals(OperationStatus.COMPLETED, engine.operations.value.single().status)
        assertEquals("aaa", ops.content("file:///cel/projekt/a.txt"))
        assertEquals("bb", ops.content("file:///cel/projekt/pod/b.txt"))
    }

    @Test
    fun `konflikt z decyzja zachowaj oba tworzy nazwe z licznikiem`() = runTest {
        val ops = FakeOps().also { it.addDir("file:///cel") }
        ops.addFile("file:///cel/raport.pdf", "stary")
        val source = file(ops, "file:///zrodlo/raport.pdf", "nowy")
        val engine = OperationEngine(ops, CoroutineScope(StandardTestDispatcher(testScheduler) + Job()))

        engine.enqueue(OperationKind.COPY, listOf(source), target)
        advanceUntilIdle()

        assertNotNull("silnik powinien zapytac o konflikt", engine.pendingConflict.value)
        engine.resolveConflict(ConflictDecision(ConflictAction.KEEP_BOTH))
        advanceUntilIdle()

        assertEquals("stary", ops.content("file:///cel/raport.pdf"))
        assertEquals("nowy", ops.content("file:///cel/raport (2).pdf"))
    }

    @Test
    fun `konflikt z decyzja zamien nadpisuje plik`() = runTest {
        val ops = FakeOps().also { it.addDir("file:///cel") }
        ops.addFile("file:///cel/raport.pdf", "stary")
        val source = file(ops, "file:///zrodlo/raport.pdf", "nowy")
        val engine = OperationEngine(ops, CoroutineScope(StandardTestDispatcher(testScheduler) + Job()))

        engine.enqueue(OperationKind.COPY, listOf(source), target)
        advanceUntilIdle()
        engine.resolveConflict(ConflictDecision(ConflictAction.REPLACE))
        advanceUntilIdle()

        assertEquals("nowy", ops.content("file:///cel/raport.pdf"))
        assertFalse(ops.exists("file:///cel/raport (2).pdf"))
    }

    @Test
    fun `konflikt z decyzja pomin zostawia oryginal i liczy pominiete`() = runTest {
        val ops = FakeOps().also { it.addDir("file:///cel") }
        ops.addFile("file:///cel/raport.pdf", "stary")
        val source = file(ops, "file:///zrodlo/raport.pdf", "nowy")
        val engine = OperationEngine(ops, CoroutineScope(StandardTestDispatcher(testScheduler) + Job()))

        engine.enqueue(OperationKind.COPY, listOf(source), target)
        advanceUntilIdle()
        engine.resolveConflict(ConflictDecision(ConflictAction.SKIP))
        advanceUntilIdle()

        val operation = engine.operations.value.single()
        assertEquals("stary", ops.content("file:///cel/raport.pdf"))
        assertEquals(1, operation.skipped)
        assertEquals(OperationStatus.COMPLETED, operation.status)
        assertEquals("pasek musi dojsc do konca mimo pominiecia",
            operation.progress.bytesTotal, operation.progress.bytesDone)
    }

    @Test
    fun `zastosuj do wszystkich nie pyta o kolejne pliki`() = runTest {
        val ops = FakeOps().also { it.addDir("file:///cel") }
        ops.addFile("file:///cel/a.txt", "stare a")
        ops.addFile("file:///cel/b.txt", "stare b")
        val sources = listOf(
            file(ops, "file:///zrodlo/a.txt", "nowe a"),
            file(ops, "file:///zrodlo/b.txt", "nowe b")
        )
        val engine = OperationEngine(ops, CoroutineScope(StandardTestDispatcher(testScheduler) + Job()))

        engine.enqueue(OperationKind.COPY, sources, target)
        advanceUntilIdle()
        engine.resolveConflict(ConflictDecision(ConflictAction.REPLACE, applyToAll = true))
        advanceUntilIdle()

        assertEquals(null, engine.pendingConflict.value)
        assertEquals("nowe a", ops.content("file:///cel/a.txt"))
        assertEquals("nowe b", ops.content("file:///cel/b.txt"))
    }

    @Test
    fun `przenoszenie kasuje zrodlo dopiero po udanej kopii`() = runTest {
        val ops = FakeOps().also { it.addDir("file:///cel") }
        val sources = listOf(
            file(ops, "file:///zrodlo/pierwszy.txt", "jeden"),
            file(ops, "file:///zrodlo/drugi.txt", "dwa"),
            file(ops, "file:///zrodlo/trzeci.txt", "trzy")
        )
        ops.failReadFor = "drugi.txt"
        val engine = OperationEngine(ops, CoroutineScope(StandardTestDispatcher(testScheduler) + Job()))

        engine.enqueue(OperationKind.MOVE, sources, target)
        advanceUntilIdle()

        val operation = engine.operations.value.single()
        assertEquals(OperationStatus.FAILED, operation.status)
        // Pierwszy przeszedł w całości: kopia jest, źródła nie ma.
        assertEquals("jeden", ops.content("file:///cel/pierwszy.txt"))
        assertFalse(ops.exists("file:///zrodlo/pierwszy.txt"))
        // Drugi się wywrócił — źródło MUSI nadal istnieć.
        assertTrue(
            "przerwane przenoszenie nie moze skasowac zrodla bez kopii",
            ops.exists("file:///zrodlo/drugi.txt")
        )
        // Trzeci nie został tknięty.
        assertTrue(ops.exists("file:///zrodlo/trzeci.txt"))
        assertFalse(ops.exists("file:///cel/trzeci.txt"))
    }

    @Test
    fun `przenoszenie na tym samym nosniku nie przepisuje bajtow`() = runTest {
        val ops = FakeOps().also { it.addDir("file:///cel"); it.fastMoveSupported = true }
        val source = file(ops, "file:///zrodlo/duzy.bin", "bardzo duza zawartosc")
        val engine = OperationEngine(ops, CoroutineScope(StandardTestDispatcher(testScheduler) + Job()))

        engine.enqueue(OperationKind.MOVE, listOf(source), target)
        advanceUntilIdle()

        assertEquals(OperationStatus.COMPLETED, engine.operations.value.single().status)
        assertEquals("bardzo duza zawartosc", ops.content("file:///cel/duzy.bin"))
        assertFalse(ops.exists("file:///zrodlo/duzy.bin"))
        assertEquals("rename nie moze czytac zawartosci", 0L, ops.bytesRead)
    }

    @Test
    fun `usuwanie kasuje cale drzewo`() = runTest {
        val ops = FakeOps()
        val folder = dir(ops, "file:///zrodlo/projekt")
        ops.addFile("file:///zrodlo/projekt/a.txt", "aaa")
        val engine = OperationEngine(ops, CoroutineScope(StandardTestDispatcher(testScheduler) + Job()))

        engine.enqueue(OperationKind.DELETE, listOf(folder), null)
        advanceUntilIdle()

        assertEquals(OperationStatus.COMPLETED, engine.operations.value.single().status)
        assertFalse(ops.exists("file:///zrodlo/projekt/a.txt"))
        assertFalse(ops.exists("file:///zrodlo/projekt"))
    }

    @Test
    fun `zakonczona operacja trafia do historii z parami do cofniecia`() = runTest {
        val ops = FakeOps().also { it.addDir("file:///cel") }
        val source = file(ops, "file:///zrodlo/raport.pdf", "tresc")
        val engine = OperationEngine(ops, CoroutineScope(StandardTestDispatcher(testScheduler) + Job()))

        engine.enqueue(OperationKind.COPY, listOf(source), target)
        advanceUntilIdle()

        val record = engine.history.value.single()
        assertEquals(OperationKind.COPY, record.kind)
        assertTrue(record.undoable)
        assertEquals(
            "file:///zrodlo/raport.pdf" to "file:///cel/raport.pdf",
            record.pairs.single()
        )
    }

    @Test
    fun `historia zapisuje tylko elementy najwyzszego poziomu`() = runTest {
        val ops = FakeOps().also { it.addDir("file:///cel") }
        val folder = dir(ops, "file:///zrodlo/projekt")
        ops.addFile("file:///zrodlo/projekt/a.txt", "aaa")
        ops.addDir("file:///zrodlo/projekt/pod")
        ops.addFile("file:///zrodlo/projekt/pod/b.txt", "bb")
        val engine = OperationEngine(ops, CoroutineScope(StandardTestDispatcher(testScheduler) + Job()))

        engine.enqueue(OperationKind.COPY, listOf(folder), target)
        advanceUntilIdle()

        // Cofanie ma operowac na przeniesionym katalogu, a nie na kazdym pliku
        // w srodku z osobna -- inaczej odtwarzanie struktury byloby niemozliwe.
        val record = engine.history.value.single()
        assertEquals(1, record.pairs.size)
        assertEquals(
            "file:///zrodlo/projekt" to "file:///cel/projekt",
            record.pairs.single()
        )
    }

    @Test
    fun `trwale usuniecie nie jest cofalne`() = runTest {
        val ops = FakeOps()
        val source = file(ops, "file:///zrodlo/raport.pdf", "tresc")
        val engine = OperationEngine(ops, CoroutineScope(StandardTestDispatcher(testScheduler) + Job()))

        engine.enqueue(OperationKind.DELETE, listOf(source), null)
        advanceUntilIdle()

        assertFalse(engine.history.value.single().undoable)
    }

    @Test
    fun `kolejne zadania wykonuja sie po kolei`() = runTest {
        val ops = FakeOps().also { it.addDir("file:///cel") }
        val first = file(ops, "file:///zrodlo/a.txt", "aaa")
        val second = file(ops, "file:///zrodlo/b.txt", "bbb")
        val engine = OperationEngine(ops, CoroutineScope(StandardTestDispatcher(testScheduler) + Job()))

        engine.enqueue(OperationKind.COPY, listOf(first), target)
        engine.enqueue(OperationKind.COPY, listOf(second), target)
        advanceUntilIdle()

        assertTrue(engine.operations.value.all { it.status == OperationStatus.COMPLETED })
        assertEquals("aaa", ops.content("file:///cel/a.txt"))
        assertEquals("bbb", ops.content("file:///cel/b.txt"))
    }

    @Test
    fun `przerwane rozpakowanie usuwa to, co zdazylo powstac`() = runTest {
        val ops = FakeOps().also { it.addDir("file:///cel") }
        ops.addFile("file:///cel/b.txt", "stary")
        val first = file(ops, "armaarc://a1/a.txt", "aaa")
        val folder = dir(ops, "armaarc://a1/pod")
        ops.addFile("armaarc://a1/pod/c.txt", "ccc")
        val second = file(ops, "armaarc://a1/b.txt", "nowy")
        val engine = OperationEngine(ops, CoroutineScope(StandardTestDispatcher(testScheduler) + Job()))

        val id = engine.enqueue(OperationKind.EXTRACT, listOf(first, folder, second), target)
        advanceUntilIdle()
        // Konflikt na b.txt zatrzymuje operację po wypakowaniu a.txt i pod/.
        assertNotNull(engine.pendingConflict.value)
        assertTrue(ops.exists("file:///cel/a.txt"))
        assertTrue(ops.exists("file:///cel/pod/c.txt"))

        engine.cancel(id)
        advanceUntilIdle()

        assertEquals(OperationStatus.CANCELLED, engine.operations.value.single().status)
        assertFalse(ops.exists("file:///cel/a.txt"))
        assertFalse(ops.exists("file:///cel/pod"))
        assertFalse(ops.exists("file:///cel/pod/c.txt"))
        assertEquals("plik sprzed rozpakowania zostaje", "stary", ops.content("file:///cel/b.txt"))
    }
}
