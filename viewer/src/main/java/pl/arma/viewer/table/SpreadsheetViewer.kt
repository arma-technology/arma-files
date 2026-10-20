package pl.arma.viewer.table

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.arma.viewer.R
import java.io.File
import kotlin.math.roundToInt

/** Arkusz z pliku na dysku: .xlsx/.xlsm albo CSV/TSV, po rozszerzeniu. */
@Composable
fun SpreadsheetViewer(file: File, modifier: Modifier = Modifier, name: String = file.name) =
    SpreadsheetViewer(name = name, key = file.path to file.lastModified(), load = { Spreadsheets.read(file, name) }, modifier = modifier)

/**
 * Arkusze do przeglądania: zakładki arkuszy, nagłówki kolumn i numery
 * wierszy przyklejone do brzegów, przewijanie w obu osiach. Dotknięcie
 * komórki pokazuje jej pełną treść do skopiowania — długi tekst w wąskiej
 * kolumnie jest ucięty jak w Excelu.
 */
@Composable
fun SpreadsheetViewer(name: String, key: Any?, load: suspend () -> Workbook, modifier: Modifier = Modifier) {
    var workbook by remember(key) { mutableStateOf<Workbook?>(null) }
    var error by remember(key) { mutableStateOf<String?>(null) }
    var selectedSheet by remember(key) { mutableIntStateOf(0) }
    val readFailed = stringResource(R.string.viewer_text_read_failed)

    LaunchedEffect(key) {
        error = null
        workbook = runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { load() } }
            .onFailure { error = it.message ?: readFailed }
            .getOrNull()
    }

    val book = workbook
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when {
            error != null -> Text(error.orEmpty(), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
            book == null -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            book.sheets.isEmpty() -> Text(stringResource(R.string.viewer_empty_document), modifier = Modifier.padding(16.dp))
            else -> {
                if (book.sheets.size > 1) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        book.sheets.forEachIndexed { index, sheet ->
                            FilterChip(selected = index == selectedSheet, onClick = { selectedSheet = index }, label = { Text(sheet.name) })
                        }
                    }
                }
                if ("truncated" in book.notes) {
                    Text(stringResource(R.string.viewer_table_truncated), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val sheet = book.sheets[selectedSheet.coerceIn(book.sheets.indices)]
                // Nowy arkusz to nowa siatka: przewinięcie i zaznaczenie od początku.
                androidx.compose.runtime.key(sheet) {
                    SheetGrid(sheet, Modifier.fillMaxWidth().weight(1f))
                }
            }
        }
    }
}

@Composable
private fun SheetGrid(sheet: Sheet, modifier: Modifier) {
    val density = LocalDensity.current
    val widths: List<Dp> = remember(sheet) {
        List(sheet.columns) { column -> columnWidth(sheet.widths.getOrElse(column) { 8.43f }) }
    }
    val lefts: FloatArray = remember(sheet, density) {
        val array = FloatArray(widths.size + 1)
        for (i in widths.indices) array[i + 1] = array[i] + with(density) { widths[i].toPx() }
        array
    }
    val merges = remember(sheet) { MergeIndex(sheet.merges) }
    var viewport by remember { mutableIntStateOf(0) }
    var scrollX by remember { mutableFloatStateOf(0f) }
    var selected by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val maxScroll = (lefts.last() - viewport).coerceAtLeast(0f)
    val horizontal = rememberScrollableState { delta ->
        val before = scrollX
        scrollX = (scrollX - delta).coerceIn(0f, maxScroll)
        before - scrollX
    }
    val listState = rememberLazyListState()
    val rowNumberWidth = when {
        sheet.rowCount >= 10_000 -> 56.dp
        sheet.rowCount >= 1_000 -> 48.dp
        else -> 40.dp
    }

    Column(modifier) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().weight(1f)
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .scrollable(horizontal, Orientation.Horizontal)
            ) {
                // Nagłówek kolumn: A, B, C… przesuwa się razem z komórkami.
                Row(Modifier.height(HEADER_HEIGHT)) {
                    HeaderCell("", rowNumberWidth)
                    VisibleColumns(
                        lefts, widths, scrollX, Modifier.weight(1f).onSizeChanged { viewport = it.width }
                    ) { column, width ->
                        HeaderCell(columnName(column), width)
                    }
                }
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(sheet.rowCount, key = { it }) { row ->
                        Row(Modifier.height(ROW_HEIGHT)) {
                            HeaderCell((row + 1).toString(), rowNumberWidth)
                            VisibleColumns(lefts, widths, scrollX, Modifier.weight(1f), coveredFrom = { merges.coveredFrom(row, it) }) { column, width ->
                                val merge = merges.spanAt(row, column)
                                val span = if (merge != null) (merge.firstColumn..merge.lastColumn).fold(0.dp) { sum, c -> sum + widths.getOrElse(c) { 0.dp } } else width
                                // Treść scalenia tylko w jego pierwszym wierszu; niższe wiersze to puste pole tej samej szerokości.
                                val cell = if (merge != null && merge.firstRow != row) null else sheet.cell(row, column)
                                val isSelected = selected == (row to column)
                                DataCell(cell, span, isSelected) { selected = if (isSelected) null else row to column }
                            }
                        }
                    }
                }
            }
        }
        // Pełna treść zaznaczonej komórki, z możliwością skopiowania.
        selected?.let { (row, column) ->
            val text = sheet.cell(row, column)?.text.orEmpty()
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text("${columnName(column)}${row + 1}", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.width(10.dp))
                    SelectionContainer { Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 6, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
    }
}

/**
 * Tylko kolumny widoczne przy bieżącym przewinięciu — szeroki arkusz nie
 * składa setek komórek w każdym wierszu. Ukryte kolumny (szerokość 0)
 * pomijamy. [coveredFrom] mówi, od której kolumny zaczyna się scalenie
 * zasłaniające daną komórkę (-1 = żadne): gdy ta kolumna jest na ekranie,
 * jej szeroka komórka już zajęła miejsce; gdy jest za lewą krawędzią,
 * zostawiamy puste miejsce, żeby kolejne kolumny stały w swoich pozycjach.
 */
@Composable
private fun VisibleColumns(
    lefts: FloatArray,
    widths: List<Dp>,
    scrollX: Float,
    modifier: Modifier,
    coveredFrom: (Int) -> Int = { -1 },
    content: @Composable (column: Int, width: Dp) -> Unit
) {
    var viewport by remember { mutableIntStateOf(0) }
    Box(modifier.fillMaxHeight().clipToBounds().onSizeChanged { viewport = it.width }) {
        if (widths.isEmpty()) return@Box
        val first = (lefts.binarySearchFloor(scrollX)).coerceIn(0, widths.lastIndex)
        val last = (lefts.binarySearchFloor(scrollX + viewport.coerceAtLeast(1))).coerceIn(first, widths.lastIndex)
        Row(Modifier.offset { IntOffset((lefts[first] - scrollX).roundToInt(), 0) }) {
            for (column in first..last) {
                if (widths[column] == 0.dp) continue
                val start = coveredFrom(column)
                if (start >= 0) {
                    if (start < first) Spacer(Modifier.width(widths[column]))
                    continue
                }
                content(column, widths[column])
            }
        }
    }
}

/** Największy indeks i, dla którego this[i] <= value. */
private fun FloatArray.binarySearchFloor(value: Float): Int {
    var low = 0
    var high = size - 2
    while (low < high) {
        val mid = (low + high + 1) / 2
        if (this[mid] <= value) low = mid else high = mid - 1
    }
    return low
}

@Composable
private fun HeaderCell(text: String, width: Dp) {
    Box(
        Modifier
            .width(width)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
        contentAlignment = Alignment.Center
    ) {
        Text(text, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
private fun DataCell(cell: Cell?, width: Dp, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .width(width)
            .fillMaxHeight()
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
            .border(if (selected) 1.5.dp else 0.5.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp),
        contentAlignment = if (cell?.numeric == true) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        if (cell != null) {
            Text(
                cell.text.replace('\n', ' '),
                fontSize = 13.sp,
                fontWeight = if (cell.bold) FontWeight.SemiBold else FontWeight.Normal,
                textAlign = if (cell.numeric) TextAlign.End else TextAlign.Start,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Szybkie pytania o scalenia. Każdy wiersz scalenia ma jedną szeroką
 * komórkę w pierwszej kolumnie, pozostałe kolumny scalenia są zasłonięte.
 */
private class MergeIndex(merges: List<Merge>) {
    private val byRow = HashMap<Int, MutableList<Merge>>().apply {
        for (m in merges) for (r in m.firstRow..minOf(m.lastRow, m.firstRow + 10_000)) getOrPut(r) { mutableListOf() } += m
    }

    fun spanAt(row: Int, column: Int): Merge? = byRow[row]?.firstOrNull { it.firstColumn == column }

    fun coveredFrom(row: Int, column: Int): Int =
        byRow[row]?.firstOrNull { column > it.firstColumn && column <= it.lastColumn }?.firstColumn ?: -1
}

/** Szerokość kolumny Excela (w znakach) na ekranie telefonu. */
private fun columnWidth(characters: Float): Dp =
    if (characters <= 0f) 0.dp else (characters * 7.2f).coerceIn(28f, 480f).dp

private val ROW_HEIGHT = 30.dp
private val HEADER_HEIGHT = 26.dp

/** Wybór czytnika po rozszerzeniu. */
object Spreadsheets {
    val EXTENSIONS = setOf("xlsx", "xlsm", "csv", "tsv")

    fun canOpen(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in EXTENSIONS

    fun read(file: File, name: String = file.name): Workbook =
        when (name.substringAfterLast('.', "").lowercase()) {
            "csv", "tsv" -> CsvReader.readFile(file, name)
            else -> XlsxReader.readFile(file)
        }
}
