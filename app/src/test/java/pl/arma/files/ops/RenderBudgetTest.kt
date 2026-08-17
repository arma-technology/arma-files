package pl.arma.files.ops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderBudgetTest {

    @Test
    fun `mala strona renderuje sie w zadanej szerokosci`() {
        val (width, height) = RenderBudget.fit(1400, pageWidth = 595, pageHeight = 842)
        assertEquals(1400, width)
        assertEquals(1981, height)
    }

    @Test
    fun `duze zadanie jest przycinane do budzetu pikseli`() {
        // 4200 x A4 to okolo 25 mln pikseli, czyli 100 MB w ARGB_8888 --
        // dokladnie to wywracalo renderowanie przy powiekszeniu.
        val (width, height) = RenderBudget.fit(4200, pageWidth = 595, pageHeight = 842)
        assertTrue("oczekiwano przyciecia, bylo ${width}x$height", width < 4200)
        assertTrue(width.toLong() * height <= RenderBudget.MAX_PIXELS)
    }

    @Test
    fun `proporcje strony sa zachowane po przycieciu`() {
        val (width, height) = RenderBudget.fit(6000, pageWidth = 595, pageHeight = 842)
        val expected = 842.0 / 595.0
        val actual = height.toDouble() / width
        assertTrue("proporcje rozjechaly sie: $actual vs $expected", kotlin.math.abs(actual - expected) < 0.02)
    }

    @Test
    fun `strona pozioma tez miesci sie w budzecie`() {
        val (width, height) = RenderBudget.fit(5000, pageWidth = 842, pageHeight = 595)
        assertTrue(width.toLong() * height <= RenderBudget.MAX_PIXELS)
    }

    @Test
    fun `zbyt mala szerokosc jest podnoszona do minimum`() {
        val (width, _) = RenderBudget.fit(100, pageWidth = 595, pageHeight = 842)
        assertEquals(RenderBudget.MIN_WIDTH, width)
    }

    @Test
    fun `zerowe wymiary strony nie wywracaja obliczen`() {
        val (width, height) = RenderBudget.fit(1400, pageWidth = 0, pageHeight = 0)
        assertTrue(width > 0 && height > 0)
    }

    @Test
    fun `szerokosc renderowania zmienia sie skokowo, nie plynnie`() {
        // Kluczowe dla wydajnosci: pinch zmienia powiekszenie ciagle, ale
        // ponowne renderowanie ma nastepowac tylko po przekroczeniu progu.
        assertEquals(RenderBudget.widthForZoom(1.0f), RenderBudget.widthForZoom(1.4f))
        assertEquals(RenderBudget.widthForZoom(1.6f), RenderBudget.widthForZoom(2.4f))
        assertTrue(RenderBudget.widthForZoom(2.6f) > RenderBudget.widthForZoom(1.6f))
        assertTrue(RenderBudget.widthForZoom(1.6f) > RenderBudget.widthForZoom(1.0f))
    }
}
