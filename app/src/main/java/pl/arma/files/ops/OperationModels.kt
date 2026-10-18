package pl.arma.files.ops

import pl.arma.files.R
import pl.arma.files.text
import android.net.Uri
import pl.arma.files.FileItem

enum class OperationKind { COPY, MOVE, TRASH, DELETE, RESTORE }

enum class OperationStatus { QUEUED, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED }

/** Co zrobić, gdy w katalogu docelowym istnieje już element o tej samej nazwie. */
enum class ConflictAction { REPLACE, SKIP, KEEP_BOTH }

/**
 * Decyzja użytkownika dotycząca konfliktu nazw. [applyToAll] sprawia, że
 * silnik nie pyta ponownie w obrębie tej samej operacji.
 */
data class ConflictDecision(
    val action: ConflictAction,
    val applyToAll: Boolean = false
)

/** Konflikt czekający na decyzję użytkownika. */
data class PendingConflict(
    val operationId: String,
    val name: String,
    val isDirectory: Boolean,
    val sourceSize: Long,
    val sourceModified: Long,
    val targetSize: Long,
    val targetModified: Long
)

data class OperationProgress(
    val filesTotal: Int = 0,
    val filesDone: Int = 0,
    val bytesTotal: Long = 0L,
    val bytesDone: Long = 0L,
    val currentName: String = "",
    val bytesPerSecond: Long = 0L
) {
    val fraction: Float
        get() = if (bytesTotal > 0L) (bytesDone.toDouble() / bytesTotal).toFloat().coerceIn(0f, 1f) else 0f

    /** Szacowany pozostały czas w sekundach, lub null gdy nie da się go policzyć. */
    val secondsRemaining: Long?
        get() {
            if (bytesPerSecond <= 0L || bytesTotal <= 0L) return null
            val left = bytesTotal - bytesDone
            return if (left <= 0L) 0L else left / bytesPerSecond
        }
}

/**
 * Pojedyncze zadanie w kolejce. Jest niemutowalne — silnik publikuje nowe
 * kopie ze zmienionym [status]/[progress].
 */
data class Operation(
    val id: String,
    val kind: OperationKind,
    val sources: List<FileItem>,
    val targetParent: Uri?,
    val status: OperationStatus = OperationStatus.QUEUED,
    val progress: OperationProgress = OperationProgress(),
    val error: String? = null,
    val skipped: Int = 0
) {
    val label: String
        get() = when (kind) {
            OperationKind.COPY -> text(R.string.op_copy)
            OperationKind.MOVE -> text(R.string.op_move)
            OperationKind.TRASH -> text(R.string.op_trash)
            OperationKind.DELETE -> text(R.string.op_delete)
            OperationKind.RESTORE -> text(R.string.op_restore)
        }

    val isFinished: Boolean
        get() = status == OperationStatus.COMPLETED ||
            status == OperationStatus.FAILED ||
            status == OperationStatus.CANCELLED
}

/**
 * Zapis zakończonej operacji na potrzeby historii i cofania.
 * [pairs] to pary źródło→cel jako łańcuchy URI; ich znaczenie zależy od [kind].
 */
data class OperationRecord(
    val id: String,
    val kind: OperationKind,
    val status: OperationStatus,
    val itemCount: Int,
    val skipped: Int,
    val bytes: Long,
    val finishedAt: Long,
    val pairs: List<Pair<String, String>> = emptyList(),
    val error: String? = null
) {
    /**
     * Kopiowanie cofamy kasując kopie, przeniesienie — przenosząc z powrotem,
     * kosz — przywracając. Trwałego usunięcia cofnąć się nie da.
     */
    val undoable: Boolean
        get() = status == OperationStatus.COMPLETED && pairs.isNotEmpty() &&
            (kind == OperationKind.COPY || kind == OperationKind.MOVE || kind == OperationKind.TRASH)
}
