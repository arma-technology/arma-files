package pl.arma.dwg

/** Wywołania LibreDWG. Używane tylko w procesie [DwgConversionService]. */
internal object DwgNative {
    init {
        System.loadLibrary("dwgjni")
    }

    /** Zapisuje [output] jako DXF; zwraca opis błędu albo null. */
    @JvmStatic
    external fun toDxf(input: String, output: String, binary: Boolean): String?
}
