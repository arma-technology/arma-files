package pl.arma.viewer.sheet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    // Telefon 1080 x 1900, strona A4 przy 1600% ma okolo 16 500 px szerokosci.
    private fun detail(left: Float, top: Float, width: Int = 16_500, height: Int = 23_350, base: Int = 1_450) =
        RenderBudget.detailRegion(left, top, width, height, viewWidth = 1080, viewHeight = 1900, baseWidth = base)

    @Test
    fun `bez powiekszenia wycinek nie jest potrzebny`() {
        assertNull(detail(0f, 0f, width = 1_030, height = 1_457, base = 1_400))
    }

    @Test
    fun `wycinek to dokladnie widoczny fragment strony`() {
        val region = detail(left = -5_000f, top = -8_000f)!!
        assertEquals(5_000, region.left)
        assertEquals(8_000, region.top)
        assertEquals(1080, region.width)
        assertEquals(1900, region.height)
        assertEquals(1f, region.scale)
    }

    @Test
    fun `wycinek nie wychodzi poza strone`() {
        // Prawy dolny rog strony w polowie ekranu.
        val region = detail(left = -(16_500f - 540f), top = -(23_350f - 950f))!!
        assertEquals(16_500, region.right)
        assertEquals(23_350, region.bottom)
        assertEquals(540, region.width)
        assertEquals(950, region.height)
    }

    @Test
    fun `strona poza ekranem nie ma wycinka`() {
        assertNull(detail(left = 0f, top = 2_500f))
        assertNull(detail(left = 0f, top = -23_350f - 10f))
    }

    @Test
    fun `wycinek miesci sie w budzecie niezaleznie od powiekszenia`() {
        for (width in listOf(3_000, 16_500, 100_000)) {
            val region = detail(left = -1_000f, top = -1_000f, width = width, height = width * 3 / 2)!!
            assertTrue(region.width.toLong() * region.height <= RenderBudget.MAX_PIXELS)
        }
    }

    @Test
    fun `duzy ekran tabletu jest skalowany do budzetu`() {
        val region = RenderBudget.detailRegion(
            -1_000f, -1_000f, pageWidth = 20_000, pageHeight = 28_000,
            viewWidth = 2560, viewHeight = 1600, baseWidth = 1_450
        )!!
        val rendered = (region.width * region.scale).toLong() * (region.height * region.scale).toLong()
        assertTrue(region.scale < 1f)
        assertTrue(rendered <= RenderBudget.MAX_PIXELS)
    }
}
