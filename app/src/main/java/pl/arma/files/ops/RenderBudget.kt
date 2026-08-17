package pl.arma.files.ops

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
}
