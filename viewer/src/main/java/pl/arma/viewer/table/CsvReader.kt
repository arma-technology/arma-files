package pl.arma.viewer.table

import pl.arma.viewer.text.TextReader
import java.io.File

/**
 * CSV i TSV jako tabela. Kodowanie jak w podglądzie tekstu (BOM, UTF-8,
 * potem polskie kodowania), separator zgadywany z pierwszych wierszy —
 * polski Excel zapisuje „;”, bo przecinek jest separatorem dziesiętnym.
 */
object CsvReader {

    /**
     * Najwyżej [MAX_CSV_BYTES] z początku pliku — eksport z bazy potrafi mieć
     * setki megabajtów, a cały tekst i tak trafia do pamięci jako komórki.
     */
    fun readFile(file: File, name: String = file.name): Workbook {
        val bytes = file.inputStream().use { input ->
            val buffer = ByteArray(minOf(file.length(), MAX_CSV_BYTES.toLong()).toInt())
            var read = 0
            while (read < buffer.size) {
                val n = input.read(buffer, read, buffer.size - read)
                if (n < 0) break
                read += n
            }
            if (read < buffer.size) buffer.copyOf(read) else buffer
        }
        val book = read(cutAtLastLine(bytes, file.length() > bytes.size), name)
        return if (file.length() > bytes.size) Workbook(book.sheets, (book.notes + "truncated").distinct()) else book
    }

    /** Ucięty plik kończymy na ostatniej pełnej linii — bez połówki wiersza i znaku UTF-8. */
    private fun cutAtLastLine(bytes: ByteArray, truncated: Boolean): ByteArray {
        if (!truncated) return bytes
        val end = bytes.indexOfLast { it == '\n'.code.toByte() }
        return if (end > 0) bytes.copyOf(end + 1) else bytes
    }

    fun read(bytes: ByteArray, name: String): Workbook {
        val (text, _) = TextReader.decode(bytes)
        val delimiter = if (name.endsWith(".tsv", ignoreCase = true)) '\t' else guessDelimiter(text)
        val notes = mutableListOf<String>()
        val rows = parse(text, delimiter, notes)
        val columns = rows.maxOfOrNull { it.size } ?: 0
        val widths = FloatArray(columns) { column ->
            // Szerokość z treści pierwszych wierszy, w granicach rozsądku.
            val longest = rows.asSequence().take(300).maxOfOrNull { it.getOrNull(column)?.text?.length ?: 0 } ?: 0
            longest.coerceIn(4, 40).toFloat() + 1f
        }
        return Workbook(listOf(Sheet(name.substringBeforeLast('.'), rows, columns, widths)), notes)
    }

    internal fun guessDelimiter(text: String): Char {
        val sample = text.lineSequence().filter { it.isNotBlank() }.take(20).toList()
        if (sample.isEmpty()) return ','
        // Wygrywa znak, który dzieli wiersze na tyle samo pól (i więcej niż jedno).
        return listOf(';', ',', '\t', '|').maxBy { candidate ->
            val counts = sample.map { line -> countOutsideQuotes(line, candidate) }
            val common = counts.groupingBy { it }.eachCount().maxByOrNull { it.value }
            if (common == null || common.key == 0) 0 else common.value * 100 + common.key
        }
    }

    private fun countOutsideQuotes(line: String, delimiter: Char): Int {
        var quoted = false
        var count = 0
        for (ch in line) {
            if (ch == '"') quoted = !quoted
            else if (ch == delimiter && !quoted) count++
        }
        return count
    }

    /** RFC 4180: pola w cudzysłowach mogą zawierać separator, nową linię i "" jako cudzysłów. */
    internal fun parse(text: String, delimiter: Char, notes: MutableList<String> = mutableListOf()): List<Array<Cell?>> {
        val rows = ArrayList<Array<Cell?>>()
        val fields = ArrayList<Cell?>()
        val field = StringBuilder()
        var quoted = false
        var cells = 0L
        var i = 0
        fun endField() {
            val value = field.toString()
            fields += if (value.isEmpty()) null else Cell(value, numeric = looksNumeric(value))
            field.setLength(0)
        }
        fun endRow() {
            endField()
            rows += fields.take(MAX_COLUMNS).toTypedArray()
            cells += fields.size
            fields.clear()
        }
        while (i < text.length) {
            val ch = text[i]
            when {
                quoted && ch == '"' && text.getOrNull(i + 1) == '"' -> { field.append('"'); i++ }
                ch == '"' && (quoted || field.isEmpty()) -> quoted = !quoted
                !quoted && ch == delimiter -> endField()
                !quoted && (ch == '\n' || ch == '\r') -> {
                    if (ch == '\r' && text.getOrNull(i + 1) == '\n') i++
                    i++
                    endRow()
                    if (rows.size >= MAX_ROWS || cells >= MAX_CELLS) {
                        notes += "truncated"
                        return rows
                    }
                    continue
                }
                else -> field.append(ch)
            }
            i++
        }
        if (field.isNotEmpty() || fields.isNotEmpty()) endRow()
        return rows
    }

    /** Liczba w zapisie polskim albo angielskim: „-1 234,50”, „1,234.5”, „12%”. */
    internal fun looksNumeric(value: String): Boolean =
        value.length < 30 && NUMBER.matches(value.trim())

    private const val MAX_CSV_BYTES = 32 * 1024 * 1024

    private val NUMBER = Regex("""^[-+]?(\d{1,3}([  .,]\d{3})*|\d+)([.,]\d+)?\s?%?$""")
}
