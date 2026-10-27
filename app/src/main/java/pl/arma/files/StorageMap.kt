package pl.arma.files

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import pl.arma.files.index.IndexCategory
import pl.arma.files.index.MapEntry
import pl.arma.files.ui.theme.InstrumentSans
import pl.arma.files.ui.theme.LocalArmaStyle
import pl.arma.files.ui.theme.PlexMono

/** Co jest pod kafelkiem: folder do przybliżenia, plik do podglądu albo zbiorczy „reszta / system”. */
private enum class TileKind { FOLDER, FILE, REST, SYSTEM }

private class MapTile(val kind: TileKind, val segment: String, val label: String, val bytes: Long, val category: IndexCategory)

/** Jeden poziom mapy: kafelki i ich prostokąty w układzie całego obszaru rysowania. */
private class Scene(val path: List<String>, val tiles: List<MapTile>, val cells: List<Cell>)

/** Najwyżej tyle kafelków na poziom; mniejsze trafiają do „Reszta” — i tak byłyby nieczytelne. */
private const val MAX_TILES = 60

/**
 * Mapa pamięci: nośnik jako prostokąty o polu proporcjonalnym do zajętego
 * miejsca, w kolorze kategorii, która w danym folderze zajmuje najwięcej.
 * Dotknięcie folderu przybliża go płynnie na cały obszar (jego zawartość
 * wyrasta z kafelka), Wstecz oddala; przytrzymanie otwiera folder jako
 * zwykły arkusz Głębi, a dotknięcie pliku — podgląd.
 *
 * Dane pochodzą z indeksu plików: bez niego mapa prosi o zbudowanie, a to,
 * czego indeks nie widzi (system, dane aplikacji), jest jednym szarym kafelkiem.
 */
@Composable
internal fun StorageMapScreen(
    storage: DeviceStorage,
    vm: FileManagerViewModel,
    indexing: Boolean,
    backEnabled: Boolean,
    onBuildIndex: () -> Unit,
    onOpenFolder: (Uri) -> Unit,
    onOpenFile: (FileItem) -> Unit,
    onClose: () -> Unit
) {
    val style = LocalArmaStyle.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val measurer = rememberTextMeasurer()
    val categoryColors = IndexCategory.entries.associateWith { indexCategoryColor(it) }
    val systemColor = MaterialTheme.colorScheme.outline
    val restColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val textDark = Color(0xFF0D0F12)
    val unindexedLabel = stringResource(R.string.map_unindexed)
    val restLabel = stringResource(R.string.map_rest)

    var path by remember(storage.id) { mutableStateOf(emptyList<String>()) }
    var current by remember(storage.id) { mutableStateOf<Scene?>(null) }
    var area by remember { mutableStateOf(Cell(0f, 0f, 0f, 0f)) }
    var loading by remember { mutableStateOf(true) }
    // Przejście między poziomami: zewnętrzny (rodzic) i wewnętrzny (podfolder) oraz kafelek, który je łączy.
    var outer by remember { mutableStateOf<Scene?>(null) }
    var inner by remember { mutableStateOf<Scene?>(null) }
    var focus by remember { mutableStateOf<Cell?>(null) }
    val zoom = remember { Animatable(0f) }
    val busy = outer != null

    fun dirUri(p: List<String>): Uri =
        Uri.parse(storage.rootUri.toString().trimEnd('/') + p.joinToString("") { "/$it" })

    suspend fun build(p: List<String>): Scene {
        val entries = vm.storageMap(dirUri(p))
        val shown = entries.take(MAX_TILES)
        val tiles = shown.map {
            MapTile(if (it.isDirectory) TileKind.FOLDER else TileKind.FILE, it.segment, Uri.decode(it.segment), it.bytes, it.category)
        }.toMutableList()
        val rest = entries.drop(MAX_TILES)
        if (rest.isNotEmpty()) tiles += MapTile(TileKind.REST, "", "$restLabel · ${rest.size}", rest.sumOf { it.bytes }, IndexCategory.OTHER)
        // Na nośniku: to, czego indeks nie widzi, jako jeden kafelek — bez niego mapa udawałaby pełną pamięć.
        if (p.isEmpty()) {
            val used = (storage.totalBytes - storage.freeBytes).coerceAtLeast(0L)
            val unindexed = used - entries.sumOf { it.bytes }
            if (entries.isNotEmpty() && unindexed > used / 50) tiles += MapTile(TileKind.SYSTEM, "", unindexedLabel, unindexed, IndexCategory.OTHER)
        }
        tiles.sortByDescending { it.bytes }
        val cells = Treemap.layout(tiles.map { it.bytes.toDouble() }, area)
        return Scene(p, tiles, cells)
    }

    // Pierwsze wczytanie i przeliczenie po zmianie rozmiaru obszaru (obrót).
    LaunchedEffect(storage.id, area, indexing) {
        if (area.width <= 0f || busy) return@LaunchedEffect
        loading = current == null
        current = build(path)
        loading = false
    }

    fun enter(tile: MapTile, cell: Cell) {
        val from = current ?: return
        scope.launch {
            val next = build(from.path + tile.segment)
            outer = from; inner = next; focus = cell
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            zoom.snapTo(0f)
            if (!style.calm) zoom.animateTo(1f, tween(420, easing = FastOutSlowInEasing))
            path = next.path; current = next
            outer = null; inner = null; focus = null
        }
    }

    fun up() {
        val from = current ?: return
        if (from.path.isEmpty()) { onClose(); return }
        scope.launch {
            val parent = build(from.path.dropLast(1))
            val index = parent.tiles.indexOfFirst { it.kind == TileKind.FOLDER && it.segment == from.path.last() }
            outer = parent; inner = from; focus = parent.cells.getOrNull(index) ?: Cell(area.left + area.width / 2, area.top + area.height / 2, 1f, 1f)
            zoom.snapTo(1f)
            if (!style.calm) zoom.animateTo(0f, tween(360, easing = FastOutSlowInEasing))
            path = parent.path; current = parent
            outer = null; inner = null; focus = null
        }
    }

    BackHandler(enabled = backEnabled) { if (!busy) up() }

    val shownPath = current?.path ?: path
    val title = shownPath.lastOrNull()?.let(Uri::decode) ?: stringResource(R.string.map_title)
    val total = current?.tiles?.sumOf { it.bytes } ?: 0L

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (!busy) up() }) {
                if (shownPath.isEmpty()) Icon(Icons.Default.Close, stringResource(R.string.viewer_close_app))
                else Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.map_up))
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { onOpenFolder(dirUri(shownPath)) }) {
                Icon(Icons.Default.FolderOpen, stringResource(R.string.map_open_folder), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, bottom = 8.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                Text(
                    (listOf(storage.label) + shownPath.map(Uri::decode)).joinToString(" / ").uppercase(),
                    style = TextStyle(fontFamily = PlexMono, fontSize = 11.sp, letterSpacing = .4.sp),
                    color = style.muted,
                    maxLines = 1
                )
            }
            Text(title, fontSize = 34.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (total > 0) "${formatSize(total)} · ${stringResource(R.string.map_hint)}" else stringResource(R.string.map_hint),
                style = MaterialTheme.typography.bodySmall,
                color = style.muted,
                maxLines = 2
            )
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)) {
            val density = LocalDensity.current
            val w = with(density) { maxWidth.toPx() }
            val h = with(density) { maxHeight.toPx() }
            if (area.width != w || area.height != h) area = Cell(0f, 0f, w, h)
            val scene = current
            when {
                loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                scene == null || scene.tiles.none { it.kind == TileKind.FOLDER || it.kind == TileKind.FILE } && shownPath.isEmpty() ->
                    NoIndex(indexing, onBuildIndex, Modifier.align(Alignment.Center))
                else -> Canvas(
                    Modifier
                        .fillMaxSize()
                        .semantics { contentDescription = "$title, ${formatSize(total)}" }
                        .pointerInput(scene, busy) {
                            if (busy) return@pointerInput
                            fun hit(at: Offset): Int = scene.cells.indexOfFirst { it.contains(at.x, at.y) }
                            detectTapGestures(
                                onTap = { at ->
                                    val i = hit(at)
                                    val tile = scene.tiles.getOrNull(i) ?: return@detectTapGestures
                                    when (tile.kind) {
                                        TileKind.FOLDER -> enter(tile, scene.cells[i])
                                        TileKind.FILE -> onOpenFile(fileItem(dirUri(scene.path), tile))
                                        else -> Unit
                                    }
                                },
                                onLongPress = { at ->
                                    val tile = scene.tiles.getOrNull(hit(at)) ?: return@detectTapGestures
                                    if (tile.kind == TileKind.FOLDER) {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onOpenFolder(dirUri(scene.path + tile.segment))
                                    }
                                }
                            )
                        }
                ) {
                    val full = area
                    val gap = 1.5.dp.toPx()
                    val radius = 8.dp.toPx()
                    val colors = TileColors(categoryColors, systemColor, restColor, textDark, style.text, style.ground)
                    val o = outer; val i = inner; val f = focus
                    if (o != null && i != null && f != null) {
                        val t = zoom.value
                        // Rodzic: okno przybliża się do kafelka, aż ten wypełni ekran; gaśnie pod koniec.
                        val window = lerpCell(full, f, t)
                        drawScene(o, window, full, (1f - t * t).coerceIn(0f, 1f), gap, radius, measurer, colors)
                        // Podfolder: wyrasta z kafelka do pełnego obszaru.
                        drawScene(i, full, lerpCell(f, full, t), t.coerceIn(0f, 1f), gap, radius, measurer, colors)
                    } else {
                        drawScene(scene, full, full, 1f, gap, radius, measurer, colors)
                    }
                }
            }
        }
        Legend(Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
    }
}

private class TileColors(
    val byCategory: Map<IndexCategory, Color>,
    val system: Color,
    val rest: Color,
    val text: Color,
    val lightText: Color,
    val ground: Color
)

private fun fileItem(dir: Uri, tile: MapTile): FileItem = FileItem(
    uri = Uri.parse(dir.toString().trimEnd('/') + "/" + tile.segment),
    name = tile.label,
    mimeType = guessMime(tile.label),
    size = tile.bytes,
    lastModified = 0L,
    isDirectory = false
)

private fun lerpCell(a: Cell, b: Cell, t: Float) = Cell(
    a.left + (b.left - a.left) * t,
    a.top + (b.top - a.top) * t,
    a.width + (b.width - a.width) * t,
    a.height + (b.height - a.height) * t
)

/** Rysuje poziom tak, że jego fragment [source] trafia w prostokąt [target] na ekranie. */
private fun DrawScope.drawScene(
    scene: Scene,
    source: Cell,
    target: Cell,
    alpha: Float,
    gap: Float,
    radius: Float,
    measurer: TextMeasurer,
    colors: TileColors
) {
    if (alpha <= 0.01f || source.width <= 0f || source.height <= 0f) return
    val sx = target.width / source.width
    val sy = target.height / source.height
    clipRect(0f, 0f, size.width, size.height) {
        scene.cells.forEachIndexed { index, cell ->
            val tile = scene.tiles[index]
            val left = target.left + (cell.left - source.left) * sx + gap
            val top = target.top + (cell.top - source.top) * sy + gap
            val w = cell.width * sx - 2 * gap
            val h = cell.height * sy - 2 * gap
            if (w <= 0.5f || h <= 0.5f || left > size.width || top > size.height || left + w < 0f || top + h < 0f) return@forEachIndexed
            val base = when (tile.kind) {
                TileKind.SYSTEM -> colors.system
                TileKind.REST -> colors.rest
                else -> colors.byCategory[tile.category] ?: colors.system
            }
            // Pliki o ton ciemniej niż foldery — przy tym samym kolorze kategorii widać, co jest folderem.
            val fill = if (tile.kind == TileKind.FILE) lerp(base, colors.ground, .28f) else base
            drawRoundRect(fill.copy(alpha = alpha), Offset(left, top), Size(w, h), CornerRadius(radius.coerceAtMost(minOf(w, h) / 3)))
            // Podpis tylko tam, gdzie się mieści: nazwa i rozmiar.
            if (w > 64.dp.toPx() && h > 38.dp.toPx() && alpha > .35f) {
                val pad = 8.dp.toPx()
                val nameLayout = measurer.measure(
                    tile.label,
                    TextStyle(fontFamily = InstrumentSans, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 15.sp),
                    overflow = TextOverflow.Ellipsis,
                    maxLines = if (h > 72.dp.toPx()) 2 else 1,
                    constraints = Constraints(maxWidth = (w - 2 * pad).toInt().coerceAtLeast(1))
                )
                val sizeLayout = measurer.measure(
                    formatSize(tile.bytes),
                    TextStyle(fontFamily = PlexMono, fontSize = 11.sp, fontWeight = FontWeight.Medium),
                    maxLines = 1,
                    constraints = Constraints(maxWidth = (w - 2 * pad).toInt().coerceAtLeast(1))
                )
                // „Reszta” i „System” są ciemne — na nich jasny napis.
                val ink = if (tile.kind == TileKind.REST || tile.kind == TileKind.SYSTEM) colors.lightText else colors.text
                drawText(nameLayout, ink.copy(alpha = alpha), Offset(left + pad, top + pad))
                if (h > nameLayout.size.height + sizeLayout.size.height + 2 * pad) {
                    drawText(sizeLayout, ink.copy(alpha = alpha * .8f), Offset(left + pad, top + h - pad - sizeLayout.size.height))
                }
            }
        }
    }
}

@Composable
private fun NoIndex(indexing: Boolean, onBuild: () -> Unit, modifier: Modifier) {
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(stringResource(R.string.map_no_index), style = MaterialTheme.typography.bodyMedium, color = LocalArmaStyle.current.muted)
        if (indexing) CircularProgressIndicator()
        else Button(onClick = onBuild) { Text(stringResource(R.string.index_build_now)) }
    }
}

@Composable
private fun Legend(modifier: Modifier) {
    val entries = listOf(
        IndexCategory.IMAGE to R.string.category_images,
        IndexCategory.VIDEO to R.string.category_videos,
        IndexCategory.AUDIO to R.string.category_audio,
        IndexCategory.DOCUMENT to R.string.category_documents,
        IndexCategory.ARCHIVE to R.string.category_archives,
        IndexCategory.OTHER to R.string.category_other
    )
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        entries.forEach { (category, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(indexCategoryColor(category)))
                Spacer(Modifier.width(5.dp))
                Text(stringResource(label), style = MaterialTheme.typography.labelSmall, color = LocalArmaStyle.current.muted, maxLines = 1)
            }
        }
    }
}
