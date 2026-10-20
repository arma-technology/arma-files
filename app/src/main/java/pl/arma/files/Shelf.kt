package pl.arma.files

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.KeyboardDoubleArrowUp
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Szuflada nad dolną nawigacją. Zwinięta: liczba plików i kilka miniatur;
 * przeciągnięcie paska w górę wyciąga całą zawartość do bieżącego folderu.
 * Rozwinięta: lista z usuwaniem pojedynczych plików i przyciski
 * „Kopiuj tutaj” / „Przenieś tutaj” / „Opróżnij”.
 */
@Composable
internal fun ShelfBar(
    items: List<FileItem>,
    vm: FileManagerViewModel,
    canDrop: Boolean,
    onDrop: (move: Boolean) -> Unit,
    onRemove: (FileItem) -> Unit,
    onClear: () -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val pull = remember { Animatable(0f) }
    val threshold = with(LocalDensity.current) { 72.dp.toPx() }
    var armed by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 4.dp,
        shadowElevation = 6.dp,
        modifier = Modifier
            .fillMaxWidth()
            .offset { IntOffset(0, -pull.value.roundToInt()) }
            .animateContentSize()
            .pointerInput(canDrop, items.size) {
                if (!canDrop) return@pointerInput
                detectVerticalDragGestures(
                    onDragEnd = {
                        if (armed) onDrop(false)
                        armed = false
                        scope.launch { pull.animateTo(0f) }
                    },
                    onDragCancel = { armed = false; scope.launch { pull.animateTo(0f) } },
                    onVerticalDrag = { change, amount ->
                        change.consume()
                        scope.launch { pull.snapTo((pull.value - amount * .6f).coerceIn(0f, threshold * 1.4f)) }
                        val nowArmed = pull.value >= threshold
                        if (nowArmed != armed) {
                            armed = nowArmed
                            if (armed) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    }
                )
            }
    ) {
        Column(Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Inbox, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.shelf_title, items.size), fontWeight = FontWeight.SemiBold)
                    Text(
                        when {
                            armed -> stringResource(R.string.shelf_release_to_copy)
                            canDrop -> stringResource(R.string.shelf_pull_hint)
                            else -> stringResource(R.string.shelf_no_target)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = .75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (!expanded) {
                    // Kilka miniatur, każda lekko nachodzi na poprzednią — jak karty w dłoni.
                    Box(Modifier.width((22 + 18 * (items.size.coerceAtMost(4) - 1)).dp)) {
                        items.take(4).forEachIndexed { index, item ->
                            Box(
                                Modifier
                                    .offset(x = (18 * index).dp)
                                    .graphicsLayer { rotationZ = (index - 1.5f) * 5f }
                            ) {
                                FileVisual(item, Modifier.size(28.dp), thumbnail = true, vm = vm, crop = true)
                            }
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                }
                if (pull.value > 0f) {
                    Icon(Icons.Default.KeyboardDoubleArrowUp, null, tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(if (expanded) Icons.Default.ExpandMore else Icons.Default.ExpandLess, null)
                }
            }
            AnimatedVisibility(expanded, enter = expandVertically(), exit = shrinkVertically()) {
                Column {
                    LazyColumn(Modifier.heightIn(max = 220.dp)) {
                        items(items, key = { it.uri.toString() }) { item ->
                            Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                FileVisual(item, Modifier.size(32.dp), thumbnail = true, vm = vm, crop = true)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                    Text(folderOf(item), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = .7f))
                                }
                                IconButton(onClick = { onRemove(item) }) { Icon(Icons.Default.Close, stringResource(R.string.common_delete)) }
                            }
                        }
                    }
                    Row(Modifier.padding(top = 4.dp, end = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { onDrop(false) }, enabled = canDrop, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.shelf_copy_here), maxLines = 1)
                        }
                        OutlinedButton(onClick = { onDrop(true) }, enabled = canDrop, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.DriveFileMove, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.shelf_move_here), maxLines = 1)
                        }
                    }
                    TextButton(onClick = onClear, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.shelf_clear)) }
                }
            }
        }
    }
}

/** Nazwa folderu, z którego plik trafił do szuflady. */
private fun folderOf(item: FileItem): String {
    val path = item.uri.lastPathSegment ?: item.uri.path ?: return ""
    return path.substringBeforeLast('/', "").substringAfterLast('/').substringAfterLast(':')
}
