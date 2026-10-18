package pl.arma.files

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.WrapText
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import pl.arma.files.text.OutlineEntry
import pl.arma.files.text.TextDocument
import pl.arma.files.text.TextOutline
import pl.arma.files.text.TextProfile

/**
 * Czytnik plików tekstowych.
 *
 * Świadomie czytnik, a nie edytor: pokazuje numery linii, spis treści,
 * wyszukiwanie i informację o kodowaniu, ale nie ciągnie za sobą silnika
 * kolorowania składni z natywnymi bibliotekami — w menedżerze plików chodzi
 * o szybkie zajrzenie do pliku, nie o pracę nad nim.
 */
@Composable
fun TextViewer(item: FileItem, vm: FileManagerViewModel, modifier: Modifier = Modifier) {
    var document by remember(item.uri, item.lastModified) { mutableStateOf<TextDocument?>(null) }
    var error by remember(item.uri, item.lastModified) { mutableStateOf<String?>(null) }
    var wrap by remember { mutableStateOf(true) }
    var query by remember(item.uri) { mutableStateOf("") }
    var searching by remember(item.uri) { mutableStateOf(false) }
    var showOutline by remember(item.uri) { mutableStateOf(false) }

    val profile = remember(item.name) { TextProfile.of(item.name) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(item.uri, item.lastModified) {
        error = null
        document = runCatching { vm.readDocument(item) }
            .onFailure { error = it.message ?: text(R.string.text_read_failed) }
            .getOrNull()
    }

    val lines = remember(document) { document?.text?.split('\n') ?: emptyList() }
    val outline = remember(document, profile) {
        document?.let { TextOutline.of(it.text, profile) } ?: emptyList()
    }
    val matches = remember(lines, query) {
        if (query.isBlank()) emptyList()
        else lines.indices.filter { lines[it].contains(query, ignoreCase = true) }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AssistChip(onClick = { }, label = { Text(profile.label) })
            Spacer(Modifier.width(6.dp))
            document?.let { doc ->
                Text(
                    pluralStringResource(R.plurals.n_lines, doc.lineCount, doc.lineCount) + " · " + doc.charset.name() + (if (doc.truncated) stringResource(R.string.text_shows_beginning) else ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            } ?: Spacer(Modifier.weight(1f))

            if (outline.isNotEmpty()) {
                Box {
                    IconButton(onClick = { showOutline = true }) {
                        Icon(Icons.Default.FormatListBulleted, stringResource(R.string.text_outline))
                    }
                    OutlineMenu(
                        entries = outline,
                        expanded = showOutline,
                        onDismiss = { showOutline = false },
                        onPick = { line ->
                            showOutline = false
                            scope.launch { listState.animateScrollToItem(line) }
                        }
                    )
                }
            }
            IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                Icon(
                    Icons.Default.Search,
                    stringResource(R.string.text_search),
                    tint = if (searching) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { wrap = !wrap }) {
                Icon(
                    Icons.Default.WrapText,
                    stringResource(R.string.text_wrap),
                    tint = if (wrap) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (searching) {
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.text_search)) },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        )
                    )
                    if (query.isNotBlank()) {
                        Text(
                            "${matches.size}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        IconButton(onClick = {
                            matches.firstOrNull()?.let { line ->
                                scope.launch { listState.animateScrollToItem(line) }
                            }
                        }) { Icon(Icons.Default.Search, stringResource(R.string.text_first_match)) }
                    }
                    IconButton(onClick = { searching = false; query = "" }) {
                        Icon(Icons.Default.Close, stringResource(R.string.common_close_search))
                    }
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth().weight(1f),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            when {
                error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        error.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(20.dp)
                    )
                }

                document == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp)
                }

                else -> {
                    val horizontal = rememberScrollState()
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(vertical = 8.dp)
                            .then(if (wrap) Modifier else Modifier.horizontalScroll(horizontal))
                    ) {
                        itemsIndexed(lines) { index, line ->
                            TextLine(
                                number = index + 1,
                                line = line,
                                query = query,
                                monospace = profile.code,
                                wrap = wrap,
                                highlighted = index in matches
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TextLine(
    number: Int,
    line: String,
    query: String,
    monospace: Boolean,
    wrap: Boolean,
    highlighted: Boolean
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                if (highlighted) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = .35f)
                } else {
                    Color.Transparent
                }
            )
            .padding(horizontal = 10.dp, vertical = 1.dp)
    ) {
        Text(
            number.toString(),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .6f),
            modifier = Modifier.widthIn(min = 34.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = highlight(line, query),
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
            softWrap = wrap,
            maxLines = if (wrap) Int.MAX_VALUE else 1
        )
    }
}

/** Podświetla wystąpienia szukanej frazy w wierszu. */
@Composable
private fun highlight(line: String, query: String) = buildAnnotatedString {
    if (query.isBlank()) {
        append(line)
        return@buildAnnotatedString
    }
    var index = 0
    while (index < line.length) {
        val found = line.indexOf(query, index, ignoreCase = true)
        if (found < 0) {
            append(line.substring(index))
            return@buildAnnotatedString
        }
        append(line.substring(index, found))
        withStyle(
            SpanStyle(
                background = MaterialTheme.colorScheme.primary.copy(alpha = .28f),
                fontWeight = FontWeight.SemiBold
            )
        ) {
            append(line.substring(found, found + query.length))
        }
        index = found + query.length
    }
}

@Composable
private fun OutlineMenu(
    entries: List<OutlineEntry>,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        Box(Modifier.heightIn(max = 400.dp)) {
            Column {
                entries.forEach { entry ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                entry.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                // Wcięcie oddaje zagnieżdżenie nagłówków.
                                modifier = Modifier.padding(start = ((entry.level - 1) * 12).dp)
                            )
                        },
                        onClick = { onPick(entry.line) }
                    )
                }
            }
        }
    }
}
