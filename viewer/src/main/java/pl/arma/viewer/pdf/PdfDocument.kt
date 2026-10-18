package pl.arma.viewer.pdf

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.PdfRendererPreV
import android.graphics.pdf.models.PageMatchBounds
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.ext.SdkExtensions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.arma.viewer.sheet.Measurement
import pl.arma.viewer.sheet.RenderBudget
import pl.arma.viewer.sheet.SheetComment
import pl.arma.viewer.sheet.SheetDocument
import pl.arma.viewer.sheet.SheetLayer
import pl.arma.viewer.sheet.SheetMatch
import pl.arma.viewer.sheet.SheetScale
import pl.arma.viewer.sheet.SheetSize
import pl.arma.viewer.sheet.SnapGeometry
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Otwarty dokument PDF.
 *
 * Obraz stron rysuje systemowy PdfRenderer — szybki i ostry. Nie pokazuje
 * jednak komentarzy ani nie przełącza warstw, więc gdy plik je ma, rysujemy
 * z kopii roboczej, w której komentarze są wtopione w treść, a warstwy
 * ustawione tak, jak wybrał użytkownik. Kopię przygotowuje [PdfStructure].
 */
class PdfDocument private constructor(
    private val original: File,
    private val workDir: File,
    descriptor: ParcelFileDescriptor,
    renderer: PdfRenderer
) : SheetDocument {

    private val lock = ReentrantLock()
    private var closed = false
    private var descriptor: ParcelFileDescriptor = descriptor
    private var renderer: PdfRenderer = renderer
    // Typ ogólny zamiast PdfRendererPreV: na systemach bez tej klasy samo
    // pole tego typu utrudniałoby weryfikację klasy dokumentu.
    private var searchRenderer: AutoCloseable? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val structure = PdfStructure(original, workDir)

    override val kindLabel = "PDF"

    override val pageSizes: List<SheetSize> = List(renderer.pageCount) { index ->
        renderer.openPage(index).use { SheetSize(it.width.toFloat(), it.height.toFloat()) }
    }

    private val _revision = MutableStateFlow(0)
    override val revision: StateFlow<Int> = _revision.asStateFlow()
    override val layers: StateFlow<List<SheetLayer>> = structure.layers
    override val comments: StateFlow<List<SheetComment>> = structure.comments
    override val dirty: StateFlow<Boolean> = structure.dirty
    override val modelUnits = false
    override val paperColor: StateFlow<Int> = MutableStateFlow(Color.WHITE)
    override val canAnnotate = true

    init {
        // Komentarze i warstwy czytamy w tle: pierwsza strona pokazuje się od
        // razu, a obraz z komentarzami podmienia ją, gdy kopia będzie gotowa.
        scope.launch {
            runCatching {
                structure.load(pageSizes)
                refreshRendering()
            }
        }
    }

    private suspend fun refreshRendering() {
        val copy = structure.renderCopy() ?: original
        swapRenderer(copy)
    }

    private fun swapRenderer(file: File) {
        val newDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val newRenderer = try {
            PdfRenderer(newDescriptor)
        } catch (error: Throwable) {
            runCatching { newDescriptor.close() }
            throw error
        }
        lock.withLock {
            if (closed) {
                newRenderer.close()
                runCatching { newDescriptor.close() }
                return
            }
            val oldRenderer = renderer
            val oldDescriptor = descriptor
            renderer = newRenderer
            descriptor = newDescriptor
            runCatching { searchRenderer?.close() }
            searchRenderer = null
            oldRenderer.close()
            runCatching { oldDescriptor.close() }
        }
        _revision.value++
    }

    override suspend fun renderPage(index: Int, targetWidth: Int): Bitmap = render(index) { page ->
        val (width, height) = RenderBudget.fit(targetWidth, page.width, page.height)
        allocate(width, height).also {
            page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        }
    }

    override suspend fun renderThumbnail(index: Int, width: Int): Bitmap = render(index) { page ->
        val height = (width.toFloat() * page.height / page.width.coerceAtLeast(1)).toInt().coerceAtLeast(1)
        allocate(width, height).also {
            page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        }
    }

    /**
     * Wycinek strony w rozdzielczości, w jakiej jest wyświetlany. Przy dużym
     * powiększeniu cała strona nie zmieściłaby się w pamięci, a ekran i tak
     * pokazuje tylko jej fragment.
     */
    override suspend fun renderRegion(index: Int, region: RenderBudget.Region): Bitmap = render(index) { page ->
        val bitmap = allocate(
            (region.width * region.scale).toInt().coerceAtLeast(1),
            (region.height * region.scale).toInt().coerceAtLeast(1)
        )
        // Skalę liczymy z faktycznej bitmapy: przy braku pamięci allocate
        // potrafi oddać mniejszą, niż prosiliśmy.
        val pixelsPerDisplayPixel = bitmap.width.toFloat() / region.width
        val scale = region.pageWidth.toFloat() / page.width * pixelsPerDisplayPixel
        val transform = Matrix().apply {
            setScale(scale, scale)
            postTranslate(-region.left * pixelsPerDisplayPixel, -region.top * pixelsPerDisplayPixel)
        }
        page.render(bitmap, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        bitmap
    }

    override val canSearch: Boolean = true

    /**
     * Szuka tekstu na wszystkich stronach. Systemowe wyszukiwanie jest
     * szybsze i zna ten sam układ co obraz; na starszych systemach szukamy
     * w tekście odczytanym przez PdfBox.
     */
    override suspend fun search(query: String, onProgress: (Int) -> Unit): List<SheetMatch> {
        val text = searchText(query, onProgress)
        // Napisy SHX z AutoCAD nie są tekstem strony — dokładamy trafienia z
        // ich adnotacji, pomijając te, które pokrywają się ze zwykłym tekstem.
        val shx = runCatching { structure.searchShx(query) }.getOrDefault(emptyList())
            .filter { s -> text.none { t -> t.page == s.page && RectF.intersects(t.bounds, s.bounds) } }
        return (text + shx).sortedWith(compareBy({ it.page }, { it.bounds.top.toInt() / 8 }, { it.bounds.left }))
    }

    // PageMatchBounds tylko po sprawdzeniu systemSearch, jak w searchPage.
    @SuppressLint("NewApi")
    private suspend fun searchText(query: String, onProgress: (Int) -> Unit): List<SheetMatch> =
        if (systemSearch) {
            withContext(Dispatchers.IO) {
                val matches = mutableListOf<SheetMatch>()
                for (index in 0 until pageCount) {
                    ensureActive()
                    val found = lock.withLock {
                        check(!closed) { "PDF document is closed." }
                        searchPage(index, query)
                    }
                    found.forEach { match -> matches += SheetMatch(index, match.bounds) }
                    onProgress(index + 1)
                }
                matches
            }
        } else {
            structure.search(query, onProgress)
        }

    // Wywołania sprawdza systemSearch: Android 15 ma wyszukiwanie w PdfRenderer,
    // a 12–14 w PdfRendererPreV z modułu PDF aktualizowanego przez Google Play.
    @SuppressLint("NewApi")
    private fun searchPage(index: Int, query: String): List<PageMatchBounds> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            return renderer.openPage(index).use { it.searchText(query) }
        }
        val preV = searchRenderer as PdfRendererPreV? ?: PdfRendererPreV(descriptor.dup()).also { searchRenderer = it }
        return preV.openPage(index).use { it.searchText(query) }
    }

    override suspend fun snapGeometry(page: Int): SnapGeometry = structure.geometry(page)

    override suspend fun detectScale(page: Int): SheetScale? = structure.scale(page)

    override suspend fun setLayerVisible(id: String, visible: Boolean) {
        structure.setLayerVisible(id, visible)
        refreshRendering()
    }

    override suspend fun setAllLayersVisible(visible: Boolean) {
        structure.setAllLayersVisible(visible)
        refreshRendering()
    }

    override suspend fun addComment(page: Int, x: Float, y: Float, text: String, author: String) {
        structure.addComment(page, x, y, text, author)
        refreshRendering()
    }

    override suspend fun addMeasurement(measurement: Measurement, caption: String, author: String) {
        structure.addMeasurement(measurement, caption, author)
        refreshRendering()
    }

    override suspend fun deleteComment(id: String) {
        structure.deleteComment(id)
        refreshRendering()
    }

    override suspend fun saveTo(target: File) = structure.saveTo(target)

    private suspend fun <T> render(index: Int, block: (PdfRenderer.Page) -> T): T =
        withContext(Dispatchers.IO) {
            lock.withLock {
                // Wycinek zamówiony w trakcie gestu bywa nieaktualny, zanim
                // doczeka się blokady — wtedy nie ma po co go rysować.
                ensureActive()
                check(!closed) { "PDF document is closed." }
                require(index in 0 until pageCount) { "Invalid PDF page number." }
                renderer.openPage(index).use(block)
            }
        }

    override fun close() {
        scope.cancel()
        lock.withLock {
            if (closed) return
            closed = true
            runCatching { searchRenderer?.close() }
            renderer.close()
            runCatching { descriptor.close() }
        }
        structure.close()
        workDir.deleteRecursively()
    }

    companion object {
        /**
         * Czy system potrafi szukać tekstu w PDF: Android 15 albo Android 12–14
         * z modułem PDF w wersji rozszerzeń 13 lub nowszej. Bez tego szukamy
         * w tekście odczytanym przez PdfBox.
         */
        val systemSearch: Boolean by lazy {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 13)
        }

        /**
         * Otwiera plik. Rzuca SecurityException dla PDF zabezpieczonego hasłem —
         * systemowy PdfRenderer go nie odszyfruje.
         */
        suspend fun open(context: Context, file: File): PdfDocument = withContext(Dispatchers.IO) {
            PdfStructure.init(context)
            val workDir = File(context.cacheDir, "pdf_work/${System.nanoTime()}").apply { mkdirs() }
            // Własna kopia: zapis komentarzy nadpisuje plik użytkownika, a
            // PdfRenderer i PdfBox czytają ze swoich plików w trakcie pracy.
            val source = File(workDir, "source.pdf")
            file.copyTo(source, overwrite = true)
            val descriptor = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = try {
                PdfRenderer(descriptor)
            } catch (error: Throwable) {
                runCatching { descriptor.close() }
                throw error
            }
            PdfDocument(source, workDir, descriptor, renderer)
        }

        /**
         * Biała bitmapa strony. Przy niedoborze pamięci schodzimy na RGB_565
         * (połowa zużycia, a strony to głównie biel i kreski), a w ostateczności
         * na mniejszą rozdzielczość — lepiej pokazać stronę mniej ostrą niż błąd.
         */
        internal fun allocate(width: Int, height: Int): Bitmap =
            runCatching { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) }
                .recoverCatching { Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565) }
                .recoverCatching {
                    Bitmap.createBitmap((width / 2).coerceAtLeast(1), (height / 2).coerceAtLeast(1), Bitmap.Config.RGB_565)
                }
                .getOrThrow()
                .apply { eraseColor(Color.WHITE) }
    }
}
