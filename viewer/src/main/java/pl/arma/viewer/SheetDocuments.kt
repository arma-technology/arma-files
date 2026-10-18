package pl.arma.viewer

import android.content.Context
import pl.arma.viewer.cad.CadDocument
import pl.arma.viewer.pdf.PdfDocument
import pl.arma.viewer.sheet.SheetDocument
import java.io.File
import java.util.Locale

/** Otwieranie plików, które umie pokazać przeglądarka arkuszy. */
object SheetDocuments {

    /**
     * Zamiana DWG na DXF. Dostarcza ją gospodarz — konwerter to osobny moduł
     * na licencji GPL, którego ta biblioteka nie wymaga.
     */
    fun interface DwgConverter {
        /** Zwraca plik DXF (tekstowy albo binarny) z rysunku [dwg]. */
        suspend fun toDxf(context: Context, dwg: File): File
    }

    @Volatile
    var dwgConverter: DwgConverter? = null

    private val cadExtensions = setOf("dxf", "dwg")

    fun canOpen(name: String): Boolean = extension(name).let { it == "pdf" || it in cadExtensions }

    fun isCad(name: String): Boolean = extension(name) in cadExtensions

    suspend fun open(context: Context, file: File, name: String): SheetDocument = when (extension(name)) {
        "dxf" -> CadDocument.open(context, file, "DXF")
        "dwg" -> {
            val converter = dwgConverter ?: error("Ta wersja aplikacji nie obsługuje plików DWG.")
            CadDocument.open(context, converter.toDxf(context, file), "DWG")
        }
        else -> PdfDocument.open(context, file)
    }

    private fun extension(name: String) = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
}
