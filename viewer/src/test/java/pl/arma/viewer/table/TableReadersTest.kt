package pl.arma.viewer.table

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
class TableReadersTest {

    @Test
    fun `csv z polskiego Excela ma srednik i cudzyslowy z nowa linia`() {
        val text = "Nazwa;Ilość;Cena\r\n\"Kabel; YDY\";12;\"1 234,50\"\r\n\"Opis\nw dwóch liniach\";;3\r\n"
        val book = CsvReader.read(text.toByteArray(), "zestawienie.csv")
        val sheet = book.sheets.single()
        assertEquals(3, sheet.rowCount)
        assertEquals(3, sheet.columns)
        assertEquals("Kabel; YDY", sheet.cell(1, 0)?.text)
        assertEquals("1 234,50", sheet.cell(1, 2)?.text)
        assertTrue(sheet.cell(1, 2)!!.numeric)
        assertFalse(sheet.cell(1, 0)!!.numeric)
        assertEquals("Opis\nw dwóch liniach", sheet.cell(2, 0)?.text)
        assertNull(sheet.cell(2, 1))
    }

    @Test
    fun `separator zgadywany z wierszy, cudzyslow w cudzyslowie`() {
        assertEquals(',', CsvReader.guessDelimiter("a,b,c\n1,2,3\n"))
        assertEquals('\t', CsvReader.guessDelimiter("a\tb\n1\t2\n"))
        val rows = CsvReader.parse("\"powiedział \"\"tak\"\"\",x", ',')
        assertEquals("powiedział \"tak\"", rows[0][0]?.text)
        assertEquals(1, rows.size)
    }

    @Test
    fun `nazwy kolumn jak w arkuszu`() {
        assertEquals("A", columnName(0))
        assertEquals("Z", columnName(25))
        assertEquals("AA", columnName(26))
        assertEquals("BC", columnName(54))
        assertEquals(54, columnIndex("BC12"))
        assertEquals(11, rowIndex("BC12"))
    }

    @Test
    fun `xlsx - wartosci, formaty, scalenia i ukryty arkusz`() {
        val file = xlsx(
            "xl/workbook.xml" to """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                <sheets><sheet name="Zestawienie" sheetId="1" r:id="rId1"/><sheet name="Ukryty" sheetId="2" state="hidden" r:id="rId2"/></sheets></workbook>""",
            "xl/_rels/workbook.xml.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                <Relationship Id="rId1" Target="worksheets/sheet1.xml" Type="x"/><Relationship Id="rId2" Target="worksheets/sheet2.xml" Type="x"/></Relationships>""",
            "xl/sharedStrings.xml" to """<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <si><t>Pozycja</t></si><si><r><t>Kabel </t></r><r><rPr><b/></rPr><t>YDY</t></r></si><si><t>Razem</t></si></sst>""",
            "xl/styles.xml" to """<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <numFmts><numFmt numFmtId="164" formatCode="0.000"/><numFmt numFmtId="165" formatCode="#,##0.00\ &quot;zł&quot;"/></numFmts>
                <fonts><font><sz val="11"/></font><font><b/><sz val="11"/></font></fonts>
                <cellXfs><xf numFmtId="0" fontId="0"/><xf numFmtId="0" fontId="1"/><xf numFmtId="14" fontId="0"/><xf numFmtId="10" fontId="0"/><xf numFmtId="164" fontId="0"/><xf numFmtId="165" fontId="0"/><xf numFmtId="3" fontId="0"/></cellXfs></styleSheet>""",
            "xl/worksheets/sheet1.xml" to """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <cols><col min="1" max="1" width="30"/><col min="3" max="3" width="0" hidden="1"/></cols>
                <sheetData>
                  <row r="1"><c r="A1" t="s" s="1"><v>0</v></c><c r="B1" t="inlineStr"><is><t>Data</t></is></c></row>
                  <row r="2"><c r="A2" t="s"><v>1</v></c><c r="B2" s="2"><v>46315</v></c><c r="D2" s="3"><v>0.125</v></c><c r="E2" s="4"><v>2.5</v></c></row>
                  <row r="4"><c r="A4" t="s"><v>2</v></c><c r="B4" s="5"><f>SUM(F1:F3)</f><v>12345.6</v></c><c r="C4" s="6"><v>1234567</v></c><c r="F4"><v>0.30000000000000004</v></c><c r="G4" t="b"><v>1</v></c></row>
                </sheetData>
                <mergeCells count="1"><mergeCell ref="A1:A2"/></mergeCells></worksheet>""",
            "xl/worksheets/sheet2.xml" to """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData/></worksheet>"""
        )
        val book = XlsxReader.readFile(file, Locale.forLanguageTag("pl-PL"))
        val sheet = book.sheets.single()
        assertEquals("Zestawienie", sheet.name)
        assertEquals(4, sheet.rowCount)
        assertEquals("Pozycja", sheet.cell(0, 0)?.text)
        assertTrue(sheet.cell(0, 0)!!.bold)
        assertEquals("Data", sheet.cell(0, 1)?.text)
        assertEquals("Kabel YDY", sheet.cell(1, 0)?.text)
        assertEquals("20.10.2026", sheet.cell(1, 1)?.text)
        assertEquals("12,50%", sheet.cell(1, 3)?.text)
        assertEquals("2,500", sheet.cell(1, 4)?.text)
        assertTrue(sheet.cell(1, 4)!!.numeric)
        // Pusty wiersz 3 zostaje pustym wierszem.
        assertEquals(0, sheet.rows[2].size)
        assertEquals("12 345,60 zł".replace(' ', ' '), sheet.cell(3, 1)?.text?.replace(' ', ' ')?.replace(' ', ' '))
        assertEquals("1 234 567", sheet.cell(3, 2)?.text?.replace(' ', ' ')?.replace(' ', ' '))
        assertEquals("0,3", sheet.cell(3, 5)?.text)
        assertEquals("PRAWDA", sheet.cell(3, 6)?.text)
        assertEquals(30f, sheet.widths[0])
        assertEquals(0f, sheet.widths[2])
        assertEquals(listOf(Merge(0, 0, 1, 0)), sheet.merges)
    }

    @Test
    fun `xlsx zapisany przez LibreOffice - widoczne arkusze, wyniki formul, waluta`() {
        val file = File(javaClass.getResource("/table/zestawienie-libreoffice.xlsx")!!.toURI())
        val book = XlsxReader.readFile(file, Locale.forLanguageTag("pl-PL"))
        assertEquals(listOf("Materiały", "Harmonogram"), book.sheets.map { it.name })
        val sheet = book.sheets[0]
        assertEquals("Zestawienie materiałów — budynek B, piętro 3", sheet.cell(0, 0)?.text)
        assertEquals(Merge(0, 0, 0, 5), sheet.merges.single())
        assertTrue(sheet.cell(2, 1)!!.bold)
        assertEquals("23%", sheet.cell(3, 4)?.text)
        assertEquals("24.10.2026", sheet.cell(3, 6)?.text)
        // Suma z formuły: wynik zapisany w pliku, z walutą i separatorem tysięcy.
        assertEquals("5 626 356,13 zł", sheet.cell(64, 5)?.text?.replace('\u00A0', ' ')?.replace('\u202F', ' '))
        assertEquals("65%", book.sheets[1].cell(2, 3)?.text)
    }

    private fun xlsx(vararg entries: Pair<String, String>): File {
        val file = File.createTempFile("test", ".xlsx")
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.trimIndent().toByteArray())
                zip.closeEntry()
            }
        }
        file.deleteOnExit()
        return file
    }
}
