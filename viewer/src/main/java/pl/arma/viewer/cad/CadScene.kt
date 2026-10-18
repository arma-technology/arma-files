package pl.arma.viewer.cad

import pl.arma.viewer.sheet.SnapGeometry
import java.util.BitSet
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Rysunek zamieniony na to, co da się narysować: łamane, wypełnienia i teksty
 * we współrzędnych świata (oś Y w górę), z rozwiniętymi blokami.
 *
 * Współrzędne trzymamy jako Float względem [originX], [originY] (pierwszy
 * punkt rysunku): rysunki bywają położone setki kilometrów od zera układu,
 * a tam Float gubiłby milimetry.
 */
class CadScene internal constructor(
    val originX: Double,
    val originY: Double,
    /** Zakres rysunku względem początku: minX, minY, maxX, maxY. */
    val bounds: FloatArray,
    val strokes: Shapes,
    val fills: Shapes,
    val texts: List<SceneText>,
    val styles: List<StrokeStyle>,
    val layerNames: List<String>,
    val layerColors: IntArray,
    val layerInitiallyVisible: BooleanArray,
    val skipped: Map<String, Int>,
    /** Milimetrów na jednostkę rysunku z $INSUNITS; null, gdy bez jednostek. */
    val mmPerUnit: Double?
) {
    val width: Float get() = bounds[2] - bounds[0]
    val height: Float get() = bounds[3] - bounds[1]

    /** Punkt sceny → punkt strony (od lewego górnego rogu, Y w dół). */
    fun pageX(x: Float) = x - bounds[0]
    fun pageY(y: Float) = bounds[3] - y

    /** Linie do przyciągania w układzie strony; kreskowania i teksty pomijamy. */
    fun snapGeometry(visible: BooleanArray): SnapGeometry {
        val b = SnapGeometry.Builder()
        val s = strokes
        for (i in 0 until s.count) {
            if (!visible[s.layer[i]]) continue
            val f = s.flags[i].toInt()
            if (f and Shapes.NO_SNAP != 0) continue
            val start = s.start[i]
            val n = s.length[i]
            if (f and Shapes.DOT != 0) {
                b.center(pageX(s.coords[2 * start]), pageY(s.coords[2 * start + 1]))
                continue
            }
            for (k in 0 until n - 1) {
                val p = start + k
                b.segment(
                    pageX(s.coords[2 * p]), pageY(s.coords[2 * p + 1]),
                    pageX(s.coords[2 * p + 2]), pageY(s.coords[2 * p + 3]),
                    s.vertex[p], s.vertex[p + 1]
                )
            }
            if (f and Shapes.CLOSED != 0 && n > 2) {
                val a = start + n - 1
                b.segment(
                    pageX(s.coords[2 * a]), pageY(s.coords[2 * a + 1]),
                    pageX(s.coords[2 * start]), pageY(s.coords[2 * start + 1]),
                    s.vertex[a], s.vertex[start]
                )
            }
        }
        for (c in centers.indices step 2) b.center(pageX(centers[c]), pageY(centers[c + 1]))
        for (c in insertions.indices step 2) b.insertion(pageX(insertions[c]), pageY(insertions[c + 1]))
        return b.build()
    }

    /** Środki okręgów i łuków (w układzie sceny), do przyciągania. */
    internal var centers: FloatArray = FloatArray(0)

    /** Punkty wstawienia bloków (w układzie sceny), do przyciągania. */
    internal var insertions: FloatArray = FloatArray(0)
}

/** Styl linii: kolor, grubość i wzór kresek w jednostkach rysunku. */
data class StrokeStyle(
    /** ARGB; [FOREGROUND] znaczy „biały na ciemnym, czarny na jasnym” (ACI 7). */
    val color: Int,
    val widthMm: Float,
    val dashes: FloatArray?
) {
    override fun equals(other: Any?): Boolean =
        other is StrokeStyle && color == other.color && widthMm == other.widthMm &&
            (dashes?.contentEquals(other.dashes) ?: (other.dashes == null))

    override fun hashCode(): Int = (color * 31 + widthMm.hashCode()) * 31 + (dashes?.contentHashCode() ?: 0)

    companion object {
        const val FOREGROUND = 0
        const val BACKGROUND = 1
    }
}

class SceneText(
    /** Punkt zaczepienia w układzie sceny. */
    val x: Float,
    val y: Float,
    /** Drugi punkt dla tekstu wyrównanego/dopasowanego między dwoma punktami. */
    val x2: Float,
    val y2: Float,
    val height: Float,
    /** Obrót w radianach, przeciwnie do wskazówek zegara (układ świata). */
    val rotation: Float,
    val widthFactor: Float,
    val skew: Float,
    val lines: List<String>,
    val lineAdvance: Float,
    /** Szerokość zawijania MTEXT; 0 bez zawijania. */
    val wrapWidth: Float,
    /** 0 lewo, 1 środek, 2 prawo, 3 wyrównany, 5 dopasowany. */
    val hAlign: Int,
    /** 0 linia bazowa, 1 dół, 2 środek, 3 góra. */
    val vAlign: Int,
    val color: Int,
    val layer: Int
) {
    val text: String get() = lines.joinToString("\n")
}

/** Łamane w jednej tablicy: indeksy początków, długości i atrybuty. */
class Shapes {
    var coords = FloatArray(1 shl 12)
    var vertex = BitSet()
    var points = 0
    var count = 0
    var start = IntArray(256)
    var length = IntArray(256)
    var style = IntArray(256)
    var layer = IntArray(256)
    var flags = ByteArray(256)
    var box = FloatArray(1024)

    fun begin(styleIndex: Int, layerIndex: Int, flag: Int) {
        if (count == start.size) {
            val n = count * 2
            start = start.copyOf(n); length = length.copyOf(n); style = style.copyOf(n)
            layer = layer.copyOf(n); flags = flags.copyOf(n); box = box.copyOf(n * 4)
        }
        start[count] = points
        length[count] = 0
        style[count] = styleIndex
        layer[count] = layerIndex
        flags[count] = flag.toByte()
        box[4 * count] = Float.MAX_VALUE
        box[4 * count + 1] = Float.MAX_VALUE
        box[4 * count + 2] = -Float.MAX_VALUE
        box[4 * count + 3] = -Float.MAX_VALUE
    }

    fun point(x: Float, y: Float, isVertex: Boolean) {
        if (points * 2 + 2 > coords.size) coords = coords.copyOf(max(coords.size * 2, 64))
        coords[points * 2] = x
        coords[points * 2 + 1] = y
        if (isVertex) vertex.set(points)
        points++
        length[count]++
        val b = 4 * count
        if (x < box[b]) box[b] = x
        if (y < box[b + 1]) box[b + 1] = y
        if (x > box[b + 2]) box[b + 2] = x
        if (y > box[b + 3]) box[b + 3] = y
    }

    /** Zamyka bieżący kształt; pusty albo jednopunktowy (poza kropką) odrzuca. */
    fun end() {
        val n = length[count]
        val dot = flags[count].toInt() and DOT != 0
        if (n == 0 || (n == 1 && !dot)) {
            points -= n
            return
        }
        count++
    }

    fun trim() {
        coords = coords.copyOf(points * 2)
    }

    companion object {
        const val CLOSED = 1
        const val NO_SNAP = 2
        const val DOT = 4
        const val WIPEOUT = 8
        const val CURVE = 16
    }
}

/**
 * Budowa sceny z rysunku: rozwijanie bloków (z tablicami MINSERT),
 * dziedziczenie koloru, warstwy i rodzaju linii, układy obiektów (OCS),
 * kreskowania wzorem i teksty.
 */
class CadSceneBuilder(private val maxShapePoints: Long = 12_000_000) {
    private val strokes = Shapes()
    private val fills = Shapes()
    private val texts = ArrayList<SceneText>()
    private val styles = ArrayList<StrokeStyle>()
    private val styleIndex = HashMap<StrokeStyle, Int>()
    private val layerIndex = LinkedHashMap<String, Int>()
    private val centers = ArrayList<Float>()
    private val insertions = ArrayList<Float>()
    private var originSet = false
    private var ox = 0.0
    private var oy = 0.0
    private var drawing: CadDrawing? = null
    private val pendingModel = ArrayList<CadEntity>()
    var truncated = false
        private set

    /** Kontekst bloku: przekształcenie i właściwości dziedziczone przez BYBLOCK. */
    private class Context(
        val m: DoubleArray, // a, b, c, d, e, f: x' = a x + c y + e; y' = b x + d y + f
        val layer: String?,
        val color: Int, // ARGB albo FOREGROUND
        val lineType: String,
        val lineWeight: Int,
        val depth: Int
    )

    private val identity = doubleArrayOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)

    /**
     * Obiekty modelu przychodzą w trakcie czytania pliku, zanim znamy bloki
     * i warstwy — te są wcześniej w pliku, ale nie mamy jeszcze całego
     * rysunku. Zbieramy je więc i budujemy scenę po przeczytaniu.
     */
    fun collect(entity: CadEntity) {
        pendingModel += entity
    }

    fun build(d: CadDrawing): CadScene {
        drawing = d
        CadGeometry.tolerance = tolerance(d.header).takeIf { !it.isNaN() } ?: toleranceFromEntities()
        dimensionTextLimit = dimensionTextLimit(d)
        for (layer in d.layers.keys) layerIndexOf(layer)
        val root = Context(identity, null, StrokeStyle.FOREGROUND, "CONTINUOUS", 25, 0)
        for (entity in pendingModel) {
            if (truncated) break
            emit(entity, root)
        }
        pendingModel.clear()
        CadGeometry.tolerance = Double.NaN
        strokes.trim()
        fills.trim()
        return finish(d)
    }

    /**
     * Odchyłka łuków: 1/50000 przekątnej rysunku z nagłówka. Przy pełnym
     * powiększeniu na telefonie to kilka pikseli, a ogranicza liczbę punktów.
     */
    private fun tolerance(h: CadHeader): Double {
        val min = h.extMin ?: return Double.NaN
        val max = h.extMax ?: return Double.NaN
        val w = max[0] - min[0]
        val hh = max[1] - min[1]
        if (!(w.isFinite() && hh.isFinite()) || w <= 0 || hh <= 0 || w > 1e9 || hh > 1e9) return Double.NaN
        return hypot(w, hh) / 50_000
    }

    /**
     * Gdy nagłówek nie ma zakresu (AutoCAD zapisuje wtedy ±1e20), liczymy go
     * z punktów charakterystycznych obiektów — środkowe 98%, żeby pojedyncze
     * zabłąkane obiekty nie rozdęły wyniku.
     */
    private fun toleranceFromEntities(): Double {
        val xs = Doubles()
        val ys = Doubles()
        val stride = max(1, pendingModel.size / 50_000)
        for (i in pendingModel.indices step stride) {
            when (val e = pendingModel[i]) {
                is CadLine -> { xs += e.x1; ys += e.y1 }
                is CadCircle -> { xs += e.cx; ys += e.cy }
                is CadArc -> { xs += e.cx; ys += e.cy }
                is CadEllipse -> { xs += e.cx; ys += e.cy }
                is CadPolyline -> if (e.xs.isNotEmpty()) { xs += e.xs[0]; ys += e.ys[0] }
                is CadSpline -> if (e.controlX.isNotEmpty()) { xs += e.controlX[0]; ys += e.controlY[0] }
                is CadInsert -> { xs += e.x; ys += e.y }
                is CadText -> { xs += e.x; ys += e.y }
                else -> Unit
            }
        }
        if (xs.size < 2) return Double.NaN
        val sx = xs.toArray().also { it.sort() }
        val sy = ys.toArray().also { it.sort() }
        val lo = (sx.size * .01).toInt()
        val hi = (sx.size * .99).toInt().coerceAtMost(sx.size - 1)
        val w = sx[hi] - sx[lo]
        val h = sy[hi] - sy[lo]
        if (w <= 0 && h <= 0) return Double.NaN
        return hypot(w, h) / 50_000
    }

    private var dimensionTextLimit = Double.MAX_VALUE
    private var inDimension = 0

    /**
     * Bloki wymiarów (*D…) z niektórych plików mają napisy o wysokości setki
     * razy większej niż pozostałe wymiary — program CAD i tak przelicza je ze
     * stylu wymiarowania, my rysujemy blok. Napisy wymiarów wyższe niż
     * czterokrotna mediana sprowadzamy do mediany.
     */
    private fun dimensionTextLimit(d: CadDrawing): Double {
        val heights = Doubles()
        for (block in d.blocks.values) {
            if (!block.name.startsWith("*D", ignoreCase = true)) continue
            for (e in block.entities) {
                when (e) {
                    is CadMText -> heights += e.height
                    is CadText -> heights += e.height
                    else -> Unit
                }
            }
        }
        if (heights.size < 5) return Double.MAX_VALUE
        val sorted = heights.toArray().also { it.sort() }
        return sorted[sorted.size / 2] * 4
    }

    private fun textHeight(h: Double): Double =
        if (inDimension > 0 && h > dimensionTextLimit) dimensionTextLimit / 4 else h

    // ---- właściwości ------------------------------------------------------

    private fun effectiveLayer(e: CadEntity, ctx: Context): String =
        if (e.layer == "0" && ctx.layer != null) ctx.layer else e.layer

    private fun layerIndexOf(name: String): Int = layerIndex.getOrPut(name) { layerIndex.size }

    private fun resolveColor(e: CadEntity, layer: String, ctx: Context): Int {
        val opaque = resolveOpaque(e, layer, ctx)
        if (e.alpha >= 255 || opaque == StrokeStyle.FOREGROUND) return opaque
        // Półprzezroczyste kreskowania: bez tego pełne kolorowe płaszczyzny
        // zasłaniają rysunek pod sobą.
        return (opaque and 0x00FFFFFF) or (e.alpha.coerceIn(20, 255) shl 24)
    }

    private fun resolveOpaque(e: CadEntity, layer: String, ctx: Context): Int {
        e.trueColor?.let { return 0xFF000000.toInt() or it }
        return when (e.color) {
            0 -> ctx.color
            256 -> drawing?.layers?.get(layer)?.let { l ->
                l.trueColor?.let { 0xFF000000.toInt() or it } ?: aci(l.color)
            } ?: StrokeStyle.FOREGROUND
            else -> aci(e.color)
        }
    }

    private fun aci(index: Int): Int = when (index) {
        7, 255 -> StrokeStyle.FOREGROUND
        else -> 0xFF000000.toInt() or Aci.rgb(index)
    }

    private fun resolveLineType(e: CadEntity, layer: String, ctx: Context): String = when (e.lineType.uppercase()) {
        "BYLAYER" -> drawing?.layers?.get(layer)?.lineType ?: "CONTINUOUS"
        "BYBLOCK" -> ctx.lineType
        else -> e.lineType
    }

    private fun resolveWeight(e: CadEntity, layer: String, ctx: Context): Int = when (e.lineWeight) {
        -1 -> drawing?.layers?.get(layer)?.lineWeight?.takeIf { it >= 0 } ?: 25
        -2 -> ctx.lineWeight
        -3 -> 25
        else -> e.lineWeight
    }

    private fun style(e: CadEntity, layer: String, ctx: Context): Int {
        val color = resolveColor(e, layer, ctx)
        val lineType = resolveLineType(e, layer, ctx)
        val pattern = drawing?.lineTypes?.get(lineType.uppercase())?.pattern
        val scale = (drawing?.header?.lineTypeScale ?: 1.0) * e.lineTypeScale
        val dashes = pattern?.takeIf { it.size >= 2 && it.any { v -> v < 0 } }?.let { p ->
            FloatArray(p.size) { (p[it] * scale).toFloat() }
        }
        val key = StrokeStyle(color, resolveWeight(e, layer, ctx) / 100f, dashes)
        return styleIndex.getOrPut(key) { styles += key; styles.size - 1 }
    }

    // ---- przekształcenia --------------------------------------------------

    private fun multiply(p: DoubleArray, q: DoubleArray): DoubleArray = doubleArrayOf(
        p[0] * q[0] + p[2] * q[1],
        p[1] * q[0] + p[3] * q[1],
        p[0] * q[2] + p[2] * q[3],
        p[1] * q[2] + p[3] * q[3],
        p[0] * q[4] + p[2] * q[5] + p[4],
        p[1] * q[4] + p[3] * q[5] + p[5]
    )

    /** Układ obiektu (OCS) rzutowany na płaszczyznę XY — algorytm „arbitrary axis”. */
    private fun ocs(e: CadEntity): DoubleArray? {
        if (!e.hasOcs) return null
        val len = sqrt(e.ex * e.ex + e.ey * e.ey + e.ez * e.ez)
        if (len == 0.0) return null
        val nx = e.ex / len; val ny = e.ey / len; val nz = e.ez / len
        // Ax = (|Nx| < 1/64 i |Ny| < 1/64) ? Wy × N : Wz × N
        var ax: Double; var ay: Double; var az: Double
        if (abs(nx) < 1.0 / 64 && abs(ny) < 1.0 / 64) {
            ax = nz; ay = 0.0; az = -nx // (0,1,0) × N
        } else {
            ax = -ny; ay = nx; az = 0.0 // (0,0,1) × N
        }
        val al = sqrt(ax * ax + ay * ay + az * az)
        ax /= al; ay /= al; az /= al
        // Ay = N × Ax
        val bx = ny * az - nz * ay
        val by = nz * ax - nx * az
        return doubleArrayOf(ax, ay, bx, by, 0.0, 0.0)
    }

    private inner class Emitter(val target: Shapes, val m: DoubleArray) : PointSink {
        override fun add(x: Double, y: Double, vertex: Boolean) {
            val wx = m[0] * x + m[2] * y + m[4]
            val wy = m[1] * x + m[3] * y + m[5]
            if (!originSet) {
                ox = wx; oy = wy; originSet = true
            }
            target.point((wx - ox).toFloat(), (wy - oy).toFloat(), vertex)
        }
    }

    private fun budget(): Boolean {
        if (strokes.points + fills.points > maxShapePoints) {
            truncated = true
            return false
        }
        return true
    }

    private fun insertion(m: DoubleArray, x: Double, y: Double) {
        val wx = m[0] * x + m[2] * y + m[4]
        val wy = m[1] * x + m[3] * y + m[5]
        if (!originSet) { ox = wx; oy = wy; originSet = true }
        insertions += (wx - ox).toFloat()
        insertions += (wy - oy).toFloat()
    }

    private fun center(m: DoubleArray, x: Double, y: Double) {
        val wx = m[0] * x + m[2] * y + m[4]
        val wy = m[1] * x + m[3] * y + m[5]
        if (!originSet) { ox = wx; oy = wy; originSet = true }
        centers += (wx - ox).toFloat()
        centers += (wy - oy).toFloat()
    }

    // ---- obiekty ----------------------------------------------------------

    private fun emit(e: CadEntity, ctx: Context) {
        if (e.invisible || !budget()) return
        val layer = effectiveLayer(e, ctx)
        val li = layerIndexOf(layer)
        val local = ocs(e)?.let { multiply(ctx.m, it) } ?: ctx.m
        when (e) {
            is CadLine -> stroke(e, layer, li, ctx, ctx.m, 0) { s ->
                s.add(e.x1, e.y1, true); s.add(e.x2, e.y2, true)
            }
            is CadPoint -> {
                strokes.begin(style(e, layer, ctx), li, Shapes.DOT or Shapes.NO_SNAP)
                Emitter(strokes, ctx.m).add(e.x, e.y, true)
                strokes.end()
                center(ctx.m, e.x, e.y)
            }
            is CadCircle -> {
                stroke(e, layer, li, ctx, local, Shapes.CLOSED or Shapes.CURVE) { s ->
                    CadGeometry.ellipseArc(e.cx, e.cy, e.r, e.r, 0.0, 0.0, 2 * Math.PI, s)
                }
                center(local, e.cx, e.cy)
            }
            is CadArc -> {
                stroke(e, layer, li, ctx, local, Shapes.CURVE) { s -> CadGeometry.arc(e.cx, e.cy, e.r, e.start, e.end, s) }
                center(local, e.cx, e.cy)
            }
            is CadEllipse -> {
                stroke(e, layer, li, ctx, ctx.m, Shapes.CURVE) { s -> CadGeometry.ellipse(e, s) }
                center(ctx.m, e.cx, e.cy)
            }
            is CadPolyline -> {
                val m = if (e.world) ctx.m else local
                if (e.width > 0 && e.xs.size >= 2) {
                    widePolyline(e, layer, li, ctx, m)
                } else {
                    stroke(e, layer, li, ctx, m, if (e.closed) Shapes.CLOSED else 0) { s ->
                        CadGeometry.bulgePolyline(e.xs, e.ys, e.bulges, false, s)
                    }
                }
                bulgeCenters(e, m)
            }
            is CadSpline -> stroke(e, layer, li, ctx, ctx.m, Shapes.CURVE or if (e.closed) Shapes.CLOSED else 0) { s ->
                if (e.controlX.isNotEmpty()) CadGeometry.nurbs(e.degree, e.knots, e.controlX, e.controlY, e.weights, s)
                else CadGeometry.throughPoints(e.fitX, e.fitY, e.closed, s)
            }
            is CadLeader -> {
                stroke(e, layer, li, ctx, ctx.m, 0) { s -> for (k in e.xs.indices) s.add(e.xs[k], e.ys[k], true) }
                if (e.arrow && e.xs.size >= 2) arrowHead(e, layer, li, ctx)
            }
            is CadSolid -> {
                val n = if (e.xs[2] == e.xs[3] && e.ys[2] == e.ys[3]) 3 else 4
                if (e.filled) {
                    fills.begin(style(e, layer, ctx), li, Shapes.CLOSED)
                    val emitter = Emitter(fills, local)
                    for (k in 0 until n) emitter.add(e.xs[k], e.ys[k], true)
                    fills.end()
                } else {
                    stroke(e, layer, li, ctx, ctx.m, Shapes.CLOSED) { s -> for (k in 0 until n) s.add(e.xs[k], e.ys[k], true) }
                }
            }
            is CadWipeout -> {
                fills.begin(style(e, layer, ctx), li, Shapes.CLOSED or Shapes.WIPEOUT)
                val emitter = Emitter(fills, ctx.m)
                for (k in e.xs.indices) emitter.add(e.xs[k], e.ys[k], true)
                fills.end()
            }
            is CadHatch -> hatch(e, layer, li, ctx, local)
            is CadText -> text(e, layer, li, ctx, local)
            is CadMText -> mtext(e, layer, li, ctx)
            is CadInsert -> insert(e, layer, ctx, local)
            is CadDimension -> {
                val block = drawing?.blocks?.get(e.block.uppercase()) ?: return
                inDimension++
                try {
                    expand(block, ctx.m, layer, resolveColor(e, layer, ctx), e, ctx)
                } finally {
                    inDimension--
                }
            }
        }
    }

    private inline fun stroke(
        e: CadEntity, layer: String, li: Int, ctx: Context, m: DoubleArray, flag: Int, body: (PointSink) -> Unit
    ) {
        strokes.begin(style(e, layer, ctx), li, flag)
        body(Emitter(strokes, m))
        strokes.end()
    }

    /** Polilinia o stałej szerokości: pasek wypełniony wzdłuż osi. */
    private fun widePolyline(e: CadPolyline, layer: String, li: Int, ctx: Context, m: DoubleArray) {
        val axis = Doubles()
        CadGeometry.bulgePolyline(e.xs, e.ys, e.bulges, e.closed, axis)
        val half = e.width / 2
        val st = style(e, layer, ctx)
        for (k in 0 until axis.size / 2 - 1) {
            val x1 = axis[2 * k]; val y1 = axis[2 * k + 1]
            val x2 = axis[2 * k + 2]; val y2 = axis[2 * k + 3]
            val len = hypot(x2 - x1, y2 - y1)
            if (len == 0.0) continue
            val nx = -(y2 - y1) / len * half
            val ny = (x2 - x1) / len * half
            fills.begin(st, li, Shapes.CLOSED)
            val emitter = Emitter(fills, m)
            emitter.add(x1 + nx, y1 + ny, true)
            emitter.add(x2 + nx, y2 + ny, true)
            emitter.add(x2 - nx, y2 - ny, true)
            emitter.add(x1 - nx, y1 - ny, true)
            fills.end()
        }
        // Oś paska też do przyciągania i jako cienka linia przy oddaleniu.
        stroke(e, layer, li, ctx, m, if (e.closed) Shapes.CLOSED else 0) { s ->
            CadGeometry.bulgePolyline(e.xs, e.ys, e.bulges, false, s)
        }
    }

    private fun arrowHead(e: CadLeader, layer: String, li: Int, ctx: Context) {
        val x0 = e.xs[0]; val y0 = e.ys[0]
        val dx = e.xs[1] - x0; val dy = e.ys[1] - y0
        val len = hypot(dx, dy)
        if (len == 0.0) return
        val size = min(len / 3, 2.5)
        val ux = dx / len; val uy = dy / len
        fills.begin(style(e, layer, ctx), li, Shapes.CLOSED or Shapes.NO_SNAP)
        val emitter = Emitter(fills, ctx.m)
        emitter.add(x0, y0, true)
        emitter.add(x0 + ux * size - uy * size / 3, y0 + uy * size + ux * size / 3, true)
        emitter.add(x0 + ux * size + uy * size / 3, y0 + uy * size - ux * size / 3, true)
        fills.end()
    }

    /** Środki łuków polilinii — kropka wymiaru i okrągły słup to często polilinia z łukami. */
    private fun bulgeCenters(e: CadPolyline, m: DoubleArray) {
        val n = e.xs.size
        val last = if (e.closed) n else n - 1
        for (i in 0 until last) {
            val b = e.bulges.getOrNull(i) ?: continue
            if (kotlin.math.abs(b) < 1e-9) continue
            val j = (i + 1) % n
            val chord = hypot(e.xs[j] - e.xs[i], e.ys[j] - e.ys[i])
            if (chord < 1e-12) continue
            val theta = 4 * kotlin.math.atan(b)
            val d = chord / (2 * sin(theta / 2)) * cos(theta / 2)
            val cx = (e.xs[i] + e.xs[j]) / 2 - (e.ys[j] - e.ys[i]) / chord * d
            val cy = (e.ys[i] + e.ys[j]) / 2 + (e.xs[j] - e.xs[i]) / chord * d
            // Kolejne łuki tego samego okręgu dałyby ten sam środek kilka razy.
            val k = centers.size
            if (k >= 2 && kotlin.math.abs(centers[k - 2] - (m[0] * cx + m[2] * cy + m[4] - ox).toFloat()) < 1e-3f &&
                kotlin.math.abs(centers[k - 1] - (m[1] * cx + m[3] * cy + m[5] - oy).toFloat()) < 1e-3f
            ) continue
            center(m, cx, cy)
        }
    }

    private fun insert(e: CadInsert, layer: String, ctx: Context, local: DoubleArray) {
        val block = drawing?.blocks?.get(e.block.uppercase()) ?: return
        if (ctx.depth > MAX_DEPTH) return
        insertion(local, e.x, e.y)
        val rot = Math.toRadians(e.rotation)
        val cr = cos(rot)
        val sr = sin(rot)
        val color = resolveColor(e, layer, ctx)
        for (row in 0 until e.rows) {
            for (column in 0 until e.columns) {
                // M = OCS·T(wstawienie)·R(obrót)·T(tablica)·S(skala)·T(−baza)
                val tx = column * e.columnSpacing
                val ty = row * e.rowSpacing
                val m = doubleArrayOf(
                    cr * e.sx, sr * e.sx,
                    -sr * e.sy, cr * e.sy,
                    e.x + cr * (tx - block.baseX * e.sx) - sr * (ty - block.baseY * e.sy),
                    e.y + sr * (tx - block.baseX * e.sx) + cr * (ty - block.baseY * e.sy)
                )
                expand(block, multiply(local, m), layer, color, e, ctx)
                if (truncated) return
            }
        }
        for (attribute in e.attributes) emit(attribute, ctx)
    }

    private fun expand(block: CadBlock, m: DoubleArray, layer: String, color: Int, owner: CadEntity, ctx: Context) {
        if (ctx.depth > MAX_DEPTH) return
        val child = Context(
            m = m,
            layer = layer,
            color = color,
            lineType = resolveLineType(owner, layer, ctx),
            lineWeight = resolveWeight(owner, layer, ctx),
            depth = ctx.depth + 1
        )
        for (entity in block.entities) {
            emit(entity, child)
            if (truncated) return
        }
    }

    // ---- kreskowania ------------------------------------------------------

    private fun hatch(e: CadHatch, layer: String, li: Int, ctx: Context, m: DoubleArray) {
        if (e.loops.isEmpty()) return
        val st = style(e, layer, ctx)
        if (e.solid) {
            // Wszystkie kontury w jednym kształcie: otwory wycina reguła parzystości.
            for (loop in e.loops) {
                fills.begin(st, li, Shapes.CLOSED or Shapes.NO_SNAP)
                val emitter = Emitter(fills, m)
                for (k in 0 until loop.size / 2) emitter.add(loop[2 * k], loop[2 * k + 1], false)
                fills.end()
                if (fills.count > 0) fills.flags[fills.count - 1] = (fills.flags[fills.count - 1].toInt() or HOLE_GROUP).toByte()
            }
            // Pierwszy kontur grupy otwiera ją, kolejne do niej należą.
            markGroupStart(e.loops.size)
            return
        }
        // Kontur zawsze, wzór — gdy da się go policzyć w rozsądnym czasie.
        for (loop in e.loops) {
            strokes.begin(st, li, Shapes.CLOSED or Shapes.NO_SNAP)
            val emitter = Emitter(strokes, m)
            for (k in 0 until loop.size / 2) emitter.add(loop[2 * k], loop[2 * k + 1], false)
            strokes.end()
        }
        val emitter = Emitter(strokes, m)
        var produced = 0
        for (line in e.pattern) {
            produced += patternLines(line, e.loops, st, li, emitter, MAX_HATCH_SEGMENTS - produced)
            if (produced >= MAX_HATCH_SEGMENTS) break
        }
    }

    private fun markGroupStart(loops: Int) {
        val first = fills.count - loops
        if (first >= 0) fills.flags[first] = (fills.flags[first].toInt() or GROUP_START).toByte()
    }

    /**
     * Jedna rodzina linii wzoru przycięta do konturów regułą parzystości,
     * z kreskami według definicji. Zwraca liczbę dodanych odcinków.
     */
    private fun patternLines(
        line: HatchLine, loops: List<DoubleArray>, st: Int, li: Int, emitter: Emitter, budget: Int
    ): Int {
        val a = Math.toRadians(line.angle)
        val dx = cos(a); val dy = sin(a)
        val nx = -dy; val ny = dx
        val spacing = line.offsetX * nx + line.offsetY * ny
        if (abs(spacing) < 1e-9 || budget <= 0) return 0
        val shift = line.offsetX * dx + line.offsetY * dy
        var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
        for (loop in loops) for (k in 0 until loop.size / 2) {
            val s = (loop[2 * k] - line.baseX) * nx + (loop[2 * k + 1] - line.baseY) * ny
            lo = min(lo, s); hi = max(hi, s)
        }
        val k0 = floor(min(lo, hi) / spacing).toInt()
        val k1 = ceil(max(lo, hi) / spacing).toInt()
        val first = min(k0, k1)
        val last = max(k0, k1)
        if (last - first > MAX_HATCH_LINES) return 0
        val period = line.dashes.sumOf { abs(it) }
        var produced = 0
        val hits = Doubles()
        for (k in first..last) {
            val px = line.baseX + k * line.offsetX
            val py = line.baseY + k * line.offsetY
            hits.clear()
            for (loop in loops) {
                val n = loop.size / 2
                for (i in 0 until n) {
                    val j = (i + 1) % n
                    val ax = loop[2 * i]; val ay = loop[2 * i + 1]
                    val bx = loop[2 * j]; val by = loop[2 * j + 1]
                    val sa = (ax - px) * nx + (ay - py) * ny
                    val sb = (bx - px) * nx + (by - py) * ny
                    if ((sa > 0) == (sb > 0)) continue
                    val t = sa / (sa - sb)
                    val ix = ax + (bx - ax) * t
                    val iy = ay + (by - ay) * t
                    hits += (ix - px) * dx + (iy - py) * dy
                }
            }
            if (hits.size < 2) continue
            val sorted = hits.toArray().also { it.sort() }
            var h = 0
            while (h + 1 < sorted.size) {
                val t0 = sorted[h]; val t1 = sorted[h + 1]
                h += 2
                if (period <= 0 || line.dashes.isEmpty()) {
                    segment(emitter, st, li, px + dx * t0, py + dy * t0, px + dx * t1, py + dy * t1)
                    produced++
                } else {
                    // Faza kresek liczona od punktu bazowego linii, przesunięta
                    // o składową odstępu wzdłuż linii.
                    val phase = k * shift
                    var cycle = floor((t0 - phase) / period) * period + phase
                    while (cycle < t1 && produced < budget) {
                        var cursor = cycle
                        for (d in line.dashes) {
                            val len = abs(d)
                            if (d >= 0) {
                                val s0 = max(cursor, t0); val s1 = min(cursor + max(len, period * 1e-3), t1)
                                if (s1 > s0) {
                                    segment(emitter, st, li, px + dx * s0, py + dy * s0, px + dx * s1, py + dy * s1)
                                    produced++
                                }
                            }
                            cursor += len
                        }
                        cycle += period
                    }
                }
                if (produced >= budget) return produced
            }
        }
        return produced
    }

    private fun segment(emitter: Emitter, st: Int, li: Int, x1: Double, y1: Double, x2: Double, y2: Double) {
        strokes.begin(st, li, Shapes.NO_SNAP)
        emitter.add(x1, y1, false)
        emitter.add(x2, y2, false)
        strokes.end()
    }

    // ---- teksty -----------------------------------------------------------

    private fun transformText(m: DoubleArray, x: Double, y: Double): Pair<Float, Float> {
        val wx = m[0] * x + m[2] * y + m[4]
        val wy = m[1] * x + m[3] * y + m[5]
        if (!originSet) { ox = wx; oy = wy; originSet = true }
        return (wx - ox).toFloat() to (wy - oy).toFloat()
    }

    private fun text(e: CadText, layer: String, li: Int, ctx: Context, m: DoubleArray) {
        if (e.text.isBlank()) return
        val useAlign = (e.hAlign != 0 || e.vAlign != 0) && e.ax != null && e.ay != null
        val anchorX = if (useAlign) e.ax!! else e.x
        val anchorY = if (useAlign) e.ay!! else e.y
        val (x, y) = transformText(m, anchorX, anchorY)
        val (x0, y0) = transformText(m, e.x, e.y)
        val rot = Math.toRadians(e.rotation)
        // Kierunek i skala osi tekstu po przekształceniu bloku.
        val ux = m[0] * cos(rot) + m[2] * sin(rot)
        val uy = m[1] * cos(rot) + m[3] * sin(rot)
        val vx = -m[0] * sin(rot) + m[2] * cos(rot)
        val vy = -m[1] * sin(rot) + m[3] * cos(rot)
        val scaleV = hypot(vx, vy)
        val scaleU = hypot(ux, uy)
        val styleWidth = drawing?.styles?.get(e.style.uppercase())?.widthFactor ?: 1.0
        texts += SceneText(
            x = x, y = y,
            x2 = if (e.hAlign == 3 || e.hAlign == 5) x0 else x, y2 = if (e.hAlign == 3 || e.hAlign == 5) y0 else y,
            height = (textHeight(e.height) * scaleV).toFloat(),
            rotation = atan2(uy, ux).toFloat(),
            widthFactor = (e.widthFactor * (if (e.widthFactor == 1.0) styleWidth else 1.0) * scaleU / max(scaleV, 1e-12)).toFloat(),
            skew = CadGeometry.obliqueSkew(e.oblique).toFloat(),
            lines = listOf(e.text),
            lineAdvance = 0f,
            wrapWidth = 0f,
            hAlign = e.hAlign,
            vAlign = e.vAlign,
            color = resolveColor(e, layer, ctx),
            layer = li
        )
    }

    private fun mtext(e: CadMText, layer: String, li: Int, ctx: Context) {
        if (e.text.isBlank()) return
        val m = ctx.m
        val (x, y) = transformText(m, e.x, e.y)
        val ux = m[0] * cos(e.rotation) + m[2] * sin(e.rotation)
        val uy = m[1] * cos(e.rotation) + m[3] * sin(e.rotation)
        val scale = hypot(ux, uy)
        val attachment = (e.attachment - 1).coerceIn(0, 8)
        val height = (textHeight(e.height) * scale).toFloat()
        texts += SceneText(
            x = x, y = y, x2 = x, y2 = y,
            height = height,
            rotation = atan2(uy, ux).toFloat(),
            widthFactor = (drawing?.styles?.get(e.style.uppercase())?.widthFactor ?: 1.0).toFloat(),
            skew = 0f,
            lines = e.text.split('\n'),
            // Odstęp linii MTEXT to 5/3 wysokości razy współczynnik.
            lineAdvance = (height * 5f / 3f * e.lineSpacing.toFloat()),
            wrapWidth = (e.width * scale).toFloat(),
            hAlign = attachment % 3,
            // Kotwica MTEXT: 1–3 góra, 4–6 środek, 7–9 dół.
            vAlign = when (attachment / 3) { 0 -> 3; 1 -> 2; else -> 1 },
            color = resolveColor(e, layer, ctx),
            layer = li
        )
    }

    // ---- wynik ------------------------------------------------------------

    private fun finish(d: CadDrawing): CadScene {
        val names = layerIndex.keys.toList()
        val colors = IntArray(names.size) { i ->
            d.layers[names[i]]?.let { l -> 0xFF000000.toInt() or (l.trueColor ?: Aci.rgb(l.color)) } ?: 0xFF000000.toInt()
        }
        val visible = BooleanArray(names.size) { i -> d.layers[names[i]]?.let { !it.off && !it.frozen } ?: true }
        val bounds = extents(visible)
        val scene = CadScene(
            originX = ox, originY = oy,
            bounds = bounds,
            strokes = strokes, fills = fills, texts = texts,
            styles = styles, layerNames = names, layerColors = colors,
            layerInitiallyVisible = visible,
            skipped = d.skipped,
            mmPerUnit = unitsToMm(d.header.insUnits)
        )
        scene.centers = centers.toFloatArray()
        scene.insertions = insertions.toFloatArray()
        return scene
    }

    /**
     * Zakres rysunku. Pojedyncze obiekty daleko od reszty (zabłąkany punkt
     * w odległości kilometrów) zmniejszyłyby rysunek do kropki — gdy pełny
     * zakres jest wielokrotnie większy od zakresu 99% obiektów, bierzemy ten drugi.
     */
    private fun extents(visible: BooleanArray): FloatArray {
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        fun add(shapes: Shapes) {
            val stride = max(1, shapes.count / 20_000)
            for (i in 0 until shapes.count) {
                if (!visible[shapes.layer[i]]) continue
                val b = 4 * i
                minX = min(minX, shapes.box[b]); minY = min(minY, shapes.box[b + 1])
                maxX = max(maxX, shapes.box[b + 2]); maxY = max(maxY, shapes.box[b + 3])
                if (i % stride == 0) {
                    xs += (shapes.box[b] + shapes.box[b + 2]) / 2
                    ys += (shapes.box[b + 1] + shapes.box[b + 3]) / 2
                }
            }
        }
        add(strokes)
        add(fills)
        for (t in texts) {
            if (!visible[t.layer]) continue
            minX = min(minX, t.x); maxX = max(maxX, t.x + t.height)
            minY = min(minY, t.y); maxY = max(maxY, t.y + t.height)
        }
        if (minX > maxX) return floatArrayOf(0f, 0f, 1f, 1f)
        if (xs.size > 50) {
            xs.sort(); ys.sort()
            val lo = (xs.size * .005).toInt(); val hi = (xs.size * .995).toInt().coerceAtMost(xs.size - 1)
            val rw = xs[hi] - xs[lo]; val rh = ys[hi] - ys[lo]
            val fw = maxX - minX; val fh = maxY - minY
            if (rw > 0 && rh > 0 && (fw > rw * 20 || fh > rh * 20)) {
                val padX = rw * .1f; val padY = rh * .1f
                return floatArrayOf(xs[lo] - padX, ys[lo] - padY, xs[hi] + padX, ys[hi] + padY)
            }
        }
        val pad = max(maxX - minX, maxY - minY) * .02f + 1e-3f
        return floatArrayOf(minX - pad, minY - pad, maxX + pad, maxY + pad)
    }

    companion object {
        private const val MAX_DEPTH = 24
        private const val MAX_HATCH_LINES = 4000
        private const val MAX_HATCH_SEGMENTS = 20_000
        /** Wypełnienie należy do grupy konturów jednego kreskowania (otwory). */
        const val HOLE_GROUP = 32
        const val GROUP_START = 64

        /** $INSUNITS → milimetry na jednostkę. */
        fun unitsToMm(code: Int): Double? = when (code) {
            1 -> 25.4
            2 -> 304.8
            3 -> 1_609_344.0
            4 -> 1.0
            5 -> 10.0
            6 -> 1000.0
            7 -> 1_000_000.0
            8 -> 25.4e-6
            9 -> 0.0254
            10 -> 914.4
            11 -> 1e-7
            12 -> 1e-6
            13 -> 1e-3
            14 -> 100.0
            15 -> 10_000.0
            16 -> 100_000.0
            else -> null
        }
    }
}
