package pl.arma.files

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
import pl.arma.files.ui.theme.LocalFileTypeColors

private val CARD_WIDTH = 140.dp
private val CARD_SPACING = 8.dp

/**
 * Rząd przypiętych folderów z przeciąganiem kolejności.
 *
 * Kafelki mają stałą szerokość, więc docelowy indeks liczy się wprost z
 * przesunięcia palca — nie trzeba mierzyć pozycji elementów listy ani sięgać
 * po zewnętrzną bibliotekę do reorderowania.
 */
@Composable
fun PinnedFolderRow(
    pinned: List<PinnedFolder>,
    stats: Map<String, Pair<Int, Long>>,
    onOpen: (PinnedFolder) -> Unit,
    onOpenInNewTab: (PinnedFolder) -> Unit,
    onUnpin: (String) -> Unit,
    onMove: (Int, Int) -> Unit,
    onAddFolder: () -> Unit
) {
    val density = LocalDensity.current
    val stepPx = with(density) { (CARD_WIDTH + CARD_SPACING).toPx() }

    var draggedIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }

    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(CARD_SPACING)
    ) {
        items(pinned, key = { it.id }) { folder ->
            val index = pinned.indexOfFirst { it.id == folder.id }
            val dragging = index == draggedIndex

            Box(
                Modifier
                    .zIndex(if (dragging) 1f else 0f)
                    .graphicsLayer {
                        if (dragging) {
                            translationX = dragOffset
                            scaleX = 1.04f
                            scaleY = 1.04f
                        }
                    }
                    .pointerInput(folder.id, pinned.size) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                draggedIndex = index
                                dragOffset = 0f
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                dragOffset += amount.x
                                // Gdy palec minie połowę sąsiedniego kafelka,
                                // zamieniamy elementy i zerujemy licznik o jeden krok.
                                val shift = (dragOffset / stepPx).roundToInt()
                                if (shift != 0) {
                                    val from = draggedIndex
                                    val to = (from + shift).coerceIn(0, pinned.lastIndex)
                                    if (to != from) {
                                        onMove(from, to)
                                        draggedIndex = to
                                        dragOffset -= (to - from) * stepPx
                                    }
                                }
                            },
                            onDragEnd = { draggedIndex = -1; dragOffset = 0f },
                            onDragCancel = { draggedIndex = -1; dragOffset = 0f }
                        )
                    }
            ) {
                PinnedCard(
                    folder = folder,
                    stat = stats[folder.id],
                    onClick = { onOpen(folder) },
                    onOpenInNewTab = { onOpenInNewTab(folder) },
                    onUnpin = { onUnpin(folder.id) }
                )
            }
        }

        item {
            OutlinedCard(
                onClick = onAddFolder,
                modifier = Modifier.width(CARD_WIDTH),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(
                        Icons.Default.AddLink,
                        null,
                        Modifier.size(26.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column {
                        Text("Dodaj folder", fontWeight = FontWeight.SemiBold)
                        Text(
                            "SAF / dostawca plików",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PinnedCard(
    folder: PinnedFolder,
    stat: Pair<Int, Long>?,
    onClick: () -> Unit,
    onOpenInNewTab: () -> Unit,
    onUnpin: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    val photos = folder.label.equals("Zdjęcia", ignoreCase = true)
    val icon = if (photos) Icons.Default.Image else Icons.Default.Folder
    val tint = LocalFileTypeColors.current.let { if (photos) it.image else it.folder }
    Card(
        onClick = onClick,
        modifier = Modifier.width(CARD_WIDTH),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(26.dp), tint = tint)
                Spacer(Modifier.weight(1f))
                Box {
                    // Krzyżyk na kaflu folderu czytał się jak „usuń folder";
                    // menu jest jednoznaczne i mieści resztę akcji.
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.MoreVert, "Więcej", Modifier.size(18.dp))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Otwórz") },
                            onClick = { menu = false; onClick() }
                        )
                        DropdownMenuItem(
                            text = { Text("Otwórz w nowej karcie") },
                            onClick = { menu = false; onOpenInNewTab() }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Odepnij") },
                            onClick = { menu = false; onUnpin() }
                        )
                    }
                }
            }
            Column(Modifier.fillMaxWidth()) {
                Text(folder.label, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    stat?.let { (count, bytes) -> "$count plików · ${formatSize(bytes)}" }
                        ?: folder.subtitle.substringAfterLast('/').ifBlank { folder.subtitle },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
