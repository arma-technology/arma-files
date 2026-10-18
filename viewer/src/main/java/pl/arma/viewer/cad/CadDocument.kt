package pl.arma.viewer.cad

import pl.arma.viewer.R
import pl.arma.viewer.ViewerText
import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import pl.arma.viewer.sheet.RenderBudget
import pl.arma.viewer.sheet.ScaleSource
import pl.arma.viewer.sheet.SheetComment
import pl.arma.viewer.sheet.SheetDocument
import pl.arma.viewer.sheet.SheetLayer
import pl.arma.viewer.sheet.SheetMatch
import pl.arma.viewer.sheet.SheetScale
import pl.arma.viewer.sheet.SheetSize
import pl.arma.viewer.sheet.SnapGeometry
import java.io.File
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Rysunek CAD (DXF albo DWG zamieniony na DXF) jako jeden arkusz: obszar
 * modelu w granicach rysunku. Jednostką strony jest jednostka rysunku.
 */
class CadDocument private constructor(
    private val scene: CadScene,
    override val kindLabel: String,
    private val truncated: Boolean
) : SheetDocument {

    private val renderer = CadRenderer(scene)
    private val visible = scene.layerInitiallyVisible.copyOf()
    private val renderLock = Mutex()
    private var geometry: SnapGeometry? = null
    private var geometryRevision = -1
    private var background = CadBackground.LIGHT

    override val pageSizes = listOf(SheetSize(scene.width, scene.height))

    private val _revision = MutableStateFlow(0)
    override val revision: StateFlow<Int> = _revision.asStateFlow()

    private val _layers = MutableStateFlow(layerList())
    override val layers: StateFlow<List<SheetLayer>> = _layers.asStateFlow()
    override val comments: StateFlow<List<SheetComment>> = MutableStateFlow(emptyList())
    override val dirty: StateFlow<Boolean> = MutableStateFlow(false)
    override val modelUnits = true

    private val _paper = MutableStateFlow(background.paper)
    override val paperColor: StateFlow<Int> = _paper.asStateFlow()
    override val backgroundModes: List<String> = CadBackground.entries.map { ViewerText.get(it.label) }
    override val backgroundMode: Int get() = background.ordinal

    override val notes: List<String> = buildList {
        if (truncated) add(ViewerText.get(R.string.viewer_cad_truncated))
        val unsupported = scene.skipped.filterKeys { it in setOf("3DSOLID", "REGION", "BODY", "SURFACE", "MESH", "OLE2FRAME", "PROXY", "ACAD_PROXY_ENTITY") }
        if (unsupported.isNotEmpty()) {
            add(ViewerText.get(R.string.viewer_cad_skipped, unsupported.entries.joinToString { "${it.key} (${it.value})" }))
        }
    }

    private fun layerList(): List<SheetLayer> = scene.layerNames.mapIndexed { i, name ->
        SheetLayer("layer:$i", name, visible[i], scene.layerColors[i])
    }

    override suspend fun setBackgroundMode(index: Int) {
        background = CadBackground.entries[index.coerceIn(0, CadBackground.entries.size - 1)]
        _paper.value = background.paper
        _revision.value++
    }

    override suspend fun renderPage(index: Int, targetWidth: Int): Bitmap = withContext(Dispatchers.Default) {
        val (w, h) = exactFit(targetWidth)
        renderLock.withLock {
            ensureActive()
            val bitmap = allocate(w, h)
            renderer.render(bitmap, bitmap.width / scene.width, 0f, 0f, visible, background)
            bitmap
        }
    }

    /** Rozmiar bitmapy całego rysunku w budżecie pikseli, z proporcjami sceny. */
    private fun exactFit(width: Int): Pair<Int, Int> {
        var w = width.toDouble()
        var h = w * scene.height / scene.width
        val pixels = w * h
        if (pixels > RenderBudget.MAX_PIXELS) {
            val k = Math.sqrt(RenderBudget.MAX_PIXELS / pixels)
            w *= k
            h *= k
        }
        return max(1, w.toInt()) to max(1, h.toInt())
    }

    override suspend fun renderThumbnail(index: Int, width: Int): Bitmap = renderPage(index, width)

    override suspend fun renderRegion(index: Int, region: RenderBudget.Region): Bitmap = withContext(Dispatchers.Default) {
        renderLock.withLock {
            ensureActive()
            val bitmap = allocate(
                (region.width * region.scale).toInt().coerceAtLeast(1),
                (region.height * region.scale).toInt().coerceAtLeast(1)
            )
            val pixelsPerDisplayPixel = bitmap.width.toFloat() / region.width
            val scale = region.pageWidth.toFloat() / scene.width * pixelsPerDisplayPixel
            renderer.render(
                bitmap, scale,
                region.left * pixelsPerDisplayPixel, region.top * pixelsPerDisplayPixel,
                visible, background
            )
            bitmap
        }
    }

    override val canSearch = true

    /**
     * Szuka w tekstach rysunku (TEXT, MTEXT, atrybuty, napisy wymiarów).
     * Spacje i wielkość liter nie mają znaczenia, bo w CAD bywają różnie.
     */
    override suspend fun search(query: String, onProgress: (Int) -> Unit): List<SheetMatch> = withContext(Dispatchers.Default) {
        val needle = normalize(query)
        if (needle.isEmpty()) return@withContext emptyList()
        val out = ArrayList<SheetMatch>()
        for ((i, t) in scene.texts.withIndex()) {
            if (i % 2048 == 0) ensureActive()
            if (!visible[t.layer]) continue
            if (!normalize(t.text).contains(needle)) continue
            out += SheetMatch(0, listOf(textRect(t)))
        }
        // Od lewego górnego rogu, wierszami — w takiej kolejności się czyta.
        out.sortWith(compareBy({ (it.bounds.top / max(1f, scene.height / 40f)).toInt() }, { it.bounds.left }))
        onProgress(1)
        out
    }

    private fun normalize(text: String) = text.filterNot { it.isWhitespace() }.lowercase()

    /** Obrys tekstu w układzie strony (przybliżony, bez metryki czcionki). */
    private fun textRect(t: SceneText): RectF {
        val longest = t.lines.maxOf { it.length }
        val width = if (t.wrapWidth > 0) min(t.wrapWidth, CadRenderer.approximateWidth("x".repeat(longest), t.height, t.widthFactor))
        else CadRenderer.approximateWidth("x".repeat(longest), t.height, t.widthFactor)
        val height = if (t.lineAdvance > 0) t.height + t.lineAdvance * (t.lines.size - 1) else t.height
        // Prostokąt w układzie tekstu: x w prawo, y w górę od linii bazowej.
        val left = when (t.hAlign) { 1, 4 -> -width / 2; 2 -> -width; else -> 0f }
        val bottom = if (t.lineAdvance > 0) {
            when (t.vAlign) { 3 -> -height; 2 -> -height / 2; else -> 0f }
        } else {
            when { t.hAlign == 4 || t.vAlign == 2 -> -t.height / 2; t.vAlign == 3 -> -t.height; else -> 0f }
        }
        val c = cos(t.rotation)
        val s = sin(t.rotation)
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for ((lx, ly) in listOf(left to bottom, left + width to bottom, left + width to bottom + height, left to bottom + height)) {
            val x = t.x + lx * c - ly * s
            val y = t.y + lx * s + ly * c
            minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
        }
        return RectF(scene.pageX(minX), scene.pageY(maxY), scene.pageX(maxX), scene.pageY(minY))
    }

    override suspend fun snapGeometry(page: Int): SnapGeometry = withContext(Dispatchers.Default) {
        val rev = _revision.value
        geometry?.takeIf { geometryRevision == rev }?.let { return@withContext it }
        scene.snapGeometry(visible).also {
            geometry = it
            geometryRevision = rev
        }
    }

    override suspend fun detectScale(page: Int): SheetScale? {
        val mm = scene.mmPerUnit
        return if (mm != null) {
            SheetScale(mm, unitLabel(mm), ScaleSource.UNITS)
        } else {
            // Rysunek bez jednostek: w polskich projektach to prawie zawsze milimetry.
            SheetScale(1.0, ViewerText.get(R.string.viewer_cad_assumed_mm), ScaleSource.UNITS)
        }
    }

    private fun unitLabel(mm: Double) = when (mm) {
        1.0 -> "mm"
        10.0 -> "cm"
        1000.0 -> "m"
        25.4 -> ViewerText.get(R.string.viewer_unit_inch)
        304.8 -> ViewerText.get(R.string.viewer_unit_foot)
        else -> ViewerText.get(R.string.viewer_mm_per_unit, mm.toString())
    }

    override suspend fun setLayerVisible(id: String, visible: Boolean) {
        val index = id.removePrefix("layer:").toIntOrNull() ?: return
        if (index !in this.visible.indices) return
        this.visible[index] = visible
        _layers.value = layerList()
        _revision.value++
    }

    override suspend fun setAllLayersVisible(visible: Boolean) {
        this.visible.fill(visible)
        _layers.value = layerList()
        _revision.value++
    }

    override fun close() = Unit

    companion object {
        /**
         * Czyta DXF i buduje scenę. Pliki z LibreDWG przychodzą jako binarny DXF —
         * czytnik rozpoznaje format sam.
         */
        suspend fun open(context: Context, file: File, kind: String): CadDocument = withContext(Dispatchers.IO) {
            ViewerText.init(context)
            val builder = CadSceneBuilder()
            val drawing = file.inputStream().use { DxfParser.parse(it, builder::collect) }
            val scene = builder.build(drawing)
            check(scene.strokes.count + scene.fills.count + scene.texts.size > 0) { ViewerText.get(R.string.viewer_cad_empty) }
            CadDocument(scene, kind, builder.truncated)
        }

        private fun allocate(width: Int, height: Int): Bitmap =
            runCatching { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) }
                .recoverCatching { Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565) }
                .getOrThrow()
    }
}
