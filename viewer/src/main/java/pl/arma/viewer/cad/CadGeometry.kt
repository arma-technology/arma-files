package pl.arma.viewer.cad

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tan

/** Odbiorca punktów łamanej; [vertex] mówi, czy punkt jest prawdziwym wierzchołkiem. */
fun interface PointSink {
    fun add(x: Double, y: Double, vertex: Boolean)
}

/** Rosnąca tablica liczb bez pakowania w obiekty. */
class Doubles(capacity: Int = 32) : PointSink {
    var data = DoubleArray(capacity)
        private set
    var size = 0
        private set

    operator fun plusAssign(value: Double) {
        if (size == data.size) data = data.copyOf(maxOf(16, size * 2))
        data[size++] = value
    }

    override fun add(x: Double, y: Double, vertex: Boolean) {
        this += x
        this += y
    }

    operator fun get(i: Int) = data[i]
    fun isEmpty() = size == 0
    fun clear() { size = 0 }
    fun toArray(): DoubleArray = data.copyOf(size)
}

/** Zamiana łuków, elips i krzywych na łamane. */
object CadGeometry {

    /** Odcinków na pełny okrąg — przy dużym powiększeniu fasety mają ułamek promienia. */
    const val FULL_CIRCLE_SEGMENTS = 128

    fun segmentsFor(sweep: Double): Int =
        ceil(abs(sweep) / (2 * PI) * FULL_CIRCLE_SEGMENTS).toInt().coerceIn(4, FULL_CIRCLE_SEGMENTS)

    /**
     * Odcinków na łuk o promieniu [radius] przy dopuszczalnej odchyłce cięciwy
     * [tolerance]. Mały łuk w dużym rysunku nie potrzebuje 128 odcinków, a
     * takich łuków bywają setki tysięcy.
     */
    fun segmentsFor(sweep: Double, radius: Double, tolerance: Double): Int {
        if (tolerance.isNaN() || tolerance <= 0 || radius <= 0) return segmentsFor(sweep)
        if (tolerance >= radius) return 2
        val step = 2 * kotlin.math.acos(1 - tolerance / radius)
        return ceil(abs(sweep) / step).toInt().coerceIn(2, segmentsFor(sweep))
    }

    /** Dopuszczalna odchyłka łuków dla bieżąco budowanej sceny; NaN = bez ograniczeń. */
    @Volatile
    var tolerance: Double = Double.NaN

    /**
     * Łuk eliptyczny o półosiach [rx], [ry] obróconych o [rotation] (radiany),
     * od parametru [t0] do [t1]; dodatni przyrost to kierunek przeciwny do
     * wskazówek zegara. Końce są wierzchołkami, punkty pośrednie nie.
     */
    fun ellipseArc(
        cx: Double, cy: Double, rx: Double, ry: Double, rotation: Double,
        t0: Double, t1: Double, sink: PointSink, skipFirst: Boolean = false
    ) {
        val n = segmentsFor(t1 - t0, maxOf(abs(rx), abs(ry)), tolerance)
        val cr = cos(rotation)
        val sr = sin(rotation)
        for (i in (if (skipFirst) 1 else 0)..n) {
            val t = t0 + (t1 - t0) * i / n
            val ex = rx * cos(t)
            val ey = ry * sin(t)
            sink.add(cx + ex * cr - ey * sr, cy + ex * sr + ey * cr, i == 0 || i == n)
        }
    }

    /** Łuk okręgu przeciwnie do wskazówek zegara, kąty w stopniach (jak w ARC). */
    fun arc(cx: Double, cy: Double, r: Double, startDeg: Double, endDeg: Double, sink: PointSink) {
        val a0 = Math.toRadians(startDeg)
        var a1 = Math.toRadians(endDeg)
        while (a1 <= a0) a1 += 2 * PI
        if (a1 - a0 > 2 * PI) a1 -= 2 * PI
        ellipseArc(cx, cy, r, r, 0.0, a0, a1, sink)
    }

    /**
     * Łuk krawędzi kreskowania. Łuki zgodne ze wskazówkami zegara mają w DXF
     * kąty odbite (jak liczone od osi skierowanej w dół), więc je zanegowujemy.
     */
    fun arcPoints(
        cx: Double, cy: Double, r: Double, startDeg: Double, endDeg: Double, ccw: Boolean, sink: PointSink
    ) {
        if (ccw) {
            val a0 = Math.toRadians(startDeg)
            var a1 = Math.toRadians(endDeg)
            while (a1 <= a0) a1 += 2 * PI
            ellipseArc(cx, cy, r, r, 0.0, a0, a1, sink)
        } else {
            val a0 = -Math.toRadians(startDeg)
            var a1 = -Math.toRadians(endDeg)
            while (a1 >= a0) a1 -= 2 * PI
            ellipseArc(cx, cy, r, r, 0.0, a0, a1, sink)
        }
    }

    fun ellipseEdge(
        cx: Double, cy: Double, mx: Double, my: Double, ratio: Double,
        startDeg: Double, endDeg: Double, ccw: Boolean, sink: PointSink
    ) {
        val rx = hypot(mx, my)
        val rotation = kotlin.math.atan2(my, mx)
        if (ccw) {
            val t0 = Math.toRadians(startDeg)
            var t1 = Math.toRadians(endDeg)
            while (t1 <= t0) t1 += 2 * PI
            ellipseArc(cx, cy, rx, rx * ratio, rotation, t0, t1, sink)
        } else {
            val t0 = -Math.toRadians(startDeg)
            var t1 = -Math.toRadians(endDeg)
            while (t1 >= t0) t1 -= 2 * PI
            ellipseArc(cx, cy, rx, rx * ratio, rotation, t0, t1, sink)
        }
    }

    /** ELLIPSE: parametry w radianach, przeciwnie do wskazówek zegara. */
    fun ellipse(e: CadEllipse, sink: PointSink) {
        val rx = hypot(e.mx, e.my)
        val rotation = kotlin.math.atan2(e.my, e.mx)
        var t1 = e.end
        while (t1 <= e.start) t1 += 2 * PI
        if (t1 - e.start > 2 * PI + 1e-9) t1 -= 2 * PI
        ellipseArc(e.cx, e.cy, rx, rx * e.ratio, rotation, e.start, t1, sink)
    }

    /**
     * Polilinia z wybrzuszeniami: wybrzuszenie to tangens ćwiartki kąta
     * środkowego łuku między wierzchołkiem a następnym.
     */
    fun bulgePolyline(xs: DoubleArray, ys: DoubleArray, bulges: DoubleArray, closed: Boolean, sink: PointSink) {
        val n = xs.size
        if (n == 0) return
        sink.add(xs[0], ys[0], true)
        val last = if (closed) n else n - 1
        for (i in 0 until last) {
            val j = (i + 1) % n
            val b = if (i < bulges.size) bulges[i] else 0.0
            if (abs(b) < 1e-9) {
                sink.add(xs[j], ys[j], true)
                continue
            }
            val x1 = xs[i]; val y1 = ys[i]; val x2 = xs[j]; val y2 = ys[j]
            val chord = hypot(x2 - x1, y2 - y1)
            if (chord < 1e-12) continue
            val theta = 4 * atan(b)
            val r = chord / (2 * sin(theta / 2))
            // Środek leży na symetralnej cięciwy w odległości r·cos(θ/2).
            val mx = (x1 + x2) / 2
            val my = (y1 + y2) / 2
            val d = r * cos(theta / 2)
            val nx = -(y2 - y1) / chord
            val ny = (x2 - x1) / chord
            val cx = mx + nx * d
            val cy = my + ny * d
            val a0 = kotlin.math.atan2(y1 - cy, x1 - cx)
            val radius = abs(r)
            val steps = segmentsFor(theta, radius, tolerance)
            for (s in 1..steps) {
                val t = a0 + theta * s / steps
                sink.add(cx + radius * cos(t), cy + radius * sin(t), s == steps)
            }
        }
    }

    /**
     * Krzywa NURBS próbkowana algorytmem de Boora. Bez poprawnego wektora
     * węzłów rysujemy łamaną przez punkty sterujące — lepsze niż nic.
     */
    fun nurbs(degree: Int, knots: DoubleArray, cx: DoubleArray, cy: DoubleArray, weights: DoubleArray, sink: PointSink) {
        val n = minOf(cx.size, cy.size)
        if (n == 0) return
        val p = degree.coerceAtLeast(1)
        if (n <= p || knots.size != n + p + 1) {
            for (i in 0 until n) sink.add(cx[i], cy[i], i == 0 || i == n - 1)
            return
        }
        val rational = weights.size == n && weights.any { abs(it - 1.0) > 1e-12 }
        val t0 = knots[p]
        val t1 = knots[n]
        if (t1 <= t0) {
            for (i in 0 until n) sink.add(cx[i], cy[i], i == 0 || i == n - 1)
            return
        }
        // Liczba próbek z długości łamanej kontrolnej względem dopuszczalnej
        // odchyłki: drobne ornamenty dostają kilka punktów, duże łuki więcej.
        var polygon = 0.0
        for (i in 1 until n) polygon += hypot(cx[i] - cx[i - 1], cy[i] - cy[i - 1])
        val samples = if (tolerance.isNaN() || tolerance <= 0) {
            (n * 8).coerceIn(16, 512)
        } else {
            ceil(polygon / (40 * tolerance)).toInt().coerceIn(4, (n * 8).coerceIn(16, 512))
        }
        val dx = DoubleArray(p + 1)
        val dy = DoubleArray(p + 1)
        val dw = DoubleArray(p + 1)
        for (s in 0..samples) {
            val t = if (s == samples) t1 else t0 + (t1 - t0) * s / samples
            // Przedział węzłów zawierający t.
            var k = p
            while (k < n - 1 && t >= knots[k + 1]) k++
            for (j in 0..p) {
                val idx = k - p + j
                val w = if (rational) weights[idx] else 1.0
                dx[j] = cx[idx] * w
                dy[j] = cy[idx] * w
                dw[j] = w
            }
            for (r in 1..p) {
                for (j in p downTo r) {
                    val i = k - p + j
                    val denominator = knots[i + p - r + 1] - knots[i]
                    val alpha = if (denominator == 0.0) 0.0 else (t - knots[i]) / denominator
                    dx[j] = (1 - alpha) * dx[j - 1] + alpha * dx[j]
                    dy[j] = (1 - alpha) * dy[j - 1] + alpha * dy[j]
                    dw[j] = (1 - alpha) * dw[j - 1] + alpha * dw[j]
                }
            }
            val w = if (dw[p] == 0.0) 1.0 else dw[p]
            sink.add(dx[p] / w, dy[p] / w, s == 0 || s == samples)
        }
    }

    /** Krzywa przez punkty dopasowania (bez punktów sterujących): Catmull-Rom. */
    fun throughPoints(xs: DoubleArray, ys: DoubleArray, closed: Boolean, sink: PointSink) {
        val n = minOf(xs.size, ys.size)
        if (n < 3) {
            for (i in 0 until n) sink.add(xs[i], ys[i], true)
            return
        }
        fun px(i: Int) = xs[if (closed) (i + n) % n else i.coerceIn(0, n - 1)]
        fun py(i: Int) = ys[if (closed) (i + n) % n else i.coerceIn(0, n - 1)]
        val spans = if (closed) n else n - 1
        sink.add(xs[0], ys[0], true)
        for (i in 0 until spans) {
            for (s in 1..8) {
                val t = s / 8.0
                val t2 = t * t
                val t3 = t2 * t
                fun cr(a: Double, b: Double, c: Double, d: Double) =
                    .5 * (2 * b + (-a + c) * t + (2 * a - 5 * b + 4 * c - d) * t2 + (-a + 3 * b - 3 * c + d) * t3)
                sink.add(
                    cr(px(i - 1), px(i), px(i + 1), px(i + 2)),
                    cr(py(i - 1), py(i), py(i + 1), py(i + 2)),
                    s == 8
                )
            }
        }
    }

    /** Kąt nachylenia tekstu jako tangens — do pochylenia czcionki. */
    fun obliqueSkew(degrees: Double): Double = tan(Math.toRadians(degrees))
}
