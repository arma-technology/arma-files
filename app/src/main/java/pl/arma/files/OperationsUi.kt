package pl.arma.files

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import pl.arma.files.ops.ConflictAction
import pl.arma.files.ops.ConflictDecision
import pl.arma.files.ops.Operation
import pl.arma.files.ops.OperationStatus
import pl.arma.files.ops.PendingConflict
import pl.arma.files.ops.TrashEntry

/**
 * Pytanie o konflikt nazw. Świadomie nie jest odrzucalne gestem ani tapnięciem
 * obok — silnik czeka na decyzję, więc zamknięcie bez odpowiedzi zawiesiłoby
 * kolejkę. Wyjściem „awaryjnym" jest Pomiń.
 */
@Composable
fun ConflictDialog(conflict: PendingConflict, onDecision: (ConflictDecision) -> Unit) {
    var applyToAll by remember(conflict.name) { mutableStateOf(false) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = { },
        title = { Text(if (conflict.isDirectory) "Folder już istnieje" else "Plik już istnieje") },
        text = {
            Column {
                Text(conflict.name, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                if (!conflict.isDirectory) {
                    Text(
                        "Nowy: ${formatSize(conflict.sourceSize)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "Istniejący: ${formatSize(conflict.targetSize)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(12.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = applyToAll, onCheckedChange = { applyToAll = it })
                    Text("Zastosuj do wszystkich", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onDecision(ConflictDecision(ConflictAction.KEEP_BOTH, applyToAll))
            }) { Text("Zachowaj oba") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    onDecision(ConflictDecision(ConflictAction.SKIP, applyToAll))
                }) { Text("Pomiń") }
                TextButton(onClick = {
                    onDecision(ConflictDecision(ConflictAction.REPLACE, applyToAll))
                }) { Text("Zamień") }
            }
        }
    )
}

/** Pasek nad dolną nawigacją: jedna aktywna operacja plus liczba oczekujących. */
@Composable
fun OperationBar(
    operations: List<Operation>,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onCancel: (String) -> Unit,
    onOpenQueue: () -> Unit
) {
    val active = operations.firstOrNull { !it.isFinished } ?: return
    val waiting = operations.count { it.status == OperationStatus.QUEUED } -
        (if (active.status == OperationStatus.QUEUED) 1 else 0)

    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (active.status == OperationStatus.PAUSED) {
                            "${active.label} — wstrzymane"
                        } else {
                            active.label
                        },
                        style = MaterialTheme.typography.labelLarge
                    )
                    Text(
                        operationDetails(active, waiting),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (active.status == OperationStatus.PAUSED) {
                    IconButton(onClick = { onResume(active.id) }) {
                        Icon(Icons.Default.PlayArrow, "Wznów")
                    }
                } else {
                    IconButton(onClick = { onPause(active.id) }) {
                        Icon(Icons.Default.Pause, "Wstrzymaj")
                    }
                }
                IconButton(onClick = { onCancel(active.id) }) {
                    Icon(Icons.Default.Close, "Anuluj")
                }
                TextButton(onClick = onOpenQueue) { Text("Kolejka") }
            }
            Spacer(Modifier.height(6.dp))
            if (active.progress.bytesTotal > 0L) {
                LinearProgressIndicator(
                    progress = { active.progress.fraction },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50))
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50))
                )
            }
        }
    }
}

private fun operationDetails(operation: Operation, waiting: Int): String {
    val progress = operation.progress
    val paused = operation.status == OperationStatus.PAUSED
    return listOfNotNull(
        progress.currentName.takeIf { it.isNotBlank() },
        "${progress.filesDone}/${progress.filesTotal}",
        if (progress.bytesTotal > 0L) {
            "${formatSize(progress.bytesDone)} z ${formatSize(progress.bytesTotal)}"
        } else null,
        if (!paused && progress.bytesPerSecond > 0L) "${formatSize(progress.bytesPerSecond)}/s" else null,
        if (!paused) formatRemaining(progress.secondsRemaining)?.let { "pozostało $it" } else null,
        if (waiting > 0) "w kolejce: $waiting" else null
    ).joinToString(" · ")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OperationQueueSheet(
    operations: List<Operation>,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onCancel: (String) -> Unit,
    onClearFinished: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Kolejka operacji", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onClearFinished) { Text("Wyczyść zakończone") }
            }
            Spacer(Modifier.height(8.dp))
            if (operations.isEmpty()) {
                Text(
                    "Brak operacji.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(operations, key = { it.id }) { operation ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${operation.label} · ${statusLabel(operation)}", fontWeight = FontWeight.Medium)
                                Text(
                                    operationDetails(operation, 0),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                operation.error?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                            if (!operation.isFinished) {
                                if (operation.status == OperationStatus.PAUSED) {
                                    IconButton(onClick = { onResume(operation.id) }) {
                                        Icon(Icons.Default.PlayArrow, "Wznów")
                                    }
                                } else {
                                    IconButton(onClick = { onPause(operation.id) }) {
                                        Icon(Icons.Default.Pause, "Wstrzymaj")
                                    }
                                }
                                IconButton(onClick = { onCancel(operation.id) }) {
                                    Icon(Icons.Default.Close, "Anuluj")
                                }
                            }
                        }
                        if (!operation.isFinished && operation.progress.bytesTotal > 0L) {
                            Spacer(Modifier.height(4.dp))
                            LinearProgressIndicator(
                                progress = { operation.progress.fraction },
                                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50))
                            )
                        }
                        HorizontalDivider(Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
    }
}

private fun statusLabel(operation: Operation): String = when (operation.status) {
    OperationStatus.QUEUED -> "oczekuje"
    OperationStatus.RUNNING -> "w toku"
    OperationStatus.PAUSED -> "wstrzymane"
    OperationStatus.COMPLETED -> if (operation.skipped > 0) "gotowe, pominięto ${operation.skipped}" else "gotowe"
    OperationStatus.FAILED -> "błąd"
    OperationStatus.CANCELLED -> "anulowane"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashSheet(
    entries: List<TrashEntry>,
    onRestore: (List<TrashEntry>) -> Unit,
    onEmpty: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Kosz", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (entries.isNotEmpty()) {
                    TextButton(onClick = onEmpty) { Text("Opróżnij") }
                }
            }
            Spacer(Modifier.height(4.dp))
            if (entries.isEmpty()) {
                Text(
                    "Kosz jest pusty. Usunięte pliki trafiają tutaj i zostają na tym samym nośniku, więc nie zajmują dodatkowego miejsca.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    "${entries.size} elementów · ${formatSize(entries.sumOf { it.size })}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(entries, key = { it.id }) { entry ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${formatSize(entry.size)} · ${entry.originalParent.substringAfterLast('/')}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            IconButton(onClick = { onRestore(listOf(entry)) }) {
                                Icon(Icons.Default.Restore, "Przywróć")
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

/** Ikona kosza z licznikiem — wpinana w ekran „Mój komputer". */
@Composable
fun TrashButton(count: Int, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Icon(Icons.Default.Delete, null, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(if (count > 0) "Kosz ($count)" else "Kosz")
    }
}
