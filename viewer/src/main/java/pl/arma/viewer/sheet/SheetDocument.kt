package pl.arma.viewer.sheet

import android.graphics.Bitmap
import android.graphics.RectF
import kotlinx.coroutines.flow.StateFlow
import java.io.Closeable
import java.io.File

/**
 * Dokument złożony z arkuszy, który przeglądarka umie rysować, przeszukiwać
 * i mierzyć — PDF albo rysunek CAD.
 *
 * Współrzędne „strony” to jednostki dokumentu (punkty PDF, jednostki rysunku)
 * z początkiem w lewym górnym rogu i osią Y w dół, jak na ekranie.
 */
interface SheetDocument : Closeable {
    /** Krótka nazwa rodzaju do komunikatów: „PDF”, „DXF”, „DWG”. */
    val kindLabel: String

    val pageSizes: List<SheetSize>
    val pageCount: Int get() = pageSizes.size

    /**
     * Rośnie, gdy zmienia się to, co dokument rysuje (warstwy, komentarze) —
     * przeglądarka wyrzuca wtedy wyrenderowane obrazy.
     */
    val revision: StateFlow<Int>

    suspend fun renderPage(index: Int, targetWidth: Int): Bitmap

    suspend fun renderThumbnail(index: Int, width: Int): Bitmap

    suspend fun renderRegion(index: Int, region: RenderBudget.Region): Bitmap

    val canSearch: Boolean

    suspend fun search(query: String, onProgress: (Int) -> Unit = {}): List<SheetMatch>

    /** Linie strony do przyciągania kursora pomiaru; liczone przy pierwszym użyciu. */
    suspend fun snapGeometry(page: Int): SnapGeometry

    /** Skala odczytana z dokumentu albo jego jednostek; null, gdy nie wiadomo. */
    suspend fun detectScale(page: Int): SheetScale?

    /** Czy liczby w skali to jednostki rysunku (CAD), a nie skala papieru (PDF). */
    val modelUnits: Boolean

    val layers: StateFlow<List<SheetLayer>>

    suspend fun setLayerVisible(id: String, visible: Boolean)

    suspend fun setAllLayersVisible(visible: Boolean) {
        layers.value.forEach { if (it.visible != visible) setLayerVisible(it.id, visible) }
    }

    val comments: StateFlow<List<SheetComment>>

    /** Czy można dodawać komentarze i zapisywać je w pliku. */
    val canAnnotate: Boolean get() = false

    suspend fun addComment(page: Int, x: Float, y: Float, text: String, author: String) {
        throw UnsupportedOperationException()
    }

    suspend fun addMeasurement(measurement: Measurement, caption: String, author: String) {
        throw UnsupportedOperationException()
    }

    suspend fun deleteComment(id: String) {
        throw UnsupportedOperationException()
    }

    /** Kolor arkusza (ARGB) — rysunki CAD bywają na ciemnym tle. */
    val paperColor: StateFlow<Int>

    /** Nazwy trybów tła do wyboru; pusta lista, gdy tło jest stałe. */
    val backgroundModes: List<String> get() = emptyList()

    val backgroundMode: Int get() = 0

    suspend fun setBackgroundMode(index: Int) = Unit

    /** Uwagi o tym, czego nie dało się pokazać (np. bryły 3D w rysunku CAD). */
    val notes: List<String> get() = emptyList()

    /** Czy są zmiany do zapisania w pliku. */
    val dirty: StateFlow<Boolean>

    /** Zapisuje dokument z komentarzami do [target]. */
    suspend fun saveTo(target: File) {
        throw UnsupportedOperationException()
    }
}

/** Trafienie wyszukiwania; prostokąty w jednostkach strony. */
class SheetMatch(val page: Int, val rects: List<RectF>) {
    /** Obrys całego trafienia; tekst łamany na dwie linie ma dwa prostokąty. */
    val bounds: RectF = RectF(rects.first()).apply { rects.drop(1).forEach { union(it) } }
}

data class SheetLayer(
    val id: String,
    val name: String,
    val visible: Boolean,
    /** Kolor warstwy jako ARGB; null, gdy dokument go nie określa. */
    val color: Int? = null,
    /** Warstwa zablokowana w pliku — widoczność zmieniana tylko przez dokument. */
    val locked: Boolean = false
)

/** Komentarz albo inna adnotacja zapisana w dokumencie. */
data class SheetComment(
    val id: String,
    val page: Int,
    /** Obszar adnotacji w jednostkach strony. */
    val rect: RectF,
    val type: String,
    val author: String?,
    val text: String?,
    val modified: String?,
    /** Dodany w tej sesji i jeszcze niezapisany. */
    val pending: Boolean = false
)
