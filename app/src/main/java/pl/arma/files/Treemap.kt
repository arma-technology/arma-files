package pl.arma.files

/** Prostokąt kafelka mapy, w pikselach. */
data class Cell(val left: Float, val top: Float, val width: Float, val height: Float) {
    val right get() = left + width
    val bottom get() = top + height
    fun contains(x: Float, y: Float) = x in left..right && y in top..bottom
}

/**
 * Układ „squarified” (Bruls, Huizing, van Wijk): kafelki o polu proporcjonalnym
 * do wagi i bokach możliwie bliskich kwadratowi — w pasku 1:20 nie zmieściłby
 * się żaden podpis. Wagi muszą być posortowane malejąco; wynik w tej samej
 * kolejności.
 */
object Treemap {
    fun layout(weights: List<Double>, area: Cell): List<Cell> {
        val total = weights.sum()
        if (weights.isEmpty() || total <= 0.0 || area.width <= 0f || area.height <= 0f) return weights.map { Cell(area.left, area.top, 0f, 0f) }
        val scale = area.width.toDouble() * area.height / total
        val sizes = weights.map { it * scale }
        val out = ArrayList<Cell>(weights.size)
        var free = area
        var row = mutableListOf<Double>()
        var i = 0
        while (i < sizes.size) {
            val side = minOf(free.width, free.height).toDouble()
            val next = sizes[i]
            if (row.isEmpty() || worst(row + next, side) <= worst(row, side)) {
                row.add(next)
                i++
            } else {
                free = place(row, free, out)
                row = mutableListOf()
            }
        }
        if (row.isNotEmpty()) place(row, free, out)
        return out
    }

    /** Najgorsza proporcja boków w rzędzie wzdłuż boku [side]. */
    private fun worst(row: List<Double>, side: Double): Double {
        val sum = row.sum()
        if (sum <= 0.0) return Double.MAX_VALUE
        val max = row.max()
        val min = row.min().coerceAtLeast(1e-9)
        val s2 = side * side
        return maxOf(s2 * max / (sum * sum), sum * sum / (s2 * min))
    }

    /** Kładzie rząd wzdłuż krótszego boku wolnego miejsca; zwraca to, co zostało. */
    private fun place(row: List<Double>, free: Cell, out: MutableList<Cell>): Cell {
        val sum = row.sum()
        return if (free.width >= free.height) {
            // Kolumna przy lewej krawędzi.
            val w = (sum / free.height).toFloat().coerceAtMost(free.width)
            var y = free.top
            row.forEach { size ->
                val h = if (w > 0f) (size / w).toFloat() else 0f
                out += Cell(free.left, y, w, h)
                y += h
            }
            Cell(free.left + w, free.top, free.width - w, free.height)
        } else {
            // Wiersz przy górnej krawędzi.
            val h = (sum / free.width).toFloat().coerceAtMost(free.height)
            var x = free.left
            row.forEach { size ->
                val w = if (h > 0f) (size / h).toFloat() else 0f
                out += Cell(x, free.top, w, h)
                x += w
            }
            Cell(free.left, free.top + h, free.width, free.height - h)
        }
    }
}
