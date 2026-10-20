package pl.arma.files

import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DepthExplorerTest {

    private fun file(name: String, dir: Boolean = false) =
        FileItem(Uri.parse("file:///sdcard/Download/$name"), name, if (dir) DIRECTORY_MIME else "image/jpeg", 10, 1, dir)

    private fun orbit(): OrbitController = OrbitController().apply {
        radius = 100f
        deadZone = 30f
        margin = 130f
        screen = Offset(1000f, 2000f)
        shelfTop = 1900f
    }

    @Test
    fun `kierunek palca wybiera akcje zgodnie z ruchem wskazowek zegara od gory`() {
        val c = orbit()
        c.begin(file("a.jpg"), Offset(500f, 1000f))
        c.move(Offset(500f, 900f))
        assertEquals(OrbitAction.SHARE, c.selected)
        // Sześć akcji co 60°: w prawo (90°) to trzecia z kolei.
        c.move(Offset(600f, 1000f))
        assertEquals(OrbitAction.RENAME, c.selected)
        c.move(Offset(500f, 1100f))
        assertEquals(OrbitAction.DELETE, c.selected)
        // 240° (lewo-dół) to piąta akcja; ikony leżą co 60°, jak w nakładce.
        c.move(Offset(500f - 87f, 1000f + 50f))
        assertEquals(OrbitAction.SELECT, c.selected)
        assertEquals(OrbitResult.Action(file("a.jpg"), OrbitAction.SELECT), c.end())
        assertNull(c.item)
    }

    @Test
    fun `puszczenie bez ruchu to szybki podglad, nad szuflada - dodanie do niej`() {
        val c = orbit()
        c.begin(file("a.jpg"), Offset(500f, 1000f))
        c.move(Offset(510f, 1005f))
        assertNull(c.selected)
        assertEquals(OrbitResult.Peek(file("a.jpg")), c.end())

        c.begin(file("b.jpg"), Offset(500f, 1000f))
        c.move(Offset(500f, 1950f))
        assertTrue(c.overShelf)
        assertEquals(OrbitResult.Action(file("b.jpg"), OrbitAction.SHELF), c.end())
    }

    @Test
    fun `kolo przy krawedzi ekranu przesuwa sie do srodka, folder nie ma udostepniania`() {
        val c = orbit()
        c.begin(file("dir", dir = true), Offset(10f, 20f))
        assertEquals(Offset(130f, 130f), c.center)
        assertTrue(OrbitAction.SHARE !in c.actions)
        assertEquals(5, c.actions.size)
    }

    @Test
    fun `kolorowanie skladni rozpoznaje slowa kluczowe, napisy i komentarze`() {
        val keyword = Color.Red
        val text = Syntax.highlight("val x = \"tekst\" // uwaga\nfun y() = 42", keyword, Color.Green, Color.Gray, Color.Blue)
        assertEquals("val x = \"tekst\" // uwaga\nfun y() = 42", text.text)
        val styles = text.spanStyles.map { text.text.substring(it.start, it.end) to it.item.color }
        assertTrue("val" to keyword in styles)
        assertTrue("fun" to keyword in styles)
        assertTrue("\"tekst\"" to Color.Green in styles)
        assertTrue("// uwaga" to Color.Gray in styles)
        assertTrue("42" to Color.Blue in styles)
    }

    @Test
    fun `szuflada przezywa ponowne uruchomienie`() {
        val store = ShelfStore(ApplicationProvider.getApplicationContext())
        val items = listOf(file("a.jpg"), file("folder", dir = true))
        store.save(items)
        assertEquals(items, ShelfStore(ApplicationProvider.getApplicationContext()).load())
    }
}
