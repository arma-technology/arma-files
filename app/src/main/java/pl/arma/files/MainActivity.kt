package pl.arma.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import pl.arma.files.ui.theme.ArmaFilesTheme
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: FileManagerViewModel by viewModels()
    private val externalUri = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        externalUri.value = intent?.data
        setContent { ArmaFilesTheme { FileManagerApp(vm, externalUri.value) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        externalUri.value = intent.data
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileManagerApp(vm: FileManagerViewModel = viewModel(), initialUri: Uri? = null) {
    val context = LocalContext.current
    val files by vm.files.collectAsStateWithLifecycle()
    val recentFiles by vm.recentFiles.collectAsStateWithLifecycle()
    val favoriteFiles by vm.favoriteFiles.collectAsStateWithLifecycle()
    val currentDir by vm.currentDir.collectAsStateWithLifecycle()
    val root by vm.root.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()

    var tab by remember { mutableStateOf(Tab.FILES) }
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(SortMode.NAME) }
    var viewMode by remember { mutableStateOf(ViewMode.LIST) }
    var selected by remember { mutableStateOf<FileItem?>(null) }
    var preview by remember { mutableStateOf<FileItem?>(null) }
    var createDialog by remember { mutableStateOf<String?>(null) }
    var renameItem by remember { mutableStateOf<FileItem?>(null) }
    var deleteItem by remember { mutableStateOf<FileItem?>(null) }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(vm::setTree)
    }

    LaunchedEffect(initialUri) {
        if (initialUri != null) {
            vm.itemFromUri(initialUri)?.let {
                preview = it
                vm.addRecent(initialUri)
            }
        }
    }

    val favorites = vm.favorites()
    val visibleFiles = remember(files, recentFiles, favoriteFiles, query, sort, tab) {
        val base = when (tab) {
            Tab.FILES -> files
            Tab.RECENT -> recentFiles
            Tab.FAVORITES -> favoriteFiles
        }
        base.filter { it.name.contains(query, ignoreCase = true) }
            .sortedWith(Comparator { a, b ->
                if (a.isDirectory != b.isDirectory) {
                    if (a.isDirectory) -1 else 1
                } else {
                    when (sort) {
                        SortMode.NAME -> a.name.lowercase().compareTo(b.name.lowercase())
                        SortMode.DATE -> b.lastModified.compareTo(a.lastModified)
                        SortMode.SIZE -> b.size.compareTo(a.size)
                        SortMode.TYPE -> a.mimeType.compareTo(b.mimeType)
                    }
                }
            })
    }

    BackHandler(
        enabled = preview != null || selected != null || tab != Tab.FILES || (currentDir != null && currentDir != root)
    ) {
        when {
            preview != null -> preview = null
            selected != null -> selected = null
            tab != Tab.FILES -> { tab = Tab.FILES; query = "" }
            else -> vm.goBack()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            if (selected != null) {
                SelectionTopBar(
                    item = selected!!,
                    favorite = selected!!.uri.toString() in favorites,
                    onClose = { selected = null },
                    onFavorite = { vm.toggleFavorite(selected!!.uri); selected = null },
                    onRename = { renameItem = selected; selected = null },
                    onCopy = { vm.setClipboard(selected!!, false); selected = null; tab = Tab.FILES },
                    onCut = { vm.setClipboard(selected!!, true); selected = null; tab = Tab.FILES },
                    onDelete = { deleteItem = selected; selected = null },
                    onShare = { shareUri(context, selected!!) }
                )
            } else {
                TopAppBar(
                    title = {
                        Text(
                            when (tab) {
                                Tab.FILES -> "ARMA Files"
                                Tab.RECENT -> "Ostatnie"
                                Tab.FAVORITES -> "Ulubione"
                            },
                            fontWeight = FontWeight.SemiBold
                        )
                    },
                    actions = {
                        if (tab == Tab.FILES && currentDir != null) {
                            IconButton(onClick = vm::refresh) { Icon(Icons.Default.Refresh, "Odśwież") }
                        }
                        IconButton(onClick = { folderPicker.launch(null) }) { Icon(Icons.Default.FolderOpen, "Wybierz folder") }
                    }
                )
            }
        },
        bottomBar = {
            NavigationBar {
                NavItem(Tab.FILES, tab, Icons.Default.Folder, "Pliki") { tab = Tab.FILES; query = "" }
                NavItem(Tab.RECENT, tab, Icons.Default.History, "Ostatnie") { tab = Tab.RECENT; query = "" }
                NavItem(Tab.FAVORITES, tab, Icons.Default.Star, "Ulubione") { tab = Tab.FAVORITES; query = "" }
            }
        },
        floatingActionButton = {
            if (tab == Tab.FILES && currentDir != null && selected == null) {
                ExtendedFloatingActionButton(
                    onClick = { createDialog = "choose" },
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text("Nowy") }
                )
            }
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (tab == Tab.FILES && currentDir == null) {
                EmptyStorage(onPick = { folderPicker.launch(null) })
            } else {
                if (tab == Tab.FILES) {
                    BreadcrumbBar(onRoot = vm::goRoot, canPaste = vm.clipboard != null, onPaste = vm::paste)
                } else {
                    Text(
                        if (tab == Tab.RECENT) "Ostatnio otwierane pliki" else "Pliki i foldery oznaczone gwiazdką",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                SearchAndSort(
                    query = query,
                    onQuery = { query = it },
                    sort = sort,
                    onSort = { sort = it },
                    view = viewMode,
                    onView = { viewMode = it },
                    placeholder = if (tab == Tab.FILES) "Szukaj w folderze" else "Szukaj"
                )

                if (visibleFiles.isEmpty()) {
                    EmptyFolder(query.isNotBlank(), tab)
                } else if (viewMode == ViewMode.LIST) {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(bottom = 88.dp)
                    ) {
                        items(visibleFiles, key = { it.uri.toString() }) { item ->
                            FileRow(
                                item = item,
                                favorite = item.uri.toString() in favorites,
                                onClick = {
                                    if (item.isDirectory) {
                                        vm.openDirectory(item)
                                        tab = Tab.FILES
                                    } else {
                                        vm.addRecent(item.uri)
                                        preview = item
                                    }
                                },
                                onLongClick = { selected = item }
                            )
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(132.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 88.dp)
                    ) {
                        items(visibleFiles, key = { it.uri.toString() }) { item ->
                            FileTile(
                                item = item,
                                favorite = item.uri.toString() in favorites,
                                onClick = {
                                    if (item.isDirectory) {
                                        vm.openDirectory(item)
                                        tab = Tab.FILES
                                    } else {
                                        vm.addRecent(item.uri)
                                        preview = item
                                    }
                                },
                                onLongClick = { selected = item }
                            )
                        }
                    }
                }
            }
        }
    }

    preview?.let {
        PreviewSheet(
            item = it,
            vm = vm,
            onDismiss = { preview = null },
            onShare = { shareUri(context, it) },
            onOpenExternal = { openExternal(context, it) }
        )
    }

    error?.let {
        AlertDialog(
            onDismissRequest = vm::clearError,
            confirmButton = { TextButton(onClick = vm::clearError) { Text("OK") } },
            title = { Text("Błąd") },
            text = { Text(it) }
        )
    }

    when (createDialog) {
        "choose" -> ChoiceDialog(onDismiss = { createDialog = null }, onFolder = { createDialog = "folder" }, onFile = { createDialog = "file" })
        "folder" -> NameDialog("Nowy folder", "Nazwa folderu", onDismiss = { createDialog = null }) { vm.createFolder(it); createDialog = null }
        "file" -> NameDialog("Nowy plik", "np. notatka.md", onDismiss = { createDialog = null }) { vm.createFile(it); createDialog = null }
    }

    renameItem?.let { item ->
        NameDialog("Zmień nazwę", item.name, item.name, onDismiss = { renameItem = null }) {
            vm.rename(item, it)
            renameItem = null
        }
    }

    deleteItem?.let { item ->
        AlertDialog(
            onDismissRequest = { deleteItem = null },
            title = { Text("Usunąć ${item.name}?") },
            text = { Text("Tej operacji nie można cofnąć.") },
            confirmButton = { TextButton(onClick = { vm.delete(item); deleteItem = null }) { Text("Usuń") } },
            dismissButton = { TextButton(onClick = { deleteItem = null }) { Text("Anuluj") } }
        )
    }
}

@Composable
private fun RowScope.NavItem(value: Tab, selected: Tab, icon: ImageVector, label: String, onClick: () -> Unit) {
    NavigationBarItem(selected = value == selected, onClick = onClick, icon = { Icon(icon, null) }, label = { Text(label) })
}

@Composable
private fun EmptyStorage(onPick: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
            Text("Wybierz katalog startowy", style = MaterialTheme.typography.headlineSmall)
            Text("Dostęp zostanie zapamiętany przez Android Storage Access Framework.", style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onPick) { Text("Wybierz folder") }
        }
    }
}

@Composable
private fun BreadcrumbBar(onRoot: () -> Unit, canPaste: Boolean, onPaste: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        AssistChip(onClick = onRoot, label = { Text("Katalog główny") }, leadingIcon = { Icon(Icons.Default.Home, null) })
        Spacer(Modifier.weight(1f))
        AnimatedVisibility(canPaste) { FilledTonalButton(onClick = onPaste) { Icon(Icons.Default.ContentPaste, null); Spacer(Modifier.width(6.dp)); Text("Wklej") } }
    }
}

@Composable
private fun SearchAndSort(
    query: String,
    onQuery: (String) -> Unit,
    sort: SortMode,
    onSort: (SortMode) -> Unit,
    view: ViewMode,
    onView: (ViewMode) -> Unit,
    placeholder: String
) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = query, onValueChange = onQuery, modifier = Modifier.weight(1f), singleLine = true,
            placeholder = { Text(placeholder) }, leadingIcon = { Icon(Icons.Default.Search, null) },
            shape = RoundedCornerShape(18.dp)
        )
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.Sort, "Sortuj") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                SortMode.entries.forEach { s -> DropdownMenuItem(text = { Text(sortLabel(s)) }, onClick = { onSort(s); menu = false }, leadingIcon = { if (s == sort) Icon(Icons.Default.Check, null) }) }
            }
        }
        IconButton(onClick = { onView(if (view == ViewMode.LIST) ViewMode.GRID else ViewMode.LIST) }) {
            Icon(if (view == ViewMode.LIST) Icons.Default.GridView else Icons.Default.ViewList, "Widok")
        }
    }
}

private fun sortLabel(s: SortMode) = when (s) { SortMode.NAME -> "Nazwa"; SortMode.DATE -> "Data"; SortMode.SIZE -> "Rozmiar"; SortMode.TYPE -> "Typ" }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(item: FileItem, favorite: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Surface(
        tonalElevation = 1.dp, shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            FileIcon(item, Modifier.size(40.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(meta(item), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (favorite) Icon(Icons.Default.Star, null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileTile(item: FileItem, favorite: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(22.dp), tonalElevation = 1.dp, modifier = Modifier.aspectRatio(.92f).combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box { FileIcon(item, Modifier.size(58.dp)); if (favorite) Icon(Icons.Default.Star, null, modifier = Modifier.align(Alignment.TopEnd).size(18.dp), tint = MaterialTheme.colorScheme.primary) }
            Spacer(Modifier.height(10.dp))
            Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(if (item.isDirectory) "Folder" else formatSize(item.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FileIcon(item: FileItem, modifier: Modifier) {
    val icon = when {
        item.isDirectory -> Icons.Default.Folder
        item.mimeType.startsWith("image/") -> Icons.Default.Image
        item.mimeType.startsWith("video/") -> Icons.Default.Movie
        item.mimeType.startsWith("audio/") -> Icons.Default.AudioFile
        item.mimeType == "application/pdf" -> Icons.Default.PictureAsPdf
        item.name.endsWith(".zip", true) || item.name.endsWith(".7z", true) || item.name.endsWith(".rar", true) -> Icons.Default.FolderZip
        item.mimeType.contains("json") || item.name.endsWith(".yaml", true) || item.name.endsWith(".yml", true) -> Icons.Default.DataObject
        isText(item) -> Icons.Default.Description
        else -> Icons.Default.InsertDriveFile
    }
    Icon(icon, null, modifier = modifier, tint = MaterialTheme.colorScheme.primary)
}

private fun meta(item: FileItem): String {
    val date = if (item.lastModified > 0) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(item.lastModified)) else ""
    return if (item.isDirectory) "Folder${if (date.isNotEmpty()) " • $date" else ""}" else "${formatSize(item.size)}${if (date.isNotEmpty()) " • $date" else ""}"
}

private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format("%.1f MB", mb)
    return String.format("%.1f GB", mb / 1024.0)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(item: FileItem, favorite: Boolean, onClose: () -> Unit, onFavorite: () -> Unit, onRename: () -> Unit, onCopy: () -> Unit, onCut: () -> Unit, onDelete: () -> Unit, onShare: () -> Unit) {
    TopAppBar(
        title = { Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Default.Close, null) } },
        actions = {
            IconButton(onClick = onFavorite) { Icon(if (favorite) Icons.Default.Star else Icons.Default.StarBorder, "Ulubione") }
            IconButton(onClick = onCopy) { Icon(Icons.Default.ContentCopy, "Kopiuj") }
            IconButton(onClick = onCut) { Icon(Icons.Default.ContentCut, "Wytnij") }
            MoreActions(onRename, onDelete, onShare)
        }
    )
}

@Composable
private fun MoreActions(onRename: () -> Unit, onDelete: () -> Unit, onShare: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) { Icon(Icons.Default.MoreVert, null) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Zmień nazwę") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { expanded = false; onRename() })
            DropdownMenuItem(text = { Text("Udostępnij") }, leadingIcon = { Icon(Icons.Default.Share, null) }, onClick = { expanded = false; onShare() })
            DropdownMenuItem(text = { Text("Usuń") }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { expanded = false; onDelete() })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreviewSheet(
    item: FileItem,
    vm: FileManagerViewModel,
    onDismiss: () -> Unit,
    onShare: () -> Unit,
    onOpenExternal: () -> Unit
) {
    var text by remember(item.uri) { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    LaunchedEffect(item.uri) { if (isText(item)) { text = vm.readText(item.uri); draft = text.orEmpty() } }

    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.fillMaxHeight(.94f)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FileIcon(item, Modifier.size(36.dp)); Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) { Text(item.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(item.mimeType, style = MaterialTheme.typography.bodySmall) }
                IconButton(onClick = onShare) { Icon(Icons.Default.Share, null) }
                if (isText(item)) IconButton(onClick = { editing = !editing }) { Icon(if (editing) Icons.Default.Visibility else Icons.Default.Edit, null) }
            }
            HorizontalDivider()
            when {
                item.mimeType.startsWith("image/") -> AsyncImage(model = item.uri, contentDescription = item.name, modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentScale = ContentScale.Fit)
                isText(item) -> {
                    if (editing) {
                        OutlinedTextField(value = draft, onValueChange = { draft = it }, modifier = Modifier.fillMaxWidth().weight(1f), textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace), shape = RoundedCornerShape(16.dp))
                        Button(onClick = { scope.launch { vm.writeText(item.uri, draft); text = draft; editing = false } }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Save, null); Spacer(Modifier.width(8.dp)); Text("Zapisz") }
                    } else {
                        Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(14.dp)) {
                            Text(text ?: "Wczytywanie…", fontFamily = FontFamily.Monospace)
                        }
                    }
                }
                else -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        FileIcon(item, Modifier.size(72.dp))
                        Text("Brak wbudowanego podglądu dla tego typu pliku.")
                        FilledTonalButton(onClick = onOpenExternal) {
                            Icon(Icons.Default.OpenInNew, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Otwórz w innej aplikacji")
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

private fun isText(item: FileItem): Boolean = item.mimeType.startsWith("text/") || item.mimeType.contains("json") || item.mimeType.contains("xml") || item.name.endsWith(".yaml", true) || item.name.endsWith(".yml", true) || item.name.endsWith(".md", true) || item.name.endsWith(".kt", true) || item.name.endsWith(".kts", true) || item.name.endsWith(".py", true) || item.name.endsWith(".sh", true)

@Composable
private fun ChoiceDialog(onDismiss: () -> Unit, onFolder: () -> Unit, onFile: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Utwórz") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onFolder, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.CreateNewFolder, null); Spacer(Modifier.width(8.dp)); Text("Folder") }
            FilledTonalButton(onClick = onFile, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.NoteAdd, null); Spacer(Modifier.width(8.dp)); Text("Plik") }
        }
    }, confirmButton = {})
}

@Composable
private fun NameDialog(title: String, hint: String, initial: String = "", onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { OutlinedTextField(value = value, onValueChange = { value = it }, singleLine = true, placeholder = { Text(hint) }) },
        confirmButton = { TextButton(enabled = value.isNotBlank(), onClick = { onConfirm(value) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } })
}

@Composable
private fun EmptyFolder(search: Boolean, tab: Tab) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val emptyIcon = if (search) Icons.Default.SearchOff else when (tab) {
                Tab.FILES -> Icons.Default.FolderOff
                Tab.RECENT -> Icons.Default.History
                Tab.FAVORITES -> Icons.Default.StarBorder
            }
            val emptyText = if (search) "Brak wyników" else when (tab) {
                Tab.FILES -> "Folder jest pusty"
                Tab.RECENT -> "Brak ostatnio otwieranych plików"
                Tab.FAVORITES -> "Brak ulubionych"
            }
            Icon(emptyIcon, null, modifier = Modifier.size(54.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            Text(emptyText, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun shareUri(context: Context, item: FileItem) {
    if (item.isDirectory) return
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = item.mimeType
        putExtra(Intent.EXTRA_STREAM, item.uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Udostępnij ${item.name}"))
}

private fun openExternal(context: Context, item: FileItem) {
    if (item.isDirectory) return
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(item.uri, item.mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }
    runCatching { context.startActivity(intent) }
        .onFailure {
            val chooser = Intent.createChooser(intent, "Otwórz ${item.name}")
            runCatching { context.startActivity(chooser) }
        }
}
