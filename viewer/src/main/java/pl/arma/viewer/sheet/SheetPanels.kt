package pl.arma.viewer.sheet

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Boczny panel komentarzy albo warstw, wysuwany nad treścią. */
@Composable
internal fun SheetPanels(
    state: SheetViewerState,
    document: SheetDocument,
    comments: List<SheetComment>,
    layers: List<SheetLayer>,
    modifier: Modifier,
    onShowComment: (SheetComment) -> Unit
) {
    AnimatedVisibility(
        visible = state.commentsOpen || state.layersOpen,
        enter = slideInHorizontally { it },
        exit = slideOutHorizontally { it },
        modifier = modifier
    ) {
        Surface(
            modifier = Modifier.fillMaxHeight().widthIn(max = 340.dp).padding(top = 64.dp, bottom = 8.dp, end = 8.dp),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = .96f),
            contentColor = MaterialTheme.colorScheme.onSurface,
            shadowElevation = 6.dp
        ) {
            if (state.commentsOpen) {
                CommentsPanel(state, document, comments, onShowComment)
            } else {
                LayersPanel(state, document, layers)
            }
        }
    }
}

@Composable
private fun PanelHeader(title: String, onClose: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        trailing()
        IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Zamknij panel") }
    }
}

@Composable
private fun CommentsPanel(
    state: SheetViewerState,
    document: SheetDocument,
    comments: List<SheetComment>,
    onShowComment: (SheetComment) -> Unit
) {
    Column(Modifier.fillMaxHeight()) {
        PanelHeader("Komentarze (${comments.size})", onClose = { state.commentsOpen = false })
        if (document.canAnnotate) {
            TextButton(
                onClick = {
                    state.commentsOpen = false
                    state.firstPoint = null
                    state.placement = Placement.COMMENT
                },
                modifier = Modifier.padding(horizontal = 8.dp)
            ) {
                Icon(Icons.Default.AddComment, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Dodaj komentarz")
            }
        }
        HorizontalDivider()
        if (comments.isEmpty()) {
            Text(
                "W tym pliku nie ma komentarzy.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
        }
        LazyColumn(Modifier.fillMaxWidth()) {
            items(comments, key = { it.id }) { comment ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onShowComment(comment) }
                        .background(if (comment.id == state.selectedComment) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            buildString {
                                append(comment.type)
                                if (document.pageCount > 1) append(" · str. ${comment.page + 1}")
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f)
                        )
                        if (comment.pending) {
                            Text("niezapisany", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                        }
                    }
                    val meta = listOfNotNull(comment.author, comment.modified).joinToString(" · ")
                    if (meta.isNotEmpty()) {
                        Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    comment.text?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun LayersPanel(state: SheetViewerState, document: SheetDocument, layers: List<SheetLayer>) {
    val scope = rememberCoroutineScope()
    var filter by remember { mutableStateOf("") }
    Column(Modifier.fillMaxHeight()) {
        PanelHeader("Warstwy (${layers.size})", onClose = { state.layersOpen = false })
        Row(Modifier.padding(horizontal = 8.dp)) {
            TextButton(onClick = { scope.launch { document.setAllLayersVisible(true) } }) { Text("Wszystkie") }
            TextButton(onClick = { scope.launch { document.setAllLayersVisible(false) } }) { Text("Żadna") }
        }
        // Rysunki CAD mają nieraz setki warstw — bez filtra nie da się ich przejrzeć.
        if (layers.size > 12) {
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                placeholder = { Text("Filtruj warstwy") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            )
        }
        HorizontalDivider(Modifier.padding(top = 6.dp))
        val shown = if (filter.isBlank()) layers else layers.filter { it.name.contains(filter.trim(), ignoreCase = true) }
        LazyColumn(Modifier.fillMaxWidth()) {
            items(shown, key = { it.id }) { layer ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !layer.locked) { scope.launch { document.setLayerVisible(layer.id, !layer.visible) } }
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = layer.visible,
                        enabled = !layer.locked,
                        onCheckedChange = { scope.launch { document.setLayerVisible(layer.id, it) } }
                    )
                    layer.color?.let { color ->
                        Box(Modifier.size(12.dp).background(Color(color), CircleShape))
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(layer.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Dymek z treścią wybranego komentarza. */
@Composable
internal fun CommentBubble(comment: SheetComment, canDelete: Boolean, onDelete: () -> Unit, onDismiss: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.BottomCenter) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shadowElevation = 8.dp,
            modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth()
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(comment.type, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        val meta = listOfNotNull(comment.author, comment.modified).joinToString(" · ")
                        if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (canDelete) IconButton(onClick = onDelete) { Icon(Icons.Default.DeleteOutline, "Usuń komentarz") }
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Zamknij") }
                }
                Text(
                    comment.text ?: "(bez treści)",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState())
                )
            }
        }
    }
}
