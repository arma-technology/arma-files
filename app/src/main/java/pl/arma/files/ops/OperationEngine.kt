package pl.arma.files.ops

import android.net.Uri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pl.arma.files.DIRECTORY_MIME
import pl.arma.files.FileItem
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.coroutineContext

/**
 * Kolejka operacji na plikach: kopiowanie, przenoszenie, kosz i usuwanie.
 *
 * Zadania wykonywane są po kolei, a nie równolegle — przy backendach sieciowych
 * (FTP, SMB) kilka równoległych transferów zwykle spowalnia całość i potrafi
 * wyczerpać limit sesji po stronie serwera.
 *
 * Silnik nie zna Androida poza [Uri], więc jego logikę da się przetestować
 * na atrapie [FileOps].
 */
class OperationEngine(
    private val ops: FileOps,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val _operations = MutableStateFlow<List<Operation>>(emptyList())
    val operations: StateFlow<List<Operation>> = _operations.asStateFlow()

    private val _pendingConflict = MutableStateFlow<PendingConflict?>(null)
    val pendingConflict: StateFlow<PendingConflict?> = _pendingConflict.asStateFlow()

    private val _history = MutableStateFlow<List<OperationRecord>>(emptyList())
    val history: StateFlow<List<OperationRecord>> = _history.asStateFlow()

    private val paused = MutableStateFlow(false)
    private var conflictAnswer: CompletableDeferred<ConflictDecision>? = null
    private var worker: Job? = null
    private var currentJob: Job? = null
    private var currentId: String? = null

    /** Dodaje zadanie do kolejki i zwraca jego identyfikator. */
    fun enqueue(
        kind: OperationKind,
        sources: List<FileItem>,
        targetParent: Uri?
    ): String {
        val operation = Operation(
            id = UUID.randomUUID().toString(),
            kind = kind,
            sources = sources,
            targetParent = targetParent
        )
        _operations.update { it + operation }
        startWorker()
        return operation.id
    }

    fun pause(id: String) {
        if (id == currentId) paused.value = true
        patch(id) { it.copy(status = OperationStatus.PAUSED) }
    }

    fun resume(id: String) {
        if (id == currentId) paused.value = false
        patch(id) { it.copy(status = OperationStatus.RUNNING) }
    }

    fun cancel(id: String) {
        if (id == currentId) {
            paused.value = false
            currentJob?.cancel()
        } else {
            patch(id) { it.copy(status = OperationStatus.CANCELLED) }
        }
    }

    /** Odpowiedź na konflikt zgłoszony przez [pendingConflict]. */
    fun resolveConflict(decision: ConflictDecision) {
        _pendingConflict.value = null
        conflictAnswer?.complete(decision)
        conflictAnswer = null
    }

    fun clearHistory() {
        _history.value = emptyList()
    }

    /** Usuwa z listy zadania zakończone, zostawiając bieżące i oczekujące. */
    fun clearFinished() {
        _operations.update { list -> list.filterNot { it.isFinished } }
    }

    private fun startWorker() {
        if (worker?.isActive == true) return
        worker = scope.launch {
            while (true) {
                val next = _operations.value.firstOrNull { it.status == OperationStatus.QUEUED } ?: break
                currentId = next.id
                paused.value = false
                val job = scope.launch { runOperation(next.id) }
                currentJob = job
                job.join()
                currentJob = null
                currentId = null
            }
        }
    }

    private suspend fun runOperation(id: String) {
        val operation = _operations.value.firstOrNull { it.id == id } ?: return
        patch(id) { it.copy(status = OperationStatus.RUNNING) }

        val context = RunContext(id, operation.kind)
        val startedAt = now()
        try {
            val plan = plan(operation.sources)
            patch(id) {
                it.copy(progress = it.progress.copy(filesTotal = plan.files, bytesTotal = plan.bytes))
            }

            when (operation.kind) {
                OperationKind.DELETE -> operation.sources.forEach { item ->
                    coroutineContext.ensureActive()
                    awaitResume()
                    deleteTree(item, context)
                }
                else -> {
                    val target = operation.targetParent
                        ?: throw IOException("Brak katalogu docelowego.")
                    operation.sources.forEach { item ->
                        coroutineContext.ensureActive()
                        awaitResume()
                        transfer(item, target, context, startedAt)
                    }
                }
            }

            patch(id) { it.copy(status = OperationStatus.COMPLETED, skipped = context.skipped) }
            record(id, OperationStatus.COMPLETED, context)
        } catch (cancellation: CancellationException) {
            patch(id) { it.copy(status = OperationStatus.CANCELLED, skipped = context.skipped) }
            record(id, OperationStatus.CANCELLED, context)
            throw cancellation
        } catch (error: Throwable) {
            val message = error.message ?: "Operacja nie powiodła się."
            patch(id) { it.copy(status = OperationStatus.FAILED, error = message, skipped = context.skipped) }
            record(id, OperationStatus.FAILED, context, message)
        } finally {
            if (_pendingConflict.value?.operationId == id) {
                _pendingConflict.value = null
                conflictAnswer = null
            }
        }
    }

    private data class Plan(val files: Int, val bytes: Long)

    private suspend fun plan(sources: List<FileItem>): Plan {
        var files = 0
        var bytes = 0L
        suspend fun walk(item: FileItem) {
            coroutineContext.ensureActive()
            if (item.isDirectory) {
                ops.listChildren(item.uri).forEach { walk(it) }
            } else {
                files++
                bytes += item.size
            }
        }
        sources.forEach { walk(it) }
        return Plan(files, bytes)
    }

    /**
     * Kopiuje lub przenosi jeden element. Przy przenoszeniu źródło kasowane jest
     * dopiero po udanym skopiowaniu *tego* elementu — dzięki temu przerwana
     * operacja nie zostawia plików skasowanych bez kopii.
     */
    private suspend fun transfer(
        item: FileItem,
        targetParent: Uri,
        context: RunContext,
        startedAt: Long,
        topLevel: Boolean = true
    ) {
        val existing = context.namesIn(targetParent)
        var name = item.name

        if (name.lowercase() in existing.keys.map { it.lowercase() }) {
            val clash = existing.entries.first { it.key.equals(name, ignoreCase = true) }.value
            when (context.decide(item, clash)) {
                ConflictAction.SKIP -> {
                    context.skipped++
                    skipProgress(context.operationId, item)
                    return
                }
                ConflictAction.REPLACE -> {
                    ops.delete(clash)
                    context.forget(targetParent, clash.name)
                }
                ConflictAction.KEEP_BOTH -> {
                    name = ConflictResolver.uniqueName(name, existing.keys, item.isDirectory)
                }
            }
        }

        val moving = context.kind != OperationKind.COPY

        if (moving && !item.isDirectory) {
            val fast = ops.fastMove(item, targetParent, name)
            if (fast != null) {
                context.remember(targetParent, name, item.copy(uri = fast, name = name))
                if (topLevel) context.pairs += item.uri.toString() to fast.toString()
                advance(context.operationId, item.size, name, startedAt)
                return
            }
        }

        if (item.isDirectory) {
            val created = ops.createFolder(targetParent, name)
                ?: throw IOException("Nie udało się utworzyć folderu: $name")
            context.remember(targetParent, name, item.copy(uri = created, name = name))
            ops.listChildren(item.uri).forEach { child ->
                coroutineContext.ensureActive()
                awaitResume()
                transfer(child, created, context, startedAt, topLevel = false)
            }
            if (moving && !ops.delete(item)) {
                throw IOException("Nie udało się usunąć katalogu źródłowego: ${item.name}")
            }
            if (topLevel) context.pairs += item.uri.toString() to created.toString()
        } else {
            val mime = if (item.mimeType == DIRECTORY_MIME) "application/octet-stream" else item.mimeType
            val created = ops.createFile(targetParent, name, mime)
                ?: throw IOException("Nie udało się utworzyć pliku: $name")
            context.remember(targetParent, name, item.copy(uri = created, name = name))
            copyBytes(item, created, context.operationId, name, startedAt)
            if (moving && !ops.delete(item)) {
                throw IOException("Kopia powstała, ale nie udało się usunąć źródła: ${item.name}")
            }
            if (topLevel) context.pairs += item.uri.toString() to created.toString()
        }
    }

    private suspend fun copyBytes(
        source: FileItem,
        target: Uri,
        operationId: String,
        displayName: String,
        startedAt: Long
    ) {
        ops.openInput(source.uri).use { input ->
            ops.openOutput(target).use { output ->
                val buffer = ByteArray(64 * 1024)
                var sinceUpdate = 0L
                var lastUpdate = now()
                while (true) {
                    coroutineContext.ensureActive()
                    awaitResume()
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    sinceUpdate += read
                    val moment = now()
                    if (moment - lastUpdate >= PROGRESS_INTERVAL_MS) {
                        advanceBytes(operationId, sinceUpdate, displayName, startedAt)
                        sinceUpdate = 0L
                        lastUpdate = moment
                    }
                }
                if (sinceUpdate > 0L) advanceBytes(operationId, sinceUpdate, displayName, startedAt)
            }
        }
        patch(operationId) { it.copy(progress = it.progress.copy(filesDone = it.progress.filesDone + 1)) }
    }

    private suspend fun deleteTree(item: FileItem, context: RunContext) {
        if (item.isDirectory) {
            ops.listChildren(item.uri).forEach { child ->
                coroutineContext.ensureActive()
                awaitResume()
                deleteTree(child, context)
            }
        }
        if (!ops.delete(item)) throw IOException("Nie udało się usunąć: ${item.name}")
        if (!item.isDirectory) {
            patch(context.operationId) {
                it.copy(
                    progress = it.progress.copy(
                        filesDone = it.progress.filesDone + 1,
                        currentName = item.name
                    )
                )
            }
        }
    }

    private fun advanceBytes(operationId: String, bytes: Long, name: String, startedAt: Long) {
        patch(operationId) { operation ->
            val done = operation.progress.bytesDone + bytes
            operation.copy(progress = operation.progress.copy(
                bytesDone = done,
                currentName = name,
                bytesPerSecond = speed(done, startedAt)
            ))
        }
    }

    private fun advance(operationId: String, bytes: Long, name: String, startedAt: Long) {
        patch(operationId) { operation ->
            val done = operation.progress.bytesDone + bytes
            operation.copy(progress = operation.progress.copy(
                bytesDone = done,
                filesDone = operation.progress.filesDone + 1,
                currentName = name,
                bytesPerSecond = speed(done, startedAt)
            ))
        }
    }

    /** Pominięty plik dolicza się do postępu, żeby pasek doszedł do końca. */
    private suspend fun skipProgress(operationId: String, item: FileItem) {
        val plan = if (item.isDirectory) plan(listOf(item)) else Plan(1, item.size)
        patch(operationId) {
            it.copy(progress = it.progress.copy(
                bytesDone = it.progress.bytesDone + plan.bytes,
                filesDone = it.progress.filesDone + plan.files
            ))
        }
    }

    private fun speed(bytesDone: Long, startedAt: Long): Long {
        val elapsed = (now() - startedAt).coerceAtLeast(1L)
        return bytesDone * 1000L / elapsed
    }

    private suspend fun awaitResume() {
        if (paused.value) paused.first { !it }
    }

    private fun record(
        id: String,
        status: OperationStatus,
        context: RunContext,
        error: String? = null
    ) {
        val operation = _operations.value.firstOrNull { it.id == id } ?: return
        _history.update { list ->
            listOf(
                OperationRecord(
                    id = id,
                    kind = operation.kind,
                    status = status,
                    itemCount = context.pairs.size,
                    skipped = context.skipped,
                    bytes = operation.progress.bytesDone,
                    finishedAt = now(),
                    pairs = context.pairs.toList(),
                    error = error
                )
            ) + list
        }
    }

    private fun patch(id: String, transform: (Operation) -> Operation) {
        _operations.update { list ->
            list.map { if (it.id == id) transform(it) else it }
        }
    }

    /**
     * Stan jednego uruchomienia: pamięć nazw zajętych w katalogach docelowych,
     * decyzja „zastosuj do wszystkich" i lista par źródło→cel do cofania.
     */
    private inner class RunContext(val operationId: String, val kind: OperationKind) {
        val pairs = mutableListOf<Pair<String, String>>()
        var skipped = 0
        private var blanket: ConflictAction? = null
        private val names = mutableMapOf<String, MutableMap<String, FileItem>>()

        suspend fun namesIn(parent: Uri): Map<String, FileItem> = names.getOrPut(parent.toString()) {
            ops.listChildren(parent).associateByTo(mutableMapOf()) { it.name }
        }

        fun remember(parent: Uri, name: String, item: FileItem) {
            names.getOrPut(parent.toString()) { mutableMapOf() }[name] = item
        }

        fun forget(parent: Uri, name: String) {
            names[parent.toString()]?.remove(name)
        }

        suspend fun decide(source: FileItem, target: FileItem): ConflictAction {
            blanket?.let { return it }
            val answer = CompletableDeferred<ConflictDecision>()
            conflictAnswer = answer
            _pendingConflict.value = PendingConflict(
                operationId = operationId,
                name = source.name,
                isDirectory = source.isDirectory,
                sourceSize = source.size,
                sourceModified = source.lastModified,
                targetSize = target.size,
                targetModified = target.lastModified
            )
            val decision = answer.await()
            if (decision.applyToAll) blanket = decision.action
            return decision.action
        }
    }

    private companion object {
        const val PROGRESS_INTERVAL_MS = 150L
    }
}
