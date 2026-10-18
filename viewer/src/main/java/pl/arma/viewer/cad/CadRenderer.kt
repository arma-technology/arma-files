package pl.arma.viewer.cad

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import androidx.annotation.StringRes
import pl.arma.viewer.R
import kotlin.math.abs
import kotlin.math.max

/** Tło rysunku: jasne (jak wydruk), ciemne (jak w programie CAD) albo czarno-białe. */
enum class CadBackground(@StringRes val label: Int, val paper: Int) {
    LIGHT(R.string.viewer_background_light, Color.WHITE),
    DARK(R.string.viewer_background_dark, 0xFF1E2227.toInt()),
    MONO(R.string.viewer_background_mono, Color.WHITE)
}

/**
 * Rysowanie sceny CAD na bitmapie. Rysujemy tylko to, co trafia w kadr,
 * a obiekty mniejsze od piksela pomijamy — przy całym rysunku to większość.
 */
class CadRenderer(private val scene: CadScene) {

    private val dashCache = HashMap<Int, DashPathEffect?>()

    /** Posortowane indeksy kształtów według stylu: jeden pędzel na serię. */
    private val strokesByStyle: Array<IntArray> by lazy { group(scene.strokes) }
    private val fillsByStyle: Array<IntArray> by lazy { group(scene.fills) }

    private fun group(shapes: Shapes): Array<IntArray> {
        val counts = IntArray(scene.styles.size)
        for (i in 0 until shapes.count) counts[shapes.style[i]]++
        val out = Array(scene.styles.size) { IntArray(counts[it]) }
        val fill = IntArray(scene.styles.size)
        for (i in 0 until shapes.count) {
            val s = shapes.style[i]
            out[s][fill[s]++] = i
        }
        return out
    }

    /**
     * Rysuje fragment strony: punkt strony (px, py) trafia w piksel
     * (px·scale − offsetX, py·scale − offsetY).
     */
    fun render(
        bitmap: Bitmap,
        scale: Float,
        offsetX: Float,
        offsetY: Float,
        visible: BooleanArray,
        background: CadBackground
    ) {
        val canvas = Canvas(bitmap)
        canvas.drawColor(background.paper)
        val b = scene.bounds
        // Strona → piksel, a scena (Y w górę) → strona (Y w dół).
        val matrix = Matrix().apply {
            setValues(
                floatArrayOf(
                    scale, 0f, -b[0] * scale - offsetX,
                    0f, -scale, b[3] * scale - offsetY,
                    0f, 0f, 1f
                )
            )
        }
        // Kadr w układzie sceny, z zapasem na grubość linii.
        val left = b[0] + offsetX / scale
        val right = b[0] + (offsetX + bitmap.width) / scale
        val top = b[3] - offsetY / scale
        val bottom = b[3] - (offsetY + bitmap.height) / scale
        val pixel = 1f / scale
        val view = floatArrayOf(left - 2 * pixel, bottom - 2 * pixel, right + 2 * pixel, top + 2 * pixel)

        canvas.save()
        canvas.concat(matrix)
        drawFills(canvas, view, pixel, visible, background, wipeouts = false)
        drawFills(canvas, view, pixel, visible, background, wipeouts = true)
        drawStrokes(canvas, view, pixel, scale, visible, background)
        canvas.restore()
        drawTexts(canvas, matrix, view, scale, visible, background)
    }

    private fun color(argb: Int, background: CadBackground): Int = when {
        argb == StrokeStyle.BACKGROUND -> background.paper
        background == CadBackground.MONO -> Color.BLACK
        argb == StrokeStyle.FOREGROUND -> if (background == CadBackground.DARK) Color.WHITE else Color.BLACK
        background == CadBackground.LIGHT -> darkenIfPale(argb)
        else -> argb
    }

    /** Jasnożółte i białawe kolory projektowane pod ciemne tło znikałyby na białym. */
    private fun darkenIfPale(argb: Int): Int {
        val r = Color.red(argb); val g = Color.green(argb); val bl = Color.blue(argb)
        val luma = (0.2126 * r + 0.7152 * g + 0.0722 * bl) / 255.0
        if (luma < 0.78) return argb
        val k = 0.62f
        return Color.rgb((r * k).toInt(), (g * k).toInt(), (bl * k).toInt())
    }

    private fun visibleBox(shapes: Shapes, i: Int, view: FloatArray): Boolean {
        val o = 4 * i
        return shapes.box[o] <= view[2] && shapes.box[o + 2] >= view[0] &&
            shapes.box[o + 1] <= view[3] && shapes.box[o + 3] >= view[1]
    }

    private fun drawFills(
        canvas: Canvas, view: FloatArray, pixel: Float, visible: BooleanArray, background: CadBackground, wipeouts: Boolean
    ) {
        val shapes = scene.fills
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val path = Path().apply { fillType = Path.FillType.EVEN_ODD }
        var open = false
        fun flush() {
            if (open) canvas.drawPath(path, paint)
            path.rewind()
            open = false
        }
        for (styleIndex in fillsByStyle.indices) {
            val items = fillsByStyle[styleIndex]
            if (items.isEmpty()) continue
            paint.color = if (wipeouts) background.paper else color(scene.styles[styleIndex].color, background)
            for (i in items) {
                val flags = shapes.flags[i].toInt()
                if ((flags and Shapes.WIPEOUT != 0) != wipeouts) continue
                if (!visible[shapes.layer[i]] || !visibleBox(shapes, i, view)) continue
                // Kontury jednego kreskowania tworzą jedną ścieżkę z otworami.
                if (flags and CadSceneBuilder.HOLE_GROUP == 0 || flags and CadSceneBuilder.GROUP_START != 0) flush()
                val o = 4 * i
                if (shapes.box[o + 2] - shapes.box[o] < pixel * .5f && shapes.box[o + 3] - shapes.box[o + 1] < pixel * .5f) continue
                val start = shapes.start[i]
                val n = shapes.length[i]
                path.moveTo(shapes.coords[2 * start], shapes.coords[2 * start + 1])
                for (k in 1 until n) path.lineTo(shapes.coords[2 * (start + k)], shapes.coords[2 * (start + k) + 1])
                path.close()
                open = true
            }
            flush()
        }
    }

    private fun drawStrokes(
        canvas: Canvas, view: FloatArray, pixel: Float, scale: Float, visible: BooleanArray, background: CadBackground
    ) {
        val shapes = scene.strokes
        val paint = Paint().apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.BUTT
            isAntiAlias = true
        }
        val buffer = FloatArray(BATCH)
        val path = Path()
        for (styleIndex in strokesByStyle.indices) {
            val items = strokesByStyle[styleIndex]
            if (items.isEmpty()) continue
            val st = scene.styles[styleIndex]
            paint.color = color(st.color, background)
            // Grubości z rysunku w pikselach ekranu: cienkie jako linia
            // jednopikselowa, grubsze proporcjonalnie, ale z umiarem.
            paint.strokeWidth = if (st.widthMm <= 0.3f) 0f else (st.widthMm * 2.4f).coerceAtMost(6f) * pixel
            val dash = dashEffect(styleIndex, st, scale)
            paint.pathEffect = dash
            var used = 0
            for (i in items) {
                if (!visible[shapes.layer[i]] || !visibleBox(shapes, i, view)) continue
                val o = 4 * i
                val small = shapes.box[o + 2] - shapes.box[o] < pixel && shapes.box[o + 3] - shapes.box[o + 1] < pixel
                val start = shapes.start[i]
                val n = shapes.length[i]
                val closed = shapes.flags[i].toInt() and Shapes.CLOSED != 0
                if (small || n == 1) {
                    // Obiekt mniejszy od piksela: jedna kropka zamiast łamanej.
                    if (used + 4 > BATCH) { canvas.drawLines(buffer, 0, used, paint); used = 0 }
                    val x = shapes.coords[2 * start]
                    val y = shapes.coords[2 * start + 1]
                    buffer[used++] = x; buffer[used++] = y; buffer[used++] = x + pixel; buffer[used++] = y
                    continue
                }
                if (dash != null) {
                    path.rewind()
                    path.moveTo(shapes.coords[2 * start], shapes.coords[2 * start + 1])
                    for (k in 1 until n) path.lineTo(shapes.coords[2 * (start + k)], shapes.coords[2 * (start + k) + 1])
                    if (closed) path.close()
                    canvas.drawPath(path, paint)
                    continue
                }
                val segments = n - 1 + if (closed) 1 else 0
                for (k in 0 until segments) {
                    if (used + 4 > BATCH) { canvas.drawLines(buffer, 0, used, paint); used = 0 }
                    val a = start + k
                    val bIdx = if (k == n - 1) start else a + 1
                    buffer[used++] = shapes.coords[2 * a]
                    buffer[used++] = shapes.coords[2 * a + 1]
                    buffer[used++] = shapes.coords[2 * bIdx]
                    buffer[used++] = shapes.coords[2 * bIdx + 1]
                }
            }
            if (used > 0) canvas.drawLines(buffer, 0, used, paint)
        }
    }

    /**
     * Wzór kresek w jednostkach rysunku. Gdy kreski byłyby krótsze niż kilka
     * pikseli, rysujemy linię ciągłą — tysiące kresek wielkości piksela to
     * tylko szara mgła i bardzo długie rysowanie.
     */
    private fun dashEffect(styleIndex: Int, st: StrokeStyle, scale: Float): DashPathEffect? {
        val dashes = st.dashes ?: return null
        val period = dashes.sumOf { abs(it).toDouble() }.toFloat()
        if (period * scale < 6f) return null
        return dashCache.getOrPut(styleIndex) {
            val intervals = FloatArray(if (dashes.size % 2 == 0) dashes.size else dashes.size * 2)
            for (k in intervals.indices) {
                val v = abs(dashes[k % dashes.size])
                // Kropka ma długość zero — dajemy jej ułamek okresu.
                intervals[k] = if (v == 0f) period * 0.01f else v
            }
            DashPathEffect(intervals, 0f)
        }
    }

    private fun drawTexts(canvas: Canvas, matrix: Matrix, view: FloatArray, scale: Float, visible: BooleanArray, background: CadBackground) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.SANS_SERIF
            isSubpixelText = true
        }
        val point = FloatArray(2)
        val second = FloatArray(2)
        for (t in scene.texts) {
            if (!visible[t.layer]) continue
            val sizePx = t.height * scale
            // Tekst niższy niż półtora piksela i tak byłby nieczytelny.
            if (sizePx < 1.5f) continue
            val reach = t.height * max(1, t.lines.maxOf { it.length }) * max(1f, t.widthFactor) + t.lineAdvance * t.lines.size + t.wrapWidth
            if (t.x + reach < view[0] || t.x - reach > view[2] || t.y + reach < view[1] || t.y - reach > view[3]) continue
            point[0] = t.x; point[1] = t.y
            matrix.mapPoints(point)
            paint.color = color(t.color, background)
            // Wysokość w CAD to wysokość wielkich liter; Roboto ma ją około 0,72 em.
            paint.textSize = sizePx / CAP_HEIGHT
            paint.textScaleX = t.widthFactor.coerceIn(0.1f, 10f)
            paint.textSkewX = -t.skew
            val capHeight = sizePx
            val descent = paint.descent()
            canvas.save()
            canvas.translate(point[0], point[1])
            // Obrót przeciwnie do wskazówek zegara w świecie to zgodnie na ekranie.
            canvas.rotate(-Math.toDegrees(t.rotation.toDouble()).toFloat())
            if (t.lineAdvance == 0f) {
                // TEXT: jedna linia z wyrównaniem do punktu.
                val line = t.lines.first()
                val stretched = (t.hAlign == 3 || t.hAlign == 5) && (t.x2 != t.x || t.y2 != t.y)
                if (stretched) {
                    // Tekst rozpięty między dwoma punktami: zaczynamy od pierwszego,
                    // „dopasowany” zwęża litery, „wyrównany” zmienia ich wielkość.
                    second[0] = t.x2; second[1] = t.y2
                    matrix.mapPoints(second)
                    val wanted = kotlin.math.hypot(point[0] - second[0], point[1] - second[1])
                    val width = paint.measureText(line)
                    canvas.restore()
                    canvas.save()
                    canvas.translate(second[0], second[1])
                    canvas.rotate(-Math.toDegrees(t.rotation.toDouble()).toFloat())
                    if (width > 0f) {
                        if (t.hAlign == 5) paint.textScaleX = paint.textScaleX * wanted / width
                        else paint.textSize = paint.textSize * (wanted / width).coerceIn(.2f, 5f)
                    }
                    canvas.drawText(line, 0f, 0f, paint)
                } else {
                    val width = paint.measureText(line)
                    val dx = when (t.hAlign) {
                        1, 4 -> -width / 2
                        2 -> -width
                        else -> 0f
                    }
                    val baseline = when {
                        t.hAlign == 4 -> capHeight / 2
                        t.vAlign == 1 -> -descent
                        t.vAlign == 2 -> capHeight / 2
                        t.vAlign == 3 -> capHeight
                        else -> 0f
                    }
                    canvas.drawText(line, dx, baseline, paint)
                }
            } else {
                // MTEXT: kolejne linie w dół, z zawijaniem do szerokości ramki.
                val wrap = t.wrapWidth * scale
                val lines = if (wrap > 0f) wrapLines(t.lines, paint, wrap) else t.lines
                val advance = t.lineAdvance * scale
                val block = capHeight + advance * (lines.size - 1)
                var baseline = when (t.vAlign) {
                    3 -> capHeight
                    2 -> capHeight - block / 2
                    else -> capHeight - block
                }
                for (line in lines) {
                    val width = paint.measureText(line)
                    val dx = when (t.hAlign) {
                        1 -> -width / 2
                        2 -> -width
                        else -> 0f
                    }
                    canvas.drawText(line, dx, baseline, paint)
                    baseline += advance
                }
            }
            canvas.restore()
        }
    }

    private fun wrapLines(lines: List<String>, paint: Paint, width: Float): List<String> {
        val out = ArrayList<String>()
        for (line in lines) {
            if (paint.measureText(line) <= width * 1.02f) {
                out += line
                continue
            }
            val words = line.split(' ')
            var current = StringBuilder()
            for (word in words) {
                val candidate = if (current.isEmpty()) word else "$current $word"
                if (paint.measureText(candidate) > width * 1.02f && current.isNotEmpty()) {
                    out += current.toString()
                    current = StringBuilder(word)
                } else {
                    current = StringBuilder(candidate)
                }
            }
            out += current.toString()
        }
        return out
    }

    companion object {
        private const val BATCH = 16 * 1024
        private const val CAP_HEIGHT = 0.72f

        /** Przybliżona szerokość tekstu w jednostkach rysunku — do prostokątów wyszukiwania. */
        fun approximateWidth(text: String, height: Float, widthFactor: Float): Float =
            text.length * height * 0.78f * widthFactor
    }
}
