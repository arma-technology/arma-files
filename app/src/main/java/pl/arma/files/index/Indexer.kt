package pl.arma.files.index

import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import pl.arma.files.guessMime
import java.io.File
import kotlin.coroutines.coroutineContext

data class IndexProgress(
    val running: Boolean = false,
    val scanned: Int = 0,
    val currentPath: String = "",
    val finishedAt: Long = 0L
)

/**
 * Buduje indeks, przechodząc po katalogach pamięci urządzenia.
 *
 * Świadomie obejmuje tylko ścieżki lokalne. Chodzenie po całym NAS-ie przez
 * SMB przy każdym odświeżeniu byłoby wolne i obciążałoby serwer, a korzyść —
 * natychmiastowe wyniki wyszukiwania — dotyczy przede wszystkim pamięci telefonu.
 */
class Indexer(
    private val db: FileIndexDb,
    private val scope: CoroutineScope
) {
    private val _progress = MutableStateFlow(IndexProgress())
    val progress: StateFlow<IndexProgress> = _progress.asStateFlow()

    private var job: Job? = null

    val isRunning: Boolean get() = job?.isActive == true

    fun start(roots: List<Uri>) {
        if (isRunning) return
        job = scope.launch(Dispatchers.IO) {
            _progress.value = IndexProgress(running = true)
            val batch = mutableListOf<IndexedFile>()
            var scanned = 0
            try {
                val visited = HashSet<String>()
                roots.forEach { root ->
                    val dir = root.path?.let(::File) ?: return@forEach
                    scanned = walk(dir, batch, scanned, depth = 0, visited = visited)
                }
                if (batch.isNotEmpty()) db.upsert(batch)
                _progress.value = IndexProgress(
                    running = false,
                    scanned = scanned,
                    finishedAt = System.currentTimeMillis()
                )
            } catch (error: Throwable) {
                // Przerwany skan zostawia to, co zdążył zebrać — częściowy
                // indeks jest lepszy niż żaden, a kolejny przebieg go uzupełni.
                if (batch.isNotEmpty()) runCatching { db.upsert(batch) }
                _progress.value = IndexProgress(running = false, scanned = scanned)
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _progress.value = _progress.value.copy(running = false)
    }

    private suspend fun walk(
        dir: File,
        batch: MutableList<IndexedFile>,
        counted: Int,
        depth: Int,
        visited: MutableSet<String>
    ): Int {
        var scanned = counted
        coroutineContext.ensureActive()

        // Katalogi zaczynające się kropką to zwykle dane aplikacji i cache;
        // własnego kosza też nie chcemy pokazywać w wynikach wyszukiwania.
        if (dir.name.startsWith('.')) return scanned

        // Dowiązanie wskazujące na katalog nadrzędny zapętliłoby skan w
        // nieskończoność, a sama głębokość drzewa wyczerpałaby stos.
        if (depth > MAX_DEPTH) return scanned
        val canonical = runCatching { dir.canonicalPath }.getOrNull() ?: return scanned
        if (!visited.add(canonical)) return scanned

        val children = dir.listFiles() ?: return scanned
        val parentUri = Uri.fromFile(dir).toString()

        children.forEach { child ->
            coroutineContext.ensureActive()
            val directory = child.isDirectory
            val mime = if (directory) "" else guessMime(child.name)
            batch += IndexedFile(
                uri = Uri.fromFile(child).toString(),
                parentUri = parentUri,
                name = child.name,
                size = if (child.isFile) child.length() else 0L,
                modified = child.lastModified(),
                isDirectory = directory,
                mime = mime,
                category = if (directory) IndexCategory.OTHER else IndexCategory.of(child.name, mime)
            )
            scanned++

            if (batch.size >= BATCH_SIZE) {
                db.upsert(batch.toList())
                batch.clear()
                _progress.value = _progress.value.copy(
                    scanned = scanned,
                    currentPath = child.absolutePath
                )
            }

            if (directory) scanned = walk(child, batch, scanned, depth + 1, visited)
        }
        return scanned
    }

    private companion object {
        const val BATCH_SIZE = 500

        /** Realne drzewa katalogów są płytsze; głębiej zaczynają się pętle. */
        const val MAX_DEPTH = 32
    }
}
