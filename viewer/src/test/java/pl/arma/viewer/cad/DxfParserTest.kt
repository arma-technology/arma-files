package pl.arma.viewer.cad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.arma.viewer.sheet.SnapKind
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DxfParserTest {

    /** Rysunek testowy jako pary (kod, wartość). */
    private val groups: List<Pair<Int, String>> = buildList {
        fun g(code: Int, value: Any) = add(code to value.toString())
        g(0, "SECTION"); g(2, "HEADER")
        g(9, "\$ACADVER"); g(1, "AC1018")
        g(9, "\$DWGCODEPAGE"); g(3, "ANSI_1250")
        g(9, "\$INSUNITS"); g(70, 4)
        g(9, "\$LTSCALE"); g(40, 10.0)
        g(0, "ENDSEC")
        g(0, "SECTION"); g(2, "TABLES")
        g(0, "TABLE"); g(2, "LTYPE")
        g(0, "LTYPE"); g(2, "DASHED"); g(70, 0); g(73, 2); g(40, 0.75); g(49, 0.5); g(49, -0.25)
        g(0, "ENDTAB")
        g(0, "TABLE"); g(2, "LAYER")
        g(0, "LAYER"); g(2, "SCIANY"); g(70, 0); g(62, 1); g(6, "CONTINUOUS")
        g(0, "LAYER"); g(2, "UKRYTA"); g(70, 0); g(62, -3); g(6, "DASHED")
        g(0, "ENDTAB")
        g(0, "ENDSEC")
        g(0, "SECTION"); g(2, "BLOCKS")
        g(0, "BLOCK"); g(8, "0"); g(2, "SLUP"); g(70, 0); g(10, 0.0); g(20, 0.0)
        g(0, "CIRCLE"); g(8, "0"); g(10, 0.0); g(20, 0.0); g(40, 100.0)
        g(0, "LINE"); g(8, "0"); g(62, 0); g(10, 0.0); g(20, 0.0); g(11, 100.0); g(21, 0.0)
        g(0, "ENDBLK")
        g(0, "BLOCK"); g(8, "0"); g(2, "*D1"); g(70, 1); g(10, 0.0); g(20, 0.0)
        g(0, "LINE"); g(8, "0"); g(10, 0.0); g(20, -500.0); g(11, 4000.0); g(21, -500.0)
        g(0, "MTEXT"); g(8, "0"); g(10, 2000.0); g(20, -450.0); g(40, 100.0); g(71, 8); g(1, "4000")
        g(0, "ENDBLK")
        g(0, "ENDSEC")
        g(0, "SECTION"); g(2, "ENTITIES")
        // Pokój 4000×3000 jako zamknięta polilinia na warstwie ścian.
        g(0, "LWPOLYLINE"); g(8, "SCIANY"); g(90, 4); g(70, 1)
        g(10, 0.0); g(20, 0.0); g(10, 4000.0); g(20, 0.0); g(10, 4000.0); g(20, 3000.0); g(10, 0.0); g(20, 3000.0)
        // Ćwierćokrąg drzwi z wybrzuszenia: tan(90°/4).
        g(0, "LWPOLYLINE"); g(8, "SCIANY"); g(90, 2); g(70, 0)
        g(10, 1000.0); g(20, 0.0); g(42, Math.tan(Math.PI / 8)); g(10, 0.0); g(20, 1000.0)
        // Słup wstawiony w (2000,1500), skala 2, obrót 90°, na warstwie ścian.
        g(0, "INSERT"); g(8, "SCIANY"); g(62, 3); g(2, "SLUP"); g(10, 2000.0); g(20, 1500.0); g(41, 2.0); g(42, 2.0); g(50, 90.0)
        g(0, "LINE"); g(8, "UKRYTA"); g(10, 0.0); g(20, 0.0); g(11, 4000.0); g(21, 3000.0)
        g(0, "TEXT"); g(8, "SCIANY"); g(10, 100.0); g(20, 100.0); g(40, 250.0); g(1, "POKÓJ %%c12"); g(50, 0.0)
        g(0, "MTEXT"); g(8, "SCIANY"); g(10, 2000.0); g(20, 2500.0); g(40, 150.0); g(71, 5)
        g(3, "{\\fArial|b1;Salon}\\PPow. 12,5 m\\U+00B2 \\S1/2;")
        g(1, "")
        g(0, "HATCH"); g(8, "SCIANY"); g(2, "SOLID"); g(70, 1); g(71, 0); g(91, 1)
        g(92, 2); g(72, 0); g(73, 1); g(93, 4)
        g(10, 100.0); g(20, 2600.0); g(10, 400.0); g(20, 2600.0); g(10, 400.0); g(20, 2900.0); g(10, 100.0); g(20, 2900.0)
        g(97, 0); g(75, 0); g(76, 1); g(98, 0)
        g(0, "DIMENSION"); g(8, "SCIANY"); g(2, "*D1"); g(1, ""); g(42, 4000.0); g(11, 2000.0); g(21, -450.0)
        g(0, "ENDSEC")
        g(0, "EOF")
    }

    private fun ascii(): ByteArray = buildString {
        for ((code, value) in groups) append(code).append("\r\n").append(value).append("\r\n")
    }.toByteArray(charset("windows-1250"))

    /** Ten sam rysunek w formacie binarnym DXF (kody dwubajtowe, liczby binarnie). */
    private fun binary(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("AutoCAD Binary DXF\r\n".toByteArray(Charsets.ISO_8859_1))
        out.write(byteArrayOf(0x1A, 0))
        fun le(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        for ((code, value) in groups) {
            out.write(le(2).putShort(code.toShort()).array())
            when (DxfReader.valueType(code)) {
                DxfReader.ValueType.DOUBLE -> out.write(le(8).putDouble(value.toDouble()).array())
                DxfReader.ValueType.INT16 -> out.write(le(2).putShort(value.toInt().toShort()).array())
                DxfReader.ValueType.INT32 -> out.write(le(4).putInt(value.toInt()).array())
                DxfReader.ValueType.INT64 -> out.write(le(8).putLong(value.toLong()).array())
                DxfReader.ValueType.BOOL -> out.write(value.toInt())
                else -> {
                    out.write(value.toByteArray(charset("windows-1250")))
                    out.write(0)
                }
            }
        }
        return out.toByteArray()
    }

    private fun scene(bytes: ByteArray): CadScene {
        val builder = CadSceneBuilder()
        val drawing = DxfParser.parse(ByteArrayInputStream(bytes), builder::collect)
        return builder.build(drawing)
    }

    private fun checkScene(scene: CadScene) {
        // Warstwy: dwie z tabeli, „UKRYTA” wyłączona (kolor ujemny).
        val ukryta = scene.layerNames.indexOf("UKRYTA")
        assertFalse(scene.layerInitiallyVisible[ukryta])
        assertEquals(1.0, scene.mmPerUnit!!, 0.0)

        // Teksty: polskie znaki ze strony 1250, %%c, MTEXT bez formatowania.
        val texts = scene.texts.map { it.text }
        assertTrue(texts.toString(), "POKÓJ Ø12" in texts)
        assertTrue(texts.toString(), "Salon\nPow. 12,5 m² 1/2" in texts)
        assertTrue("napis wymiaru z bloku *D", "4000" in texts)

        // Wstawiony słup: środek w (2000,1500), promień 200 (skala 2).
        val geometry = scene.snapGeometry(BooleanArray(scene.layerNames.size) { true })
        val px = scene.pageX((2000 - scene.originX).toFloat())
        val py = scene.pageY((1500 - scene.originY).toFloat())
        val hit = geometry.snap(px + 3, py - 2, radius = 10f)
        // Linia bloku zaczyna się w środku okręgu, więc wygrywa „koniec” —
        // ważne, że to ten sam punkt.
        assertTrue(hit.kind in setOf(SnapKind.CENTER, SnapKind.END, SnapKind.INTERSECTION))
        assertEquals(px, hit.x, 0.01f)
        assertEquals(py, hit.y, 0.01f)
        // Linia bloku po obrocie o 90° biegnie w górę: koniec w (2000, 1700).
        val end = geometry.snap(px, scene.pageY((1700 - scene.originY).toFloat()) + 4, radius = 10f)
        assertEquals(SnapKind.END, end.kind)

        // Łuk drzwi: punkt w połowie łuku leży na okręgu o promieniu 1000.
        val mid = Math.sqrt(0.5) * 1000
        val onArc = geometry.snap(scene.pageX((mid - scene.originX).toFloat()) + 2, scene.pageY((mid - scene.originY).toFloat()), radius = 5f)
        assertEquals(SnapKind.NEAREST, onArc.kind)

        // Wypełnienie kreskowania i styl kreskowany z LTSCALE 10.
        assertEquals(1, scene.fills.count)
        val dashed = scene.styles.firstOrNull { it.dashes != null }!!
        assertEquals(5f, dashed.dashes!![0], 1e-4f)
        assertEquals(-2.5f, dashed.dashes!![1], 1e-4f)
    }

    @Test
    fun `tekstowy DXF`() = checkScene(scene(ascii()))

    @Test
    fun `binarny DXF daje to samo`() = checkScene(scene(binary()))

    @Test
    fun `zakres rysunku obejmuje pokoj i wymiar`() {
        val s = scene(ascii())
        assertTrue(s.width >= 4000f)
        assertTrue(s.height >= 3500f)
    }

    @Test
    fun `MTEXT formatowanie`() {
        assertEquals("A\nB", DxfText.mtextPlain("{\\H2.5x;A}\\PB"))
        assertEquals("Ø 10 ±1°", DxfText.mtextPlain("%%c 10 %%p1%%d"))
        assertEquals("ł", DxfText.unescapeUnicode("\\U+0142"))
    }

    @Test
    fun `krzywa NURBS przechodzi przez konce`() {
        val pts = Doubles()
        CadGeometry.nurbs(
            3, doubleArrayOf(0.0, 0.0, 0.0, 0.0, 1.0, 1.0, 1.0, 1.0),
            doubleArrayOf(0.0, 1.0, 2.0, 3.0), doubleArrayOf(0.0, 2.0, 2.0, 0.0), DoubleArray(0), pts
        )
        assertEquals(0.0, pts[0], 1e-9)
        assertEquals(3.0, pts[pts.size - 2], 1e-9)
        assertEquals(0.0, pts[pts.size - 1], 1e-9)
    }

    @Test
    fun `paleta ACI`() {
        assertEquals(0xFF0000, Aci.rgb(1))
        assertEquals(0xFF7F7F, Aci.rgb(11))
        assertEquals(0xCC6666, Aci.rgb(13))
        assertEquals(0xFF3F00, Aci.rgb(20))
        assertEquals(0x828282, Aci.rgb(253))
    }

    @Test
    fun `kropka wymiaru przyciaga do punktu wymiarowego`() {
        // Wymiar 4524 z kropkami jak w rysunkach z Revit: linia wymiarowa
        // i kropka w każdym końcu jako blok wstawiony w skali 280.
        val dot = CadBlock("_DOT", 0.0, 0.0, 0, mutableListOf(
            CadPolyline(doubleArrayOf(-.25, .25), doubleArrayOf(0.0, 0.0), doubleArrayOf(1.0, 1.0), closed = true, width = .5)
        ))
        val dim = CadBlock("*D1", 0.0, 0.0, 1, mutableListOf(
            CadLine(0.0, 0.0, 4524.0, 0.0),
            CadInsert("_DOT", 0.0, 0.0, 280.0, 280.0, 0.0, 1, 1, 0.0, 0.0, emptyList()),
            CadInsert("_DOT", 4524.0, 0.0, 280.0, 280.0, 0.0, 1, 1, 0.0, 0.0, emptyList())
        ))
        val drawing = CadDrawing(CadHeader("AC1032", 4, 1.0, true, null, null), emptyMap(), emptyMap(), emptyMap(),
            mapOf("_DOT" to dot, "*D1" to dim), emptyMap())
        val builder = CadSceneBuilder()
        builder.collect(CadDimension("*D1", "", 4524.0, 0.0, 0.0))
        val scene = builder.build(drawing)
        val g = scene.snapGeometry(BooleanArray(scene.layerNames.size) { true })
        val y = scene.pageY((0 - scene.originY).toFloat())
        val a = g.snap(scene.pageX((30 - scene.originX).toFloat()), y + 20, radius = 120f)
        val b = g.snap(scene.pageX((4524 - 40 - scene.originX).toFloat()), y - 20, radius = 120f)
        assertEquals(4524f, b.x - a.x, 0.5f)
    }
}
