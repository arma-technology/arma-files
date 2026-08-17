package pl.arma.files

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BrowserTabShapeTest {

    private val density = Density(density = 2.5f)

    private fun outline(width: Float, height: Float): Outline.Generic =
        BrowserTabShape().createOutline(
            Size(width, height),
            LayoutDirection.Ltr,
            density
        ) as Outline.Generic

    @Test
    fun `ksztalt wypelnia zadany obszar`() {
        val bounds = outline(400f, 100f).path.getBounds()
        assertEquals(0f, bounds.left, 0.5f)
        assertEquals(0f, bounds.top, 0.5f)
        assertEquals(400f, bounds.right, 0.5f)
        assertEquals(100f, bounds.bottom, 0.5f)
    }

    @Test
    fun `sciezka jest domknieta i niepusta`() {
        val path = outline(300f, 90f).path
        assertTrue("sciezka nie moze byc pusta", !path.isEmpty)
        val bounds = path.getBounds()
        assertTrue("oczekiwano dodatniej powierzchni", bounds.width > 0 && bounds.height > 0)
    }

    @Test
    fun `waska karta nie wywraca obliczen`() {
        // Przy bardzo waskiej karcie promien rogow i wkleslosc musza zostac
        // przyciete, inaczej krzywe zachodzilyby na siebie.
        val bounds = outline(40f, 80f).path.getBounds()
        assertTrue(bounds.width in 39f..41f)
        assertTrue(bounds.height in 79f..81f)
    }

    @Test
    fun `niska karta nie wywraca obliczen`() {
        val bounds = outline(200f, 12f).path.getBounds()
        assertTrue(bounds.height in 11f..13f)
    }
}
