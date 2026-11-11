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

    // Prostokąt 1000 x 600 zakreskowany pod kątem 45° co 40 jednostek; kreski
    // kończą się na krawędziach, jak w rysunku wydrukowanym do PDF.
    private fun hatched(extra: SnapGeometry.Builder.() -> Unit = {}): SnapGeometry = SnapGeometry.Builder().apply {
        polyline(floatArrayOf(0f, 0f, 1000f, 0f, 1000f, 600f, 0f, 600f), closed = true)
        var c = -560f
        while (c < 1000f) {
            // y = x - c przycięte do prostokąta
            val x1 = maxOf(0f, c)
            val x2 = minOf(1000f, c + 600f)
            if (x2 > x1) line(x1, x1 - c, x2, x2 - c)
            c += 40f
        }
        extra()
    }.build()

    @Test
    fun `kreskowanie rozpoznane a krawedzie nie`() {
        val g = hatched()
        for (i in 0 until 4) assertEquals("krawędź $i", false, g.isHatching(i))
        val marked = (4 until g.segmentCount).count { g.isHatching(it) }
        // Skrajne kreski w narożnikach nie mają pełnej rodziny sąsiadów.
        assertEquals(true, marked >= g.segmentCount - 4 - 6)
    }

    @Test
    fun `krawedzie maja pierwszenstwo przed kreskowaniem`() {
        val g = hatched()
        // Tuż przy dolnej krawędzi, obok końca kreski.
        val plain = g.snap(523f, 596f, radius = 12f)
        assertEquals(520f, plain.x, 0.01f)
        val edges = g.snap(523f, 596f, radius = 12f, edgesOnly = true)
        assertEquals(SnapKind.NEAREST, edges.kind)
        assertEquals(600f, edges.y, 0.01f)
        assertEquals(523f, edges.x, 0.01f)
        // W środku kreskowania nie ma do czego przyciągać.
        assertEquals(SnapKind.NONE, g.snap(500f, 300f, radius = 12f, edgesOnly = true).kind)
    }

    @Test
    fun `sciana z dwoch linii i schody z czterech stopni to nie kreskowanie`() {
        val g = SnapGeometry.Builder().apply {
            line(0f, 0f, 1000f, 0f)
            line(0f, 25f, 1000f, 25f)
            for (k in 0 until 4) line(2000f + 30f * k, 0f, 2000f + 30f * k, 120f)
        }.build()
        for (i in 0 until g.segmentCount) assertEquals(false, g.isHatching(i))
    }

    @Test
    fun `krawedz rownolegla do kreskowania o jeden odstep zostaje krawedzia`() {
        // Poziome kreski co 20 w prostokącie, którego dolna i górna krawędź
        // leżą dokładnie o jeden odstęp od skrajnych kresek.
        val g = SnapGeometry.Builder().apply {
            polyline(floatArrayOf(0f, 0f, 500f, 0f, 500f, 200f, 0f, 200f), closed = true)
            for (k in 1..9) line(0f, 20f * k, 500f, 20f * k)
        }.build()
        for (i in 0 until 4) assertEquals(false, g.isHatching(i))
        assertEquals(9, (4 until g.segmentCount).count { g.isHatching(it) })
    }

    @Test
    fun `prowadnica prostopadla trafia w linie naprzeciwko`() {
        // Dwie ściany pod kątem; z punktu na pierwszej prostopadle do drugiej.
        val g = SnapGeometry.Builder().apply {
            line(0f, 0f, 1000f, 1000f)
            line(0f, 200f, 1000f, 1200f)
        }.build()
        val from = SnapResult(500f, 500f, SnapKind.NEAREST)
        // Kierunek prostopadły do ściany (1, 1) to (-1, 1); celownik niedokładnie.
        val s = g.snapOnLine(410f, 615f, radius = 40f, from = from, dirX = -1f, dirY = 1f)
        assertEquals(SnapKind.AXIS_INTERSECTION, s.kind)
        assertEquals(400f, s.x, 0.01f)
        assertEquals(600f, s.y, 0.01f)
        // Daleko od linii kursor zostaje na prowadnicy.
        val free = g.snapOnLine(470f, 540f, radius = 10f, from = from, dirX = -1f, dirY = 1f)
        assertEquals(SnapKind.AXIS, free.kind)
        assertEquals(1000f, free.x + free.y, 0.01f)
    }

    @Test
    fun `najblizszy odcinek jako linia odniesienia`() {
        val s = room.nearestSegment(3990f, 1400f, radius = 50f)!!
        assertEquals(4000f, s[0], 0.01f)
        assertEquals(4000f, s[2], 0.01f)
        assertNull(room.nearestSegment(3000f, 1500f, radius = 50f))
    }

    @Test
    fun `rozpoznanie kreskowania na duzym rysunku miesci sie w czasie`() {
        val b = SnapGeometry.Builder()
        val random = java.util.Random(7)
        // 300 tys. odcinków: połowa to gęste kreskowania, połowa luźne linie.
        repeat(1500) {
            val ox = random.nextFloat() * 20000f
            val oy = random.nextFloat() * 20000f
            for (k in 0 until 100) b.line(ox + k * 3f, oy, ox + k * 3f + 150f, oy + 150f)
        }
        repeat(150_000) {
            val x = random.nextFloat() * 20000f
            val y = random.nextFloat() * 20000f
            b.line(x, y, x + random.nextFloat() * 400f - 200f, y + random.nextFloat() * 400f - 200f)
        }
        val start = System.nanoTime()
        val g = b.build()
        val ms = (System.nanoTime() - start) / 1_000_000
        println("hatch build: $ms ms, segments ${g.segmentCount}")
        assertEquals(true, ms < 20_000)
    }
}
