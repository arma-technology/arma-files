package pl.arma.viewer.sheet

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Rodzaj punktu, do którego przyciągnął się kursor pomiaru. */
enum class SnapKind(val label: String) {
    END("koniec"),
    INTERSECTION("przecięcie"),
    CENTER("środek okręgu"),
    INSERTION("punkt wstawienia"),
    PERPENDICULAR("prostopadle"),
    AXIS_INTERSECTION("oś × linia"),
    MIDPOINT("środek"),
    NEAREST("na linii"),
    AXIS("oś"),
    NONE("")
}

/** Wynik przyciągania w jednostkach strony. */
data class SnapResult(val x: Float, val y: Float, val kind: SnapKind)

/**
 * Geometria strony do przyciągania: odcinki i punkty szczególne w jednostkach
 * strony (oś Y w dół, jak na ekranie).
 *
 * Krzywe trafiają tu jako łamane, ale ich wewnętrzne wierzchołki nie są
 * „końcami” — inaczej łuk drzwi przyciągałby kursor co kilka milimetrów.
 */
class SnapGeometry private constructor(
    private val coords: FloatArray,
    private val flags: ByteArray,
    val segmentCount: Int,
    private val pointCoords: FloatArray,
    private val pointKinds: ByteArray,
    val pointCount: Int,
    private val index: Grid
) {

    class Builder {
        private var coords = FloatArray(4096)
        private var flags = ByteArray(1024)
        private var count = 0
        private var points = FloatArray(256)
        private var pointCount = 0
        private var pointKinds = ByteArray(128)

        /** Odcinek prosty: oba końce i środek są punktami przyciągania. */
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = add(x1, y1, x2, y2, START or END_FLAG)

        /** Łamana z prawdziwymi wierzchołkami (polilinia, prostokąt, ściana). */
        fun polyline(xy: FloatArray, closed: Boolean) {
            val n = xy.size / 2
            if (n < 2) return
            for (i in 0 until n - 1) line(xy[2 * i], xy[2 * i + 1], xy[2 * i + 2], xy[2 * i + 3])
            if (closed) line(xy[2 * n - 2], xy[2 * n - 1], xy[0], xy[1])
        }

        /** Krzywa spłaszczona do łamanej: przyciąga tylko na końcach i „na linii”. */
        fun curve(xy: FloatArray) {
            val n = xy.size / 2
            if (n < 2) return
            for (i in 0 until n - 1) {
                var f = CURVE
                if (i == 0) f = f or START
                if (i == n - 2) f = f or END_FLAG
                add(xy[2 * i], xy[2 * i + 1], xy[2 * i + 2], xy[2 * i + 3], f)
            }
        }

        /**
         * Odcinek z opisem końców: prawdziwy wierzchołek przyciąga jako „koniec”,
         * a odcinek bez dwóch wierzchołków to kawałek krzywej (bez „środka”).
         */
        fun segment(x1: Float, y1: Float, x2: Float, y2: Float, startVertex: Boolean, endVertex: Boolean) {
            var f = 0
            if (startVertex) f = f or START
            if (endVertex) f = f or END_FLAG
            if (!(startVertex && endVertex)) f = f or CURVE
            add(x1, y1, x2, y2, f)
        }

        /** Punkt szczególny bez odcinka: środek okręgu, punkt wstawienia. */
        fun center(x: Float, y: Float) = point(x, y, SnapKind.CENTER)

        /** Punkt wstawienia bloku — w wymiarach to punkt, który wymiar mierzy. */
        fun insertion(x: Float, y: Float) = point(x, y, SnapKind.INSERTION)

        private fun point(x: Float, y: Float, kind: SnapKind) {
            if (pointCount * 2 + 2 > points.size) points = points.copyOf(points.size * 2)
            if (pointCount + 1 > pointKinds.size) pointKinds = pointKinds.copyOf(pointKinds.size * 2)
            pointKinds[pointCount] = kind.ordinal.toByte()
            points[pointCount * 2] = x
            points[pointCount * 2 + 1] = y
            pointCount++
        }

        private fun add(x1: Float, y1: Float, x2: Float, y2: Float, f: Int) {
            if (!(x1.isFinite() && y1.isFinite() && x2.isFinite() && y2.isFinite())) return
            if (x1 == x2 && y1 == y2) return
            if (count * 4 + 4 > coords.size) coords = coords.copyOf(coords.size * 2)
            if (count + 1 > flags.size) flags = flags.copyOf(flags.size * 2)
            coords[count * 4] = x1
            coords[count * 4 + 1] = y1
            coords[count * 4 + 2] = x2
            coords[count * 4 + 3] = y2
            flags[count] = f.toByte()
            count++
        }

        val size: Int get() = count

        fun build(): SnapGeometry {
            val c = coords.copyOf(count * 4)
            val p = points.copyOf(pointCount * 2)
            return SnapGeometry(c, flags.copyOf(count), count, p, pointKinds.copyOf(pointCount), pointCount, Grid.build(c, count, p, pointCount))
        }
    }

    /**
     * Najlepszy punkt w promieniu [radius] od kursora ([x], [y]).
     *
     * @param from pierwszy punkt pomiaru — włącza przyciąganie prostopadłe
     * @param axisLock kursor wiedziony po osi poziomej albo pionowej przez [from]
     */
    fun snap(x: Float, y: Float, radius: Float, from: SnapResult? = null, axisLock: Boolean = false): SnapResult {
        if (axisLock && from != null) return snapOnAxis(x, y, radius, from)

        val candidates = index.segmentsNear(x, y, radius)
        var best: SnapResult? = null
        var bestPriority = Int.MAX_VALUE
        var bestDistance = Float.MAX_VALUE

        fun offer(px: Float, py: Float, kind: SnapKind, strong: Boolean = true) {
            val raw = hypot(px - x, py - y)
            if (raw > radius) return
            // Przecięcie liczy się jak dwa razy bliższe: kreska wymiaru „+” ma
            // końce milimetr od środka, a to środek jest punktem wymiarowym.
            // Tak samo środek okręgu i punkt wstawienia — to punkty, które
            // mierzy wymiar, a obrys kropki wymiaru przecina linię 1–2 mm obok.
            val d = when {
                !strong -> raw
                kind == SnapKind.INTERSECTION || kind == SnapKind.AXIS_INTERSECTION ||
                    kind == SnapKind.CENTER || kind == SnapKind.INSERTION -> raw * .5f
                else -> raw
            }
            val priority = tier(kind)
            // W tej samej grupie wygrywa bliższy; przy remisie (ten sam punkt
            // na dwa sposoby) rodzaj ważniejszy — „prostopadle” przed „środkiem”.
            val better = priority < bestPriority || (priority == bestPriority &&
                (d < bestDistance - 1e-4f || (d <= bestDistance + 1e-4f && kind.ordinal < (best?.kind?.ordinal ?: Int.MAX_VALUE))))
            if (better) {
                best = SnapResult(px, py, kind)
                bestPriority = priority
                bestDistance = d
            }
        }

        for (i in index.pointsNear(x, y, radius)) {
            offer(pointCoords[2 * i], pointCoords[2 * i + 1], SnapKind.entries[pointKinds[i].toInt()])
        }

        // Przecięcia liczymy tylko między odcinkami blisko kursora, a tych
        // najbliższych bierzemy ograniczoną liczbę: gęsty rysunek ma ich setki.
        val near = ArrayList<Int>(candidates.size)
        for (i in candidates) {
            val o = i * 4
            val x1 = coords[o]; val y1 = coords[o + 1]; val x2 = coords[o + 2]; val y2 = coords[o + 3]
            if (distanceToSegment(x, y, x1, y1, x2, y2) > radius) continue
            near += i
            val f = flags[i].toInt()
            if (f and START != 0) offer(x1, y1, SnapKind.END)
            if (f and END_FLAG != 0) offer(x2, y2, SnapKind.END)
            if (f and CURVE == 0) offer((x1 + x2) / 2f, (y1 + y2) / 2f, SnapKind.MIDPOINT)
            val (nx, ny) = nearestOnSegment(x, y, x1, y1, x2, y2)
            offer(nx, ny, SnapKind.NEAREST)
            if (from != null && f and CURVE == 0) {
                foot(from.x, from.y, x1, y1, x2, y2)?.let { (fx, fy) -> offer(fx, fy, SnapKind.PERPENDICULAR) }
            }
        }
        if (near.size > MAX_INTERSECTION_CANDIDATES) {
            near.sortBy { i ->
                val o = i * 4
                distanceToSegment(x, y, coords[o], coords[o + 1], coords[o + 2], coords[o + 3])
            }
        }
        val limit = min(near.size, MAX_INTERSECTION_CANDIDATES)
        for (a in 0 until limit) {
            val i = near[a] * 4
            for (b in a + 1 until limit) {
                val j = near[b] * 4
                intersect(
                    coords[i], coords[i + 1], coords[i + 2], coords[i + 3],
                    coords[j], coords[j + 1], coords[j + 2], coords[j + 3]
                )?.let { (ix, iy) ->
                    // Przecięcie z krzywą (np. obrysem kropki wymiaru) nie
                    // dostaje premii — punktem wymiarowym jest środek kropki.
                    val curve = (flags[near[a]].toInt() or flags[near[b]].toInt()) and CURVE != 0
                    offer(ix, iy, SnapKind.INTERSECTION, strong = !curve)
                }
            }
        }
        return best ?: SnapResult(x, y, SnapKind.NONE)
    }

    /**
     * Kursor na osi przez pierwszy punkt — tej, do której jest bliżej. Na osi
     * szukamy przecięcia z linią rysunku: to dokładnie „odległość elementu od
     * ściany w poziomie”.
     */
    private fun snapOnAxis(x: Float, y: Float, radius: Float, from: SnapResult): SnapResult {
        val horizontal = abs(x - from.x) >= abs(y - from.y)
        val ax = if (horizontal) x else from.x
        val ay = if (horizontal) from.y else y
        var best: SnapResult? = null
        var bestDistance = Float.MAX_VALUE
        for (i in index.segmentsNear(ax, ay, radius)) {
            val o = i * 4
            val hit = if (horizontal) {
                intersect(ax - radius, ay, ax + radius, ay, coords[o], coords[o + 1], coords[o + 2], coords[o + 3])
            } else {
                intersect(ax, ay - radius, ax, ay + radius, coords[o], coords[o + 1], coords[o + 2], coords[o + 3])
            } ?: continue
            val d = hypot(hit.first - ax, hit.second - ay)
            if (d < bestDistance) {
                bestDistance = d
                best = SnapResult(hit.first, hit.second, SnapKind.AXIS_INTERSECTION)
            }
        }
        return best ?: SnapResult(ax, ay, SnapKind.AXIS)
    }

    /** Odcinki w prostokącie — do narysowania podświetlenia linii pod kursorem. */
    fun segment(i: Int): FloatArray = coords.copyOfRange(i * 4, i * 4 + 4)

    private class Grid(
        private val minX: Float,
        private val minY: Float,
        private val cell: Float,
        private val columns: Int,
        private val rows: Int,
        private val starts: IntArray,
        private val items: IntArray,
        private val pointStarts: IntArray,
        private val pointItems: IntArray,
        segmentCount: Int
    ) {
        private val stamp = IntArray(segmentCount)
        private var generation = 0

        fun segmentsNear(x: Float, y: Float, r: Float): List<Int> {
            if (columns == 0) return emptyList()
            generation++
            val out = ArrayList<Int>()
            forCells(x, y, r) { c ->
                for (k in starts[c] until starts[c + 1]) {
                    val i = items[k]
                    if (stamp[i] != generation) {
                        stamp[i] = generation
                        out += i
                    }
                }
            }
            return out
        }

        fun pointsNear(x: Float, y: Float, r: Float): List<Int> {
            if (columns == 0) return emptyList()
            val out = ArrayList<Int>()
            forCells(x, y, r) { c -> for (k in pointStarts[c] until pointStarts[c + 1]) out += pointItems[k] }
            return out
        }

        private inline fun forCells(x: Float, y: Float, r: Float, block: (Int) -> Unit) {
            val c0 = ((x - r - minX) / cell).toInt().coerceIn(0, columns - 1)
            val c1 = ((x + r - minX) / cell).toInt().coerceIn(0, columns - 1)
            val r0 = ((y - r - minY) / cell).toInt().coerceIn(0, rows - 1)
            val r1 = ((y + r - minY) / cell).toInt().coerceIn(0, rows - 1)
            for (row in r0..r1) for (col in c0..c1) block(row * columns + col)
        }

        companion object {
            fun build(c: FloatArray, n: Int, p: FloatArray, pn: Int): Grid {
                if (n == 0 && pn == 0) return Grid(0f, 0f, 1f, 0, 0, IntArray(1), IntArray(0), IntArray(1), IntArray(0), 0)
                var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
                var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
                for (k in 0 until n * 2) {
                    minX = min(minX, c[2 * k]); maxX = max(maxX, c[2 * k])
                    minY = min(minY, c[2 * k + 1]); maxY = max(maxY, c[2 * k + 1])
                }
                for (k in 0 until pn) {
                    minX = min(minX, p[2 * k]); maxX = max(maxX, p[2 * k])
                    minY = min(minY, p[2 * k + 1]); maxY = max(maxY, p[2 * k + 1])
                }
                val w = max(maxX - minX, 1e-3f)
                val h = max(maxY - minY, 1e-3f)
                // Około jednego odcinka na komórkę, ale nie więcej niż 512×512.
                val target = sqrt(max(n, 1).toDouble()).toInt().coerceIn(1, 512)
                val cell = max(w, h) / target
                val columns = (w / cell).toInt() + 1
                val rows = (h / cell).toInt() + 1

                fun cellsOf(x1: Float, y1: Float, x2: Float, y2: Float, block: (Int) -> Unit) {
                    val c0 = ((min(x1, x2) - minX) / cell).toInt().coerceIn(0, columns - 1)
                    val c1 = ((max(x1, x2) - minX) / cell).toInt().coerceIn(0, columns - 1)
                    val r0 = ((min(y1, y2) - minY) / cell).toInt().coerceIn(0, rows - 1)
                    val r1 = ((max(y1, y2) - minY) / cell).toInt().coerceIn(0, rows - 1)
                    // Długa ukośna linia przecina tylko część komórek swojego
                    // prostokąta — sprawdzamy odległość, żeby nie wpisywać jej wszędzie.
                    val diagonal = c1 > c0 && r1 > r0
                    for (row in r0..r1) for (col in c0..c1) {
                        if (diagonal) {
                            val cx = minX + (col + .5f) * cell
                            val cy = minY + (row + .5f) * cell
                            if (distanceToSegment(cx, cy, x1, y1, x2, y2) > cell * .71f) continue
                        }
                        block(row * columns + col)
                    }
                }

                val counts = IntArray(columns * rows + 1)
                for (i in 0 until n) cellsOf(c[4 * i], c[4 * i + 1], c[4 * i + 2], c[4 * i + 3]) { counts[it]++ }
                val starts = IntArray(columns * rows + 1)
                for (k in 0 until columns * rows) starts[k + 1] = starts[k] + counts[k]
                val fill = starts.copyOf()
                val items = IntArray(starts[columns * rows])
                for (i in 0 until n) cellsOf(c[4 * i], c[4 * i + 1], c[4 * i + 2], c[4 * i + 3]) { items[fill[it]++] = i }

                val pc = IntArray(columns * rows + 1)
                fun pointCell(k: Int): Int {
                    val col = ((p[2 * k] - minX) / cell).toInt().coerceIn(0, columns - 1)
                    val row = ((p[2 * k + 1] - minY) / cell).toInt().coerceIn(0, rows - 1)
                    return row * columns + col
                }
                for (k in 0 until pn) pc[pointCell(k)]++
                val pStarts = IntArray(columns * rows + 1)
                for (k in 0 until columns * rows) pStarts[k + 1] = pStarts[k] + pc[k]
                val pFill = pStarts.copyOf()
                val pItems = IntArray(pn)
                for (k in 0 until pn) pItems[pFill[pointCell(k)]++] = k
                return Grid(minX, minY, cell, columns, rows, starts, items, pStarts, pItems, n)
            }
        }
    }

    companion object {
        /**
         * Punkty charakterystyczne (koniec, przecięcie, środek okręgu) konkurują
         * odległością od celownika, jak w programach CAD. Bezwzględne
         * pierwszeństwo końca wybierało koniec przerwanej linii wymiarowej
         * zamiast kreski wymiaru 3 mm obok.
         */
        private fun tier(kind: SnapKind): Int = when (kind) {
            SnapKind.END, SnapKind.INTERSECTION, SnapKind.CENTER, SnapKind.INSERTION, SnapKind.AXIS_INTERSECTION -> 0
            SnapKind.PERPENDICULAR, SnapKind.MIDPOINT -> 1
            SnapKind.NEAREST -> 2
            else -> 3
        }

        private const val START = 1
        private const val END_FLAG = 2
        private const val CURVE = 4
        private const val MAX_INTERSECTION_CANDIDATES = 48

        val EMPTY: SnapGeometry = Builder().build()

        fun distanceToSegment(px: Float, py: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
            val (nx, ny) = nearestOnSegment(px, py, x1, y1, x2, y2)
            return hypot(px - nx, py - ny)
        }

        fun nearestOnSegment(px: Float, py: Float, x1: Float, y1: Float, x2: Float, y2: Float): Pair<Float, Float> {
            val dx = x2 - x1
            val dy = y2 - y1
            val len2 = dx * dx + dy * dy
            if (len2 == 0f) return x1 to y1
            val t = (((px - x1) * dx + (py - y1) * dy) / len2).coerceIn(0f, 1f)
            return (x1 + t * dx) to (y1 + t * dy)
        }

        /** Spodek prostopadłej z punktu na odcinek; null, gdy wypada poza odcinek. */
        fun foot(px: Float, py: Float, x1: Float, y1: Float, x2: Float, y2: Float): Pair<Float, Float>? {
            val dx = x2 - x1
            val dy = y2 - y1
            val len2 = dx * dx + dy * dy
            if (len2 == 0f) return null
            val t = ((px - x1) * dx + (py - y1) * dy) / len2
            if (t < 0f || t > 1f) return null
            return (x1 + t * dx) to (y1 + t * dy)
        }

        fun intersect(
            ax: Float, ay: Float, bx: Float, by: Float,
            cx: Float, cy: Float, dx: Float, dy: Float
        ): Pair<Float, Float>? {
            // W podwójnej precyzji: rysunek CAD ma współrzędne rzędu 10⁵, a
            // iloczyny w Float gubiłyby milimetry.
            val rX = (bx - ax).toDouble(); val rY = (by - ay).toDouble()
            val sX = (dx - cx).toDouble(); val sY = (dy - cy).toDouble()
            val denominator = rX * sY - rY * sX
            if (abs(denominator) < 1e-12) return null
            val qpX = (cx - ax).toDouble(); val qpY = (cy - ay).toDouble()
            val t = (qpX * sY - qpY * sX) / denominator
            val u = (qpX * rY - qpY * rX) / denominator
            val eps = 1e-6
            if (t < -eps || t > 1 + eps || u < -eps || u > 1 + eps) return null
            // Dwa odcinki stykające się końcami (kolejne boki łamanej, kawałki
            // łuku) to nie przecięcie — tam jest już „koniec” albo nic.
            val tEnd = t < 1e-4 || t > 1 - 1e-4
            val uEnd = u < 1e-4 || u > 1 - 1e-4
            if (tEnd && uEnd) return null
            return (ax + t * rX).toFloat() to (ay + t * rY).toFloat()
        }
    }
}
