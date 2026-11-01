package pl.arma.files

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Limit pamięci podręcznej: rysunki DWG przekonwertowane do DXF, kopie plików
 * z sieci do podglądu i udostępniania, kopie archiwów. Każde otwarcie
 * rysunku z serwera dokłada kopię, więc bez sprzątania pamięć tylko rośnie.
 *
 * Usuwane są pliki nieużywane dłużej niż [maxAgeDays], a gdy razem zajmują
 * więcej niż [maxBytes] — najdawniej używane. Pliki tknięte w ostatnich
 * minutach zostają, bo mogą być właśnie otwarte.
 */
class CacheJanitor(private val context: Context, private val scope: CoroutineScope) {

    companion object {
        /** Katalogi w `cacheDir`, którymi zarządzamy. */
        val DIRECTORIES = listOf("dwg_dxf", "doc_preview", "image_preview", "media_preview", "remote_files", "archives", "archive_out", "pdf_work", VideoThumbnails.DIRECTORY)

        val SIZE_OPTIONS_MB = listOf(100, 250, 500, 1000, 2000)
        val AGE_OPTIONS_DAYS = listOf(1, 3, 7, 14, 30)

        private const val DEFAULT_MB = 500
        private const val DEFAULT_DAYS = 7
        private val IN_USE_MS = TimeUnit.MINUTES.toMillis(10)
    }

    private val prefs = context.getSharedPreferences("cache", Context.MODE_PRIVATE)
    private var pending: Job? = null

    var maxMegabytes: Int
        get() = prefs.getInt("max_mb", DEFAULT_MB)
        set(value) {
            prefs.edit().putInt("max_mb", value).apply()
            applyDwgLimit()
            trimAsync()
        }

    var maxAgeDays: Int
        get() = prefs.getInt("max_days", DEFAULT_DAYS)
        set(value) {
            prefs.edit().putInt("max_days", value).apply()
            trimAsync()
        }

    val maxBytes: Long get() = maxMegabytes * 1_048_576L

    private fun roots(): List<File> = DIRECTORIES.map { File(context.cacheDir, it) }

    /** Konwerter DWG pilnuje limitu sam, zaraz po konwersji. */
    fun applyDwgLimit() {
        pl.arma.dwg.LibreDwgConverter.cacheLimitBytes = maxBytes / 2
    }

    fun usedBytes(): Long = roots().sumOf { root -> root.walkBottomUp().filter { it.isFile }.sumOf { it.length() } }

    /** Sprzątanie w tle; kolejne wywołania w trakcie nie mnożą pracy. */
    fun trimAsync() {
        if (pending?.isActive == true) return
        pending = scope.launch { trim() }
    }

    @Synchronized
    fun trim(now: Long = System.currentTimeMillis()) {
        val maxAge = TimeUnit.DAYS.toMillis(maxAgeDays.toLong())
        // Katalogi robocze PDF należą do otwartych dokumentów; usuwamy tylko
        // te sprzed doby, porzucone przez proces zabity w trakcie pracy.
        File(context.cacheDir, "pdf_work").listFiles()
            ?.filter { now - it.lastModified() > TimeUnit.DAYS.toMillis(1) }
            ?.forEach { it.deleteRecursively() }
        val files = roots().filter { it.name != "pdf_work" }
            .flatMap { root -> root.walkBottomUp().filter { it.isFile }.toList() }
        val removable = files.filter { now - it.lastModified() > IN_USE_MS }
        removable.filter { now - it.lastModified() > maxAge }.forEach { it.delete() }

        val remaining = files.filter { it.exists() }
        var total = remaining.sumOf { it.length() }
        for (file in remaining.filter { now - it.lastModified() > IN_USE_MS }.sortedBy { it.lastModified() }) {
            if (total <= maxBytes) break
            val size = file.length()
            if (file.delete()) total -= size
        }
        roots().filter { it.name != "pdf_work" }.forEach { root ->
            root.walkBottomUp().filter { it.isDirectory && it != root && it.list()?.isEmpty() == true }.forEach { it.delete() }
        }
    }

    /**
     * „Wyczyść teraz” — wszystko poza katalogami roboczymi otwartych PDF.
     * Kopia potrzebna jeszcze raz zostanie po prostu pobrana od nowa.
     */
    @Synchronized
    fun clear() {
        roots().filter { it.name != "pdf_work" }.forEach { root ->
            root.walkBottomUp().filter { it.isFile }.forEach { it.delete() }
        }
    }
}
