package pl.arma.files

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import pl.arma.files.ui.theme.ArmaFilesTheme
import java.io.File
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

    override fun onResume() {
        super.onResume()
        vm.refreshDeviceLocations()
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
    val sources by vm.sources.collectAsStateWithLifecycle()
    val currentSource by vm.currentSource.collectAsStateWithLifecycle()
    val ftpConfig by vm.ftpConfig.collectAsStateWithLifecycle()
    val smbConfig by vm.smbConfig.collectAsStateWithLifecycle()
    val deviceStorages by vm.deviceStorages.collectAsStateWithLifecycle()
    val pinnedFolders by vm.pinnedFolders.collectAsStateWithLifecycle()
    val allFilesAccess by vm.allFilesAccess.collectAsStateWithLifecycle()

    var tab by remember { mutableStateOf(Tab.FILES) }
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(SortMode.NAME) }
    var viewMode by rememberSaveable { mutableStateOf(ViewMode.LIST) }
    var gridColumns by rememberSaveable { mutableIntStateOf(2) }
    var category by remember { mutableStateOf(FileCategory.ALL) }
    var selectedUris by remember { mutableStateOf<Set<String>>(emptySet()) }
    var preview by remember { mutableStateOf<FileItem?>(null) }
    var details by remember { mutableStateOf<FileItem?>(null) }
    var createDialog by remember { mutableStateOf<String?>(null) }
    var renameItem by remember { mutableStateOf<FileItem?>(null) }
    var deleteItems by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    var archiveItems by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    var dragX by remember { mutableFloatStateOf(0f) }

    val snackbarHostState = remember { SnackbarHostState() }
    val appScope = rememberCoroutineScope()
    var pendingCloudSource by remember { mutableStateOf<SourceType?>(null) }
    var pendingLocalUri by remember { mutableStateOf<Uri?>(null) }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(vm::setTree)
    }
    val cloudFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val type = pendingCloudSource
        pendingCloudSource = null
        if (uri != null && type != null) vm.setCloudTree(type, uri)
    }
    val broadStorageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        vm.refreshDeviceLocations()
        pendingLocalUri?.let { uri ->
            pendingLocalUri = null
            vm.openLocalLocation(uri)
        }
    }
    val legacyStorageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.refreshDeviceLocations()
        pendingLocalUri?.let { uri ->
            pendingLocalUri = null
            vm.openLocalLocation(uri)
        }
    }

    fun requestBroadStorageAccess() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            runCatching { broadStorageLauncher.launch(intent) }
                .onFailure { broadStorageLauncher.launch(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        } else {
            legacyStorageLauncher.launch(
                arrayOf(
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            )
        }
    }

    fun openLocal(uri: Uri) {
        if (!allFilesAccess) {
            pendingLocalUri = uri
            requestBroadStorageAccess()
        } else {
            vm.openLocalLocation(uri)
            tab = Tab.FILES
            query = ""
            category = FileCategory.ALL
        }
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
        Tab.SETTINGS -> emptyList()
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
    val computerHome = tab == Tab.FILES && currentDir == null

    BackHandler(
        enabled = preview != null || details != null || selectionMode || tab != Tab.FILES || currentDir != null
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
                    onFavorite = { vm.toggleFavorites(selectedItems); selectedUris = emptySet() },
                    onCopy = {
                        vm.setClipboard(selectedItems, cut = false)
                        selectedUris = emptySet(); tab = Tab.FILES
                    },
                    onCut = {
                        vm.setClipboard(selectedItems, cut = true)
                        selectedUris = emptySet(); tab = Tab.FILES
                    },
                    onArchive = { archiveItems = selectedItems },
                    onExtract = { selectedItems.firstOrNull()?.let(vm::extractArchive); selectedUris = emptySet() },
                    onRename = { renameItem = selectedItems.firstOrNull(); selectedUris = emptySet() },
                    onDetails = { details = selectedItems.firstOrNull(); selectedUris = emptySet() },
                    onShare = { appScope.launch { shareItems(context, selectedItems, vm) }; selectedUris = emptySet() },
                    onDelete = { deleteItems = selectedItems; selectedUris = emptySet() }
                )
            }
        },
        bottomBar = {
            if (!selectionMode) {
                NavigationBar(tonalElevation = 0.dp) {
                    NavItem(Tab.FILES, tab, Icons.Default.Computer, "Mój komputer") {
                        tab = Tab.FILES; query = ""; category = FileCategory.ALL; vm.goComputerHome()
                    }
                    NavItem(Tab.RECENT, tab, Icons.Default.Schedule, "Ostatnie") {
                        tab = Tab.RECENT; query = ""; category = FileCategory.ALL
                    }
                    NavItem(Tab.FAVORITES, tab, Icons.Default.Star, "Ulubione") {
                        tab = Tab.FAVORITES; query = ""; category = FileCategory.ALL
                    }
                    NavItem(Tab.SETTINGS, tab, Icons.Default.Tune, "Ustawienia") {
                        tab = Tab.SETTINGS; query = ""; category = FileCategory.ALL
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
            when {
                tab == Tab.SETTINGS -> {
                    SettingsScreen(
                        sources = sources,
                        ftpConfig = ftpConfig,
                        smbConfig = smbConfig,
                        allFilesAccess = allFilesAccess,
                        onGrantAllFilesAccess = ::requestBroadStorageAccess,
                        onPickLocalFolder = { folderPicker.launch(null) },
                        onEnabled = vm::setSourceEnabled,
                        onConnectCloud = { type ->
                            pendingCloudSource = type
                            cloudFolderPicker.launch(null)
                        },
                        onSaveFtp = vm::saveFtp,
                        onTestFtp = { vm.testSource(SourceType.FTP) },
                        onSaveSmb = vm::saveSmb,
                        onTestSmb = { vm.testSource(SourceType.SMB) }
                    )
                }

                computerHome -> {
                    ComputerHome(
                        storages = deviceStorages,
                        pinned = pinnedFolders,
                        connectedSources = sources.filter {
                            (it.type == SourceType.LOCAL && it.configured) ||
                                (it.type != SourceType.LOCAL && it.enabled && it.configured)
                        },
                        allFilesAccess = allFilesAccess,
                        onOpenPinned = { openLocal(it.rootUri) },
                        onOpenStorage = { openLocal(it.rootUri) },
                        onOpenSource = { vm.openSource(it.type) },
                        onGrantAccess = ::requestBroadStorageAccess,
                        onAddFolder = { folderPicker.launch(null) },
                        onSettings = { tab = Tab.SETTINGS }
                    )
                }

                else -> {
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
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    LocationStrip(
                                        label = folderLabel(currentDir),
                                        atRoot = currentDir == root,
                                        onRoot = vm::goRoot
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    IconButton(onClick = vm::goComputerHome) {
                                        Icon(Icons.Default.Computer, "Mój komputer")
                                    }
                                }
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

                            CategoryRow(selected = category, source = baseFiles, onSelected = { category = it })

                            AnimatedVisibility(clipboard != null && tab == Tab.FILES) {
                                ClipboardCard(payload = clipboard, onPaste = vm::paste, onCancel = vm::clearClipboard)
                            }

                            if (viewMode == ViewMode.GRID) {
                                Text(
                                    "$gridColumns kolumny • przeciągnij w lewo/prawo, aby zmienić gęstość",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            val fileAreaModifier = Modifier
                                .fillMaxSize()
                                .pointerInput(viewMode, gridColumns) {
                                    val columnSwipeThreshold = 72.dp.toPx()
                                    detectHorizontalDragGestures(
                                        onDragStart = { dragX = 0f },
                                        onHorizontalDrag = { change, amount ->
                                            change.consume()
                                            dragX += amount
                                        },
                                        onDragEnd = {
                                            if (kotlin.math.abs(dragX) >= columnSwipeThreshold) {
                                                if (dragX < 0f) {
                                                    if (viewMode == ViewMode.LIST) {
                                                        viewMode = ViewMode.GRID
                                                        gridColumns = 2
                                                    } else {
                                                        gridColumns = (gridColumns + 1).coerceAtMost(5)
                                                    }
                                                } else {
                                                    if (viewMode == ViewMode.GRID && gridColumns > 2) {
                                                        gridColumns--
                                                    } else {
                                                        viewMode = ViewMode.LIST
                                                    }
                                                }
                                            }
                                            dragX = 0f
                                        },
                                        onDragCancel = { dragX = 0f }
                                    )
                                }

                            Box(fileAreaModifier) {
                                if (visibleFiles.isEmpty()) {
                                    EmptyFolder(filtered = query.isNotBlank() || category != FileCategory.ALL, tab = tab)
                                } else if (viewMode == ViewMode.LIST) {
                                    LazyColumn(
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                        contentPadding = PaddingValues(bottom = 96.dp)
                                    ) {
                                        items(visibleFiles, key = { it.uri.toString() }) { item ->
                                            FileRow(
                                                item = item,
                                                vm = vm,
                                                favorite = item.uri.toString() in favoriteUris,
                                                selected = item.uri.toString() in selectedUris,
                                                selectionMode = selectionMode,
                                                onClick = {
                                                    if (selectionMode) selectedUris = toggleSelection(selectedUris, item)
                                                    else {
                                                        openItem(vm, item, onOpenFile = { preview = it })
                                                        if (item.isDirectory) {
                                                            tab = Tab.FILES; query = ""; category = FileCategory.ALL
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
                                        columns = GridCells.Fixed(gridColumns),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp),
                                        contentPadding = PaddingValues(bottom = 96.dp)
                                    ) {
                                        gridItems(visibleFiles, key = { it.uri.toString() }) { item ->
                                            FileTile(
                                                item = item,
                                                vm = vm,
                                                favorite = item.uri.toString() in favoriteUris,
                                                selected = item.uri.toString() in selectedUris,
                                                selectionMode = selectionMode,
                                                onClick = {
                                                    if (selectionMode) selectedUris = toggleSelection(selectedUris, item)
                                                    else {
                                                        openItem(vm, item, onOpenFile = { preview = it })
                                                        if (item.isDirectory) {
                                                            tab = Tab.FILES; query = ""; category = FileCategory.ALL
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
            onShare = { appScope.launch { shareItems(context, listOf(item), vm) } },
            onOpenExternal = { appScope.launch { openExternal(context, item, vm) } }
        )
    }

    details?.let { item -> DetailsSheet(item = item, vm = vm, onDismiss = { details = null }) }

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
            vm.rename(item, it); renameItem = null
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
                TextButton(onClick = { vm.delete(itemsToDelete); deleteItems = emptyList() }) { Text("Usuń") }
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
private fun ComputerHome(
    storages: List<DeviceStorage>,
    pinned: List<PinnedFolder>,
    connectedSources: List<StorageSource>,
    allFilesAccess: Boolean,
    onOpenPinned: (PinnedFolder) -> Unit,
    onOpenStorage: (DeviceStorage) -> Unit,
    onOpenSource: (StorageSource) -> Unit,
    onGrantAccess: () -> Unit,
    onAddFolder: () -> Unit,
    onSettings: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.linearGradient(
                            listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary.copy(alpha = .88f))
                        ),
                        RoundedCornerShape(bottomStart = 34.dp, bottomEnd = 34.dp)
                    )
                    .padding(horizontal = 20.dp, vertical = 24.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = .14f)
                    ) {
                        Icon(
                            Icons.Default.Computer,
                            null,
                            modifier = Modifier.padding(13.dp).size(32.dp),
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Mój komputer",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Text(
                            "Pamięć urządzenia, karty i zasoby sieciowe w jednym miejscu",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = .82f)
                        )
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, "Ustawienia", tint = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            }
        }

        if (!allFilesAccess) {
            item {
                Surface(
                    modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Row(
                        Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(Icons.Default.AdminPanelSettings, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        Column(Modifier.weight(1f)) {
                            Text("Pełny dostęp do pamięci", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Włącz go, aby przeglądać pamięć wbudowaną i kartę SD jak w klasycznym menedżerze plików.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Button(onClick = onGrantAccess) { Text("Włącz") }
                    }
                }
            }
        }

        item {
            Text(
                "Przypięte foldery",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 18.dp)
            )
        }
        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(pinned, key = { it.id }) { folder ->
                    val isPictures = folder.id == "pictures"
                    Surface(
                        onClick = { onOpenPinned(folder) },
                        modifier = Modifier.width(168.dp),
                        shape = RoundedCornerShape(24.dp),
                        color = if (isPictures) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surface.copy(alpha = .72f)
                            ) {
                                Icon(
                                    if (isPictures) Icons.Default.PhotoLibrary else Icons.Default.Download,
                                    null,
                                    modifier = Modifier.padding(10.dp).size(26.dp),
                                    tint = if (isPictures) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
                                )
                            }
                            Column {
                                Text(folder.label, fontWeight = FontWeight.Bold)
                                Text(
                                    if (isPictures) "Obrazy i zdjęcia" else "Pobrane pliki",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                item {
                    OutlinedCard(
                        onClick = onAddFolder,
                        modifier = Modifier.width(168.dp),
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(Icons.Default.AddLink, null, Modifier.size(34.dp), tint = MaterialTheme.colorScheme.primary)
                            Column {
                                Text("Dodaj folder", fontWeight = FontWeight.SemiBold)
                                Text("SAF / dostawca plików", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }

        item {
            Text(
                "Ten komputer",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 18.dp)
            )
        }
        items(storages, key = { it.id }) { storage ->
            DeviceStorageCard(storage = storage, onClick = { onOpenStorage(storage) })
        }

        if (connectedSources.isNotEmpty()) {
            item {
                Text(
                    "Połączone zasoby",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 2.dp)
                )
            }
            items(connectedSources, key = { "source_${it.type.name}" }) { source ->
                ConnectedSourceCard(source = source, onClick = { onOpenSource(source) })
            }
        }
    }
}

@Composable
private fun DeviceStorageCard(storage: DeviceStorage, onClick: () -> Unit) {
    val used = (storage.totalBytes - storage.freeBytes).coerceAtLeast(0L)
    val fraction = if (storage.totalBytes > 0) (used.toFloat() / storage.totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
    Surface(
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Icon(
                    if (storage.removable) Icons.Default.SdStorage else Icons.Default.Smartphone,
                    null,
                    Modifier.padding(12.dp).size(28.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(storage.label, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (storage.removable) {
                        AssistChip(onClick = {}, label = { Text("SD") }, leadingIcon = { Icon(Icons.Default.Memory, null, Modifier.size(15.dp)) })
                    }
                }
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(50)))
                Text(
                    if (storage.totalBytes > 0) "${formatSize(storage.freeBytes)} wolne z ${formatSize(storage.totalBytes)}" else storage.rootUri.path.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ConnectedSourceCard(source: StorageSource, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                Icon(sourceIcon(source.type), null, Modifier.padding(10.dp).size(24.dp), tint = MaterialTheme.colorScheme.secondary)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(source.label, fontWeight = FontWeight.SemiBold)
                Text(source.subtitle.ifBlank { "Podłączony" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            Tab.SETTINGS -> "Ustawienia"
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
private fun ActiveSourcesRow(
    sources: List<StorageSource>,
    selected: SourceType,
    onOpen: (StorageSource) -> Unit
) {
    if (sources.isEmpty()) return
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 2.dp)
    ) {
        items(sources, key = { it.type.name }) { source ->
            val active = source.type == selected
            FilterChip(
                selected = active,
                onClick = { onOpen(source) },
                label = {
                    Column {
                        Text(source.label, maxLines = 1)
                        if (!source.configured) {
                            Text(
                                "Skonfiguruj",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                leadingIcon = {
                    Icon(
                        if (source.configured) sourceIcon(source.type) else Icons.Default.AddLink,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            )
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
    vm: FileManagerViewModel,
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
            FileVisual(item, Modifier.size(54.dp), thumbnail = true, vm = vm)
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
    vm: FileManagerViewModel,
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
                FileVisual(item, Modifier.fillMaxSize(), thumbnail = true, vm = vm)
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
private fun FileVisual(
    item: FileItem,
    modifier: Modifier,
    thumbnail: Boolean,
    vm: FileManagerViewModel? = null
) {
    val tint = fileTint(item)
    val rasterImage = thumbnail && item.mimeType.startsWith("image/") && !item.name.endsWith(".svg", true) && !item.mimeType.contains("svg")
    val svgImage = thumbnail && item.mimeType.startsWith("image/") && (item.name.endsWith(".svg", true) || item.mimeType.contains("svg"))

    if (rasterImage && vm != null) {
        var bitmap by remember(item.uri, item.lastModified) { mutableStateOf<Bitmap?>(null) }
        var failed by remember(item.uri, item.lastModified) { mutableStateOf(false) }
        LaunchedEffect(item.uri, item.lastModified) {
            failed = false
            bitmap = runCatching { vm.decodeImage(item, targetMaxSide = 640) }
                .onFailure { failed = true }
                .getOrNull()
        }
        Surface(modifier = modifier, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                bitmap?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = item.name,
                        modifier = Modifier.fillMaxSize().padding(4.dp),
                        contentScale = ContentScale.Fit
                    )
                }
                if (bitmap == null && !failed) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                if (failed) Icon(Icons.Default.Image, null, Modifier.fillMaxSize(.5f), tint = tint)
            }
        }
    } else if (svgImage && vm != null) {
        var uri by remember(item.uri, item.lastModified) { mutableStateOf<Uri?>(null) }
        LaunchedEffect(item.uri, item.lastModified) {
            uri = runCatching { vm.prepareImageUri(item) }.getOrNull()
        }
        Surface(modifier = modifier, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            AsyncImage(
                model = uri,
                contentDescription = item.name,
                modifier = Modifier.fillMaxSize().padding(4.dp),
                contentScale = ContentScale.Fit
            )
        }
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
        Column(
            Modifier.fillMaxSize().padding(horizontal = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FileVisual(item, Modifier.size(44.dp), thumbnail = true, vm = vm)
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
                isPdf(item) -> {
                    PdfPreview(item = item, vm = vm, modifier = Modifier.fillMaxWidth().weight(1f))
                }
                item.mimeType.startsWith("image/") -> {
                    if (item.name.endsWith(".svg", true) || item.mimeType.contains("svg")) {
                        SvgImagePreview(item = item, vm = vm, modifier = Modifier.fillMaxWidth().weight(1f))
                    } else {
                        NativeImagePreview(item = item, vm = vm, modifier = Modifier.fillMaxWidth().weight(1f))
                    }
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

@Composable
private fun NativeImagePreview(item: FileItem, vm: FileManagerViewModel, modifier: Modifier = Modifier) {
    var bitmap by remember(item.uri, item.lastModified) { mutableStateOf<Bitmap?>(null) }
    var error by remember(item.uri, item.lastModified) { mutableStateOf<String?>(null) }
    var scale by remember(item.uri) { mutableFloatStateOf(1f) }
    var offsetX by remember(item.uri) { mutableFloatStateOf(0f) }
    var offsetY by remember(item.uri) { mutableFloatStateOf(0f) }
    var fullscreen by remember(item.uri) { mutableStateOf(false) }

    LaunchedEffect(item.uri, item.lastModified) {
        error = null
        bitmap = runCatching { vm.decodeImage(item, targetMaxSide = 4096) }
            .onFailure { error = it.message ?: "Nie udało się otworzyć obrazu." }
            .getOrNull()
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth().weight(1f),
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            // W podglądzie arkuszowym obraz jest kotwiczony do górnej krawędzi.
            // Dzięki temu pionowe obrazy/dokumenty nie zaczynają się w połowie popupu.
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                bitmap?.let { image ->
                    Image(
                        bitmap = image.asImageBitmap(),
                        contentDescription = item.name,
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.TopCenter,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(4.dp)
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offsetX
                                translationY = offsetY
                            }
                            .pointerInput(item.uri) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    val next = (scale * zoom).coerceIn(1f, 8f)
                                    scale = next
                                    if (next > 1.01f) {
                                        val shift = 2200f * (next - 1f)
                                        offsetX = (offsetX + pan.x).coerceIn(-shift, shift)
                                        offsetY = (offsetY + pan.y).coerceIn(-shift, shift)
                                    } else {
                                        offsetX = 0f
                                        offsetY = 0f
                                    }
                                }
                            }
                    )
                }
                if (bitmap == null && error == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                error?.let {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Default.BrokenImage, null, Modifier.size(54.dp), tint = MaterialTheme.colorScheme.error)
                            Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                IconButton(onClick = {
                    scale = (scale - .25f).coerceAtLeast(1f)
                    if (scale <= 1.01f) { offsetX = 0f; offsetY = 0f }
                }) { Icon(Icons.Default.ZoomOut, "Pomniejsz") }
                Text("${(scale * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
                IconButton(onClick = { scale = (scale + .25f).coerceAtMost(8f) }) { Icon(Icons.Default.ZoomIn, "Powiększ") }
                IconButton(onClick = { scale = 1f; offsetX = 0f; offsetY = 0f }) { Icon(Icons.Default.FitScreen, "Dopasuj") }
                IconButton(onClick = { fullscreen = true }, enabled = bitmap != null) {
                    Icon(Icons.Default.Fullscreen, "Pełny ekran")
                }
            }
        }
    }

    if (fullscreen) {
        bitmap?.let { image ->
            FullscreenBitmapViewer(image = image, name = item.name, onDismiss = { fullscreen = false })
        }
    }
}

@Composable
private fun SvgImagePreview(item: FileItem, vm: FileManagerViewModel, modifier: Modifier = Modifier) {
    var uri by remember(item.uri, item.lastModified) { mutableStateOf<Uri?>(null) }
    var fullscreen by remember(item.uri) { mutableStateOf(false) }
    LaunchedEffect(item.uri, item.lastModified) {
        uri = runCatching { vm.prepareImageUri(item) }.getOrNull()
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth().weight(1f),
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                AsyncImage(
                    model = uri,
                    contentDescription = item.name,
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.TopCenter
                )
            }
        }
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                IconButton(onClick = { fullscreen = true }, enabled = uri != null) {
                    Icon(Icons.Default.Fullscreen, "Pełny ekran")
                }
            }
        }
    }

    if (fullscreen) {
        uri?.let { imageUri ->
            FullscreenSvgViewer(uri = imageUri, name = item.name, onDismiss = { fullscreen = false })
        }
    }
}

@Composable
private fun ImmersiveDialogSystemBars() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = ((view.parent as? DialogWindowProvider) ?: (view as? DialogWindowProvider))?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

@Composable
private fun FullscreenBitmapViewer(image: Bitmap, name: String, onDismiss: () -> Unit) {
    var scale by remember(name) { mutableFloatStateOf(1f) }
    var offsetX by remember(name) { mutableFloatStateOf(0f) }
    var offsetY by remember(name) { mutableFloatStateOf(0f) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        ImmersiveDialogSystemBars()
        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                Image(
                    bitmap = image.asImageBitmap(),
                    contentDescription = name,
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offsetX
                            translationY = offsetY
                        }
                        .pointerInput(name) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                val next = (scale * zoom).coerceIn(1f, 8f)
                                scale = next
                                if (next > 1.01f) {
                                    val shift = 3200f * (next - 1f)
                                    offsetX = (offsetX + pan.x).coerceIn(-shift, shift)
                                    offsetY = (offsetY + pan.y).coerceIn(-shift, shift)
                                } else {
                                    offsetX = 0f
                                    offsetY = 0f
                                }
                            }
                        }
                )

                Surface(
                    modifier = Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp),
                    shape = RoundedCornerShape(18.dp),
                    color = Color.Black.copy(alpha = .58f)
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, "Zamknij pełny ekran", tint = Color.White)
                    }
                }

                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp),
                    shape = RoundedCornerShape(22.dp),
                    color = Color.Black.copy(alpha = .62f)
                ) {
                    Row(
                        Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        IconButton(onClick = {
                            scale = (scale - .25f).coerceAtLeast(1f)
                            if (scale <= 1.01f) { offsetX = 0f; offsetY = 0f }
                        }) { Icon(Icons.Default.ZoomOut, "Pomniejsz", tint = Color.White) }
                        Text("${(scale * 100).toInt()}%", color = Color.White, style = MaterialTheme.typography.labelLarge)
                        IconButton(onClick = { scale = (scale + .25f).coerceAtMost(8f) }) {
                            Icon(Icons.Default.ZoomIn, "Powiększ", tint = Color.White)
                        }
                        IconButton(onClick = { scale = 1f; offsetX = 0f; offsetY = 0f }) {
                            Icon(Icons.Default.FitScreen, "Dopasuj", tint = Color.White)
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.FullscreenExit, "Wyjdź z pełnego ekranu", tint = Color.White)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FullscreenSvgViewer(uri: Uri, name: String, onDismiss: () -> Unit) {
    var scale by remember(name) { mutableFloatStateOf(1f) }
    var offsetX by remember(name) { mutableFloatStateOf(0f) }
    var offsetY by remember(name) { mutableFloatStateOf(0f) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        ImmersiveDialogSystemBars()
        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                AsyncImage(
                    model = uri,
                    contentDescription = name,
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offsetX
                            translationY = offsetY
                        }
                        .pointerInput(name) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                val next = (scale * zoom).coerceIn(1f, 8f)
                                scale = next
                                if (next > 1.01f) {
                                    val shift = 3200f * (next - 1f)
                                    offsetX = (offsetX + pan.x).coerceIn(-shift, shift)
                                    offsetY = (offsetY + pan.y).coerceIn(-shift, shift)
                                } else {
                                    offsetX = 0f
                                    offsetY = 0f
                                }
                            }
                        }
                )

                Surface(
                    modifier = Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp),
                    shape = RoundedCornerShape(18.dp),
                    color = Color.Black.copy(alpha = .58f)
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, "Zamknij pełny ekran", tint = Color.White)
                    }
                }

                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp),
                    shape = RoundedCornerShape(22.dp),
                    color = Color.Black.copy(alpha = .62f)
                ) {
                    Row(
                        Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        IconButton(onClick = {
                            scale = (scale - .25f).coerceAtLeast(1f)
                            if (scale <= 1.01f) { offsetX = 0f; offsetY = 0f }
                        }) { Icon(Icons.Default.ZoomOut, "Pomniejsz", tint = Color.White) }
                        Text("${(scale * 100).toInt()}%", color = Color.White, style = MaterialTheme.typography.labelLarge)
                        IconButton(onClick = { scale = (scale + .25f).coerceAtMost(8f) }) {
                            Icon(Icons.Default.ZoomIn, "Powiększ", tint = Color.White)
                        }
                        IconButton(onClick = { scale = 1f; offsetX = 0f; offsetY = 0f }) {
                            Icon(Icons.Default.FitScreen, "Dopasuj", tint = Color.White)
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.FullscreenExit, "Wyjdź z pełnego ekranu", tint = Color.White)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfPreview(
    item: FileItem,
    vm: FileManagerViewModel,
    modifier: Modifier = Modifier
) {
    var pdfFile by remember(item.uri) { mutableStateOf<File?>(null) }
    var pageCount by remember(item.uri) { mutableIntStateOf(0) }
    var loading by remember(item.uri) { mutableStateOf(true) }
    var error by remember(item.uri) { mutableStateOf<String?>(null) }
    var zoom by remember(item.uri) { mutableFloatStateOf(1f) }
    var jumpDialog by remember(item.uri) { mutableStateOf(false) }
    var jumpText by remember(item.uri) { mutableStateOf("") }
    val listState = rememberLazyListState()
    val thumbState = rememberLazyListState()
    val horizontal = rememberScrollState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(item.uri) {
        loading = true
        error = null
        runCatching {
            val file = vm.preparePdf(item)
            val count = vm.pdfPageCount(file)
            require(count > 0) { "Dokument PDF nie zawiera stron." }
            file to count
        }.onSuccess { (file, count) ->
            pdfFile = file
            pageCount = count
        }.onFailure { failure ->
            error = if (failure is SecurityException) {
                "PDF jest zabezpieczony hasłem lub używa nieobsługiwanego szyfrowania."
            } else {
                failure.message ?: "Nie udało się otworzyć dokumentu PDF."
            }
        }
        loading = false
    }

    val currentPage = if (pageCount > 0) (listState.firstVisibleItemIndex + 1).coerceIn(1, pageCount) else 0

    LaunchedEffect(currentPage, pageCount) {
        if (pageCount > 0) {
            val target = (currentPage - 3).coerceIn(0, (pageCount - 1).coerceAtLeast(0))
            runCatching { thumbState.animateScrollToItem(target) }
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { scope.launch { listState.animateScrollToItem((currentPage - 2).coerceAtLeast(0)) } },
                    enabled = currentPage > 1
                ) { Icon(Icons.Default.KeyboardArrowUp, "Poprzednia strona") }

                TextButton(
                    onClick = {
                        jumpText = if (currentPage > 0) currentPage.toString() else "1"
                        jumpDialog = true
                    },
                    enabled = pageCount > 0
                ) {
                    Text(
                        if (pageCount > 0) "$currentPage / $pageCount" else "PDF",
                        fontWeight = FontWeight.SemiBold
                    )
                }

                IconButton(
                    onClick = { scope.launch { listState.animateScrollToItem(currentPage.coerceAtMost(pageCount - 1)) } },
                    enabled = currentPage in 1 until pageCount
                ) { Icon(Icons.Default.KeyboardArrowDown, "Następna strona") }

                Spacer(Modifier.weight(1f))

                IconButton(onClick = { zoom = (zoom - .25f).coerceAtLeast(1f) }) {
                    Icon(Icons.Default.ZoomOut, "Pomniejsz")
                }
                Text("${(zoom * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
                IconButton(onClick = { zoom = (zoom + .25f).coerceAtMost(4f) }) {
                    Icon(Icons.Default.ZoomIn, "Powiększ")
                }
                IconButton(onClick = { zoom = 1f; scope.launch { horizontal.animateScrollTo(0) } }) {
                    Icon(Icons.Default.FitScreen, "Dopasuj")
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth().weight(1f),
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when {
                    loading -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator()
                        Text("Wczytywanie PDF…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    error != null -> Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(Icons.Default.PictureAsPdf, null, Modifier.size(58.dp), tint = MaterialTheme.colorScheme.error)
                        Text("Nie udało się wyświetlić PDF", fontWeight = FontWeight.SemiBold)
                        Text(error.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    pdfFile != null && pageCount > 0 -> BoxWithConstraints(Modifier.fillMaxSize()) {
                        val documentWidth = maxWidth * zoom
                        Box(
                            Modifier
                                .fillMaxSize()
                                .pointerInput(item.uri) {
                                    awaitEachGesture {
                                        awaitFirstDown(requireUnconsumed = false)
                                        var active = true
                                        while (active) {
                                            val event = awaitPointerEvent()
                                            val pressed = event.changes.count { it.pressed }
                                            if (pressed >= 2) {
                                                val gestureZoom = event.calculateZoom()
                                                if (gestureZoom != 1f) {
                                                    zoom = (zoom * gestureZoom).coerceIn(1f, 4f)
                                                    event.changes.forEach { it.consume() }
                                                }
                                            }
                                            active = event.changes.any { it.pressed }
                                        }
                                    }
                                }
                                .horizontalScroll(horizontal)
                        ) {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.width(documentWidth).fillMaxHeight(),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(pageCount, key = { it }) { page ->
                                    PdfPageCard(
                                        file = pdfFile!!,
                                        pageIndex = page,
                                        pageCount = pageCount,
                                        vm = vm,
                                        targetWidth = (1500 * zoom.coerceAtMost(2f)).toInt()
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (pdfFile != null && pageCount > 0 && error == null) {
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                LazyRow(
                    state = thumbState,
                    modifier = Modifier.fillMaxWidth().height(92.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    items(pageCount, key = { "thumb_$it" }) { page ->
                        PdfThumbnail(
                            file = pdfFile!!,
                            pageIndex = page,
                            selected = page == currentPage - 1,
                            vm = vm,
                            onClick = { scope.launch { listState.animateScrollToItem(page) } }
                        )
                    }
                }
            }
        }
    }

    if (jumpDialog) {
        AlertDialog(
            onDismissRequest = { jumpDialog = false },
            title = { Text("Przejdź do strony") },
            text = {
                OutlinedTextField(
                    value = jumpText,
                    onValueChange = { jumpText = it.filter(Char::isDigit).take(6) },
                    singleLine = true,
                    label = { Text("Numer strony (1–$pageCount)") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val page = jumpText.toIntOrNull()?.coerceIn(1, pageCount) ?: 1
                    jumpDialog = false
                    scope.launch { listState.animateScrollToItem(page - 1) }
                }) { Text("Przejdź") }
            },
            dismissButton = { TextButton(onClick = { jumpDialog = false }) { Text("Anuluj") } }
        )
    }
}

@Composable
private fun PdfPageCard(
    file: File,
    pageIndex: Int,
    pageCount: Int,
    vm: FileManagerViewModel,
    targetWidth: Int
) {
    var bitmap by remember(file.absolutePath, pageIndex, targetWidth) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(file.absolutePath, pageIndex) { mutableStateOf(false) }

    LaunchedEffect(file.absolutePath, pageIndex, targetWidth) {
        failed = false
        bitmap = runCatching { vm.renderPdfPage(file, pageIndex, targetWidth.coerceIn(900, 3000)) }
            .onFailure { failed = true }
            .getOrNull()
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        shadowElevation = 2.dp,
        color = Color.White
    ) {
        val rendered = bitmap
        when {
            rendered != null -> Image(
                bitmap = rendered.asImageBitmap(),
                contentDescription = "Strona ${pageIndex + 1} z $pageCount",
                modifier = Modifier.fillMaxWidth().aspectRatio(rendered.width.toFloat() / rendered.height.toFloat()),
                contentScale = ContentScale.FillWidth
            )
            failed -> Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                Text("Nie udało się wyrenderować strony ${pageIndex + 1}", color = MaterialTheme.colorScheme.error)
            }
            else -> Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }
    }
}

@Composable
private fun PdfThumbnail(
    file: File,
    pageIndex: Int,
    selected: Boolean,
    vm: FileManagerViewModel,
    onClick: () -> Unit
) {
    var bitmap by remember(file.absolutePath, pageIndex) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(file.absolutePath, pageIndex) {
        bitmap = runCatching { vm.renderPdfPage(file, pageIndex, 220) }.getOrNull()
    }
    Surface(
        onClick = onClick,
        modifier = Modifier.width(62.dp).fillMaxHeight(),
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        ),
        color = Color.White
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            bitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = "Miniatura strony ${pageIndex + 1}",
                    modifier = Modifier.fillMaxSize().padding(2.dp),
                    contentScale = ContentScale.Fit
                )
            } ?: CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp),
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surface.copy(alpha = .88f)
            ) {
                Text("${pageIndex + 1}", modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall)
            }
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
                FileVisual(item, Modifier.size(58.dp), thumbnail = true, vm = vm)
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
                Tab.SETTINGS -> Icons.Default.Tune
            }
            val text = if (filtered) "Nic tu nie pasuje" else when (tab) {
                Tab.FILES -> "Ten folder jest pusty"
                Tab.RECENT -> "Brak ostatnio otwieranych plików"
                Tab.FAVORITES -> "Nie masz jeszcze ulubionych"
                Tab.SETTINGS -> "Brak ustawień"
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
    if (uri.scheme == RemoteRepository.FTP_SCHEME) return uri.path.orEmpty().trim('/').substringAfterLast('/').ifBlank { "FTP" }
    if (uri.scheme == RemoteRepository.SMB_SCHEME) return uri.path.orEmpty().trim('/').substringAfterLast('/').ifBlank { "SMB / Samba" }
    if (uri.scheme == "file") return uri.path?.trimEnd('/')?.substringAfterLast('/')?.ifBlank { "Pamięć" } ?: "Pamięć"
    return runCatching {
        val id = DocumentsContract.getDocumentId(uri)
        id.substringAfterLast('/').substringAfterLast(':').ifBlank { "Katalog główny" }
    }.getOrDefault(uri.lastPathSegment ?: "Katalog")
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

private fun isPdf(item: FileItem): Boolean = item.mimeType == "application/pdf" || item.name.endsWith(".pdf", true)

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

private suspend fun shareItems(context: Context, items: List<FileItem>, vm: FileManagerViewModel) {
    val files = items.filterNot { it.isDirectory }
    if (files.isEmpty()) return
    val uris = files.map { vm.materializeUri(it) }
    val intent = if (files.size == 1) {
        Intent(Intent.ACTION_SEND).apply {
            type = files.first().mimeType
            putExtra(Intent.EXTRA_STREAM, uris.first())
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
    runCatching { context.startActivity(Intent.createChooser(intent, "Udostępnij")) }
}

private suspend fun openExternal(context: Context, item: FileItem, vm: FileManagerViewModel) {
    if (item.isDirectory) return
    val uri = vm.materializeUri(item)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, item.mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }
    runCatching { context.startActivity(intent) }
        .onFailure { runCatching { context.startActivity(Intent.createChooser(intent, "Otwórz ${item.name}")) } }
}
