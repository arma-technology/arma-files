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
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.material3.Checkbox
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
import pl.arma.files.ui.theme.LocalFileTypeColors
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
    onCancel: () -> Unit,
    onRefresh: () -> Unit,
    hasRemote: Boolean = false,
    searchRemote: Boolean = false,
    onSearchRemote: (Boolean) -> Unit = {},
    searchingIn: String? = null
) {
    var expanded by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    // Pole jest zawsze — indeks tylko przyspiesza szukanie. Wcześniej bez
    // indeksu w okienku była sama podpowiedź i nie dało się nic wpisać.
    LaunchedEffect(expanded) {
        if (expanded) {
            runCatching { focus.requestFocus() }
            keyboard?.show()
        }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (expanded) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.weight(1f)
                ) {
                    TextField(
                        value = query,
                        onValueChange = onQuery,
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.index_search_all)) },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        )
                    )
                }
                // Odświeżenie indeksu mieszka tutaj — indeks jest migawką, a bez
                // tego przycisku nowe pliki nigdy by się w wynikach nie pojawiły.
                if (indexed > 0 && query.isBlank() && !progress.running) {
                    IconButton(onClick = onRefresh) {
                        Icon(
                            Icons.Default.Refresh,
                            stringResource(R.string.index_refresh),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(onClick = { expanded = false; onQuery("") }) {
                    Icon(Icons.Default.Close, stringResource(R.string.common_close_search))
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
                            label = { Text(stringResource(label.first)) },
                            icon = { Icon(label.second, null, Modifier.size(17.dp), tint = indexCategoryColor(category)) },
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                labelColor = MaterialTheme.colorScheme.onSurface
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
                        stringResource(R.string.index_search_all),
                        Modifier.padding(9.dp).size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        if (expanded) {
            if (hasRemote) {
                Row(
                    Modifier.fillMaxWidth().clickable { onSearchRemote(!searchRemote) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = searchRemote, onCheckedChange = onSearchRemote)
                    Text(stringResource(R.string.search_remote_too), style = MaterialTheme.typography.bodyMedium)
                }
            }
            when {
                progress.running -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(R.string.index_building, progress.scanned),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.index_stop)) }
                }
                // Bez indeksu szukanie działa, tylko wolniej — proponujemy go, ale go nie wymagamy.
                indexed == 0 -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.search_without_index),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onBuild) { Text(stringResource(R.string.index_build)) }
                }
            }
            if (searchingIn != null && query.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(R.string.search_status, searchingIn),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
internal fun indexCategoryColor(category: IndexCategory): Color {
    val colors = LocalFileTypeColors.current
    return when (category) {
        IndexCategory.IMAGE -> colors.image
        IndexCategory.VIDEO -> colors.video
        IndexCategory.AUDIO -> colors.audio
        IndexCategory.DOCUMENT -> colors.document
        IndexCategory.ARCHIVE -> colors.archive
        IndexCategory.APK -> colors.apk
        IndexCategory.OTHER -> colors.other
    }
}

private val categoryEntries = listOf(
    IndexCategory.IMAGE to (R.string.category_images to Icons.Default.Image),
    IndexCategory.VIDEO to (R.string.category_videos to Icons.Default.Movie),
    IndexCategory.AUDIO to (R.string.category_audio to Icons.Default.AudioFile),
    IndexCategory.DOCUMENT to (R.string.category_documents to Icons.Default.Description),
    IndexCategory.ARCHIVE to (R.string.category_archives to Icons.Default.FolderZip),
    IndexCategory.APK to (R.string.category_apk to Icons.Default.Android)
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
    onPreview: (FileItem) -> Unit,
    onDeleteDuplicates: (List<FileItem>) -> Unit,
    location: (FileItem) -> String,
    vm: FileManagerViewModel,
    marked: Set<String>,
    onMarked: (Set<String>) -> Unit,
    initialSection: Int = 0,
    onSection: (Int) -> Unit = {},
    onDismiss: () -> Unit
) {
    var section by remember { mutableStateOf(initialSection) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.analysis_title), style = MaterialTheme.typography.titleMedium)
            analysis.stats?.let { stats ->
                Text(
                    pluralStringResource(R.plurals.n_files, stats.files.toInt(), stats.files.toInt()) + " · " + pluralStringResource(R.plurals.n_folders, stats.folders.toInt(), stats.folders.toInt()) + " · " + formatSize(stats.totalBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(10.dp))

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val sections = listOf(
                    R.string.analysis_largest_files,
                    R.string.analysis_largest_folders,
                    R.string.analysis_old_files,
                    R.string.analysis_duplicates
                )
                items(sections.size) { target ->
                    val label = stringResource(sections[target])
                    AssistChip(
                        onClick = {
                            section = target
                            onSection(target)
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
                        if (section == 3) stringResource(R.string.analysis_comparing) else stringResource(R.string.analysis_counting),
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
                            pluralStringResource(R.plurals.n_files, folder.fileCount.toInt(), folder.fileCount.toInt()) + " · " + folder.parentUri.removePrefix("file://")
                        ) { }
                    }
                    2 -> items(analysis.oldFiles, key = { it.uri.toString() }) { item ->
                        FileLine(item.name, formatSize(item.size), java.text.DateFormat.getDateInstance().format(java.util.Date(item.lastModified)) + " · " + parentOf(item)) {
                            onOpen(item)
                        }
                    }
                    else -> {
                        if (duplicates.isEmpty() && !busy) {
                            item {
                                Text(
                                    stringResource(R.string.analysis_no_duplicates),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        duplicates.forEach { set ->
                            item {
                                Text(
                                    stringResource(R.string.analysis_duplicate_detail, pluralStringResource(R.plurals.n_copies, set.files.size, set.files.size), formatSize(set.size), formatSize(set.size * (set.files.size - 1))),
                                    style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                                )
                            }
                            items(set.files, key = { it.uri.toString() }) { item ->
                                val key = item.uri.toString()
                                DuplicateLine(
                                    item = item,
                                    location = location(item),
                                    checked = key in marked,
                                    vm = vm,
                                    onCheck = { onMarked(if (it) marked + key else marked - key) },
                                    onPreview = { onPreview(item) },
                                    onReveal = { onOpen(item) }
                                )
                            }
                        }
                    }
                }
            }
            if (section == 3 && duplicates.isNotEmpty()) {
                val chosen = duplicates.flatMap { it.files }.filter { it.uri.toString() in marked }
                Text(
                    stringResource(R.string.duplicates_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp)
                )
                Button(
                    onClick = { onDeleteDuplicates(chosen) },
                    enabled = chosen.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) {
                    Icon(Icons.Default.DeleteOutline, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.delete_to_trash) + " · " +
                            pluralStringResource(R.plurals.n_files, chosen.size, chosen.size) + " · " +
                            formatSize(chosen.sumOf { it.size })
                    )
                }
            }
        }
    }
}

/** Jedna kopia: miniatura (dotknięcie = podgląd), gdzie leży, zaznaczenie do kosza. */
@Composable
private fun DuplicateLine(
    item: FileItem,
    location: String,
    checked: Boolean,
    vm: FileManagerViewModel,
    onCheck: (Boolean) -> Unit,
    onPreview: () -> Unit,
    onReveal: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clickable { onCheck(!checked) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheck)
        FileVisual(
            item,
            Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onPreview),
            thumbnail = true,
            vm = vm,
            crop = true
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // Folder w dwóch liniach: przy długiej ścieżce liczy się jej koniec.
            Text(
                location,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(item.lastModified)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onReveal) {
            Icon(Icons.Default.FolderOpen, stringResource(R.string.show_in_folder))
        }
    }
    HorizontalDivider()
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
