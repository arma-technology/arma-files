package pl.arma.viewer.sheet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class SnapTest {

    // Pokój 4000 x 3000 (jednostki rysunku), ściany jako polilinia, w środku
    // słup jako okrąg spłaszczony do łamanej.
    private val room: SnapGeometry = SnapGeometry.Builder().apply {
        polyline(floatArrayOf(0f, 0f, 4000f, 0f, 4000f, 3000f, 0f, 3000f), closed = true)
        line(2000f, -500f, 2000f, 3500f) // oś konstrukcyjna przez pokój
        val circle = FloatArray(66)
        for (i in 0..32) {
            val a = i / 32.0 * 2 * Math.PI
            circle[2 * i] = (1000 + 100 * cos(a)).toFloat()
            circle[2 * i + 1] = (1500 + 100 * sin(a)).toFloat()
        }
        curve(circle)
        center(1000f, 1500f)
    }.build()

    @Test
    fun `koniec ma pierwszenstwo przed linia`() {
        val s = room.snap(3990f, 15f, radius = 50f)
        assertEquals(SnapKind.END, s.kind)
        assertEquals(4000f, s.x, 0.01f)
        assertEquals(0f, s.y, 0.01f)
    }

    @Test
    fun `przeciecie osi ze sciana`() {
        val s = room.snap(2010f, 20f, radius = 50f)
        assertEquals(SnapKind.INTERSECTION, s.kind)
        assertEquals(2000f, s.x, 0.01f)
        assertEquals(0f, s.y, 0.01f)
    }

    @Test
    fun `srodek odcinka`() {
        val s = room.snap(4010f, 1490f, radius = 30f)
        assertEquals(SnapKind.MIDPOINT, s.kind)
        assertEquals(1500f, s.y, 0.01f)
    }

    @Test
    fun `na linii gdy nic lepszego w zasiegu`() {
        val s = room.snap(4012f, 700f, radius = 30f)
        assertEquals(SnapKind.NEAREST, s.kind)
        assertEquals(4000f, s.x, 0.01f)
        assertEquals(700f, s.y, 0.01f)
    }

    @Test
    fun `srodek okregu, a wewnetrzne wierzcholki luku nie sa koncami`() {
        assertEquals(SnapKind.CENTER, room.snap(1005f, 1495f, radius = 30f).kind)
        // Punkt na obwodzie, z dala od początku łuku: tylko „na linii”.
        val s = room.snap(1000f, 1601f, radius = 10f)
        assertEquals(SnapKind.NEAREST, s.kind)
    }

    @Test
    fun `prostopadle do sciany od pierwszego punktu`() {
        val from = SnapResult(1000f, 1500f, SnapKind.CENTER)
        // Kursor przy prawej ścianie, trochę obok spodka prostopadłej.
        val s = room.snap(3995f, 1530f, radius = 60f, from = from)
        assertEquals(SnapKind.PERPENDICULAR, s.kind)
        assertEquals(4000f, s.x, 0.01f)
        assertEquals(1500f, s.y, 0.01f)
    }

    @Test
    fun `os pozioma trafia w sciane dokladnie na wysokosci punktu`() {
        val from = SnapResult(1000f, 1234f, SnapKind.CENTER)
        // Palec odjechał w pionie o 40 jednostek — oś to wyrównuje.
        val s = room.snap(3990f, 1274f, radius = 60f, from = from, axisLock = true)
        assertEquals(SnapKind.AXIS_INTERSECTION, s.kind)
        assertEquals(4000f, s.x, 0.01f)
        assertEquals(1234f, s.y, 0.01f)
    }

    @Test
    fun `os bez linii w poblizu zostaje na osi`() {
        val from = SnapResult(1000f, 1000f, SnapKind.NONE)
        val s = room.snap(1300f, 2500f, radius = 20f, from = from, axisLock = true)
        assertEquals(SnapKind.AXIS, s.kind)
        assertEquals(1000f, s.x, 0.01f)
        assertEquals(2500f, s.y, 0.01f)
    }

    @Test
    fun `poza zasiegiem kursor zostaje gdzie byl`() {
        val s = room.snap(3000f, 2000f, radius = 20f)
        assertEquals(SnapKind.NONE, s.kind)
        assertEquals(3000f, s.x, 0.01f)
    }

    @Test
    fun `duzo odcinkow nadal szybko`() {
        val builder = SnapGeometry.Builder()
        for (i in 0 until 200_000) {
            val x = (i % 1000) * 10f
            val y = (i / 1000) * 10f
            builder.line(x, y, x + 8f, y + 3f)
        }
        val g = builder.build()
        val start = System.nanoTime()
        repeat(1000) { g.snap(5003f, 1001f, radius = 15f) }
        val perQueryMs = (System.nanoTime() - start) / 1e6 / 1000
        assert(perQueryMs < 2.0) { "zapytanie trwało $perQueryMs ms" }
    }

    @Test
    fun `przeciecie odcinkow liczy sie w podwojnej precyzji`() {
        val hit = SnapGeometry.intersect(500_000f, 0f, 500_000f, 10f, 499_990f, 5f, 500_010f, 5f)!!
        assertEquals(500_000f, hit.first, 0.001f)
        assertEquals(5f, hit.second, 0.001f)
        assertNull(SnapGeometry.intersect(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))
    }

    @Test
    fun `kreska wymiaru blizej niz koniec przerwanej linii wymiarowej`() {
        // Wymiar z AutoCAD: linia przerwana 2,9 jednostki obok kreski „+”.
        val g = SnapGeometry.Builder().apply {
            line(1719.84f, 0f, 1725.48f, 0f)   // kawałek linii przez kreskę
            line(1722.60f, -1.38f, 1722.60f, 1.38f) // pionowa kreska
            line(1725.48f, 0f, 1807.92f, 0f)   // linia wymiarowa
        }.build()
        val s = g.snap(1723.0f, 0.3f, radius = 6f)
        assertEquals(SnapKind.INTERSECTION, s.kind)
        assertEquals(1722.60f, s.x, 0.01f)
    }

    @Test
    fun `srodek kreski wymiaru wygrywa z jej koncami przy niedokladnym celowaniu`() {
        // Kreska „+”: pozioma 5,64 i pionowa 2,76 jednostki, linia wymiaru przerwana obok.
        val g = SnapGeometry.Builder().apply {
            line(1719.84f, 0f, 1725.48f, 0f)
            line(1722.60f, -1.38f, 1722.60f, 1.38f)
            line(1725.48f, 0f, 1807.92f, 0f)
        }.build()
        // Celownik bliżej końca pionowej kreski niż jej środka.
        val s = g.snap(1722.9f, 0.8f, radius = 14f)
        assertEquals(SnapKind.INTERSECTION, s.kind)
        assertEquals(1722.60f, s.x, 0.01f)
        assertEquals(0f, s.y, 0.01f)
    }
}
