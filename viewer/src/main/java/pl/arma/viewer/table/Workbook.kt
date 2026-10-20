package pl.arma.viewer.table

/**
 * Arkusze do obejrzenia — same wartości już sformatowane do wyświetlenia,
 * bez formuł i bez możliwości edycji.
 */
class Workbook(val sheets: List<Sheet>, val notes: List<String> = emptyList())

/**
 * Jeden arkusz. [rows] są rzadkie: brakujący wiersz albo komórka to pusta
 * komórka. [widths] to szerokości kolumn w znakach (jak w Excelu).
 */
class Sheet(
    val name: String,
    val rows: List<Array<Cell?>>,
    val columns: Int,
    val widths: FloatArray,
    val merges: List<Merge> = emptyList()
) {
    val rowCount: Int get() = rows.size

    fun cell(row: Int, column: Int): Cell? = rows.getOrNull(row)?.getOrNull(column)
}

/** Komórka: tekst do pokazania, liczby do prawej, pogrubienie z pliku. */
class Cell(val text: String, val numeric: Boolean = false, val bold: Boolean = false)

/** Scalony obszar, indeksy od zera, włącznie. */
data class Merge(val firstRow: Int, val firstColumn: Int, val lastRow: Int, val lastColumn: Int) {
    fun contains(row: Int, column: Int) = row in firstRow..lastRow && column in firstColumn..lastColumn
}

/** Nazwy kolumn jak w arkuszach: A…Z, AA…ZZ, AAA… */
fun columnName(index: Int): String {
    val name = StringBuilder()
    var n = index + 1
    while (n > 0) {
        val rest = (n - 1) % 26
        name.insert(0, 'A' + rest)
        n = (n - 1) / 26
    }
    return name.toString()
}

/** Odwrotność [columnName] dla adresów komórek („BC12” → 54). */
internal fun columnIndex(reference: String): Int {
    var index = 0
    for (ch in reference) {
        if (ch !in 'A'..'Z') break
        index = index * 26 + (ch - 'A' + 1)
    }
    return index - 1
}

/** Wiersz z adresu komórki („BC12” → 11). */
internal fun rowIndex(reference: String): Int =
    reference.dropWhile { it in 'A'..'Z' }.toIntOrNull()?.minus(1) ?: -1

/** Limity: arkusz większy niż to pokazujemy przycięty, z uwagą. */
internal const val MAX_ROWS = 100_000
internal const val MAX_COLUMNS = 1_000
internal const val MAX_CELLS = 2_000_000
