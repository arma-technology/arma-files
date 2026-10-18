package pl.arma.viewer.pdf

import android.content.Context
import android.graphics.RectF
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.contentstream.PDFGraphicsStreamEngine
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImage
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLine
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationMarkup
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import pl.arma.viewer.sheet.MeasureFormat
import pl.arma.viewer.sheet.Measurement
import pl.arma.viewer.sheet.ScaleSource
import pl.arma.viewer.sheet.SheetComment
import pl.arma.viewer.sheet.SheetLayer
import pl.arma.viewer.sheet.SheetMatch
import pl.arma.viewer.sheet.SheetScale
import pl.arma.viewer.sheet.SheetSize
import pl.arma.viewer.sheet.SnapGeometry
import java.io.Closeable
import java.io.File
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Wszystko, czego systemowy PdfRenderer nie daje: komentarze, warstwy,
 * linie do przyciągania, skala z pliku, dodawanie adnotacji i zapis.
 *
 * Trzyma „dokument roboczy” PdfBox — oryginał z dodanymi w tej sesji
 * komentarzami. Warstwy wyłączone przez użytkownika to tylko widok, więc nie
 * trafiają do zapisywanego pliku, a jedynie do kopii, z której rysujemy.
 */
internal class PdfStructure(private val original: File, private val workDir: File) : Closeable {

    private val mutex = Mutex()
    private var doc: PDDocument? = null
    private var frames: List<PageFrame> = emptyList()
    private val geometryCache = HashMap<Int, SnapGeometry>()
    private val scaleCache = HashMap<Int, SheetScale?>()
    private val annotationsById = HashMap<String, Pair<Int, PDAnnotation>>()
    private val pendingIds = HashSet<String>()
    private var nextId = 0
    private var layerDefaults: List<Boolean> = emptyList()
    private var copyCounter = 0
    private var lastCopy: File? = null

    val layers = MutableStateFlow<List<SheetLayer>>(emptyList())
    val comments = MutableStateFlow<List<SheetComment>>(emptyList())
    val dirty = MutableStateFlow(false)

    suspend fun load(sizes: List<SheetSize>) = locked {
        ensureLoaded(sizes)
    }

    private fun ensureLoaded(sizes: List<SheetSize>? = null): PDDocument {
        doc?.let { return it }
        // Pliki tymczasowe zamiast pamięci: skan na kilkaset stron nie
        // wyczerpie sterty aplikacji.
        val loaded = PDDocument.load(original, MemoryUsageSetting.setupTempFileOnly())
        doc = loaded
        frames = loaded.pages.mapIndexed { index, page ->
            val size = sizes?.getOrNull(index)
            PageFrame.of(page, size?.width, size?.height)
        }
        val properties = loaded.documentCatalog.ocProperties
        if (properties != null) {
            val groups = properties.optionalContentGroups.toList()
            layerDefaults = groups.map { properties.isGroupEnabled(it) }
            layers.value = groups.mapIndexed { index, group ->
                SheetLayer("ocg:$index", group.name ?: "Warstwa ${index + 1}", layerDefaults[index])
            }
        }
        refreshComments(loaded)
        return loaded
    }

    // ---- komentarze -------------------------------------------------------

    private fun refreshComments(d: PDDocument) {
        annotationsById.clear()
        val list = mutableListOf<SheetComment>()
        d.pages.forEachIndexed { pageIndex, page ->
            val frame = frames[pageIndex]
            for (annotation in runCatching { page.annotations }.getOrDefault(emptyList())) {
                if (!isComment(annotation) || isShxText(annotation)) continue
                // Ten sam obiekt dostaje ten sam identyfikator po każdej zmianie,
                // żeby zaznaczenie na liście nie przeskakiwało.
                val id = identityIds.getOrPut(annotation) { "c${nextId++}" }
                annotationsById[id] = pageIndex to annotation
                val rect = annotation.rectangle ?: continue
                list += SheetComment(
                    id = id,
                    page = pageIndex,
                    rect = frame.toView(rect),
                    type = typeLabel(annotation),
                    author = (annotation as? PDAnnotationMarkup)?.titlePopup?.takeIf { it.isNotBlank() },
                    text = annotation.contents?.takeIf { it.isNotBlank() }
                        ?: (annotation as? PDAnnotationMarkup)?.subject?.takeIf { it.isNotBlank() },
                    modified = formatPdfDate(annotation.modifiedDate),
                    pending = id in pendingIds
                )
            }
        }
        comments.value = list
    }

    private val identityIds = java.util.IdentityHashMap<PDAnnotation, String>()

    suspend fun addComment(page: Int, x: Float, y: Float, text: String, author: String) = locked {
        val d = ensureLoaded()
        val frame = frames[page]
        val (px, py) = frame.fromView(x, y)
        // Ikona notatki stoi lewym górnym rogiem w punkcie, który wskazano.
        val note = PDAnnotationText().apply {
            rectangle = PDRectangle(px, py - NOTE_SIZE, NOTE_SIZE, NOTE_SIZE)
            contents = text
            titlePopup = author
            name = PDAnnotationText.NAME_COMMENT
            color = PDColor(floatArrayOf(1f, .82f, 0f), PDDeviceRGB.INSTANCE)
            creationDate = Calendar.getInstance()
            setModifiedDate(Calendar.getInstance())
            isPrinted = true
            constructAppearances(d)
        }
        attach(d, page, note)
    }

    suspend fun addMeasurement(measurement: Measurement, caption: String, author: String) = locked {
        val d = ensureLoaded()
        val frame = frames[measurement.page]
        val (x1, y1) = frame.fromView(measurement.ax, measurement.ay)
        val (x2, y2) = frame.fromView(measurement.bx, measurement.by)
        val pad = 12f
        val line = PDAnnotationLine().apply {
            this.line = floatArrayOf(x1, y1, x2, y2)
            rectangle = PDRectangle(min(x1, x2) - pad, min(y1, y2) - pad, abs(x2 - x1) + 2 * pad, abs(y2 - y1) + 2 * pad)
            contents = caption
            subject = "Pomiar"
            titlePopup = author
            color = PDColor(floatArrayOf(.85f, .1f, .1f), PDDeviceRGB.INSTANCE)
            startPointEndingStyle = PDAnnotationLine.LE_OPEN_ARROW
            endPointEndingStyle = PDAnnotationLine.LE_OPEN_ARROW
            setCaption(true)
            borderStyle = PDBorderStyleDictionary().apply { width = .8f }
            // Czytniki PDF rozpoznają linię wymiarową po tym zamiarze.
            cosObject.setItem(COSName.getPDFName("IT"), COSName.getPDFName("LineDimension"))
            creationDate = Calendar.getInstance()
            setModifiedDate(Calendar.getInstance())
            isPrinted = true
            constructAppearances(d)
        }
        attach(d, measurement.page, line)
    }

    private fun attach(d: PDDocument, page: Int, annotation: PDAnnotation) {
        val target = d.getPage(page)
        val list = target.annotations.toMutableList()
        list += annotation
        target.annotations = list
        val id = "c${nextId++}"
        identityIds[annotation] = id
        pendingIds += id
        dirty.value = true
        refreshComments(d)
    }

    suspend fun deleteComment(id: String) = locked {
        val d = ensureLoaded()
        val (pageIndex, annotation) = annotationsById[id] ?: return@locked
        val page = d.getPage(pageIndex)
        val popup = (annotation as? PDAnnotationMarkup)?.popup
        page.annotations = page.annotations.filterNot { it === annotation || (popup != null && it.cosObject === popup.cosObject) }
        pendingIds -= id
        dirty.value = true
        refreshComments(d)
    }

    suspend fun saveTo(target: File) = locked {
        val d = ensureLoaded()
        d.save(target)
        pendingIds.clear()
        dirty.value = false
        refreshComments(d)
    }

    // ---- warstwy ----------------------------------------------------------

    suspend fun setLayerVisible(id: String, visible: Boolean) = locked {
        layers.value = layers.value.map { if (it.id == id) it.copy(visible = visible) else it }
    }

    suspend fun setAllLayersVisible(visible: Boolean) = locked {
        layers.value = layers.value.map { it.copy(visible = visible) }
    }

    // ---- kopia do rysowania -----------------------------------------------

    /**
     * Plik, z którego rysować: komentarze wtopione w treść, warstwy jak
     * wybrał użytkownik. Null, gdy oryginał wystarcza.
     */
    suspend fun renderCopy(): File? = locked {
        val d = ensureLoaded()
        val layerChoice = layers.value.map { it.visible }
        val layersChanged = layerChoice != layerDefaults
        val hasDrawable = d.pages.any { page ->
            runCatching { page.annotations }.getOrDefault(emptyList()).any { flattenable(it) }
        }
        if (!layersChanged && !hasDrawable && !dirty.value) return@locked null

        val source = if (dirty.value) {
            File(workDir, "working.pdf").also { d.save(it) }
        } else {
            original
        }
        val copy = PDDocument.load(source, MemoryUsageSetting.setupTempFileOnly())
        try {
            copy.documentCatalog.ocProperties?.let { properties ->
                properties.optionalContentGroups.toList().forEachIndexed { index, group ->
                    layerChoice.getOrNull(index)?.let { properties.setGroupEnabled(group, it) }
                }
            }
            flatten(copy)
            val out = File(workDir, "render-${copyCounter++}.pdf")
            copy.save(out)
            // Poprzednią kopię zostawiamy: renderer może jeszcze z niej czytać
            // do chwili podmiany. Starsze już nie są potrzebne.
            lastCopy?.let { old -> workDir.listFiles()?.filter { it.name.startsWith("render-") && it != old && it != out }?.forEach { it.delete() } }
            lastCopy = out
            out
        } finally {
            copy.close()
        }
    }

    /**
     * Wtapia wygląd adnotacji w treść strony (PDF 32000-1, 12.5.5): prostokąt
     * BBox przekształcony macierzą wyglądu trafia w prostokąt adnotacji.
     */
    private fun flatten(d: PDDocument) {
        for (page in d.pages) {
            val annotations = runCatching { page.annotations }.getOrDefault(emptyList())
            if (annotations.isEmpty()) continue
            val keep = mutableListOf<PDAnnotation>()
            val draw = mutableListOf<PDAnnotation>()
            for (annotation in annotations) {
                when {
                    annotation is PDAnnotationLink || annotation.subtype == "Popup" || isShxText(annotation) -> keep += annotation
                    annotation.isHidden || annotation.isNoView -> Unit
                    flattenable(annotation, d) -> draw += annotation
                    else -> keep += annotation
                }
            }
            if (draw.isEmpty()) continue
            PDPageContentStream(d, page, PDPageContentStream.AppendMode.APPEND, true, true).use { stream ->
                for (annotation in draw) {
                    val appearance = annotation.normalAppearanceStream ?: continue
                    val rect = annotation.rectangle ?: continue
                    val bbox = appearance.bBox ?: continue
                    val box = transformed(bbox, appearance.matrix)
                    if (box.width() <= 0f || box.height() <= 0f) continue
                    val sx = rect.width / box.width()
                    val sy = rect.height / box.height()
                    stream.saveGraphicsState()
                    stream.transform(Matrix(sx, 0f, 0f, sy, rect.lowerLeftX - box.left * sx, rect.lowerLeftY - box.top * sy))
                    stream.drawForm(appearance)
                    stream.restoreGraphicsState()
                }
            }
            page.annotations = keep
        }
    }

    private fun flattenable(annotation: PDAnnotation, d: PDDocument? = null): Boolean {
        if (annotation is PDAnnotationLink || annotation.subtype == "Popup" || isShxText(annotation)) return false
        if (annotation.isHidden || annotation.isNoView) return false
        if (annotation.normalAppearanceStream != null) return true
        // Część programów zapisuje komentarze bez wyglądu — PdfBox umie go
        // zbudować dla typowych adnotacji.
        if (d != null && annotation is PDAnnotationMarkup) {
            runCatching { annotation.constructAppearances(d) }
            return annotation.normalAppearanceStream != null
        }
        return annotation is PDAnnotationMarkup
    }

    /** Obrys BBox po przekształceniu macierzą; „top” to tu dolna krawędź (Y w górę). */
    private fun transformed(bbox: PDRectangle, matrix: Matrix?): RectF {
        // RectF tylko jako cztery liczby: left/top to najmniejsze X i Y.
        val m = matrix ?: Matrix()
        val corners = listOf(
            m.transformPoint(bbox.lowerLeftX, bbox.lowerLeftY),
            m.transformPoint(bbox.upperRightX, bbox.lowerLeftY),
            m.transformPoint(bbox.upperRightX, bbox.upperRightY),
            m.transformPoint(bbox.lowerLeftX, bbox.upperRightY)
        )
        return RectF(
            corners.minOf { it.x }, corners.minOf { it.y },
            corners.maxOf { it.x }, corners.maxOf { it.y }
        )
    }

    // ---- geometria i skala ------------------------------------------------

    suspend fun geometry(page: Int): SnapGeometry = locked {
        geometryCache[page]?.let { return@locked it }
        val d = ensureLoaded()
        val collector = GeometryCollector(d.getPage(page), frames[page])
        runCatching { collector.processPage(d.getPage(page)) }
        collector.builder.build().also { geometryCache[page] = it }
    }

    suspend fun scale(page: Int): SheetScale? = locked {
        if (scaleCache.containsKey(page)) return@locked scaleCache[page]
        val d = ensureLoaded()
        val result = viewportScale(d.getPage(page)) ?: runCatching {
            val stripper = PDFTextStripper().apply {
                startPage = page + 1
                endPage = page + 1
            }
            MeasureFormat.detectScale(stripper.getText(d))?.let {
                SheetScale.pdfRatio(it.toDouble(), ScaleSource.TITLE_BLOCK)
            }
        }.getOrNull()
        scaleCache[page] = result
        result
    }

    /**
     * Skala z rzutni zapisanej przez program CAD (słownik /VP z /Measure).
     * Przy kilku rzutniach bierzemy największą — to zwykle rzut, a nie detal.
     */
    private fun viewportScale(page: PDPage): SheetScale? {
        val viewports = page.cosObject.getDictionaryObject("VP") as? COSArray ?: return null
        var best: SheetScale? = null
        var bestArea = -1f
        var paper: SheetScale? = null
        for (i in 0 until viewports.size()) {
            val viewport = viewports.getObject(i) as? COSDictionary ?: continue
            val measure = viewport.getDictionaryObject("Measure") as? COSDictionary ?: continue
            if (measure.getNameAsString("Subtype") != "RL") continue
            val x = measure.getDictionaryObject("X") as? COSArray ?: continue
            val format = x.getObject(0) as? COSDictionary ?: continue
            // AutoCAD zapisuje jednostkę jako spację: to jednostki rysunku,
            // u nas praktycznie zawsze milimetry.
            val unit = format.getString("U")?.trim().orEmpty().ifEmpty { "mm" }
            val factor = (format.getDictionaryObject("C") as? COSNumber)?.floatValue() ?: continue
            val mm = MeasureFormat.mmPerUnit(unit) ?: continue
            val box = viewport.getDictionaryObject("BBox") as? COSArray
            val area = if (box != null && box.size() == 4) {
                val n = (0 until 4).map { (box.getObject(it) as? COSNumber)?.floatValue() ?: 0f }
                abs((n[2] - n[0]) * (n[3] - n[1]))
            } else 0f
            val mmPerUnit = factor * mm
            val label = measure.getString("R")?.takeIf { it.isNotBlank() }
                ?: "1:" + SheetScale.formatRatio(mmPerUnit / SheetScale.MM_PER_POINT)
            val scale = SheetScale(mmPerUnit, label, ScaleSource.DOCUMENT)
            // Rzutnia obejmująca cały arkusz w skali 1:1 to sam papier — rzut
            // z prawdziwą skalą to inna, mniejsza rzutnia.
            if (abs(mmPerUnit / SheetScale.MM_PER_POINT - 1.0) < 0.02) {
                paper = scale
                continue
            }
            if (area > bestArea) {
                bestArea = area
                best = scale
            }
        }
        return best ?: paper
    }

    /**
     * Napisy SHX z AutoCAD: na stronie to kreski, a tekst zapisany jest w
     * niewidocznych adnotacjach „AutoCAD SHX Text”. Szukamy także w nich.
     */
    suspend fun searchShx(query: String): List<SheetMatch> = locked {
        val needle = query.filterNot { it.isWhitespace() }.lowercase()
        if (needle.isEmpty()) return@locked emptyList()
        val d = ensureLoaded()
        val out = mutableListOf<SheetMatch>()
        d.pages.forEachIndexed { index, page ->
            for (annotation in runCatching { page.annotations }.getOrDefault(emptyList())) {
                if (!isShxText(annotation)) continue
                val text = annotation.contents?.filterNot { it.isWhitespace() }?.lowercase() ?: continue
                if (!text.contains(needle)) continue
                val rect = annotation.rectangle ?: continue
                out += SheetMatch(index, listOf(frames[index].toView(rect)))
            }
        }
        out
    }

    /** Czy na stronach są napisy SHX — wtedy wyszukiwanie zagląda też do nich. */
    suspend fun hasShx(): Boolean = locked {
        ensureLoaded().pages.any { page -> runCatching { page.annotations }.getOrDefault(emptyList()).any(::isShxText) }
    }

    // ---- wyszukiwanie zapasowe --------------------------------------------

    suspend fun search(query: String, onProgress: (Int) -> Unit): List<SheetMatch> = withContext(Dispatchers.IO) {
        val needle = query.filterNot { it.isWhitespace() }.lowercase()
        if (needle.isEmpty()) return@withContext emptyList()
        val result = mutableListOf<SheetMatch>()
        val pageCount = locked { ensureLoaded().numberOfPages }
        for (page in 0 until pageCount) {
            ensureActive()
            val found = locked {
                val d = ensureLoaded()
                val positions = mutableListOf<TextPosition>()
                val stripper = object : PDFTextStripper() {
                    override fun writeString(text: String?, textPositions: MutableList<TextPosition>?) {
                        textPositions?.let { positions += it }
                    }
                }.apply {
                    startPage = page + 1
                    endPage = page + 1
                    sortByPosition = true
                }
                runCatching { stripper.getText(d) }
                matchPositions(page, positions, needle)
            }
            result += found
            onProgress(page + 1)
        }
        result
    }

    private fun matchPositions(page: Int, positions: List<TextPosition>, needle: String): List<SheetMatch> {
        val chars = StringBuilder()
        val owner = ArrayList<Int>()
        positions.forEachIndexed { index, position ->
            for (c in position.unicode.orEmpty()) {
                if (c.isWhitespace()) continue
                chars.append(c.lowercaseChar())
                owner += index
            }
        }
        val haystack = chars.toString()
        val frame = frames[page]
        val matches = mutableListOf<SheetMatch>()
        var from = 0
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) break
            val used = (at until at + needle.length).map { owner[it] }.distinct().map { positions[it] }
            // Prostokąt na każdą linię tekstu, którą obejmuje trafienie.
            val rects = used.groupBy { (it.yDirAdj / 2).toInt() }.values.map { line ->
                val r = RectF(
                    line.minOf { it.xDirAdj }, line.minOf { it.yDirAdj - it.heightDir },
                    line.maxOf { it.xDirAdj + it.widthDirAdj }, line.maxOf { it.yDirAdj }
                )
                frame.scaleDisplay(r)
            }
            if (rects.isNotEmpty()) matches += SheetMatch(page, rects)
            from = at + needle.length
        }
        return matches
    }

    private suspend fun <T> locked(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock { block() }
    }

    override fun close() {
        runCatching { doc?.close() }
        doc = null
    }

    /**
     * Linie strony w układzie widoku. Ścieżki obcinające (W n) pomijamy —
     * nie są tym, co widać na rysunku.
     */
    private class GeometryCollector(page: PDPage, private val frame: PageFrame) : PDFGraphicsStreamEngine(page) {
        val builder = SnapGeometry.Builder()
        private val pending = mutableListOf<Piece>()
        private var startX = 0f
        private var startY = 0f
        private var curX = 0f
        private var curY = 0f

        private sealed interface Piece
        private class Straight(val x1: Float, val y1: Float, val x2: Float, val y2: Float) : Piece
        private class Curve(val points: FloatArray) : Piece

        override fun appendRectangle(p0: android.graphics.PointF, p1: android.graphics.PointF, p2: android.graphics.PointF, p3: android.graphics.PointF) {
            moveTo(p0.x, p0.y)
            lineTo(p1.x, p1.y)
            lineTo(p2.x, p2.y)
            lineTo(p3.x, p3.y)
            closePath()
        }

        override fun drawImage(pdImage: PDImage?) = Unit
        override fun clip(windingRule: android.graphics.Path.FillType?) = Unit
        override fun shadingFill(shadingName: COSName?) = Unit

        override fun moveTo(x: Float, y: Float) {
            startX = x; startY = y; curX = x; curY = y
        }

        override fun lineTo(x: Float, y: Float) {
            pending += Straight(curX, curY, x, y)
            curX = x; curY = y
        }

        override fun curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
            val steps = CURVE_STEPS
            val pts = FloatArray((steps + 1) * 2)
            for (i in 0..steps) {
                val t = i / steps.toFloat()
                val u = 1 - t
                val a = u * u * u
                val b = 3 * u * u * t
                val c = 3 * u * t * t
                val e = t * t * t
                pts[2 * i] = a * curX + b * x1 + c * x2 + e * x3
                pts[2 * i + 1] = a * curY + b * y1 + c * y2 + e * y3
            }
            pending += Curve(pts)
            curX = x3; curY = y3
        }

        override fun getCurrentPoint(): android.graphics.PointF = android.graphics.PointF(curX, curY)

        override fun closePath() {
            if (curX != startX || curY != startY) pending += Straight(curX, curY, startX, startY)
            curX = startX; curY = startY
        }

        override fun endPath() = pending.clear()
        override fun strokePath() = flush()
        override fun fillPath(windingRule: android.graphics.Path.FillType?) = flush()
        override fun fillAndStrokePath(windingRule: android.graphics.Path.FillType?) = flush()

        private fun flush() {
            for (piece in pending) {
                when (piece) {
                    is Straight -> {
                        val (ax, ay) = frame.toViewPoint(piece.x1, piece.y1)
                        val (bx, by) = frame.toViewPoint(piece.x2, piece.y2)
                        builder.line(ax, ay, bx, by)
                    }
                    is Curve -> {
                        val out = FloatArray(piece.points.size)
                        for (i in 0 until piece.points.size / 2) {
                            val (vx, vy) = frame.toViewPoint(piece.points[2 * i], piece.points[2 * i + 1])
                            out[2 * i] = vx
                            out[2 * i + 1] = vy
                        }
                        builder.curve(out)
                    }
                }
            }
            pending.clear()
        }
    }

    companion object {
        private const val NOTE_SIZE = 20f
        private const val CURVE_STEPS = 8

        @Volatile
        private var initialized = false

        fun init(context: Context) {
            if (initialized) return
            PDFBoxResourceLoader.init(context.applicationContext)
            initialized = true
        }

        fun isShxText(annotation: PDAnnotation): Boolean =
            (annotation as? PDAnnotationMarkup)?.titlePopup == "AutoCAD SHX Text" ||
                annotation.cosObject.getString("T") == "AutoCAD SHX Text"

        private fun isComment(annotation: PDAnnotation): Boolean {
            val type = annotation.subtype ?: return false
            return type !in setOf("Link", "Popup", "Widget", "PrinterMark", "TrapNet", "Watermark", "3D", "Screen", "Movie")
        }

        fun typeLabel(annotation: PDAnnotation): String = when (annotation.subtype) {
            "Text" -> "Notatka"
            "FreeText" -> "Tekst"
            "Line" -> if ((annotation.cosObject.getNameAsString("IT") ?: "").contains("Dimension")) "Wymiar" else "Linia"
            "Square" -> "Prostokąt"
            "Circle" -> "Elipsa"
            "Polygon" -> if ((annotation.cosObject.getNameAsString("IT") ?: "").contains("Dimension")) "Pole" else "Wielokąt"
            "PolyLine" -> if ((annotation.cosObject.getNameAsString("IT") ?: "").contains("Dimension")) "Obwód" else "Łamana"
            "Highlight" -> "Wyróżnienie"
            "Underline" -> "Podkreślenie"
            "StrikeOut" -> "Przekreślenie"
            "Squiggly" -> "Falista linia"
            "Ink" -> "Odręczny rysunek"
            "Stamp" -> "Pieczątka"
            "FileAttachment" -> "Załącznik"
            "Caret" -> "Wstawienie"
            "Sound" -> "Dźwięk"
            "Redact" -> "Do usunięcia"
            else -> annotation.subtype ?: "Adnotacja"
        }

        /** „D:20250314153000+01'00'” → „14.03.2025 15:30”. */
        fun formatPdfDate(raw: String?): String? {
            val m = Regex("""D?:?(\d{4})(\d{2})?(\d{2})?(\d{2})?(\d{2})?""").find(raw ?: return null) ?: return null
            val (y, mo, d, h, mi) = m.destructured
            if (mo.isEmpty() || d.isEmpty()) return y
            val time = if (h.isNotEmpty() && mi.isNotEmpty()) " $h:$mi" else ""
            return "$d.$mo.$y$time"
        }
    }
}

/**
 * Przejście z przestrzeni strony PDF (początek w lewym dolnym rogu, bez
 * obrotu) do układu, w jakim PdfRenderer rysuje stronę: lewy górny róg
 * widocznego obszaru (CropBox), z obrotem /Rotate, w jego rozmiarze.
 */
internal class PageFrame(
    private val left: Float,
    private val bottom: Float,
    private val width: Float,
    private val height: Float,
    private val rotation: Int,
    viewWidth: Float?,
    viewHeight: Float?
) {
    private val rotatedWidth = if (rotation == 90 || rotation == 270) height else width
    private val rotatedHeight = if (rotation == 90 || rotation == 270) width else height
    private val sx = (viewWidth ?: rotatedWidth) / rotatedWidth.coerceAtLeast(1e-3f)
    private val sy = (viewHeight ?: rotatedHeight) / rotatedHeight.coerceAtLeast(1e-3f)

    fun toViewPoint(x: Float, y: Float): Pair<Float, Float> {
        val u = x - left
        val v = y - bottom
        val (rx, ry) = when (rotation) {
            90 -> v to u
            180 -> (width - u) to v
            270 -> (height - v) to (width - u)
            else -> u to (height - v)
        }
        return rx * sx to ry * sy
    }

    fun fromView(x: Float, y: Float): Pair<Float, Float> {
        val rx = x / sx
        val ry = y / sy
        val (u, v) = when (rotation) {
            90 -> ry to rx
            180 -> (width - rx) to ry
            270 -> (width - ry) to (height - rx)
            else -> rx to (height - ry)
        }
        return (u + left) to (v + bottom)
    }

    fun toView(rect: PDRectangle): RectF {
        val (ax, ay) = toViewPoint(rect.lowerLeftX, rect.lowerLeftY)
        val (bx, by) = toViewPoint(rect.upperRightX, rect.upperRightY)
        return RectF(min(ax, bx), min(ay, by), max(ax, bx), max(ay, by))
    }

    /** Prostokąt z PdfBox TextPosition (już w układzie widoku, w punktach). */
    fun scaleDisplay(rect: RectF): RectF = RectF(rect.left * sx, rect.top * sy, rect.right * sx, rect.bottom * sy)

    companion object {
        fun of(page: PDPage, viewWidth: Float?, viewHeight: Float?): PageFrame {
            val crop = page.cropBox
            val rotation = ((page.rotation % 360) + 360) % 360
            return PageFrame(crop.lowerLeftX, crop.lowerLeftY, crop.width, crop.height, rotation, viewWidth, viewHeight)
        }
    }
}
