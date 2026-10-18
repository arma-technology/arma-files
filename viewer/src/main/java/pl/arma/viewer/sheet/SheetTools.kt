package pl.arma.viewer.sheet

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SquareFoot
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import pl.arma.viewer.R
import kotlin.math.roundToInt

@Composable
internal fun SheetTools(
    state: SheetViewerState,
    document: SheetDocument,
    currentPage: Int,
    pageCount: Int,
    zoomPercent: Int,
    commentCount: Int,
    hasLayers: Boolean,
    dirty: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onJump: () -> Unit,
    onZoomOut: () -> Unit,
    onZoomIn: () -> Unit,
    onFit: () -> Unit,
    onSave: (() -> Unit)?,
    onClose: (() -> Unit)?,
    title: String?,
    actions: List<ViewerAction>
) {
    // Węższe przyciski: w pionie na telefonie pełny zestaw musi się zmieścić
    // w jednym rzędzie, a gdy się nie mieści — rząd da się przewinąć.
    val button = Modifier.size(40.dp)
    val accent = MaterialTheme.colorScheme.primary
    Row(
        Modifier.padding(horizontal = 4.dp, vertical = 2.dp).horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onClose != null) {
            IconButton(onClick = onClose, modifier = button) { Icon(Icons.Default.Close, stringResource(R.string.viewer_close)) }
        }
        // Arkusz z CAD to zwykle jedna strona — wtedy strzałki stron są zbędne.
        if (pageCount > 1) {
            IconButton(onClick = onPrevious, enabled = currentPage > 1, modifier = button) {
                Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.viewer_previous_page))
            }
            TextButton(onClick = onJump, contentPadding = PaddingValues(horizontal = 6.dp)) {
                Text("$currentPage / $pageCount", fontWeight = FontWeight.SemiBold)
            }
            IconButton(onClick = onNext, enabled = currentPage < pageCount, modifier = button) {
                Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.viewer_next_page))
            }
        }
        if (document.canSearch) {
            IconButton(onClick = {
                state.focusSearch = true
                state.searchOpen = true
            }, modifier = button) { Icon(Icons.Default.Search, stringResource(R.string.viewer_search_text)) }
        }
        IconButton(
            onClick = {
                state.placement = if (state.placement == Placement.MEASURE) Placement.NONE else Placement.MEASURE
                state.firstPoint = null
            },
            modifier = button
        ) {
            Icon(
                Icons.Default.Straighten,
                stringResource(R.string.viewer_measure),
                tint = if (state.placement == Placement.MEASURE) accent else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (commentCount > 0 || document.canAnnotate) {
            IconButton(onClick = { state.commentsOpen = !state.commentsOpen; state.layersOpen = false }, modifier = button) {
                BadgedBox(badge = { if (commentCount > 0) Badge { Text("$commentCount") } }) {
                    Icon(Icons.Default.ChatBubbleOutline, stringResource(R.string.viewer_comments), tint = if (state.commentsOpen) accent else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (hasLayers) {
            IconButton(onClick = { state.layersOpen = !state.layersOpen; state.commentsOpen = false }, modifier = button) {
                Icon(Icons.Default.Layers, stringResource(R.string.viewer_layers), tint = if (state.layersOpen) accent else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (dirty && onSave != null) {
            IconButton(onClick = onSave, modifier = button) { Icon(Icons.Default.Save, stringResource(R.string.viewer_save_comments), tint = accent) }
        }
        Spacer(Modifier.width(4.dp))
        // Mnożnik zamiast stałego kroku: do dużych powiększeń nie da się dojść po 25%.
        IconButton(onClick = onZoomOut, enabled = zoomPercent > 100, modifier = button) {
            Icon(Icons.Default.ZoomOut, stringResource(R.string.viewer_zoom_out))
        }
        Text("$zoomPercent%", style = MaterialTheme.typography.labelLarge)
        IconButton(onClick = onZoomIn, enabled = zoomPercent < (MAX_ZOOM * 100).roundToInt(), modifier = button) {
            Icon(Icons.Default.ZoomIn, stringResource(R.string.viewer_zoom_in))
        }
        var menu by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        Box {
            IconButton(onClick = { menu = true }, modifier = button) { Icon(Icons.Default.MoreVert, stringResource(R.string.viewer_more)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (title != null) {
                    Text(
                        title,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).width(240.dp)
                    )
                    HorizontalDivider()
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.viewer_fit_screen)) },
                    leadingIcon = { Icon(Icons.Default.FitScreen, null) },
                    onClick = { menu = false; onFit() }
                )
                document.backgroundModes.forEachIndexed { index, label ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        leadingIcon = {
                            Icon(
                                if (index == document.backgroundMode) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                                null
                            )
                        },
                        onClick = { menu = false; scope.launch { document.setBackgroundMode(index) } }
                    )
                }
                if (document.backgroundModes.isNotEmpty()) HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.viewer_measure_scale_menu)) },
                    leadingIcon = { Icon(Icons.Default.SquareFoot, null) },
                    onClick = { menu = false; state.scaleDialog = true }
                )
                if (dirty && onSave != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.viewer_save_comments)) },
                        leadingIcon = { Icon(Icons.Default.Save, null) },
                        onClick = { menu = false; onSave() }
                    )
                }
                if (actions.isNotEmpty()) HorizontalDivider()
                actions.forEach { action ->
                    DropdownMenuItem(
                        text = { Text(action.label) },
                        leadingIcon = { Icon(action.icon, null) },
                        onClick = { menu = false; action.onClick() }
                    )
                }
            }
        }
    }
}

/**
 * Pasek wyszukiwania: pole, licznik trafień i przeskok strzałkami. Zastępuje
 * narzędzia na czas szukania, żeby nie zabierać kolejnego rzędu ekranu.
 */
@Composable
internal fun SheetSearchBar(
    state: SheetViewerState,
    document: SheetDocument,
    pageCount: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (state.focusSearch) {
            state.focusSearch = false
            runCatching { focus.requestFocus() }
        }
    }
    Row(
        Modifier.padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp).widthIn(max = 560.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Search, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (state.query.isEmpty()) {
                Text(
                    stringResource(R.string.viewer_search_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            BasicTextField(
                value = state.query,
                onValueChange = { state.query = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                // Oznaczenia z rysunków („LP3.1/8”) klawiatura tekstowa psuje
                // spacją po kropce i autokorektą; klawiatura adresów tego nie
                // robi i ma „/” pod ręką.
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Search
                ),
                keyboardActions = KeyboardActions(onSearch = { onNext() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus)
            )
        }
        Spacer(Modifier.width(8.dp))
        val status = when {
            state.query.isBlank() -> ""
            state.searching -> if (pageCount > 1) stringResource(R.string.viewer_searching_pages, state.searchedPages, pageCount) else stringResource(R.string.viewer_searching)
            state.matches.isEmpty() -> stringResource(R.string.viewer_no_matches)
            else -> stringResource(R.string.viewer_match_of, state.currentMatch + 1, state.matches.size)
        }
        val nothingFound = !state.searching && state.query.isNotBlank() && state.matches.isEmpty()
        Text(
            status,
            style = MaterialTheme.typography.labelLarge,
            color = if (nothingFound) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        val hasMatches = state.matches.isNotEmpty()
        IconButton(onClick = onPrevious, enabled = hasMatches, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.viewer_previous_match))
        }
        IconButton(onClick = onNext, enabled = hasMatches, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.viewer_next_match))
        }
        IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Default.Close, stringResource(R.string.viewer_close_search))
        }
    }
}

@Composable
internal fun SheetThumbnailStrip(document: SheetDocument, revision: Int, currentPage: Int, onPick: (Int) -> Unit) {
    val listState = rememberLazyListState()
    LaunchedEffect(currentPage) {
        runCatching { listState.animateScrollToItem((currentPage - 3).coerceAtLeast(0)) }
    }
    LazyRow(
        state = listState,
        modifier = Modifier.fillMaxWidth().height(92.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        items(document.pageCount, key = { it }) { page ->
            SheetThumbnail(document, revision, page, selected = page == currentPage - 1, onClick = { onPick(page) })
        }
    }
}

@Composable
private fun SheetThumbnail(document: SheetDocument, revision: Int, pageIndex: Int, selected: Boolean, onClick: () -> Unit) {
    var bitmap by remember(document, pageIndex) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(document, pageIndex, revision) {
        runCatching { document.renderThumbnail(pageIndex, 160).asImageBitmap() }.onSuccess { bitmap = it }
    }
    Surface(
        onClick = onClick,
        modifier = Modifier.width(62.dp).fillMaxHeight(),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        ),
        color = Color.White
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            bitmap?.let {
                Image(
                    bitmap = it,
                    contentDescription = stringResource(R.string.viewer_page_thumbnail, pageIndex + 1),
                    modifier = Modifier.fillMaxSize().padding(2.dp),
                    contentScale = ContentScale.Fit
                )
            } ?: CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp),
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surface.copy(alpha = .88f)
            ) {
                Text(
                    "${pageIndex + 1}",
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
internal fun JumpDialog(pageCount: Int, currentPage: Int, onDismiss: () -> Unit, onJump: (Int) -> Unit) {
    // Numer zaznaczony: wpisany od razu go zastępuje, zamiast doklejać się
    // do bieżącego („5” i „40” dawało „540”).
    var value by remember {
        val text = currentPage.toString()
        mutableStateOf(TextFieldValue(text, TextRange(0, text.length)))
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val confirm = { onJump(value.text.toIntOrNull()?.coerceIn(1, pageCount) ?: 1) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.viewer_go_to_page)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it.copy(text = it.text.filter(Char::isDigit).take(6)) },
                singleLine = true,
                label = { Text(stringResource(R.string.viewer_page_number, pageCount)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { confirm() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus)
            )
        },
        confirmButton = { TextButton(onClick = confirm) { Text(stringResource(R.string.viewer_go)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.viewer_cancel)) } }
    )
}
