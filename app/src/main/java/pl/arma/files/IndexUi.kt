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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import pl.arma.files.index.IndexCategory
import pl.arma.files.index.IndexProgress

/**
 * Kategorie i wyszukiwanie w jednym rzędzie.
 *
 * Wyszukiwanie zwinięte jest do samej ikony, bo pole tekstowe zajmowałoby
 * całą szerokość dla czynności, która zaczyna się od tapnięcia. Po kliknięciu
 * ikona rozwija się w pasek na miejscu kategorii.
 */
@Composable
fun CategorySearchRow(
    indexed: Int,
    progress: IndexProgress,
    query: String,
    onQuery: (String) -> Unit,
    onPick: (IndexCategory) -> Unit,
    onBuild: () -> Unit,
    onCancel: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(expanded) {
        if (expanded && indexed > 0) runCatching { focus.requestFocus() }
    }

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (expanded) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.weight(1f)
            ) {
                when {
                    progress.running -> Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Buduję indeks… ${progress.scanned}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = onCancel) { Text("Przerwij") }
                    }

                    // Bez indeksu nie ma czego przeszukiwać, więc zamiast pola
                    // proponujemy jego zbudowanie.
                    indexed == 0 -> Row(
                        Modifier.padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Zbuduj indeks, aby szukać w całej pamięci",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = onBuild) { Text("Zbuduj") }
                    }

                    else -> TextField(
                        value = query,
                        onValueChange = onQuery,
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        singleLine = true,
                        placeholder = { Text("Szukaj we wszystkich plikach") },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        )
                    )
                }
            }
            IconButton(onClick = { expanded = false; onQuery("") }) {
                Icon(Icons.Default.Close, "Zamknij wyszukiwanie")
            }
        } else {
            LazyRow(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items(categoryEntries) { (category, label) ->
                    SuggestionChip(
                        onClick = { onPick(category) },
                        label = { Text(label.first) },
                        icon = { Icon(label.second, null, Modifier.size(17.dp)) },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            labelColor = MaterialTheme.colorScheme.onSurface,
                            iconContentColor = MaterialTheme.colorScheme.primary
                        ),
                        border = null
                    )
                }
            }
            Spacer(Modifier.width(4.dp))
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainer,
                onClick = { expanded = true }
            ) {
                Icon(
                    Icons.Default.Search,
                    "Szukaj we wszystkich plikach",
                    Modifier.padding(9.dp).size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

private val categoryEntries = listOf(
    IndexCategory.IMAGE to ("Obrazy" to Icons.Default.Image),
    IndexCategory.VIDEO to ("Filmy" to Icons.Default.Movie),
    IndexCategory.AUDIO to ("Audio" to Icons.Default.AudioFile),
    IndexCategory.DOCUMENT to ("Dokumenty" to Icons.Default.Description),
    IndexCategory.ARCHIVE to ("Archiwa" to Icons.Default.FolderZip),
    IndexCategory.APK to ("APK" to Icons.Default.Android)
)

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
