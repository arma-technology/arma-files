package pl.arma.viewer.sheet

/** Rozmiar strony w jednostkach dokumentu. */
data class SheetSize(val width: Float, val height: Float)

/**
 * Rozmieszczenie stron dokumentu przy powiększeniu 1, w pikselach ekranu.
 * Strony mają wspólną szerokość widoku pomniejszoną o margines, a wysokość
 * wynika z ich proporcji — arkusz A0 i strona A4 w jednym pliku też się mieszczą.
 */
class SheetLayout(
    /** Rozmiary stron w jednostkach dokumentu: punkty PDF albo jednostki rysunku CAD. */
    pageSizes: List<SheetSize>,
    val viewWidth: Float,
    val margin: Float,
    val gap: Float,
    /** Miejsce nad pierwszą stroną, np. pod narzędziami nałożonymi na treść. */
    val topPadding: Float = 0f,
    val bottomPadding: Float = 0f
) {
    val pageWidth: Float = (viewWidth - 2 * margin).coerceAtLeast(1f)
    val heights: FloatArray = FloatArray(pageSizes.size) { index ->
        val size = pageSizes[index]
        pageWidth * size.height.coerceAtLeast(1e-6f) / size.width.coerceAtLeast(1e-6f)
    }
    val tops: FloatArray = FloatArray(pageSizes.size).also { tops ->
        var y = topPadding + margin
        for (index in tops.indices) {
            tops[index] = y
            y += heights[index] + gap
        }
    }
    val height: Float = if (tops.isEmpty()) 0f else tops.last() + heights.last() + margin + bottomPadding
    val pageCount: Int get() = heights.size

    /**
     * Punkt dokumentu jako strona i ułamki jej szerokości i wysokości —
     * niezależny od układu, więc przenosi się między pionem a poziomem.
     */
    fun anchorAt(x: Float, y: Float): Triple<Int, Float, Float> {
        val page = pageAt(y)
        return Triple(page, (x - margin) / pageWidth, (y - tops[page]) / heights[page])
    }

    /** Odwrotność [anchorAt]: punkt dokumentu przy powiększeniu 1. */
    fun pointOf(page: Int, fractionX: Float, fractionY: Float): Pair<Float, Float> =
        (margin + fractionX * pageWidth) to (tops[page] + fractionY * heights[page])

    /** Pikseli układu (przy powiększeniu 1) na jednostkę dokumentu strony [page]. */
    fun unitScale(page: Int, size: SheetSize): Float = pageWidth / size.width.coerceAtLeast(1e-6f)

    /** Strona, na której leży punkt [y] dokumentu przy powiększeniu 1. */
    fun pageAt(y: Float): Int {
        if (pageCount == 0) return 0
        var low = 0
        var high = pageCount - 1
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (tops[mid] <= y) low = mid else high = mid - 1
        }
        return low
    }
}

/**
 * Powiększenie i przesunięcie widoku. Punkt dokumentu `p` (przy powiększeniu 1)
 * leży na ekranie w `p * zoom - (x, y)`.
 *
 * Cały stan w jednym obiekcie, zmieniany jednym przypisaniem: powiększenie
 * i przesunięcie zmieniają się w tej samej klatce. Rozdzielone między listę
 * a własne przesunięcie rozjeżdżały się o klatkę i obraz szarpał.
 */
data class SheetView(val zoom: Float = 1f, val x: Float = 0f, val y: Float = 0f) {

    /** Przesunięcie o ruch palca: treść jedzie razem z nim. */
    fun panned(dx: Float, dy: Float): SheetView = copy(x = x - dx, y = y - dy)

    /** Zmiana powiększenia, przy której punkt ekranu ([fx], [fy]) zostaje w miejscu. */
    fun zoomedAround(factor: Float, fx: Float, fy: Float, maxZoom: Float): SheetView {
        val next = (zoom * factor).coerceIn(1f, maxZoom)
        val applied = next / zoom
        return SheetView(next, (x + fx) * applied - fx, (y + fy) * applied - fy)
    }

    /**
     * Widok mieszczący się w dokumencie. Dokument niższy od ekranu (poziomy
     * arkusz na telefonie) stoi pośrodku zamiast przyklejać się do góry.
     */
    fun clamped(layout: SheetLayout, viewHeight: Float, maxZoom: Float): SheetView {
        val z = zoom.coerceIn(1f, maxZoom)
        val maxX = (layout.viewWidth * z - layout.viewWidth).coerceAtLeast(0f)
        val contentHeight = layout.height * z
        val clampedY = if (contentHeight <= viewHeight) {
            -(viewHeight - contentHeight) / 2f
        } else {
            y.coerceIn(0f, contentHeight - viewHeight)
        }
        return SheetView(z, x.coerceIn(0f, maxX), clampedY)
    }

    /** Widok z punktem dokumentu ([px], [py] przy powiększeniu 1) na środku ekranu. */
    fun centeredOn(px: Float, py: Float, zoom: Float, viewWidth: Float, viewHeight: Float): SheetView =
        SheetView(zoom, px * zoom - viewWidth / 2f, py * zoom - viewHeight / 2f)

    /**
     * Ten sam punkt dokumentu na środku ekranu po zmianie układu, np. po
     * obrocie. Punkt przechodzi jako miejsce na stronie, a nie jako piksele:
     * marginesy i zapas pod narzędziami nie skalują się z szerokością, a przy
     * 16x każdy ich piksel przesuwałby widok o szesnaście.
     */
    fun relaidOut(old: SheetLayout, oldHeight: Float, new: SheetLayout, newHeight: Float): SheetView {
        val (page, fractionX, fractionY) = old.anchorAt((x + old.viewWidth / 2f) / zoom, (y + oldHeight / 2f) / zoom)
        val (px, py) = new.pointOf(page, fractionX, fractionY)
        return centeredOn(px, py, zoom, new.viewWidth, newHeight)
    }
}
