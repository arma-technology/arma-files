package pl.arma.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import kotlinx.coroutines.launch
import pl.arma.files.ui.theme.ArmaFilesTheme
import java.text.DateFormat
import java.util.Date
import java.util.Locale

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
    val favoriteUris by vm.favoriteUris.collectAsStateWithLifecycle()
    val currentDir by vm.currentDir.collectAsStateWithLifecycle()
    val root by vm.root.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val clipboard by vm.clipboard.collectAsStateWithLifecycle()

    var tab by remember { mutableStateOf(Tab.FILES) }
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(SortMode.NAME) }
    var viewMode by remember { mutableStateOf(ViewMode.LIST) }
    var category by remember { mutableStateOf(FileCategory.ALL) }
    var selectedUris by remember { mutableStateOf<Set<String>>(emptySet()) }
    var preview by remember { mutableStateOf<FileItem?>(null) }
    var details by remember { mutableStateOf<FileItem?>(null) }
    var createDialog by remember { mutableStateOf<String?>(null) }
    var renameItem by remember { mutableStateOf<FileItem?>(null) }
    var deleteItems by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    var archiveItems by remember { mutableStateOf<List<FileItem>>(emptyList()) }

    val snackbarHostState = remember { SnackbarHostState() }
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

    LaunchedEffect(notice) {
        notice?.let {
            snackbarHostState.showSnackbar(it)
            vm.clearNotice()
        }
    }

    val baseFiles = when (tab) {
        Tab.FILES -> files
        Tab.RECENT -> recentFiles
        Tab.FAVORITES -> favoriteFiles
    }

    val visibleFiles = remember(baseFiles, query, sort, category) {
        baseFiles
            .asSequence()
            .filter { it.name.contains(query, ignoreCase = true) }
            .filter { matchesCategory(it, category) }
            .sortedWith(fileComparator(sort))
            .toList()
    }

    val selectedItems = remember(baseFiles, selectedUris) {
        baseFiles.filter { it.uri.toString() in selectedUris }
    }
    val selectionMode = selectedUris.isNotEmpty()

    BackHandler(
        enabled = preview != null || details != null || selectionMode || tab != Tab.FILES || (currentDir != null && currentDir != root)
    ) {
        when {
            preview != null -> preview = null
            details != null -> details = null
            selectionMode -> selectedUris = emptySet()
            tab != Tab.FILES -> {
                tab = Tab.FILES
                query = ""
                category = FileCategory.ALL
            }
            else -> vm.goBack()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (selectionMode) {
                MultiSelectionTopBar(
                    count = selectedItems.size,
                    canRename = selectedItems.size == 1,
                    canExtract = selectedItems.size == 1 && isZip(selectedItems.first()),
                    onClose = { selectedUris = emptySet() },
                    onSelectAll = { selectedUris = visibleFiles.map { it.uri.toString() }.toSet() },
                    onFavorite = {
                        vm.toggleFavorites(selectedItems)
                        selectedUris = emptySet()
                    },
                    onCopy = {
                        vm.setClipboard(selectedItems, cut = false)
                        selectedUris = emptySet()
                        tab = Tab.FILES
                    },
                    onCut = {
                        vm.setClipboard(selectedItems, cut = true)
                        selectedUris = emptySet()
                        tab = Tab.FILES
                    },
                    onArchive = { archiveItems = selectedItems },
                    onExtract = {
                        selectedItems.firstOrNull()?.let(vm::extractArchive)
                        selectedUris = emptySet()
                    },
                    onRename = {
                        renameItem = selectedItems.firstOrNull()
                        selectedUris = emptySet()
                    },
                    onDetails = {
                        details = selectedItems.firstOrNull()
                        selectedUris = emptySet()
                    },
                    onShare = {
                        shareItems(context, selectedItems)
                        selectedUris = emptySet()
                    },
                    onDelete = {
                        deleteItems = selectedItems
                        selectedUris = emptySet()
                    }
                )
            }
        },
        bottomBar = {
            if (!selectionMode) {
                NavigationBar(tonalElevation = 0.dp) {
                    NavItem(Tab.FILES, tab, Icons.Default.Folder, "Pliki") {
                        tab = Tab.FILES; query = ""; category = FileCategory.ALL
                    }
                    NavItem(Tab.RECENT, tab, Icons.Default.Schedule, "Ostatnie") {
                        tab = Tab.RECENT; query = ""; category = FileCategory.ALL
                    }
                    NavItem(Tab.FAVORITES, tab, Icons.Default.Star, "Ulubione") {
                        tab = Tab.FAVORITES; query = ""; category = FileCategory.ALL
                    }
                }
            }
        },
        floatingActionButton = {
            if (tab == Tab.FILES && currentDir != null && !selectionMode) {
                ExtendedFloatingActionButton(
                    onClick = { createDialog = "choose" },
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text("Nowy") },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (tab == Tab.FILES && currentDir == null) {
                EmptyStorage(onPick = { folderPicker.launch(null) })
            } else {
                Column(Modifier.fillMaxSize()) {
                    AppHero(
                        tab = tab,
                        currentDir = currentDir,
                        fileCount = baseFiles.size,
                        totalSize = baseFiles.filterNot { it.isDirectory }.sumOf { it.size },
                        onPickRoot = { folderPicker.launch(null) },
                        onRefresh = vm::refresh
                    )

                    Column(
                        Modifier.fillMaxSize().padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Spacer(Modifier.height(2.dp))

                        if (tab == Tab.FILES) {
                            LocationStrip(
                                label = folderLabel(currentDir),
                                atRoot = currentDir == root,
                                onRoot = vm::goRoot
                            )
                        } else {
                            SectionHint(tab)
                        }

                        SearchToolbar(
                            query = query,
                            onQuery = { query = it },
                            sort = sort,
                            onSort = { sort = it },
                            view = viewMode,
                            onView = { viewMode = it },
                            placeholder = if (tab == Tab.FILES) "Szukaj w tym folderze" else "Szukaj plików"
                        )

                        CategoryRow(
                            selected = category,
                            source = baseFiles,
                            onSelected = { category = it }
                        )

                        AnimatedVisibility(clipboard != null && tab == Tab.FILES) {
                            ClipboardCard(
                                payload = clipboard,
                                onPaste = vm::paste,
                                onCancel = vm::clearClipboard
                            )
                        }

                        if (visibleFiles.isEmpty()) {
                            EmptyFolder(
                                filtered = query.isNotBlank() || category != FileCategory.ALL,
                                tab = tab
                            )
                        } else if (viewMode == ViewMode.LIST) {
                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(bottom = 96.dp)
                            ) {
                                items(visibleFiles, key = { it.uri.toString() }) { item ->
                                    FileRow(
                                        item = item,
                                        favorite = item.uri.toString() in favoriteUris,
                                        selected = item.uri.toString() in selectedUris,
                                        selectionMode = selectionMode,
                                        onClick = {
                                            if (selectionMode) {
                                                selectedUris = toggleSelection(selectedUris, item)
                                            } else {
                                                openItem(vm, item, onOpenFile = { preview = it })
                                                if (item.isDirectory) {
                                                    tab = Tab.FILES
                                                    query = ""
                                                    category = FileCategory.ALL
                                                }
                                            }
                                        },
                                        onLongClick = { selectedUris = toggleSelection(selectedUris, item) },
                                        onMenu = { selectedUris = setOf(item.uri.toString()) }
                                    )
                                }
                            }
                        } else {
                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(145.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                contentPadding = PaddingValues(bottom = 96.dp)
                            ) {
                                gridItems(visibleFiles, key = { it.uri.toString() }) { item ->
                                    FileTile(
                                        item = item,
                                        favorite = item.uri.toString() in favoriteUris,
                                        selected = item.uri.toString() in selectedUris,
                                        selectionMode = selectionMode,
                                        onClick = {
                                            if (selectionMode) {
                                                selectedUris = toggleSelection(selectedUris, item)
                                            } else {
                                                openItem(vm, item, onOpenFile = { preview = it })
                                                if (item.isDirectory) {
                                                    tab = Tab.FILES
                                                    query = ""
                                                    category = FileCategory.ALL
                                                }
                                            }
                                        },
                                        onLongClick = { selectedUris = toggleSelection(selectedUris, item) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (busy) {
                Box(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = .18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(shape = RoundedCornerShape(24.dp), tonalElevation = 6.dp) {
                        Row(
                            Modifier.padding(horizontal = 24.dp, vertical = 18.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 3.dp)
                            Text("Przetwarzanie…", fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }

    preview?.let { item ->
        PreviewSheet(
            item = item,
            vm = vm,
            favorite = item.uri.toString() in favoriteUris,
            onDismiss = { preview = null },
            onFavorite = { vm.toggleFavorite(item.uri) },
            onDetails = { preview = null; details = item },
            onShare = { shareItems(context, listOf(item)) },
            onOpenExternal = { openExternal(context, item) }
        )
    }

    details?.let { item ->
        DetailsSheet(item = item, vm = vm, onDismiss = { details = null })
    }

    error?.let {
        AlertDialog(
            onDismissRequest = vm::clearError,
            confirmButton = { TextButton(onClick = vm::clearError) { Text("OK") } },
            icon = { Icon(Icons.Default.ErrorOutline, null) },
            title = { Text("Nie udało się wykonać operacji") },
            text = { Text(it) }
        )
    }

    when (createDialog) {
        "choose" -> ChoiceDialog(
            onDismiss = { createDialog = null },
            onFolder = { createDialog = "folder" },
            onFile = { createDialog = "file" }
        )
        "folder" -> NameDialog("Nowy folder", "Nazwa folderu", onDismiss = { createDialog = null }) {
            vm.createFolder(it); createDialog = null
        }
        "file" -> NameDialog("Nowy plik", "np. notatka.md", onDismiss = { createDialog = null }) {
            vm.createFile(it); createDialog = null
        }
    }

    renameItem?.let { item ->
        NameDialog("Zmień nazwę", item.name, item.name, onDismiss = { renameItem = null }) {
            vm.rename(item, it)
            renameItem = null
        }
    }

    if (deleteItems.isNotEmpty()) {
        val itemsToDelete = deleteItems
        AlertDialog(
            onDismissRequest = { deleteItems = emptyList() },
            icon = { Icon(Icons.Default.DeleteOutline, null) },
            title = { Text(if (itemsToDelete.size == 1) "Usunąć ${itemsToDelete.first().name}?" else "Usunąć ${itemsToDelete.size} elementów?") },
            text = { Text("Tej operacji nie można cofnąć.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(itemsToDelete)
                    deleteItems = emptyList()
                }) { Text("Usuń") }
            },
            dismissButton = { TextButton(onClick = { deleteItems = emptyList() }) { Text("Anuluj") } }
        )
    }

    if (archiveItems.isNotEmpty()) {
        val itemsToArchive = archiveItems
        NameDialog(
            title = "Utwórz archiwum ZIP",
            hint = "Nazwa archiwum",
            initial = if (itemsToArchive.size == 1) itemsToArchive.first().name.substringBeforeLast('.', itemsToArchive.first().name) + ".zip" else "archiwum.zip",
            onDismiss = { archiveItems = emptyList() }
        ) { name ->
            vm.createArchive(itemsToArchive, name)
            archiveItems = emptyList()
            selectedUris = emptySet()
        }
    }
}

@Composable
private fun AppHero(
    tab: Tab,
    currentDir: Uri?,
    fileCount: Int,
    totalSize: Long,
    onPickRoot: () -> Unit,
    onRefresh: () -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    Box(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.linearGradient(listOf(primary, primary.copy(alpha = .88f), tertiary.copy(alpha = .86f))),
                RoundedCornerShape(bottomStart = 30.dp, bottomEnd = 30.dp)
            )
            .padding(horizontal = 18.dp, vertical = 18.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = .14f)
                ) {
                    Icon(
                        Icons.Default.FolderSpecial,
                        contentDescription = null,
                        modifier = Modifier.padding(10.dp).size(26.dp),
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("ARMA Files", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        when (tab) {
                            Tab.FILES -> folderLabel(currentDir)
                            Tab.RECENT -> "Ostatnio otwierane"
                            Tab.FAVORITES -> "Twoje ulubione"
                        },
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = .8f),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (tab == Tab.FILES) {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, "Odśwież", tint = MaterialTheme.colorScheme.onPrimary)
                    }
                }
                IconButton(onClick = onPickRoot) {
                    Icon(Icons.Default.FolderOpen, "Wybierz katalog", tint = MaterialTheme.colorScheme.onPrimary)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HeroMetric(Icons.Default.Inventory2, "$fileCount", "elementów", Modifier.weight(1f))
                HeroMetric(Icons.Default.DataUsage, formatSize(totalSize), "rozmiar plików", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun HeroMetric(icon: ImageVector, value: String, label: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = .12f),
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onPrimary)
            Spacer(Modifier.width(9.dp))
            Column {
                Text(value, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(label, color = MaterialTheme.colorScheme.onPrimary.copy(alpha = .72f), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun LocationStrip(label: String, atRoot: Boolean, onRoot: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(14.dp)) {
            Row(Modifier.padding(horizontal = 11.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Folder, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(7.dp))
                Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
            }
        }
        Spacer(Modifier.weight(1f))
        if (!atRoot) {
            TextButton(onClick = onRoot) {
                Icon(Icons.Default.Home, null, Modifier.size(18.dp))
                Spacer(Modifier.width(5.dp))
                Text("Start")
            }
        }
    }
}

@Composable
private fun SectionHint(tab: Tab) {
    Text(
        if (tab == Tab.RECENT) "Pliki, do których ostatnio zaglądałeś" else "Szybki dostęp do oznaczonych elementów",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun SearchToolbar(
    query: String,
    onQuery: (String) -> Unit,
    sort: SortMode,
    onSort: (SortMode) -> Unit,
    view: ViewMode,
    onView: (ViewMode) -> Unit,
    placeholder: String
) {
    var menu by remember { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            Modifier.fillMaxWidth().padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            TextField(
                value = query,
                onValueChange = onQuery,
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text(placeholder) },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (query.isNotBlank()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Default.Close, "Wyczyść") }
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                )
            )
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.SwapVert, "Sortuj") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    SortMode.entries.forEach { mode ->
                        DropdownMenuItem(
                            text = { Text(sortLabel(mode)) },
                            leadingIcon = { if (sort == mode) Icon(Icons.Default.Check, null) },
                            onClick = { onSort(mode); menu = false }
                        )
                    }
                }
            }
            IconButton(onClick = { onView(if (view == ViewMode.LIST) ViewMode.GRID else ViewMode.LIST) }) {
                Icon(if (view == ViewMode.LIST) Icons.Default.GridView else Icons.Default.ViewList, "Zmień widok")
            }
        }
    }
}

@Composable
private fun CategoryRow(selected: FileCategory, source: List<FileItem>, onSelected: (FileCategory) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 2.dp)) {
        items(FileCategory.entries) { category ->
            val count = source.count { matchesCategory(it, category) }
            FilterChip(
                selected = selected == category,
                onClick = { onSelected(category) },
                label = { Text("${categoryLabel(category)} · $count") },
                leadingIcon = { Icon(categoryIcon(category), null, Modifier.size(17.dp)) }
            )
        }
    }
}

@Composable
private fun ClipboardCard(payload: ClipboardPayload?, onPaste: () -> Unit, onCancel: () -> Unit) {
    if (payload == null) return
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (payload.cut) Icons.Default.DriveFileMove else Icons.Default.ContentCopy, null)
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(if (payload.cut) "Do przeniesienia" else "Do skopiowania", fontWeight = FontWeight.SemiBold)
                Text("${payload.items.size} elementów", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onCancel) { Text("Anuluj") }
            Button(onClick = onPaste) { Text("Wklej") }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    item: FileItem,
    favorite: Boolean,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMenu: () -> Unit
) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        border = if (selected) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .45f)) else null,
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            FileVisual(item, Modifier.size(54.dp), thumbnail = true)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(item.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (favorite) {
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Default.Star, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.tertiary)
                    }
                }
                Spacer(Modifier.height(3.dp))
                Text(meta(item), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            if (selectionMode) {
                Checkbox(checked = selected, onCheckedChange = { onClick() })
            } else {
                IconButton(onClick = onMenu) { Icon(Icons.Default.MoreVert, "Opcje") }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileTile(
    item: FileItem,
    favorite: Boolean,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(24.dp),
        border = if (selected) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .45f)) else null,
        modifier = Modifier.aspectRatio(.88f).combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Column(Modifier.padding(10.dp)) {
            Box(Modifier.fillMaxWidth().weight(1f)) {
                FileVisual(item, Modifier.fillMaxSize(), thumbnail = true)
                if (favorite) {
                    Surface(
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = .88f)
                    ) {
                        Icon(Icons.Default.Star, null, Modifier.padding(5.dp).size(17.dp), tint = MaterialTheme.colorScheme.tertiary)
                    }
                }
                if (selectionMode) {
                    Checkbox(checked = selected, onCheckedChange = { onClick() }, modifier = Modifier.align(Alignment.TopStart))
                }
            }
            Spacer(Modifier.height(9.dp))
            Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(2.dp))
            Text(if (item.isDirectory) "Folder" else formatSize(item.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FileVisual(item: FileItem, modifier: Modifier, thumbnail: Boolean) {
    val tint = fileTint(item)
    if (thumbnail && item.mimeType.startsWith("image/") && !item.mimeType.contains("svg")) {
        AsyncImage(
            model = item.uri,
            contentDescription = item.name,
            modifier = modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentScale = ContentScale.Crop
        )
    } else {
        Surface(modifier = modifier, shape = RoundedCornerShape(16.dp), color = tint.copy(alpha = .13f)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(fileIcon(item), null, Modifier.fillMaxSize(.55f), tint = tint)
            }
        }
    }
}

@Composable
private fun fileTint(item: FileItem): Color = when {
    item.isDirectory -> MaterialTheme.colorScheme.primary
    item.mimeType.startsWith("image/") -> MaterialTheme.colorScheme.tertiary
    item.mimeType.startsWith("video/") -> MaterialTheme.colorScheme.secondary
    item.mimeType.startsWith("audio/") -> MaterialTheme.colorScheme.tertiary
    item.mimeType == "application/pdf" -> MaterialTheme.colorScheme.error
    isZip(item) -> MaterialTheme.colorScheme.primary
    item.mimeType.contains("json") || item.name.endsWith(".yaml", true) || item.name.endsWith(".yml", true) -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.primary
}

private fun fileIcon(item: FileItem): ImageVector = when {
    item.isDirectory -> Icons.Default.Folder
    item.mimeType.startsWith("image/") -> Icons.Default.Image
    item.mimeType.startsWith("video/") -> Icons.Default.Movie
    item.mimeType.startsWith("audio/") -> Icons.Default.AudioFile
    item.mimeType == "application/pdf" -> Icons.Default.PictureAsPdf
    isZip(item) -> Icons.Default.FolderZip
    item.mimeType.contains("json") || item.name.endsWith(".yaml", true) || item.name.endsWith(".yml", true) -> Icons.Default.DataObject
    isText(item) -> Icons.Default.Description
    else -> Icons.Default.InsertDriveFile
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MultiSelectionTopBar(
    count: Int,
    canRename: Boolean,
    canExtract: Boolean,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onFavorite: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onArchive: () -> Unit,
    onExtract: () -> Unit,
    onRename: () -> Unit,
    onDetails: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    var more by remember { mutableStateOf(false) }
    TopAppBar(
        title = { Text("Wybrano: $count", fontWeight = FontWeight.SemiBold) },
        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Zamknij") } },
        actions = {
            IconButton(onClick = onFavorite) { Icon(Icons.Default.StarBorder, "Ulubione") }
            IconButton(onClick = onCopy) { Icon(Icons.Default.ContentCopy, "Kopiuj") }
            IconButton(onClick = onCut) { Icon(Icons.Default.ContentCut, "Wytnij") }
            Box {
                IconButton(onClick = { more = true }) { Icon(Icons.Default.MoreVert, "Więcej") }
                DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                    DropdownMenuItem(text = { Text("Zaznacz wszystko") }, leadingIcon = { Icon(Icons.Default.SelectAll, null) }, onClick = { more = false; onSelectAll() })
                    DropdownMenuItem(text = { Text("Spakuj do ZIP") }, leadingIcon = { Icon(Icons.Default.FolderZip, null) }, onClick = { more = false; onArchive() })
                    if (canExtract) DropdownMenuItem(text = { Text("Rozpakuj tutaj") }, leadingIcon = { Icon(Icons.Default.Unarchive, null) }, onClick = { more = false; onExtract() })
                    if (canRename) DropdownMenuItem(text = { Text("Zmień nazwę") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { more = false; onRename() })
                    if (canRename) DropdownMenuItem(text = { Text("Właściwości") }, leadingIcon = { Icon(Icons.Default.Info, null) }, onClick = { more = false; onDetails() })
                    DropdownMenuItem(text = { Text("Udostępnij") }, leadingIcon = { Icon(Icons.Default.Share, null) }, onClick = { more = false; onShare() })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Usuń") }, leadingIcon = { Icon(Icons.Default.DeleteOutline, null) }, onClick = { more = false; onDelete() })
                }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreviewSheet(
    item: FileItem,
    vm: FileManagerViewModel,
    favorite: Boolean,
    onDismiss: () -> Unit,
    onFavorite: () -> Unit,
    onDetails: () -> Unit,
    onShare: () -> Unit,
    onOpenExternal: () -> Unit
) {
    var text by remember(item.uri) { mutableStateOf<String?>(null) }
    var editing by remember(item.uri) { mutableStateOf(false) }
    var draft by remember(item.uri) { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(item.uri) {
        if (isText(item)) {
            text = vm.readText(item.uri)
            draft = text.orEmpty()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.fillMaxHeight(.96f)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FileVisual(item, Modifier.size(44.dp), thumbnail = true)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(meta(item), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onFavorite) { Icon(if (favorite) Icons.Default.Star else Icons.Default.StarBorder, "Ulubione") }
                IconButton(onClick = onDetails) { Icon(Icons.Default.Info, "Właściwości") }
                IconButton(onClick = onShare) { Icon(Icons.Default.Share, "Udostępnij") }
            }
            HorizontalDivider()

            when {
                item.mimeType.startsWith("image/") -> {
                    AsyncImage(
                        model = item.uri,
                        contentDescription = item.name,
                        modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                        contentScale = ContentScale.Fit
                    )
                }
                isText(item) -> {
                    if (editing) {
                        OutlinedTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                            shape = RoundedCornerShape(18.dp)
                        )
                        Button(
                            onClick = {
                                scope.launch {
                                    vm.writeText(item.uri, draft)
                                    text = draft
                                    editing = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Save, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Zapisz zmiany")
                        }
                    } else {
                        Surface(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer
                        ) {
                            Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                                Text(text ?: "Wczytywanie…", fontFamily = FontFamily.Monospace)
                            }
                        }
                        FilledTonalButton(onClick = { editing = true }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Edit, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Edytuj")
                        }
                    }
                }
                else -> {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            FileVisual(item, Modifier.size(92.dp), thumbnail = false)
                            Text("Ten format zostanie otwarty w kompatybilnej aplikacji.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Button(onClick = onOpenExternal) {
                                Icon(Icons.Default.OpenInNew, null)
                                Spacer(Modifier.width(8.dp))
                                Text("Otwórz")
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailsSheet(item: FileItem, vm: FileManagerViewModel, onDismiss: () -> Unit) {
    var checksum by remember(item.uri) { mutableStateOf<String?>(null) }
    var checksumError by remember(item.uri) { mutableStateOf<String?>(null) }

    LaunchedEffect(item.uri) {
        if (!item.isDirectory) {
            runCatching { vm.sha256(item.uri) }
                .onSuccess { checksum = it }
                .onFailure { checksumError = it.message ?: "Nie udało się obliczyć sumy." }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FileVisual(item, Modifier.size(58.dp), thumbnail = true)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(if (item.isDirectory) "Folder" else formatSize(item.size), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider()
            DetailRow("Typ", item.mimeType)
            DetailRow("Zmodyfikowano", formatDate(item.lastModified))
            if (!item.isDirectory) DetailRow("Rozmiar", "${formatSize(item.size)} (${String.format(Locale.getDefault(), "%,d", item.size)} B)")
            DetailRow("URI", item.uri.toString(), monospace = true)
            if (!item.isDirectory) {
                DetailRow("SHA-256", checksum ?: checksumError ?: "Obliczanie…", monospace = true)
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String, monospace: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
            modifier = Modifier.horizontalScroll(rememberScrollState())
        )
    }
}

@Composable
private fun ChoiceDialog(onDismiss: () -> Unit, onFolder: () -> Unit, onFile: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.AddCircleOutline, null) },
        title = { Text("Utwórz nowy element") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(onClick = onFolder, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.CreateNewFolder, null); Spacer(Modifier.width(8.dp)); Text("Folder")
                }
                FilledTonalButton(onClick = onFile, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.NoteAdd, null); Spacer(Modifier.width(8.dp)); Text("Plik")
                }
            }
        },
        confirmButton = {}
    )
}

@Composable
private fun NameDialog(
    title: String,
    hint: String,
    initial: String = "",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var value by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                placeholder = { Text(hint) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            )
        },
        confirmButton = { TextButton(enabled = value.isNotBlank(), onClick = { onConfirm(value.trim()) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } }
    )
}

@Composable
private fun EmptyStorage(onPick: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.background))
        ),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.padding(24.dp).fillMaxWidth(),
            shape = RoundedCornerShape(30.dp),
            tonalElevation = 2.dp
        ) {
            Column(
                Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Icon(Icons.Default.FolderOpen, null, Modifier.padding(18.dp).size(58.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Text("Witaj w ARMA Files", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "Wybierz katalog startowy. Android zapamięta dostęp, więc aplikacja będzie mogła wrócić do niego po ponownym uruchomieniu.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(onClick = onPick, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.FolderOpen, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Wybierz katalog")
                }
            }
        }
    }
}

@Composable
private fun EmptyFolder(filtered: Boolean, tab: Tab) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val icon = if (filtered) Icons.Default.SearchOff else when (tab) {
                Tab.FILES -> Icons.Default.FolderOff
                Tab.RECENT -> Icons.Default.Schedule
                Tab.FAVORITES -> Icons.Default.StarBorder
            }
            val text = if (filtered) "Nic tu nie pasuje" else when (tab) {
                Tab.FILES -> "Ten folder jest pusty"
                Tab.RECENT -> "Brak ostatnio otwieranych plików"
                Tab.FAVORITES -> "Nie masz jeszcze ulubionych"
            }
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Icon(icon, null, Modifier.padding(18.dp).size(44.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun RowScope.NavItem(value: Tab, selected: Tab, icon: ImageVector, label: String, onClick: () -> Unit) {
    NavigationBarItem(
        selected = value == selected,
        onClick = onClick,
        icon = { Icon(icon, null) },
        label = { Text(label) }
    )
}

private fun openItem(vm: FileManagerViewModel, item: FileItem, onOpenFile: (FileItem) -> Unit) {
    if (item.isDirectory) {
        vm.openDirectory(item)
    } else {
        vm.addRecent(item.uri)
        onOpenFile(item)
    }
}

private fun toggleSelection(current: Set<String>, item: FileItem): Set<String> {
    val key = item.uri.toString()
    return current.toMutableSet().apply { if (!add(key)) remove(key) }
}

private fun fileComparator(sort: SortMode) = Comparator<FileItem> { a, b ->
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
}

private fun sortLabel(mode: SortMode) = when (mode) {
    SortMode.NAME -> "Nazwa"
    SortMode.DATE -> "Data modyfikacji"
    SortMode.SIZE -> "Rozmiar"
    SortMode.TYPE -> "Typ pliku"
}

private fun categoryLabel(category: FileCategory) = when (category) {
    FileCategory.ALL -> "Wszystko"
    FileCategory.FOLDERS -> "Foldery"
    FileCategory.IMAGES -> "Obrazy"
    FileCategory.VIDEO -> "Wideo"
    FileCategory.AUDIO -> "Audio"
    FileCategory.DOCUMENTS -> "Dokumenty"
    FileCategory.ARCHIVES -> "Archiwa"
}

private fun categoryIcon(category: FileCategory): ImageVector = when (category) {
    FileCategory.ALL -> Icons.Default.Dashboard
    FileCategory.FOLDERS -> Icons.Default.Folder
    FileCategory.IMAGES -> Icons.Default.Image
    FileCategory.VIDEO -> Icons.Default.Movie
    FileCategory.AUDIO -> Icons.Default.AudioFile
    FileCategory.DOCUMENTS -> Icons.Default.Description
    FileCategory.ARCHIVES -> Icons.Default.FolderZip
}

private fun matchesCategory(item: FileItem, category: FileCategory): Boolean = when (category) {
    FileCategory.ALL -> true
    FileCategory.FOLDERS -> item.isDirectory
    FileCategory.IMAGES -> !item.isDirectory && item.mimeType.startsWith("image/")
    FileCategory.VIDEO -> !item.isDirectory && item.mimeType.startsWith("video/")
    FileCategory.AUDIO -> !item.isDirectory && item.mimeType.startsWith("audio/")
    FileCategory.ARCHIVES -> !item.isDirectory && (isZip(item) || item.name.endsWith(".rar", true) || item.name.endsWith(".7z", true))
    FileCategory.DOCUMENTS -> !item.isDirectory && !item.mimeType.startsWith("image/") && !item.mimeType.startsWith("video/") && !item.mimeType.startsWith("audio/") && !isZip(item)
}

private fun folderLabel(uri: Uri?): String {
    if (uri == null) return "Pamięć plików"
    return runCatching {
        val id = DocumentsContract.getDocumentId(uri)
        id.substringAfterLast('/').substringAfterLast(':').ifBlank { "Katalog główny" }
    }.getOrDefault("Katalog")
}

private fun meta(item: FileItem): String {
    val date = formatDate(item.lastModified)
    return if (item.isDirectory) {
        if (date.isBlank()) "Folder" else "Folder • $date"
    } else {
        if (date.isBlank()) formatSize(item.size) else "${formatSize(item.size)} • $date"
    }
}

private fun formatDate(timestamp: Long): String {
    if (timestamp <= 0) return ""
    return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestamp))
}

private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.getDefault(), "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.getDefault(), "%.1f MB", mb)
    val gb = mb / 1024.0
    if (gb < 1024) return String.format(Locale.getDefault(), "%.1f GB", gb)
    return String.format(Locale.getDefault(), "%.1f TB", gb / 1024.0)
}

private fun isZip(item: FileItem): Boolean = item.mimeType == "application/zip" || item.name.endsWith(".zip", true)

private fun isText(item: FileItem): Boolean =
    item.mimeType.startsWith("text/") ||
        item.mimeType.contains("json") ||
        item.mimeType.contains("xml") ||
        item.name.endsWith(".yaml", true) ||
        item.name.endsWith(".yml", true) ||
        item.name.endsWith(".md", true) ||
        item.name.endsWith(".kt", true) ||
        item.name.endsWith(".kts", true) ||
        item.name.endsWith(".py", true) ||
        item.name.endsWith(".sh", true)

private fun shareItems(context: Context, items: List<FileItem>) {
    val files = items.filterNot { it.isDirectory }
    if (files.isEmpty()) return
    val intent = if (files.size == 1) {
        Intent(Intent.ACTION_SEND).apply {
            type = files.first().mimeType
            putExtra(Intent.EXTRA_STREAM, files.first().uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(files.map { it.uri }))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
    runCatching { context.startActivity(Intent.createChooser(intent, "Udostępnij")) }
}

private fun openExternal(context: Context, item: FileItem) {
    if (item.isDirectory) return
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(item.uri, item.mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }
    runCatching { context.startActivity(intent) }
        .onFailure {
            runCatching { context.startActivity(Intent.createChooser(intent, "Otwórz ${item.name}")) }
        }
}
