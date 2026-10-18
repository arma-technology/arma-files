package pl.arma.viewer.sheet

/**
 * Ograniczenie rozmiaru renderowanej bitmapy.
 *
 * Strona A4 przy szerokości 3000 px to około 3000×4240 pikseli, czyli 51 MB w
 * ARGB_8888. Kilka takich stron widocznych naraz wyczerpuje pamięć aplikacji i
 * renderowanie kończy się błędem — dlatego rozmiar tnie budżet pikseli, a nie
 * sama szerokość.
 */
object RenderBudget {

    /** Około 12 MB na bitmapę w ARGB_8888. */
    const val MAX_PIXELS = 3_000_000

    const val MIN_WIDTH = 720

    /**
     * Zwraca (szerokość, wysokość) mieszczące się w budżecie, zachowując
     * proporcje strony.
     */
    fun fit(requestedWidth: Int, pageWidth: Int, pageHeight: Int): Pair<Int, Int> {
        val safePageWidth = pageWidth.coerceAtLeast(1)
        val safePageHeight = pageHeight.coerceAtLeast(1)
        val width = requestedWidth.coerceAtLeast(MIN_WIDTH)
        val height = (width.toFloat() * safePageHeight / safePageWidth).toInt().coerceAtLeast(1)

        val pixels = width.toLong() * height
        if (pixels <= MAX_PIXELS) return width to height

        val factor = Math.sqrt(MAX_PIXELS.toDouble() / pixels)
        val cappedWidth = (width * factor).toInt().coerceAtLeast(1)
        val cappedHeight = (height * factor).toInt().coerceAtLeast(1)
        return cappedWidth to cappedHeight
    }

    /**
     * Szerokość renderowania dla danego powiększenia, zaokrąglona do kilku
     * stopni. Bez tego każda zmiana powiększenia w trakcie gestu wywoływałaby
     * ponowne renderowanie wszystkich widocznych stron.
     */
    fun widthForZoom(zoom: Float, baseWidth: Int = 1400): Int = when {
        zoom < 1.5f -> baseWidth
        zoom < 2.5f -> baseWidth * 2
        else -> baseWidth * 3
    }

    /**
     * Widoczny fragment strony wyświetlanej w rozmiarze [pageWidth]×[pageHeight]
     * pikseli. Współrzędne są w pikselach tej wyświetlanej strony; [scale]
     * mówi, w jakiej części tej rozdzielczości wycinek da się wyrenderować.
     */
    data class Region(
        val pageWidth: Int,
        val pageHeight: Int,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val scale: Float
    ) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    /**
     * Fragment strony do wyrenderowania osobno, w rozdzielczości ekranu.
     *
     * Bitmapa całej strony mieści się w budżecie tylko do pewnego powiększenia;
     * dalej jest rozciągana i rozmyta. Widać jednak najwyżej tyle, ile ma ekran,
     * więc wycinek widocznej części zawsze się mieści — niezależnie od tego,
     * jak bardzo strona jest powiększona.
     *
     * @param pageLeft pozycja lewej krawędzi strony względem widoku (ujemna,
     *   gdy strona wystaje w lewo poza ekran)
     * @param baseWidth szerokość bitmapy całej strony; dopóki strona nie jest
     *   wyświetlana szerzej, wycinek nic nie wnosi
     * @return null, gdy strona jest poza widokiem albo wystarcza bitmapa całości
     */
    fun detailRegion(
        pageLeft: Float,
        pageTop: Float,
        pageWidth: Int,
        pageHeight: Int,
        viewWidth: Int,
        viewHeight: Int,
        baseWidth: Int
    ): Region? {
        if (pageWidth <= 0 || pageHeight <= 0) return null
        // Rozciągnięcie o kilka procent jest niewidoczne, a każdy wycinek to
        // ponowne renderowanie strony.
        if (pageWidth <= baseWidth * DETAIL_THRESHOLD) return null

        val left = (-pageLeft).coerceIn(0f, pageWidth.toFloat()).toInt()
        val top = (-pageTop).coerceIn(0f, pageHeight.toFloat()).toInt()
        val right = kotlin.math.ceil(viewWidth - pageLeft).coerceIn(0f, pageWidth.toFloat()).toInt()
        val bottom = kotlin.math.ceil(viewHeight - pageTop).coerceIn(0f, pageHeight.toFloat()).toInt()
        if (right - left < MIN_DETAIL_SIDE || bottom - top < MIN_DETAIL_SIDE) return null

        // Duży ekran tabletu potrafi przekroczyć budżet sam w sobie; wtedy
        // wycinek jest odrobinę mniej ostry, ale wciąż ostrzejszy od całości.
        val pixels = (right - left).toLong() * (bottom - top)
        val scale = if (pixels <= MAX_PIXELS) 1f else Math.sqrt(MAX_PIXELS.toDouble() / pixels).toFloat()
        return Region(pageWidth, pageHeight, left, top, right, bottom, scale)
    }

    private const val DETAIL_THRESHOLD = 1.1f
    private const val MIN_DETAIL_SIDE = 8
}
