package pl.arma.viewer.sheet

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import pl.arma.viewer.R
import kotlinx.coroutines.CancellationException

/** Akcja gospodarza w menu przeglądarki (udostępnij, ulubione…). */
class ViewerAction(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val onClick: () -> Unit)

/** Ostry wycinek strony; współrzędne w pikselach strony o szerokości region.pageWidth. */
internal class SheetDetail(val image: ImageBitmap, val region: RenderBudget.Region, val revision: Int)

/** Punkt pomiaru razem ze stroną, na której leży. */
internal data class PagePoint(val page: Int, val snap: SnapResult)

internal enum class Placement { NONE, MEASURE, COMMENT }

/**
 * Stan przeglądarki: otwarty dokument, widok, wyrenderowane strony,
 * wyszukiwanie i pomiary.
 *
 * Trzymany poza widokiem, żeby po obrocie przeglądarka w innym kontenerze —
 * arkusz w pionie, pełny ekran w poziomie — wróciła w to samo miejsce
 * z tym samym powiększeniem, pomiarami i bez ponownego renderowania.
 */
@Stable
class SheetViewerState internal constructor() {
    var document by mutableStateOf<SheetDocument?>(null)
        internal set
    var error by mutableStateOf<String?>(null)
        internal set
    var loading by mutableStateOf(true)
        internal set

    /** Cały widok — powiększenie i przesunięcie w obu osiach — w jednym stanie. */
    internal var view by mutableStateOf(SheetView())
    internal var lastLayout: SheetLayout? = null
    internal var lastHeight = 0f
    internal val bases = mutableStateMapOf<Int, ImageBitmap>()
    internal val details = mutableStateMapOf<Int, SheetDetail>()

    internal var searchOpen by mutableStateOf(false)
    internal var query by mutableStateOf("")
    internal var matches by mutableStateOf<List<SheetMatch>>(emptyList())
    internal var currentMatch by mutableIntStateOf(-1)
    internal var searching by mutableStateOf(false)
    internal var searchedPages by mutableIntStateOf(0)
    /** Zapytanie, dla którego są [matches] — po obrocie nie szukamy od nowa. */
    internal var searchedQuery: String? = null
    /** Klawiatura tylko po tapnięciu lupy, nie przy odtworzeniu paska po obrocie. */
    internal var focusSearch = false

    internal var placement by mutableStateOf(Placement.NONE)
    internal val measurements = mutableStateListOf<Measurement>()
    internal var firstPoint by mutableStateOf<PagePoint?>(null)
    internal var axisLock by mutableStateOf(false)
    internal var snapping by mutableStateOf(true)
    /**
     * Celownik jako ułamek szerokości i wysokości obszaru dokumentu — po
     * obrocie zostaje w tym samym miejscu ekranu, choć zmienia się jego rozmiar.
     */
    internal var aim by mutableStateOf(Offset(0.5f, 0.5f))
    /** Przełączniki pomiaru wczytane z ustawień (raz na przeglądarkę). */
    internal var measurePrefsLoaded = false
    internal val geometries = mutableStateMapOf<Int, SnapGeometry>()
    internal val loadingGeometry = mutableStateMapOf<Int, Boolean>()
    /** Skala wybrana przez użytkownika dla strony; null w mapie = brak wyboru. */
    internal val scales = mutableStateMapOf<Int, SheetScale>()
    internal val detectedScales = mutableStateMapOf<Int, SheetScale?>()
    /** Ostatnio wybrana skala — proponowana dla kolejnych stron. */
    internal var lastScale by mutableStateOf<SheetScale?>(null)

    internal var commentsOpen by mutableStateOf(false)
    internal var layersOpen by mutableStateOf(false)
    internal var scaleDialog by mutableStateOf(false)
    internal var selectedComment by mutableStateOf<String?>(null)
    internal var message by mutableStateOf<String?>(null)

    /** Skala obowiązująca na stronie: wybrana, wykryta, albo ostatnio użyta. */
    internal fun scaleFor(page: Int): SheetScale? = scales[page] ?: detectedScales[page] ?: lastScale
}

/**
 * Otwiera dokument i trzyma go do opuszczenia kompozycji.
 *
 * @param open otwiera dokument — gospodarz może najpierw pobrać plik z sieci;
 *   do tego czasu przeglądarka pokazuje wczytywanie
 */
@Composable
fun rememberSheetViewerState(key: Any, open: suspend (Context) -> SheetDocument): SheetViewerState {
    val context = LocalContext.current.applicationContext
    val state = remember(key) { SheetViewerState() }
    val currentOpen by rememberUpdatedState(open)
    LaunchedEffect(state) {
        try {
            val document = currentOpen(context)
            if (document.pageCount == 0) {
                document.close()
                state.error = context.getString(R.string.viewer_empty_document)
            } else {
                state.document = document
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: SecurityException) {
            state.error = context.getString(R.string.viewer_encrypted)
        } catch (failure: OutOfMemoryError) {
            state.error = context.getString(R.string.viewer_too_large)
        } catch (failure: Exception) {
            state.error = failure.message ?: context.getString(R.string.viewer_open_failed)
        }
        state.loading = false
    }
    DisposableEffect(state) {
        onDispose { state.document?.close() }
    }
    return state
}
