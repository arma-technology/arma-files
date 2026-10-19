package pl.arma.viewer.sheet

import org.junit.Assert.assertEquals
import org.junit.Test

class SheetLayoutTest {

    // Telefon: widok 1000 px szerokosci, margines 10, odstep 20.
    private val a4 = SheetSize(595f, 842f)
    private val layout = SheetLayout(listOf(a4, a4, a4), viewWidth = 1000f, margin = 10f, gap = 20f)
    private val max = 16f

    @Test
    fun `strony ukladaja sie jedna pod druga`() {
        assertEquals(980f, layout.pageWidth, 0.01f)
        val pageHeight = 980f * 842 / 595
        assertEquals(10f, layout.tops[0], 0.01f)
        assertEquals(10f + pageHeight + 20f, layout.tops[1], 0.01f)
        assertEquals(10f + 3 * pageHeight + 2 * 20f + 10f, layout.height, 0.01f)
    }

    @Test
    fun `strona pod punktem dokumentu`() {
        assertEquals(0, layout.pageAt(0f))
        assertEquals(0, layout.pageAt(layout.tops[1] - 1f))
        assertEquals(1, layout.pageAt(layout.tops[1]))
        assertEquals(2, layout.pageAt(layout.height + 500f))
    }

    @Test
    fun `punkt pod palcami zostaje w miejscu przy powiekszaniu`() {
        val view = SheetView(zoom = 2f, x = 300f, y = 900f)
        val zoomed = view.zoomedAround(1.7f, fx = 420f, fy = 610f, maxZoom = max)
        // Punkt dokumentu pod palcami przed i po.
        val before = ((420f + view.x) / view.zoom) to ((610f + view.y) / view.zoom)
        val after = ((420f + zoomed.x) / zoomed.zoom) to ((610f + zoomed.y) / zoomed.zoom)
        assertEquals(before.first, after.first, 0.01f)
        assertEquals(before.second, after.second, 0.01f)
    }

    @Test
    fun `powiekszenie nie przekracza granic`() {
        assertEquals(max, SheetView(zoom = 12f).zoomedAround(3f, 0f, 0f, max).zoom, 0.001f)
        assertEquals(1f, SheetView(zoom = 1.2f).zoomedAround(.5f, 0f, 0f, max).zoom, 0.001f)
    }

    @Test
    fun `przesuniecie po skosie dziala w obu osiach naraz`() {
        val view = SheetView(zoom = 4f, x = 1000f, y = 1000f).panned(dx = -120f, dy = 80f)
        assertEquals(1120f, view.x, 0.01f)
        assertEquals(920f, view.y, 0.01f)
    }

    @Test
    fun `przesuniecie jest 1 do 1 niezaleznie od powiekszenia`() {
        // Palec przesuniety o 100 px przesuwa obraz o 100 px — takze przy 16x.
        val view = SheetView(zoom = 16f, x = 5000f, y = 5000f)
        assertEquals(4900f, view.panned(100f, 0f).x, 0.01f)
    }

    @Test
    fun `widok nie wychodzi poza dokument`() {
        val clamped = SheetView(zoom = 2f, x = -50f, y = 1e9f).clamped(layout, viewHeight = 1800f, maxZoom = max)
        assertEquals(0f, clamped.x, 0.01f)
        assertEquals(layout.height * 2f - 1800f, clamped.y, 0.01f)
        val right = SheetView(zoom = 2f, x = 1e9f).clamped(layout, 1800f, max)
        assertEquals(1000f, right.x, 0.01f)
    }

    @Test
    fun `niski arkusz stoi posrodku ekranu`() {
        val sheet = SheetLayout(listOf(SheetSize(2551f, 1684f)), viewWidth = 1000f, margin = 10f, gap = 20f)
        val view = SheetView().clamped(sheet, viewHeight = 1800f, maxZoom = max)
        assertEquals(-(1800f - sheet.height) / 2f, view.y, 0.01f)
    }

    @Test
    fun `pionowa strona w poziomie da sie oddalic do calej strony`() {
        // Telefon w poziomie: 2000 x 900 px, strona A4 ma przy powiekszeniu 1 ok. 2800 px wysokosci.
        val landscape = SheetLayout(listOf(a4, a4), viewWidth = 2000f, margin = 10f, gap = 20f)
        val min = landscape.minZoom(viewHeight = 900f)
        assertEquals(900f / (landscape.heights[0] + 20f), min, 0.0001f)
        val view = SheetView().zoomedAround(0.01f, 1000f, 450f, max, min).clamped(landscape, 900f, max)
        assertEquals(min, view.zoom, 0.0001f)
        // Wezsza od ekranu strona stoi posrodku w poziomie.
        assertEquals(-(2000f - 2000f * min) / 2f, view.x, 0.01f)
        // W pionie pozwalamy zejsc ponizej 1 tylko tyle, ile trzeba.
        assertEquals(1f, layout.minZoom(viewHeight = 1e5f), 0.0001f)
    }

    @Test
    fun `trafienie wyszukiwania laduje na srodku ekranu`() {
        val view = SheetView().centeredOn(px = 300f, py = 2_000f, zoom = 4f, viewWidth = 1000f, viewHeight = 1800f)
        // Punkt dokumentu na ekranie: p * zoom - przesuniecie.
        assertEquals(500f, 300f * view.zoom - view.x, 0.01f)
        assertEquals(900f, 2_000f * view.zoom - view.y, 0.01f)
    }

    @Test
    fun `po obrocie srodek ekranu pokazuje to samo miejsce na stronie`() {
        // Pion bez zapasu pod narzedzia, poziom pelnoekranowy z zapasem 160 px —
        // przy 16x zapas skalowany razem z dokumentem przesunalby widok o 2560 px.
        val sheet = listOf(SheetSize(2551f, 1684f))
        val portrait = SheetLayout(sheet, viewWidth = 1000f, margin = 20f, gap = 30f)
        val landscape = SheetLayout(sheet, viewWidth = 2400f, margin = 20f, gap = 30f, topPadding = 160f)
        val (px, py) = portrait.pointOf(0, .3f, .6f)
        val before = SheetView().centeredOn(px, py, zoom = 16f, viewWidth = 1000f, viewHeight = 1700f)

        val after = before.relaidOut(portrait, 1700f, landscape, 1080f)

        val (lx, ly) = landscape.pointOf(0, .3f, .6f)
        assertEquals(1200f, lx * after.zoom - after.x, 0.5f)
        assertEquals(540f, ly * after.zoom - after.y, 0.5f)
    }
}
