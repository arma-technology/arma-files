package pl.arma.files

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import pl.arma.files.index.IndexCategory
import pl.arma.files.index.IndexProgress

/** Pasek stanu indeksu — pokazywany tylko wtedy, gdy jest co powiedzieć. */
@Composable
fun IndexBar(progress: IndexProgress, indexed: Int, onBuild: () -> Unit, onCancel: () -> Unit) {
    Surface(
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Indeks plików", fontWeight = FontWeight.SemiBold)
                    Text(
                        when {
                            progress.running -> "Skanowanie… ${progress.scanned} elementów"
                            indexed > 0 -> "$indexed elementów · migawka, odśwież po zmianach"
                            else -> "Zbuduj indeks, aby szukać w całej pamięci i analizować zajętość"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (progress.running) {
                    TextButton(onClick = onCancel) { Text("Przerwij") }
                } else {
                    FilledTonalButton(onClick = onBuild) {
                        Text(if (indexed > 0) "Odśwież" else "Zbuduj")
                    }
                }
            }
            if (progress.running) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50))
                )
            }
        }
    }
}

/** Kategorie obejmujące całą pamięć, nie tylko otwarty katalog. */
@Composable
fun GlobalCategoryRow(onPick: (IndexCategory) -> Unit) {
    val entries = listOf(
        IndexCategory.IMAGE to ("Obrazy" to Icons.Default.Image),
        IndexCategory.VIDEO to ("Filmy" to Icons.Default.Movie),
        IndexCategory.AUDIO to ("Audio" to Icons.Default.AudioFile),
        IndexCategory.DOCUMENT to ("Dokumenty" to Icons.Default.Description),
        IndexCategory.ARCHIVE to ("Archiwa" to Icons.Default.FolderZip),
        IndexCategory.APK to ("APK" to Icons.Default.Android)
    )
    LazyRow(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(entries) { (category, label) ->
            AssistChip(
                onClick = { onPick(category) },
                label = { Text(label.first) },
                leadingIcon = { Icon(label.second, null, Modifier.size(17.dp)) }
            )
        }
    }
}

/** Analiza zajętości: fakty do samodzielnej oceny, bez kasowania za użytkownika. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageAnalysisSheet(
    analysis: StorageAnalysis,
    duplicates: List<DuplicateSet>,
    busy: Boolean,
    onFindDuplicates: () -> Unit,
    onOpen: (FileItem) -> Unit,
    onDismiss: () -> Unit
) {
    var section by remember { mutableStateOf(0) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("Analiza pamięci", style = MaterialTheme.typography.titleMedium)
            analysis.stats?.let { stats ->
                Text(
                    "${stats.files} plików · ${stats.folders} katalogów · ${formatSize(stats.totalBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(10.dp))

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf("Największe pliki", "Największe katalogi", "Stare pliki", "Duplikaty")) { label ->
                    val target = listOf("Największe pliki", "Największe katalogi", "Stare pliki", "Duplikaty")
                        .indexOf(label)
                    AssistChip(
                        onClick = {
                            section = target
                            if (target == 3 && duplicates.isEmpty()) onFindDuplicates()
                        },
                        label = { Text(label) }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            if (busy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (section == 3) "Porównuję zawartość plików…" else "Liczę…",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                when (section) {
                    0 -> items(analysis.largestFiles, key = { it.uri.toString() }) { item ->
                        FileLine(item.name, formatSize(item.size), parentOf(item)) { onOpen(item) }
                    }
                    1 -> items(analysis.largestFolders, key = { it.parentUri }) { folder ->
                        FileLine(
                            folder.parentUri.trimEnd('/').substringAfterLast('/'),
                            formatSize(folder.totalBytes),
                            "${folder.fileCount} plików · ${folder.parentUri.removePrefix("file://")}"
                        ) { }
                    }
                    2 -> items(analysis.oldFiles, key = { it.uri.toString() }) { item ->
                        FileLine(item.name, formatSize(item.size), "ostatnia zmiana: " + parentOf(item)) {
                            onOpen(item)
                        }
                    }
                    else -> {
                        if (duplicates.isEmpty() && !busy) {
                            item {
                                Text(
                                    "Nie znaleziono duplikatów wśród plików powyżej 4 KB. " +
                                        "Porównywany jest skrót SHA-256, więc pliki o tym samym " +
                                        "rozmiarze, ale innej zawartości nie są zliczane.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        duplicates.forEach { set ->
                            item {
                                Text(
                                    "${set.files.size} kopii · ${formatSize(set.size)} każda " +
                                        "· do odzyskania ${formatSize(set.size * (set.files.size - 1))}",
                                    style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                                )
                            }
                            items(set.files, key = { it.uri.toString() }) { item ->
                                FileLine(item.name, "", item.uri.path.orEmpty()) { onOpen(item) }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun parentOf(item: FileItem): String =
    item.uri.path.orEmpty().substringBeforeLast('/')

@Composable
private fun FileLine(title: String, trailing: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (trailing.isNotBlank()) {
            Spacer(Modifier.width(8.dp))
            Text(trailing, style = MaterialTheme.typography.labelLarge)
        }
    }
    HorizontalDivider()
}
