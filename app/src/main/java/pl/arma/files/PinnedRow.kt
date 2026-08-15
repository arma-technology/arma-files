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
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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

private val CARD_WIDTH = 168.dp
private val CARD_SPACING = 12.dp

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
    onOpen: (PinnedFolder) -> Unit,
    onUnpin: (String) -> Unit,
    onMove: (Int, Int) -> Unit,
    onAddFolder: () -> Unit
) {
    val density = LocalDensity.current
    val stepPx = with(density) { (CARD_WIDTH + CARD_SPACING).toPx() }

    var draggedIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }

    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
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
                PinnedCard(folder, onClick = { onOpen(folder) }, onUnpin = { onUnpin(folder.id) })
            }
        }

        item {
            OutlinedCard(
                onClick = onAddFolder,
                modifier = Modifier.width(CARD_WIDTH),
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(
                        Icons.Default.AddLink,
                        null,
                        Modifier.size(34.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column {
                        Text("Dodaj folder", fontWeight = FontWeight.SemiBold)
                        Text("SAF / dostawca plików", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun PinnedCard(folder: PinnedFolder, onClick: () -> Unit, onUnpin: () -> Unit) {
    val icon = when {
        folder.label.equals("Zdjęcia", ignoreCase = true) -> Icons.Default.Image
        else -> Icons.Default.Folder
    }
    Card(onClick = onClick, modifier = Modifier.width(CARD_WIDTH), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(34.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onUnpin, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, "Odepnij", Modifier.size(16.dp))
                }
            }
            Column(Modifier.fillMaxWidth()) {
                Text(folder.label, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    folder.subtitle.substringAfterLast('/').ifBlank { folder.subtitle },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
