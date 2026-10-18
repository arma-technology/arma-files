package pl.arma.viewer.sheet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class MeasureTest {

    @Test
    fun `dlugosc w milimetrach i metrach z separatorem jezyka`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("pl-PL"))
            assertEquals("845 mm", MeasureFormat.length(845.2))
            assertEquals("3,245 m", MeasureFormat.length(3245.0))
            Locale.setDefault(Locale.ENGLISH)
            assertEquals("3.245 m", MeasureFormat.length(3245.0))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `skala 1 do 50 na arkuszu PDF`() {
        val scale = SheetScale.pdfRatio(50.0, ScaleSource.USER)
        // 72 punkty to cal na papierze, czyli 25,4 mm × 50 w rzeczywistości.
        assertEquals(1270.0, 72 * scale.mmPerUnit, 1e-9)
        assertEquals("1:50", scale.label)
    }

    @Test
    fun `skala opisana w tabelce`() {
        assertEquals(100, MeasureFormat.detectScale("RZUT PARTERU   SKALA 1:100   arkusz A0"))
        assertEquals(50, MeasureFormat.detectScale("Scale: 1 : 50"))
        assertEquals(25, MeasureFormat.detectScale("sk. 1/25"))
    }

    @Test
    fun `samotne 1 do N tylko dla typowych skal`() {
        assertEquals(50, MeasureFormat.detectScale("detal A 1:50, mieszanka cement:piasek 1:3"))
        assertNull(MeasureFormat.detectScale("mieszanka 1:3, proporcja 1:4"))
        assertNull(MeasureFormat.detectScale("godzina 11:50"))
    }

    @Test
    fun `wpisana dlugosc z jednostka`() {
        assertEquals(3250.0, MeasureFormat.parseLength("3,25 m")!!, 1e-9)
        assertEquals(1200.0, MeasureFormat.parseLength("120cm")!!, 1e-9)
        assertEquals(900.0, MeasureFormat.parseLength("900")!!, 1e-9)
        assertNull(MeasureFormat.parseLength("abc"))
    }

    @Test
    fun `jednostki z PDF i CAD`() {
        assertEquals(304.8, MeasureFormat.mmPerUnit("ft")!!, 1e-9)
        assertEquals(1000.0, MeasureFormat.mmPerUnit("M")!!, 1e-9)
        assertNull(MeasureFormat.mmPerUnit("furlong"))
    }

    @Test
    fun `skala opisana w innych jezykach`() {
        assertEquals(50, MeasureFormat.detectScale("Maßstab 1:50"))
        assertEquals(100, MeasureFormat.detectScale("MĚŘÍTKO 1:100"))
        assertEquals(200, MeasureFormat.detectScale("МАСШТАБ 1:200"))
        assertEquals(25, MeasureFormat.detectScale("Méretarány: 1:25"))
    }
}
