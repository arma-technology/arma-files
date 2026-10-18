package pl.arma.viewer.sheet

import androidx.annotation.StringRes
import pl.arma.viewer.R
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Skala strony: ile milimetrów w rzeczywistości odpowiada jednej jednostce
 * strony (punktowi PDF albo jednostce rysunku CAD).
 */
data class SheetScale(val mmPerUnit: Double, val label: String, val source: ScaleSource) {
    companion object {
        /** Punkt PDF to 1/72 cala na papierze. */
        const val MM_PER_POINT = 25.4 / 72.0

        /** Skala rysunku „1:N” dla strony PDF. */
        fun pdfRatio(denominator: Double, source: ScaleSource): SheetScale =
            SheetScale(MM_PER_POINT * denominator, "1:${formatRatio(denominator)}", source)

        fun formatRatio(value: Double): String =
            if (abs(value - value.roundToInt()) < 1e-6) value.roundToInt().toString()
            else String.format(Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.')
    }
}

enum class ScaleSource(@StringRes val label: Int) {
    /** Słownik pomiarowy zapisany w PDF przez program CAD. */
    DOCUMENT(R.string.viewer_scale_from_file),
    /** Skala odczytana z tekstu tabelki rysunkowej — do potwierdzenia. */
    TITLE_BLOCK(R.string.viewer_scale_from_title_block),
    /** Jednostki zapisane w rysunku CAD. */
    UNITS(R.string.viewer_scale_from_units),
    USER(R.string.viewer_scale_user),
    CALIBRATION(R.string.viewer_scale_calibration)
}

/** Odcinek pomiaru w jednostkach strony. */
data class Measurement(
    val page: Int,
    val ax: Float,
    val ay: Float,
    val bx: Float,
    val by: Float
) {
    val lengthUnits: Double get() = hypot((bx - ax).toDouble(), (by - ay).toDouble())
}

object MeasureFormat {
    /** Separator dziesiętny z języka aplikacji: „2,997 m” po polsku, „2.997 m” po angielsku. */

    /**
     * Długość czytelna na budowie: do metra w milimetrach, dalej w metrach
     * z dokładnością do milimetra.
     */
    fun length(mm: Double): String = when {
        mm.isNaN() -> "—"
        abs(mm) < 1000 -> String.format(Locale.getDefault(), "%.0f mm", mm)
        else -> String.format(Locale.getDefault(), "%.3f m", mm / 1000.0)
    }

    fun area(mm2: Double): String = String.format(Locale.getDefault(), "%.2f m²", mm2 / 1_000_000.0)

    /**
     * Skala „1:N” z tekstu strony. Najpierw opisane wprost („skala 1:50”,
     * „scale 1:100”), potem samotne „1:N” — ale tylko typowe skale rysunków,
     * bo „1:3” trafia się też w opisach mieszanek.
     */
    /** „Skala” w językach, w których bywają opisane tabelki rysunkowe. */
    private const val SCALE_WORDS = "skala|scale|sk\\.|maßstab|massstab|měřítko|meritko|mierka|méretarány|meretarany|" +
        "масштаб|scara|merilo|mjerilo|razmera|размера|mastelis|mērogs|merogs|mõõtkava|shkalla|мерило"

    fun detectScale(text: String): Int? {
        val labelled = Regex("""(?iu)(?:$SCALE_WORDS)\s*[:=]?\s*1\s*[:/]\s*(\d{1,5})""")
            .findAll(text).mapNotNull { it.groupValues[1].toIntOrNull() }.filter { it > 0 }.toList()
        if (labelled.isNotEmpty()) return labelled.groupingBy { it }.eachCount().maxByOrNull { it.value }!!.key
        val bare = Regex("""(?<![\d.,:])1\s*:\s*(\d{1,4})(?!\d|[.,:]\d)""")
            .findAll(text).mapNotNull { it.groupValues[1].toIntOrNull() }.filter { it in TYPICAL }.toList()
        if (bare.isEmpty()) return null
        return bare.groupingBy { it }.eachCount().maxByOrNull { it.value }!!.key
    }

    val TYPICAL = setOf(1, 2, 5, 10, 20, 25, 50, 75, 100, 200, 250, 500, 1000, 2000, 2500, 5000)

    /** Skale proponowane w wyborze. */
    val COMMON = listOf(1, 5, 10, 20, 25, 50, 100, 200, 250, 500, 1000)

    /** Milimetry z liczby wpisanej przez człowieka: „3,25” z jednostką albo bez. */
    fun parseLength(input: String, defaultUnit: String = "mm"): Double? {
        val match = Regex("""^\s*([0-9]+(?:[.,][0-9]+)?)\s*(mm|cm|dm|m)?\s*$""", RegexOption.IGNORE_CASE)
            .find(input) ?: return null
        val value = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val unit = match.groupValues[2].ifEmpty { defaultUnit }.lowercase(Locale.ROOT)
        return value * when (unit) {
            "m" -> 1000.0
            "dm" -> 100.0
            "cm" -> 10.0
            else -> 1.0
        }
    }

    /** Milimetry w jednostce długości z PDF albo CAD; null, gdy nieznana. */
    fun mmPerUnit(unit: String): Double? = when (unit.trim().lowercase(Locale.ROOT).trimEnd('.')) {
        "mm", "millimeter", "millimeters", "millimetre" -> 1.0
        "cm", "centimeter", "centimeters" -> 10.0
        "dm" -> 100.0
        "m", "meter", "meters", "metre", "metres" -> 1000.0
        "km" -> 1_000_000.0
        "in", "inch", "inches", "\"" -> 25.4
        "ft", "foot", "feet", "'" -> 304.8
        "yd", "yard", "yards" -> 914.4
        "mi", "mile", "miles" -> 1_609_344.0
        "pt", "point", "points" -> SheetScale.MM_PER_POINT
        else -> null
    }
}
