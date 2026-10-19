package pl.arma.viewer.sheet

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import pl.arma.viewer.R
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pełnoekranowa przeglądarka: treść pod paskami systemu, narzędzia nałożone
 * na nią na półprzezroczystym tle. Pojedyncze tapnięcie chowa narzędzia.
 */
@Composable
fun SheetViewerDialog(
    state: SheetViewerState,
    onDismiss: () -> Unit,
    title: String? = null,
    actions: List<ViewerAction> = emptyList(),
    onSave: (suspend (File) -> Unit)? = null
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        HiddenSystemBars()
        SheetViewer(
            state = state,
            modifier = Modifier.fillMaxSize(),
            immersive = true,
            onClose = onDismiss,
            title = title,
            actions = actions,
            onSave = onSave
        )
    }
}

/**
 * Przeglądarka arkuszy: PDF albo rysunek CAD.
 *
 * @param immersive narzędzia na treści, na półprzezroczystym tle; bez tego
 *   pasek narzędzi stoi nad treścią, a miniatury pod nią
 * @param onClose przycisk zamknięcia w trybie [immersive]
 * @param actions akcje gospodarza w menu ⋮
 * @param onSave zapisuje plik z komentarzami tam, skąd pochodzi oryginał;
 *   bez tego komentarze można tylko oglądać
 */
@Composable
fun SheetViewer(
    state: SheetViewerState,
    modifier: Modifier = Modifier,
    immersive: Boolean = false,
    onClose: (() -> Unit)? = null,
    title: String? = null,
    actions: List<ViewerAction> = emptyList(),
    onSave: (suspend (File) -> Unit)? = null
) {
    val document = state.document
    when {
        state.loading -> Box(modifier, contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator()
                Text(stringResource(R.string.viewer_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        document == null -> Box(modifier, contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(Icons.Default.InsertDriveFile, null, Modifier.size(58.dp), tint = MaterialTheme.colorScheme.error)
                Text(stringResource(R.string.viewer_open_failed_title), fontWeight = FontWeight.SemiBold)
                Text(state.error.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (onClose != null) TextButton(onClick = onClose) { Text(stringResource(R.string.viewer_close)) }
            }
        }
        else -> DocumentView(state, document, modifier, immersive, onClose, title, actions, onSave)
    }
}

/** Wspólne obliczenia przejścia ekran ↔ strona dla bieżącego widoku. */
internal class Projection(val layout: SheetLayout, val sizes: List<SheetSize>, val view: SheetView) {
    fun unitScale(page: Int) = layout.unitScale(page, sizes[page])

    fun toScreen(page: Int, x: Float, y: Float): Offset {
        val s = unitScale(page)
        return Offset(
            (layout.margin + x * s) * view.zoom - view.x,
            (layout.tops[page] + y * s) * view.zoom - view.y
        )
    }

    /** Strona i punkt strony pod punktem ekranu. */
    fun toPage(sx: Float, sy: Float): Triple<Int, Float, Float> {
        val docX = (sx + view.x) / view.zoom
        val docY = (sy + view.y) / view.zoom
        val page = layout.pageAt(docY)
        val s = unitScale(page)
        return Triple(page, (docX - layout.margin) / s, (docY - layout.tops[page]) / s)
    }

    /** Ile jednostek strony przypada na piksel ekranu. */
    fun unitsPerPixel(page: Int): Float = 1f / (unitScale(page) * view.zoom)
}

@Composable
private fun DocumentView(
    state: SheetViewerState,
    document: SheetDocument,
    modifier: Modifier,
    immersive: Boolean,
    onClose: (() -> Unit)?,
    title: String?,
    actions: List<ViewerAction>,
    onSave: (suspend (File) -> Unit)?
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val context = LocalContext.current
    val pageCount = document.pageCount
    val revision by document.revision.collectAsState()
    val paper by document.paperColor.collectAsState()
    // Rysunek CAD nie ma kartki: tło ma kolor arkusza, rysunek zajmuje cały
    // ekran bez marginesów, a narzędzia leżą na nim.
    val fillView = document.modelUnits
    val backdrop = if (fillView) Color(paper) else MaterialTheme.colorScheme.surfaceContainerHigh
    // Uwagi dokumentu (np. pominięte bryły 3D) pokazujemy raz, po otwarciu.
    LaunchedEffect(document) {
        document.notes.firstOrNull()?.let { state.message = it }
    }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var toolsVisible by remember { mutableStateOf(true) }
    var jumpDialog by remember { mutableStateOf(false) }
    // Na niskim ekranie (telefon w poziomie) miniatury zabierałyby prawie
    // połowę wysokości; strony przełącza wtedy pasek narzędzi.
    val showThumbnails = pageCount > 1 && LocalConfiguration.current.screenHeightDp >= 500

    // Przez State, nie zwykłą wartość: obsługa gestu i stany pochodne powstają
    // raz i bez tego widziałyby układ sprzed obrotu.
    val layout by rememberUpdatedState(
        // Wysokość też w kluczu: po schowaniu klawiatury nowy układ przenosi
        // środek, więc znalezione trafienie zostaje pośrodku ekranu.
        remember(document, viewSize, immersive, showThumbnails) {
            if (viewSize.width == 0) {
                null
            } else with(density) {
                SheetLayout(
                    document.pageSizes,
                    viewSize.width.toFloat(),
                    margin = if (fillView) 0f else 8.dp.toPx(),
                    gap = 12.dp.toPx(),
                    // Narzędzia leżą na treści; pierwsza strona startuje pod nimi,
                    // a schowanie narzędzi odsłania ją bez przesuwania widoku.
                    topPadding = if (immersive && !fillView) 64.dp.toPx() else 0f,
                    bottomPadding = if (immersive && showThumbnails && !fillView) 100.dp.toPx() else 0f
                )
            }
        }
    )
    var animation by remember { mutableStateOf<Job?>(null) }

    fun projection(): Projection? = layout?.let { Projection(it, document.pageSizes, state.view) }

    fun settled(next: SheetView): SheetView =
        layout?.let { next.clamped(it, viewSize.height.toFloat(), MAX_ZOOM) } ?: next

    // Po obrocie na środku ekranu zostaje to samo miejsce dokumentu. Lewy
    // górny róg byłby złą kotwicą: w poziomie widać zupełnie inny wycinek.
    LaunchedEffect(layout) {
        val current = layout ?: return@LaunchedEffect
        val previous = state.lastLayout
        if (previous != null && previous !== current) {
            // Cały rysunek przed obrotem zostaje całym rysunkiem po nim.
            val whole = fillView && state.view.zoom <= previous.minZoom(state.lastHeight) * 1.01f
            state.view = if (whole) SheetView(current.minZoom(viewSize.height.toFloat()))
            else state.view.relaidOut(previous, state.lastHeight, current, viewSize.height.toFloat())
        } else if (previous == null && fillView) {
            // Rysunek otwiera się w całości, jak „pokaż wszystko” w programach CAD.
            state.view = SheetView(current.minZoom(viewSize.height.toFloat()))
        }
        state.lastLayout = current
        state.lastHeight = viewSize.height.toFloat()
        state.view = settled(state.view)
    }

    fun animateTo(target: () -> SheetView) {
        animation?.cancel()
        animation = scope.launch {
            val start = state.view
            val end = settled(target())
            animate(0f, 1f, animationSpec = tween(260)) { t, _ ->
                state.view = settled(
                    SheetView(
                        start.zoom + (end.zoom - start.zoom) * t,
                        start.x + (end.x - start.x) * t,
                        start.y + (end.y - start.y) * t
                    )
                )
            }
        }
    }

    fun minZoom(): Float = layout?.minZoom(viewSize.height.toFloat()) ?: 1f

    fun zoomBy(factor: Float, focus: Offset = Offset(viewSize.width / 2f, viewSize.height / 2f)) =
        animateTo { state.view.zoomedAround(factor, focus.x, focus.y, MAX_ZOOM, minZoom()) }

    fun showPage(index: Int) {
        val current = layout ?: return
        val page = index.coerceIn(0, current.pageCount - 1)
        animateTo { state.view.copy(y = (current.tops[page] - current.margin - current.topPadding) * state.view.zoom) }
    }

    /**
     * Dopasowanie do szerokości, a gdy już jest — do całej strony, jeśli
     * w tej szerokości nie mieści się na wysokość (pion na telefonie w poziomie).
     */
    fun fit() {
        val current = layout ?: return
        val page = current.pageAt((state.view.y + viewSize.height / 3f) / state.view.zoom)
        val height = viewSize.height.toFloat()
        val whole = (height / (current.heights[page] + 2 * current.margin)).coerceAtMost(1f)
        if (whole < 1f && (fillView || abs(state.view.zoom - 1f) < 0.01f)) {
            val center = current.tops[page] + current.heights[page] / 2f
            animateTo { SheetView(whole, 0f, center * whole - height / 2f) }
        } else {
            animateTo { SheetView(1f, 0f, current.tops[page] - current.margin - current.topPadding) }
        }
    }

    /** Pokazuje obszar strony na środku; [targetPixels] to jego docelowa wysokość na ekranie. */
    fun showArea(page: Int, rect: android.graphics.RectF, targetPixels: Float, onlyCloser: Boolean = true) {
        val current = layout ?: return
        val s = current.unitScale(page, document.pageSizes[page])
        val px = current.margin + rect.centerX() * s
        val py = current.tops[page] + rect.centerY() * s
        val height = (rect.height() * s).coerceAtLeast(1f)
        val wanted = (targetPixels / height).coerceIn(1f, MAX_ZOOM)
        val zoom = if (onlyCloser) maxOf(state.view.zoom, wanted) else wanted
        animateTo { SheetView().centeredOn(px, py, zoom, viewSize.width.toFloat(), viewSize.height.toFloat()) }
    }

    fun showMatch(index: Int) {
        val match = state.matches.getOrNull(index) ?: return
        state.currentMatch = index
        // Przybliżamy tak, żeby znaleziony tekst dało się przeczytać — na
        // rysunku z CAD przy całym arkuszu ma ułamek milimetra — ale nie
        // oddalamy, jeśli ktoś już patrzy z bliska.
        showArea(match.page, match.bounds, with(density) { SEARCH_TEXT_HEIGHT.toPx() })
    }

    fun showComment(comment: SheetComment) {
        state.selectedComment = comment.id
        showArea(comment.page, comment.rect, viewSize.height / 4f, onlyCloser = false)
    }

    // Szukamy po krótkiej przerwie w pisaniu; każda nowa litera przerywa
    // poprzednie przeszukiwanie.
    LaunchedEffect(document, state.query) {
        val query = state.query.trim()
        // Przeglądarka po obrocie powstaje w innym kontenerze; wyniki i bieżące
        // trafienie mają przetrwać, a nie wracać do pierwszego.
        if (query == state.searchedQuery && !state.searching) return@LaunchedEffect
        state.searchedQuery = null
        state.matches = emptyList()
        state.currentMatch = -1
        state.searchedPages = 0
        if (query.isEmpty() || !document.canSearch) {
            state.searching = false
            return@LaunchedEffect
        }
        delay(300)
        state.searching = true
        val found = runCatching { document.search(query) { state.searchedPages = it } }.getOrDefault(emptyList())
        state.matches = found
        state.searchedQuery = query
        state.searching = false
        if (found.isNotEmpty()) showMatch(0)
    }

    fun stepMatch(delta: Int) {
        val count = state.matches.size
        if (count == 0) return
        showMatch(((state.currentMatch + delta) % count + count) % count)
    }

    // Trafienia pogrupowane po stronach — płótno rysuje tylko widoczne.
    val matchesByPage by remember {
        derivedStateOf { state.matches.withIndex().groupBy { it.value.page } }
    }

    // Strona liczona od jednej trzeciej wysokości ekranu: tam patrzy się
    // najpierw, a granica stron nie przełącza numeru przy każdym drgnięciu.
    val currentPage by remember {
        derivedStateOf {
            val current = layout ?: return@derivedStateOf 1
            current.pageAt((state.view.y + viewSize.height / 3f) / state.view.zoom) + 1
        }
    }
    // Ładujemy strony widoczne i po jednej z każdej strony; reszta zwalnia pamięć.
    val loadedPages by remember {
        derivedStateOf {
            val current = layout ?: return@derivedStateOf IntRange.EMPTY
            val view = state.view
            val first = current.pageAt(view.y / view.zoom)
            val last = current.pageAt((view.y + viewSize.height) / view.zoom)
            (first - 1).coerceAtLeast(0)..(last + 1).coerceAtMost(current.pageCount - 1)
        }
    }
    // Stopniowana szerokość: gest zmienia powiększenie płynnie, a cała strona
    // renderuje się od nowa dopiero po przekroczeniu progu.
    val baseWidth by remember { derivedStateOf { RenderBudget.widthForZoom(state.view.zoom) } }
    // Narzędzia czytają tylko to, co pokazują — przesuwanie nie przebudowuje ekranu.
    val zoomPercent by remember { derivedStateOf { (state.view.zoom * 100).roundToInt() } }

    // ---- celownik pomiaru i komentarza ----------------------------------

    /** Strona i punkt pod środkiem ekranu, przyciągnięty do linii. */
    val cursor by remember {
        derivedStateOf {
            if (state.placement == Placement.NONE) return@derivedStateOf null
            val p = projection() ?: return@derivedStateOf null
            val (page, ux, uy) = p.toPage(viewSize.width / 2f, viewSize.height / 2f)
            val geometry = state.geometries[page]
            val first = state.firstPoint?.takeIf { it.page == page }?.snap
            val radius = with(density) { SNAP_RADIUS.toPx() } * p.unitsPerPixel(page)
            val snap = if (geometry != null && state.snapping && state.placement == Placement.MEASURE) {
                geometry.snap(ux, uy, radius, first, state.axisLock && first != null)
            } else if (first != null && state.axisLock && state.placement == Placement.MEASURE) {
                SnapGeometry.EMPTY.snap(ux, uy, radius, first, axisLock = true)
            } else {
                SnapResult(ux, uy, SnapKind.NONE)
            }
            PagePoint(page, snap)
        }
    }

    // Linie strony pod celownikiem wczytujemy przy pierwszym pomiarze na niej.
    LaunchedEffect(document, state.placement) {
        if (state.placement != Placement.MEASURE) return@LaunchedEffect
        snapshotFlow { cursor?.page }.collectLatest { page ->
            if (page == null || state.geometries.containsKey(page)) return@collectLatest
            state.loadingGeometry[page] = true
            val geometry = runCatching { document.snapGeometry(page) }.getOrDefault(SnapGeometry.EMPTY)
            state.geometries[page] = geometry
            state.loadingGeometry.remove(page)
        }
    }
    // Skalę strony próbujemy odczytać z pliku przy pierwszym pomiarze.
    LaunchedEffect(document, state.placement) {
        if (state.placement != Placement.MEASURE) return@LaunchedEffect
        snapshotFlow { cursor?.page }.distinctUntilChanged().collectLatest { page ->
            if (page == null || state.detectedScales.containsKey(page)) return@collectLatest
            val found = runCatching { document.detectScale(page) }.getOrNull()
            state.detectedScales[page] = found
            if (found != null && state.scales[page] == null) {
                state.message = context.getString(R.string.viewer_scale_detected_message, found.label, context.getString(found.source.label))
            } else if (found == null && state.scaleFor(page) == null) {
                state.scaleDialog = true
            }
        }
    }

    fun placePoint() {
        val point = cursor ?: return
        val first = state.firstPoint
        if (first == null) {
            state.firstPoint = point
            return
        }
        if (first.page != point.page) {
            state.message = context.getString(R.string.viewer_points_same_page)
            return
        }
        state.measurements += Measurement(point.page, first.snap.x, first.snap.y, point.snap.x, point.snap.y)
        state.firstPoint = null
    }

    fun centerOnScreenPoint(position: Offset) {
        val view = state.view
        animateTo {
            view.copy(x = view.x + position.x - viewSize.width / 2f, y = view.y + position.y - viewSize.height / 2f)
        }
    }

    val comments by document.comments.collectAsState()
    val layers by document.layers.collectAsState()
    val dirty by document.dirty.collectAsState()

    val save: (() -> Unit)? = if (onSave != null && document.canAnnotate) {
        {
            scope.launch {
                val result = runCatching {
                    val temp = File.createTempFile("save", ".pdf", context.cacheDir)
                    try {
                        document.saveTo(temp)
                        onSave(temp)
                    } finally {
                        temp.delete()
                    }
                }
                state.message = if (result.isSuccess) context.getString(R.string.viewer_saved) else context.getString(R.string.viewer_save_failed, result.exceptionOrNull()?.message.orEmpty())
            }
        }
    } else null

    val tools: @Composable () -> Unit = {
        SheetTools(
            state = state,
            document = document,
            currentPage = currentPage,
            pageCount = pageCount,
            zoomPercent = zoomPercent,
            minZoomPercent = (minZoom() * 100).roundToInt(),
            commentCount = comments.size,
            hasLayers = layers.isNotEmpty(),
            dirty = dirty && save != null,
            onPrevious = { showPage(currentPage - 2) },
            onNext = { showPage(currentPage) },
            onJump = { jumpDialog = true },
            onZoomOut = { zoomBy(1f / ZOOM_STEP) },
            onZoomIn = { zoomBy(ZOOM_STEP) },
            onFit = ::fit,
            onSave = save,
            onClose = onClose.takeIf { immersive },
            title = title,
            actions = actions
        )
    }
    val searchBar: @Composable () -> Unit = {
        SheetSearchBar(
            state = state,
            document = document,
            pageCount = pageCount,
            onPrevious = { stepMatch(-1) },
            onNext = { stepMatch(1) },
            onClose = {
                state.searchOpen = false
                state.query = ""
            }
        )
    }
    val toolbar: @Composable () -> Unit = { if (state.searchOpen) searchBar() else tools() }
    val thumbnails: @Composable () -> Unit = {
        SheetThumbnailStrip(document, revision, currentPage, onPick = ::showPage)
    }

    val canvas: @Composable (Modifier) -> Unit = { canvasModifier ->
        Box(canvasModifier, contentAlignment = Alignment.Center) {
            for (page in loadedPages) {
                key(page) {
                    SheetPageLoader(
                        document = document,
                        pageIndex = page,
                        baseWidth = baseWidth,
                        revision = revision,
                        bases = state.bases,
                        details = state.details,
                        region = {
                            val current = layout
                            val base = state.bases[page]
                            if (current == null || base == null) {
                                null
                            } else {
                                val view = state.view
                                RenderBudget.detailRegion(
                                    pageLeft = current.margin * view.zoom - view.x,
                                    pageTop = current.tops[page] * view.zoom - view.y,
                                    pageWidth = (current.pageWidth * view.zoom).roundToInt(),
                                    pageHeight = (current.heights[page] * view.zoom).roundToInt(),
                                    viewWidth = viewSize.width,
                                    viewHeight = viewSize.height,
                                    baseWidth = base.width
                                )
                            }
                        }
                    )
                }
            }
            val decay = rememberSplineBasedDecay<Float>()
            val doubleTapSlop = with(density) { 48.dp.toPx() }
            val overlay = rememberMeasurePaints()
            Canvas(
                Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .onSizeChanged { viewSize = it }
                    .pointerInput(state) {
                        var lastTapTime = 0L
                        var lastTapPosition = Offset.Zero
                        var singleTap: Job? = null
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            animation?.cancel()
                            val tracker = VelocityTracker()
                            tracker.addPosition(down.uptimeMillis, down.position)
                            var travel = Offset.Zero
                            var moved = false
                            var pinched = false
                            do {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.size >= 2) {
                                    pinched = true
                                    moved = true
                                    val zoom = event.calculateZoom()
                                    val pan = event.calculatePan()
                                    val centroid = event.calculateCentroid()
                                    // W trybie pomiaru powiększamy wokół celownika —
                                    // inaczej punkt pod nim uciekałby przy każdym geście.
                                    val focus = if (state.placement != Placement.NONE) {
                                        Offset(size.width / 2f, size.height / 2f)
                                    } else centroid
                                    state.view = settled(
                                        state.view.zoomedAround(zoom, focus.x, focus.y, MAX_ZOOM, minZoom())
                                            .panned(pan.x, pan.y)
                                    )
                                    event.changes.forEach { it.consume() }
                                } else if (pressed.size == 1) {
                                    // Jeden palec przesuwa w obu osiach naraz — także
                                    // ten, który został po szczypaniu.
                                    val change = pressed.first()
                                    val delta = change.position - change.previousPosition
                                    travel += delta
                                    if (!moved && travel.getDistance() > viewConfiguration.touchSlop) moved = true
                                    if (moved) {
                                        state.view = settled(state.view.panned(delta.x, delta.y))
                                        change.consume()
                                    }
                                    tracker.addPosition(change.uptimeMillis, change.position)
                                }
                            } while (event.changes.any { it.pressed })

                            if (moved && !pinched) {
                                val velocity = tracker.calculateVelocity()
                                animation = scope.launch {
                                    launch {
                                        var previous = 0f
                                        AnimationState(0f, velocity.x).animateDecay(decay) {
                                            state.view = settled(state.view.panned(value - previous, 0f))
                                            previous = value
                                        }
                                    }
                                    launch {
                                        var previous = 0f
                                        AnimationState(0f, velocity.y).animateDecay(decay) {
                                            state.view = settled(state.view.panned(0f, value - previous))
                                            previous = value
                                        }
                                    }
                                }
                            } else if (!moved) {
                                val now = down.uptimeMillis
                                if (now - lastTapTime < DOUBLE_TAP_MS &&
                                    (down.position - lastTapPosition).getDistance() < doubleTapSlop
                                ) {
                                    // Podwójne: przybliża miejsce pod palcem albo
                                    // wraca do całej strony.
                                    singleTap?.cancel()
                                    lastTapTime = 0L
                                    val view = state.view
                                    zoomBy(if (view.zoom < 2f) 3f / view.zoom else 1f / view.zoom, down.position)
                                } else {
                                    lastTapTime = now
                                    lastTapPosition = down.position
                                    val position = down.position
                                    singleTap = scope.launch {
                                        delay(DOUBLE_TAP_MS)
                                        when {
                                            // Przy celowniku tapnięcie przenosi wskazane
                                            // miejsce pod celownik — palec zasłania punkt,
                                            // a celownik nie.
                                            state.placement != Placement.NONE -> centerOnScreenPoint(position)
                                            else -> {
                                                val hit = projection()?.let { p ->
                                                    val (page, ux, uy) = p.toPage(position.x, position.y)
                                                    val tolerance = with(density) { 12.dp.toPx() } * p.unitsPerPixel(page)
                                                    comments.lastOrNull { c ->
                                                        c.page == page && ux >= c.rect.left - tolerance && ux <= c.rect.right + tolerance &&
                                                            uy >= c.rect.top - tolerance && uy <= c.rect.bottom + tolerance
                                                    }
                                                }
                                                when {
                                                    hit != null -> state.selectedComment = hit.id
                                                    state.selectedComment != null -> state.selectedComment = null
                                                    immersive -> toolsVisible = !toolsVisible
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
            ) {
                val current = layout ?: return@Canvas
                val view = state.view
                val p = Projection(current, document.pageSizes, view)
                val first = current.pageAt(view.y / view.zoom)
                val last = current.pageAt((view.y + size.height) / view.zoom)
                for (page in first..last) {
                    val left = current.margin * view.zoom - view.x
                    val top = current.tops[page] * view.zoom - view.y
                    val width = current.pageWidth * view.zoom
                    val height = current.heights[page] * view.zoom
                    drawRect(Color(paper), Offset(left, top), Size(width, height))
                    val dstOffset = IntOffset(left.roundToInt(), top.roundToInt())
                    val dstSize = IntSize(width.roundToInt().coerceAtLeast(1), height.roundToInt().coerceAtLeast(1))
                    state.bases[page]?.let { drawImage(it, dstOffset = dstOffset, dstSize = dstSize) }
                    state.details[page]?.takeIf { it.revision == revision }?.let { shown ->
                        // Ułamki strony, nie piksele: w trakcie gestu strona
                        // zmienia rozmiar, a wycinek zostaje na swoim miejscu.
                        val region = shown.region
                        val sx = width / region.pageWidth
                        val sy = height / region.pageHeight
                        drawImage(
                            shown.image,
                            dstOffset = IntOffset(
                                (left + region.left * sx).roundToInt(),
                                (top + region.top * sy).roundToInt()
                            ),
                            dstSize = IntSize(
                                (region.width * sx).roundToInt().coerceAtLeast(1),
                                (region.height * sy).roundToInt().coerceAtLeast(1)
                            )
                        )
                    }
                    matchesByPage[page]?.forEach { (index, match) ->
                        val active = index == state.currentMatch
                        match.rects.forEach { rect ->
                            val a = p.toScreen(page, rect.left, rect.top)
                            val b = p.toScreen(page, rect.right, rect.bottom)
                            val box = Size(b.x - a.x, b.y - a.y)
                            drawRect(if (active) ACTIVE_MATCH else MATCH, a, box)
                            if (active) drawRect(ACTIVE_MATCH_OUTLINE, a, box, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                        }
                    }
                }
                drawCommentMarks(p, comments, state.selectedComment, first..last)
                drawMeasurements(p, state, first..last, overlay)
                if (state.placement != Placement.NONE) drawCursor(p, state, cursor, overlay)
            }
            if (state.bases[currentPage - 1] == null) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            // Wczytywanie linii do przyciągania trwa przy dużych rysunkach kilka
            // sekund — dokument zostaje widoczny, a nad nim widać, że narzędzie
            // się przygotowuje.
            val preparing = state.placement == Placement.MEASURE && cursor?.page?.let { state.loadingGeometry[it] } == true
            androidx.compose.animation.AnimatedVisibility(
                visible = preparing,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.Center)
            ) {
                OverlaySurface {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.viewer_preparing_measure), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }

    val measurePanel: @Composable () -> Unit = {
        when (state.placement) {
            Placement.MEASURE -> MeasurePanel(
                state = state,
                cursor = cursor,
                onPoint = ::placePoint,
                onSaveToFile = if (document.canAnnotate && save != null) {
                    {
                        scope.launch {
                            val author = ViewerPreferences.author(context)
                            for (m in state.measurements) {
                                val scale = state.scaleFor(m.page)
                                val caption = scale?.let { MeasureFormat.length(m.lengthUnits * it.mmPerUnit) } ?: continue
                                document.addMeasurement(m, caption, author)
                            }
                            state.measurements.clear()
                            state.message = context.getString(R.string.viewer_measurements_added)
                        }
                    }
                } else null,
                onClose = {
                    state.placement = Placement.NONE
                    state.firstPoint = null
                }
            )
            Placement.COMMENT -> CommentPlacementPanel(
                state = state,
                document = document,
                cursor = cursor,
                scope = scope,
                onDone = { state.placement = Placement.NONE }
            )
            Placement.NONE -> Unit
        }
    }

    if (immersive) {
        Box(modifier.fillMaxSize().background(backdrop)) {
            canvas(Modifier.fillMaxSize())
            AnimatedVisibility(
                visible = toolsVisible || state.searchOpen || state.placement != Placement.NONE,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter).padding(8.dp)
            ) {
                OverlaySurface { toolbar() }
            }
            Column(Modifier.align(Alignment.BottomCenter).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (state.placement != Placement.NONE) {
                    OverlaySurface { measurePanel() }
                } else if (showThumbnails) {
                    AnimatedVisibility(visible = toolsVisible, enter = fadeIn(), exit = fadeOut()) {
                        OverlaySurface { thumbnails() }
                    }
                }
            }
            SheetPanels(state, document, comments, layers, Modifier.align(Alignment.CenterEnd), ::showComment)
        }
    } else {
        Box(modifier) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    toolbar()
                }
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        shape = RoundedCornerShape(22.dp),
                        color = backdrop
                    ) {
                        canvas(Modifier.fillMaxSize())
                    }
                    if (state.placement != Placement.NONE) {
                        Box(Modifier.align(Alignment.BottomCenter).padding(8.dp)) {
                            OverlaySurface { measurePanel() }
                        }
                    }
                    SheetPanels(state, document, comments, layers, Modifier.align(Alignment.CenterEnd), ::showComment)
                }
                if (showThumbnails && state.placement == Placement.NONE) {
                    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                        thumbnails()
                    }
                }
            }
        }
    }

    // Wybrany komentarz: dymek z treścią nad dolną krawędzią.
    state.selectedComment?.let { id ->
        comments.firstOrNull { it.id == id }?.let { comment ->
            CommentBubble(
                comment = comment,
                canDelete = document.canAnnotate && save != null,
                onDelete = {
                    scope.launch { document.deleteComment(comment.id) }
                    state.selectedComment = null
                },
                onDismiss = { state.selectedComment = null }
            )
        }
    }

    if (jumpDialog) {
        JumpDialog(pageCount, currentPage, onDismiss = { jumpDialog = false }) { page ->
            jumpDialog = false
            showPage(page - 1)
        }
    }

    if (state.scaleDialog) {
        ScaleDialog(
            state = state,
            document = document,
            page = (cursor?.page ?: (currentPage - 1)),
            onDismiss = { state.scaleDialog = false }
        )
    }

    state.message?.let { text ->
        LaunchedEffect(text) {
            delay(3500)
            if (state.message == text) state.message = null
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.inverseSurface,
                modifier = Modifier.padding(bottom = 140.dp, start = 24.dp, end = 24.dp)
            ) {
                Text(
                    text,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
        }
    }
}

/** Półprzezroczyste tło narzędzi leżących na treści. */
@Composable
internal fun OverlaySurface(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = .86f),
        // Kolor z przezroczystością nie jest kolorem z palety, więc Surface sam
        // nie dobrałby koloru tekstu — bez tego tekst wychodził czarny na ciemnym.
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 3.dp
    ) { content() }
}

/**
 * Ładuje bitmapę całej strony i ostry wycinek jej widocznej części. Nic nie
 * rysuje — obrazy trafiają do map, z których korzysta płótno przeglądarki.
 */
@Composable
private fun SheetPageLoader(
    document: SheetDocument,
    pageIndex: Int,
    baseWidth: Int,
    revision: Int,
    bases: MutableMap<Int, androidx.compose.ui.graphics.ImageBitmap>,
    details: MutableMap<Int, SheetDetail>,
    region: () -> RenderBudget.Region?
) {
    // Poprzednia bitmapa zostaje do czasu nowej, więc przekroczenie progu
    // rozdzielczości nie zostawia na chwilę pustej strony.
    LaunchedEffect(document, pageIndex, baseWidth, revision) {
        runCatching { document.renderPage(pageIndex, baseWidth) }
            .onSuccess { bases[pageIndex] = it.asImageBitmap() }
    }

    LaunchedEffect(document, pageIndex, revision) {
        snapshotFlow(region)
            .distinctUntilChanged()
            .collectLatest { wanted ->
                if (wanted == null) {
                    details.remove(pageIndex)
                    return@collectLatest
                }
                // Renderujemy dopiero, gdy gest ustanie; do tego czasu stary
                // wycinek trzyma się swojego miejsca na stronie.
                delay(150)
                runCatching { document.renderRegion(pageIndex, wanted) }
                    .onSuccess { details[pageIndex] = SheetDetail(it.asImageBitmap(), wanted, revision) }
            }
    }

    DisposableEffect(document, pageIndex) {
        onDispose {
            bases.remove(pageIndex)
            details.remove(pageIndex)
        }
    }
}

/** Paski systemu schowane; gest od krawędzi pokazuje je na chwilę. */
@Composable
internal fun HiddenSystemBars() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = ((view.parent as? DialogWindowProvider) ?: (view as? DialogWindowProvider))?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

/** Największe powiększenie. Ostrość nie zależy od niego — wycinek ma rozdzielczość ekranu. */
internal const val MAX_ZOOM = 64f
internal const val ZOOM_STEP = 1.5f
private const val DOUBLE_TAP_MS = 280L

/** Wysokość znalezionego tekstu po przeskoku — da się go przeczytać bez przybliżania. */
private val SEARCH_TEXT_HEIGHT = 18.dp
/** Zasięg przyciągania wokół celownika. */
private val SNAP_RADIUS = 22.dp
private val MATCH = Color(0x66FFD600)
private val ACTIVE_MATCH = Color(0x80FF9100)
private val ACTIVE_MATCH_OUTLINE = Color(0xFFE65100)
