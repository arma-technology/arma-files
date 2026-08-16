package pl.arma.files

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Pasek zakładek katalogów. Karta pokazywana w drugim panelu jest wyróżniona,
 * bo nie da się jej jednocześnie uczynić aktywną.
 */
@Composable
fun TabStrip(
    tabs: List<TabInfo>,
    dualPane: Boolean,
    canSplit: Boolean,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onNew: () -> Unit,
    onToggleDual: () -> Unit
) {
    if (tabs.size <= 1) {
        // Jedna karta to zwykłe przeglądanie — pasek tylko zabierałby miejsce.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.End
        ) {
            if (canSplit) {
                IconButton(onClick = onToggleDual) {
                    Icon(
                        if (dualPane) Icons.Default.Fullscreen else Icons.Default.VerticalSplit,
                        if (dualPane) "Jeden panel" else "Dwa panele"
                    )
                }
            }
            TextButton(onClick = onNew) {
                Icon(Icons.Default.Add, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Nowa karta")
            }
        }
        return
    }

    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items(tabs, key = { it.id }) { tab ->
            val background = when {
                tab.active -> MaterialTheme.colorScheme.primaryContainer
                tab.secondary -> MaterialTheme.colorScheme.tertiaryContainer
                else -> MaterialTheme.colorScheme.surfaceContainerHighest
            }
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(background)
                    .clickable { onSelect(tab.id) }
                    .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    tab.title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (tab.active) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 140.dp)
                )
                IconButton(onClick = { onClose(tab.id) }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, "Zamknij kartę", Modifier.size(15.dp))
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onNew) { Icon(Icons.Default.Add, "Nowa karta") }
                if (canSplit) {
                    IconButton(onClick = onToggleDual) {
                        Icon(
                            if (dualPane) Icons.Default.Fullscreen else Icons.Default.VerticalSplit,
                            if (dualPane) "Jeden panel" else "Dwa panele"
                        )
                    }
                }
            }
        }
    }
}

/**
 * Drugi panel obok głównego. Ma własną nawigację, ale świadomie uproszczoną
 * listę — służy do przenoszenia plików, a nie do przeglądania podglądów.
 */
@Composable
fun SecondaryPane(
    dir: android.net.Uri?,
    files: List<FileItem>,
    selectionCount: Int,
    onOpen: (FileItem) -> Unit,
    onBack: () -> Unit,
    onCopyHere: () -> Unit,
    onMoveHere: () -> Unit,
    onSwap: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Wstecz") }
                Text(
                    dir?.path?.trimEnd('/')?.substringAfterLast('/')?.ifBlank { "Katalog główny" }
                        ?: "Mój komputer",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onSwap) { Icon(Icons.Default.SwapHoriz, "Zamień panele") }
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Zamknij panel") }
            }

            if (selectionCount > 0 && dir != null) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(onClick = onCopyHere, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Kopiuj tu ($selectionCount)")
                    }
                    TextButton(onClick = onMoveHere, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.DriveFileMove, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Przenieś tu")
                    }
                }
            }

            if (dir == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Otwórz katalog w tym panelu,\nwybierając kartę z paska powyżej.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                return@Column
            }

            LazyColumn(Modifier.fillMaxSize()) {
                items(files, key = { it.uri.toString() }) { item ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onOpen(item) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (item.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                            null,
                            Modifier.size(22.dp),
                            tint = if (item.isDirectory) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                item.name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (!item.isDirectory) {
                                Text(
                                    formatSize(item.size),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
