package pl.arma.viewer.table

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.math.BigDecimal
import java.math.MathContext
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipFile

/**
 * Plik .xlsx (Office Open XML) do odczytu: arkusze, wartości, wyniki formuł
 * zapisane przez Excel, scalenia, szerokości kolumn, pogrubienie i formaty
 * liczb (daty, procenty, miejsca po przecinku, separator tysięcy).
 *
 * Bez Apache POI — to kilkanaście megabajtów i wolne parsowanie DOM. Tu jest
 * strumieniowy XmlPullParser wbudowany w Androida.
 */
object XlsxReader {

    fun readFile(file: File, locale: Locale = Locale.getDefault()): Workbook = ZipFile(file).use { zip ->
        fun open(path: String): InputStream? = zip.getEntry(path.trimStart('/'))?.let(zip::getInputStream)

        val workbook = open("xl/workbook.xml")?.use(::parseWorkbook) ?: throw IllegalArgumentException("not an xlsx file")
        val targets = open("xl/_rels/workbook.xml.rels")?.use(::parseRelationships).orEmpty()
        val strings = open("xl/sharedStrings.xml")?.use(::parseSharedStrings).orEmpty()
        val styles = open("xl/styles.xml")?.use(::parseStyles) ?: Styles(emptyList(), emptyMap(), emptyList())
        val format = ValueFormat(locale, workbook.date1904)
        val notes = mutableListOf<String>()

        val sheets = workbook.sheets.mapNotNull { (name, relation) ->
            val target = targets[relation] ?: return@mapNotNull null
            // Ścieżki w relacjach są względne do xl/, czasem bezwzględne.
            val path = if (target.startsWith("/")) target.drop(1) else "xl/$target"
            open(path)?.use { parseSheet(it, name, strings, styles, format, notes) }
        }
        Workbook(sheets, notes.distinct())
    }

    internal class WorkbookInfo(val sheets: List<Pair<String, String>>, val date1904: Boolean)

    internal class Styles(
        /** Dla każdego stylu komórki: numer formatu liczby i czy czcionka pogrubiona. */
        val cells: List<Pair<Int, Boolean>>,
        val customFormats: Map<Int, String>,
        val boldFonts: List<Boolean>
    ) {
        fun format(style: Int): String? {
            val id = cells.getOrNull(style)?.first ?: return null
            return customFormats[id] ?: BUILTIN_FORMATS[id]
        }

        fun bold(style: Int): Boolean = cells.getOrNull(style)?.second ?: false
    }

    private fun parser(input: InputStream): XmlPullParser = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        setInput(input, null)
    }

    internal fun parseWorkbook(input: InputStream): WorkbookInfo {
        val p = parser(input)
        val sheets = mutableListOf<Pair<String, String>>()
        var date1904 = false
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType != XmlPullParser.START_TAG) continue
            when (p.name) {
                "sheet" -> {
                    val name = p.getAttributeValue(null, "name").orEmpty()
                    val id = p.getAttributeValue(null, "r:id") ?: p.attributeEndingWith(":id")
                    // Arkusze ukryte przez autora zostają ukryte. LibreOffice pisze też
                    // state="visible" — to nie jest ukrycie.
                    val state = p.getAttributeValue(null, "state")
                    if (id != null && state != "hidden" && state != "veryHidden") sheets += name to id
                }
                "workbookPr" -> date1904 = p.getAttributeValue(null, "date1904").let { it == "1" || it == "true" }
            }
        }
        return WorkbookInfo(sheets, date1904)
    }

    private fun XmlPullParser.attributeEndingWith(suffix: String): String? =
        (0 until attributeCount).firstOrNull { getAttributeName(it).endsWith(suffix) }?.let(::getAttributeValue)

    internal fun parseRelationships(input: InputStream): Map<String, String> {
        val p = parser(input)
        val map = HashMap<String, String>()
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG && p.name == "Relationship") {
                map[p.getAttributeValue(null, "Id")] = p.getAttributeValue(null, "Target")
            }
        }
        return map
    }

    /** Teksty współdzielone; wpis z kilkoma przebiegami formatowania (`<r>`) sklejamy. */
    internal fun parseSharedStrings(input: InputStream): List<String> {
        val p = parser(input)
        val list = ArrayList<String>()
        val current = StringBuilder()
        var inText = false
        var inPhonetic = false
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "si" -> current.setLength(0)
                    "t" -> inText = !inPhonetic
                    "rPh" -> inPhonetic = true
                }
                XmlPullParser.TEXT -> if (inText) current.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "t" -> inText = false
                    "rPh" -> inPhonetic = false
                    "si" -> list += current.toString()
                }
            }
        }
        return list
    }

    internal fun parseStyles(input: InputStream): Styles {
        val p = parser(input)
        val custom = HashMap<Int, String>()
        val bold = ArrayList<Boolean>()
        val cells = ArrayList<Pair<Int, Boolean>>()
        var section = ""
        var fontBold = false
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "numFmt" -> p.getAttributeValue(null, "numFmtId")?.toIntOrNull()?.let { custom[it] = p.getAttributeValue(null, "formatCode").orEmpty() }
                    "fonts", "cellXfs", "cellStyleXfs", "dxfs" -> section = p.name
                    "font" -> if (section == "fonts") fontBold = false
                    "b" -> if (section == "fonts") fontBold = p.getAttributeValue(null, "val").let { it == null || it == "1" || it == "true" }
                    "xf" -> if (section == "cellXfs") {
                        val format = p.getAttributeValue(null, "numFmtId")?.toIntOrNull() ?: 0
                        val font = p.getAttributeValue(null, "fontId")?.toIntOrNull() ?: 0
                        cells += format to (bold.getOrNull(font) ?: false)
                    }
                }
                XmlPullParser.END_TAG -> when (p.name) {
                    "font" -> if (section == "fonts") bold += fontBold
                    "fonts", "cellXfs", "cellStyleXfs", "dxfs" -> section = ""
                }
            }
        }
        return Styles(cells, custom, bold)
    }

    internal fun parseSheet(
        input: InputStream,
        name: String,
        strings: List<String>,
        styles: Styles,
        format: ValueFormat,
        notes: MutableList<String>
    ): Sheet {
        val p = parser(input)
        val rows = ArrayList<Array<Cell?>>()
        val widths = HashMap<Int, Float>()
        val merges = ArrayList<Merge>()
        var defaultWidth = 8.43f
        var columns = 0
        var cells = 0L

        var rowCells = HashMap<Int, Cell>()
        var row = -1
        var nextRow = 0
        var column = -1
        var nextColumn = 0
        var type: String? = null
        var style = 0
        var value: StringBuilder? = null
        var inValue = false
        var inline = false

        fun flushRow() {
            if (row < 0) return
            if (row >= MAX_ROWS || cells > MAX_CELLS) { notes += "truncated"; return }
            while (rows.size < row) rows.add(arrayOf())
            val width = (rowCells.keys.maxOrNull() ?: -1) + 1
            columns = maxOf(columns, width)
            rows.add(Array(width) { rowCells[it] })
            cells += width
            rowCells = HashMap()
        }

        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "sheetFormatPr" -> p.getAttributeValue(null, "defaultColWidth")?.toFloatOrNull()?.let { defaultWidth = it }
                    "col" -> {
                        val min = p.getAttributeValue(null, "min")?.toIntOrNull() ?: continue
                        val max = (p.getAttributeValue(null, "max")?.toIntOrNull() ?: min).coerceAtMost(min + MAX_COLUMNS)
                        val hidden = p.getAttributeValue(null, "hidden").let { it == "1" || it == "true" }
                        val width = if (hidden) 0f else p.getAttributeValue(null, "width")?.toFloatOrNull() ?: continue
                        for (c in min..max) widths[c - 1] = width
                    }
                    "row" -> {
                        flushRow()
                        // Wiersze bez numeru idą po kolei.
                        row = p.getAttributeValue(null, "r")?.toIntOrNull()?.minus(1) ?: nextRow
                        nextRow = row + 1
                        nextColumn = 0
                    }
                    "c" -> {
                        val ref = p.getAttributeValue(null, "r")
                        column = if (ref != null) columnIndex(ref) else nextColumn
                        nextColumn = column + 1
                        type = p.getAttributeValue(null, "t")
                        style = p.getAttributeValue(null, "s")?.toIntOrNull() ?: 0
                        value = null
                        inline = false
                    }
                    "v" -> { inValue = true; value = StringBuilder() }
                    "is" -> { inline = true; value = StringBuilder() }
                    "t" -> if (inline) inValue = true
                    "mergeCell" -> p.getAttributeValue(null, "ref")?.let { ref ->
                        val parts = ref.split(':')
                        if (parts.size == 2) merges += Merge(rowIndex(parts[0]), columnIndex(parts[0]), rowIndex(parts[1]), columnIndex(parts[1]))
                    }
                }
                XmlPullParser.TEXT -> if (inValue) value?.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "v" -> inValue = false
                    "t" -> if (inline) inValue = false
                    "c" -> {
                        val raw = value?.toString()
                        if (raw != null && column in 0 until MAX_COLUMNS) {
                            cellOf(raw, type, style, strings, styles, format)?.let { rowCells[column] = it }
                        }
                    }
                    "row" -> { flushRow(); row = -1 }
                }
            }
        }
        flushRow()
        val widthArray = FloatArray(columns) { widths[it] ?: defaultWidth }
        return Sheet(name, rows, columns, widthArray, merges.filter { it.firstRow >= 0 && it.firstColumn >= 0 })
    }

    private fun cellOf(raw: String, type: String?, style: Int, strings: List<String>, styles: Styles, format: ValueFormat): Cell? {
        val bold = styles.bold(style)
        return when (type) {
            "s" -> strings.getOrNull(raw.trim().toIntOrNull() ?: -1)?.let { Cell(it, bold = bold) }
            "inlineStr", "str" -> Cell(raw, bold = bold)
            "b" -> Cell(if (raw.trim() == "1") "PRAWDA".takeIf { format.polish } ?: "TRUE" else "FAŁSZ".takeIf { format.polish } ?: "FALSE", bold = bold)
            "e" -> Cell(raw, bold = bold)
            else -> {
                val number = raw.trim().toDoubleOrNull() ?: return Cell(raw, bold = bold)
                Cell(format.format(number, styles.format(style)), numeric = true, bold = bold)
            }
        }
    }

    /** Wbudowane formaty Excela, których plik nie zapisuje (ECMA-376, 18.8.30). */
    private val BUILTIN_FORMATS = mapOf(
        0 to "General", 1 to "0", 2 to "0.00", 3 to "#,##0", 4 to "#,##0.00",
        9 to "0%", 10 to "0.00%", 11 to "0.00E+00", 12 to "# ?/?", 13 to "# ??/??",
        14 to "dd.mm.yyyy", 15 to "d-mmm-yy", 16 to "d-mmm", 17 to "mmm-yy",
        18 to "h:mm AM/PM", 19 to "h:mm:ss AM/PM", 20 to "hh:mm", 21 to "hh:mm:ss", 22 to "dd.mm.yyyy hh:mm",
        37 to "#,##0 ;(#,##0)", 38 to "#,##0 ;[Red](#,##0)", 39 to "#,##0.00;(#,##0.00)", 40 to "#,##0.00;[Red](#,##0.00)",
        45 to "mm:ss", 46 to "[h]:mm:ss", 47 to "mm:ss.0", 48 to "##0.0E+0", 49 to "@"
    )
}

/**
 * Liczba według formatu z pliku, z separatorami języka telefonu. Obsługuje
 * to, co spotyka się w arkuszach z biura: daty i czasy, procenty, stałą
 * liczbę miejsc po przecinku, separator tysięcy, waluty w cudzysłowie lub
 * nawiasach kwadratowych. Reszta jak „Ogólny”.
 */
internal class ValueFormat(private val locale: Locale, private val date1904: Boolean) {
    val polish = locale.language == "pl"
    private val symbols = DecimalFormatSymbols.getInstance(locale)

    fun format(value: Double, code: String?): String {
        if (code == null || code.equals("General", true) || code == "@") return general(value)
        // Format z sekcjami „dodatnie;ujemne;zero” — bierzemy pierwszą, minus dopisujemy sami.
        val section = code.split(';').first()
        val plain = section.replace(Regex("""\[[^\]]*]"""), "").replace(Regex(""""[^"]*""""), "").replace("\\", "")
        val literal = Regex(""""([^"]*)"""").findAll(section).joinToString("") { it.groupValues[1] } +
            Regex("""\[\$([^-\]]*)""").find(section)?.groupValues?.get(1).orEmpty()

        if (isDate(plain)) return date(value, plain) ?: general(value)
        if (plain.contains('%')) return number(value * 100, plain.replace("%", "")) + "%"
        if (plain.contains('E', ignoreCase = true)) return String.format(locale, "%.2E", value)
        if (!plain.contains('0') && !plain.contains('#')) return general(value)
        val text = number(value, plain)
        return if (literal.isBlank()) text else "$text ${literal.trim()}"
    }

    private fun isDate(code: String): Boolean {
        val lower = code.lowercase()
        return lower.any { it == 'y' || it == 'd' || it == 'h' || it == 's' } ||
            (lower.contains('m') && !lower.contains('0') && !lower.contains('#'))
    }

    private fun number(value: Double, code: String): String {
        val decimals = code.substringAfter('.', "").count { it == '0' || it == '#' }
        val grouping = code.contains(',') && code.substringBefore('.').contains(Regex("[#0],[#0]"))
        val pattern = (if (grouping) "#,##0" else "0") + if (decimals > 0) "." + "0".repeat(decimals) else ""
        return DecimalFormat(pattern, symbols).format(value)
    }

    /** Liczba w formacie ogólnym: do 11 cyfr znaczących, bez ogona 0.30000000000000004. */
    fun general(value: Double): String {
        if (value == Math.rint(value) && kotlin.math.abs(value) < 1e15) return value.toLong().toString()
        val rounded = BigDecimal(value).round(MathContext(11)).stripTrailingZeros()
        return rounded.toPlainString().replace('.', symbols.decimalSeparator)
    }

    /** Numer seryjny Excela → data. 1900: dzień 1 = 1.01.1900, z fałszywym 29.02.1900. */
    private fun date(value: Double, code: String): String? {
        if (value < 0 || value > 2_958_465) return null
        val base = if (date1904) LocalDateTime.of(1904, 1, 1, 0, 0) else LocalDateTime.of(1899, 12, 30, 0, 0)
        val days = kotlin.math.floor(value).toLong()
        val seconds = kotlin.math.round((value - days) * 86_400).toLong()
        val time = base.plusDays(days).plusSeconds(seconds)
        val lower = code.lowercase()
        val hasDate = lower.contains('y') || lower.contains('d')
        val hasTime = lower.contains('h') || lower.contains('s')
        val datePattern = if (polish) "dd.MM.yyyy" else "yyyy-MM-dd"
        val timePattern = if (lower.contains('s')) "HH:mm:ss" else "HH:mm"
        // Czas trwania „[h]:mm” liczy godziny ponad dobę.
        if (lower.startsWith("[h]")) {
            val total = kotlin.math.round(value * 86_400).toLong()
            return "%d:%02d:%02d".format(total / 3600, total % 3600 / 60, total % 60)
        }
        val pattern = when {
            hasDate && hasTime -> "$datePattern $timePattern"
            hasTime -> timePattern
            // „mmm-yy” i podobne: miesiąc i rok.
            !lower.contains('d') && lower.contains('y') -> if (polish) "MM.yyyy" else "yyyy-MM"
            else -> datePattern
        }
        return time.format(DateTimeFormatter.ofPattern(pattern, locale))
    }
}
