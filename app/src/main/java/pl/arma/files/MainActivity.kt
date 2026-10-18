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
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.tween
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.Job
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.platform.LocalConfiguration
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
import pl.arma.files.net.RemoteAccount
import pl.arma.files.net.RemoteType
import pl.arma.files.ui.theme.ArmaFilesTheme
import pl.arma.files.ui.theme.LocalFileTypeColors
import pl.arma.viewer.SheetDocuments
import pl.arma.viewer.sheet.SheetViewer
import pl.arma.viewer.sheet.SheetViewerDialog
import pl.arma.viewer.sheet.SheetViewerState
import pl.arma.viewer.sheet.ViewerAction
import pl.arma.viewer.sheet.rememberSheetViewerState
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val vm: FileManagerViewModel by viewModels()
    // Uri razem z chwilą otwarcia: ten sam plik otwarty z zewnątrz drugi raz
    // to ten sam Uri, a podgląd ma się otworzyć mimo to.
    private val externalOpen = mutableStateOf<Pair<Uri, Long>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        externalOpen.value = intent?.data?.let { it to System.nanoTime() }
        setContent {
            val open = externalOpen.value
            ArmaFilesTheme { FileManagerApp(vm, open?.first, open?.second) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        externalOpen.value = intent.data?.let { it to System.nanoTime() }
    }

    override fun onResume() {
        super.onResume()
        vm.refreshDeviceLocations()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileManagerApp(vm: FileManagerViewModel = viewModel(), initialUri: Uri? = null, openRequest: Long? = null) {
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
    val deviceStorages by vm.deviceStorages.collectAsStateWithLifecycle()
    val pinnedFolders by vm.pinnedFolders.collectAsStateWithLifecycle()
    val allFilesAccess by vm.allFilesAccess.collectAsStateWithLifecycle()
    val operations by vm.operations.collectAsStateWithLifecycle()
    val pendingConflict by vm.pendingConflict.collectAsStateWithLifecycle()
    val operationHistory by vm.operationHistory.collectAsStateWithLifecycle()
    val trashEntries by vm.trashEntries.collectAsStateWithLifecycle()
    val remoteAccounts by vm.remoteAccounts.collectAsStateWithLifecycle()
    val pinnedStats by vm.pinnedStats.collectAsStateWithLifecycle()
    val indexProgress by vm.indexProgress.collectAsStateWithLifecycle()
    val searchResults by vm.searchResults.collectAsStateWithLifecycle()
    val globalSearch by vm.globalSearch.collectAsStateWithLifecycle()
    val analysis by vm.analysis.collectAsStateWithLifecycle()
    val duplicates by vm.duplicates.collectAsStateWithLifecycle()
    val analyzing by vm.analyzing.collectAsStateWithLifecycle()
    val tabs by vm.tabs.collectAsStateWithLifecycle()
    val dualPane by vm.dualPane.collectAsStateWithLifecycle()
    val secondaryFiles by vm.secondaryFiles.collectAsStateWithLifecycle()
    val secondaryDir by vm.secondaryDir.collectAsStateWithLifecycle()
    val accountTestResult by vm.accountTestResult.collectAsStateWithLifecycle()
    val testingAccount by vm.testingAccount.collectAsStateWithLifecycle()
    val discovered by vm.discovered.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()

    var tab by remember { mutableStateOf(Tab.FILES) }
    var query by remember { mutableStateOf("") }
    // Sortowanie należy do folderu: wraca przy każdym wejściu, także po restarcie.
    var sort by remember(tab, currentDir) { mutableStateOf(vm.sortMode(tab, currentDir)) }
    var searchOpen by remember { mutableStateOf(false) }
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
    var showQueue by remember { mutableStateOf(false) }
    var showTrash by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var apkToInstall by remember { mutableStateOf<FileItem?>(null) }
    var editedAccount by remember { mutableStateOf<Pair<RemoteAccount, Boolean>?>(null) }
    var showDiscovery by remember { mutableStateOf(false) }
    var showAnalysis by remember { mutableStateOf(false) }

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
    var pendingGoogleAccount by remember { mutableStateOf<RemoteAccount?>(null) }
    val googleSignInLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val target = pendingGoogleAccount
        pendingGoogleAccount = null
        val data = result.data
        if (target == null || data == null) return@rememberLauncherForActivityResult
        appScope.launch {
            vm.googleSignIn.completeSignIn(context, data)
                .onSuccess { vm.completeGoogleSignIn(target.id, target.label, it) }
                .onFailure { snackbarHostState.showSnackbar(it.message ?: "Logowanie nie powiodło się.") }
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

    LaunchedEffect(initialUri, openRequest) {
        if (initialUri != null) {
            vm.itemFromUri(initialUri)?.let {
                preview = it
                vm.addRecent(initialUri)
            }
        }
    }

    // Pole wyszukiwania zostaje w folderze, w którym je otwarto.
    LaunchedEffect(currentDir, tab) { if (query.isBlank()) searchOpen = false }

    LaunchedEffect(query, globalSearch) {
        // Tylko przy wpisanej frazie. Puste zapytanie wyczyściłoby wyniki
        // wybrane przez kliknięcie kategorii, która też korzysta z tej listy.
        if (globalSearch && query.isNotBlank()) vm.searchEverywhere(query)
    }

    LaunchedEffect(notice) {
        notice?.let {
            snackbarHostState.showSnackbar(it)
            vm.clearNotice()
        }
    }

    // Po zakończonej operacji proponujemy cofnięcie. Snackbar jest jedynym
    // miejscem, w którym „Cofnij" ma sens czasowo — potem zostaje historia.
    LaunchedEffect(operationHistory.firstOrNull()?.id) {
        val record = operationHistory.firstOrNull() ?: return@LaunchedEffect
        if (!record.undoable) return@LaunchedEffect
        val what = when (record.kind) {
            pl.arma.files.ops.OperationKind.COPY -> "Skopiowano"
            pl.arma.files.ops.OperationKind.MOVE -> "Przeniesiono"
            pl.arma.files.ops.OperationKind.TRASH -> "Przeniesiono do kosza"
            else -> "Gotowe"
        }
        val skipped = if (record.skipped > 0) ", pominięto ${record.skipped}" else ""
        val result = snackbarHostState.showSnackbar(
            message = "$what ${record.itemCount}$skipped",
            actionLabel = "Cofnij",
            duration = SnackbarDuration.Long
        )
        if (result == SnackbarResult.ActionPerformed) vm.undo(record)
    }

    val baseFiles = when (tab) {
        Tab.FILES -> files
        Tab.RECENT -> recentFiles
        Tab.FAVORITES -> favoriteFiles
        Tab.SETTINGS -> emptyList()
    }

    val visibleFiles = remember(baseFiles, query, sort, category, globalSearch, searchResults) {
        (if (globalSearch) searchResults else baseFiles)
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
        enabled = preview != null || details != null || selectionMode || tab != Tab.FILES || currentDir != null ||
            (!computerHome && (searchOpen || query.isNotBlank()))
    ) {
        when {
            preview != null -> preview = null
            details != null -> details = null
            selectionMode -> selectedUris = emptySet()
            (searchOpen || query.isNotBlank()) && !computerHome -> {
                searchOpen = false
                query = ""
                vm.setGlobalSearch(false)
            }
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
                    onDelete = { deleteItems = selectedItems; selectedUris = emptySet() },
                    canPin = selectedItems.size == 1 && selectedItems.first().isDirectory,
                    onPin = {
                        selectedItems.firstOrNull()?.let(vm::pinFolder); selectedUris = emptySet()
                    },
                    onOpenInNewTab = {
                        selectedItems.firstOrNull()?.let(vm::openInNewTab); selectedUris = emptySet()
                    }
                )
            }
        },
        bottomBar = {
            Column {
                OperationBar(
                    operations = operations,
                    onPause = vm::pauseOperation,
                    onResume = vm::resumeOperation,
                    onCancel = vm::cancelOperation,
                    onOpenQueue = { showQueue = true }
                )
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
            }
        },
        floatingActionButton = {
            if (tab == Tab.FILES && currentDir != null && !selectionMode) {
                // Sama ikona — rozszerzony przycisk z napisem zasłaniał
                // menu ostatniego widocznego wiersza.
                FloatingActionButton(
                    onClick = { createDialog = "choose" },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) { Icon(Icons.Default.Add, "Nowy") }
            }
        }
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding)) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
        if (tab == Tab.FILES && !selectionMode) {
            // Dwa panele mają sens dopiero wtedy, gdy każdy dostanie realną
            // szerokość — na wąskim telefonie byłyby nieczytelne.
            val canSplit = LocalConfiguration.current.screenWidthDp >= 600
            val newTab = { vm.openTab(); query = ""; category = FileCategory.ALL }
            // Pasek ma zawsze tę samą budowę, także przy jednej karcie —
            // wcześniej zamieniał się w inny element i ekran wyglądał, jakby
            // aplikacja miała dwa różne układy.
            BrowserTabStrip(
                tabs = tabs,
                dualPane = dualPane,
                canSplit = canSplit,
                onSelect = vm::selectTab,
                onClose = vm::closeTab,
                onCloseOthers = vm::closeOtherTabs,
                onDuplicate = vm::duplicateTab,
                onPin = vm::pinTabFolder,
                onNew = newTab,
                onToggleDual = { vm.setDualPane(!dualPane) }
            )
        }
        Box(Modifier.fillMaxSize()) {
            when {
                tab == Tab.SETTINGS -> {
                    SettingsScreen(
                        sources = sources,
                        accounts = remoteAccounts,
                        allFilesAccess = allFilesAccess,
                        onGrantAllFilesAccess = ::requestBroadStorageAccess,
                        onPickLocalFolder = { folderPicker.launch(null) },
                        onEnabled = vm::setSourceEnabled,
                        onConnectCloud = { type ->
                            pendingCloudSource = type
                            cloudFolderPicker.launch(null)
                        },
                        onEditAccount = { editedAccount = it to false },
                        onAccountEnabled = { account, on -> vm.setAccountEnabled(account.id, on) },
                        onAddAccount = { editedAccount = vm.newAccountDraft() to true },
                        onDiscover = { showDiscovery = true; vm.discoverLan() }
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
                        onOpenSource = { vm.openSource(it) },
                        onGrantAccess = ::requestBroadStorageAccess,
                        onAddFolder = { folderPicker.launch(null) },
                        trashCount = trashEntries.size,
                        onOpenTrash = { showTrash = true },
                        indexProgress = indexProgress,
                        indexedCount = analysis.stats?.files ?: 0,
                        globalQuery = query,
                        onGlobalQuery = { value ->
                            // Wpisanie frazy tutaj włącza wyszukiwanie globalne
                            // i przenosi na listę wyników.
                            query = value
                            vm.setGlobalSearch(value.isNotBlank())
                            if (value.isNotBlank()) tab = Tab.FILES
                        },
                        onBuildIndex = { vm.buildIndex(); vm.analyzeStorage() },
                        onCancelIndex = vm::cancelIndexing,
                        onAnalyze = { showAnalysis = true; vm.analyzeStorage() },
                        onCategory = { category ->
                            vm.browseCategory(category); tab = Tab.FILES; query = ""
                        },
                        onUnpin = vm::unpinFolder,
                        onMovePinned = vm::movePinned,
                        pinnedStats = pinnedStats,
                        onOpenPinnedInNewTab = { folder ->
                            vm.openTab(); openLocal(folder.rootUri)
                        },
                        searchResults = if (globalSearch && query.isNotBlank()) searchResults else emptyList(),
                        onOpenResult = { item ->
                            if (item.isDirectory) vm.openDirectory(item) else preview = item
                        },
                        onRevealResult = vm::revealParent,
                        storageUsage = analysis.usageByCategory
                            .filterValues { it > 0 }
                            .toList()
                            .sortedByDescending { it.second }
                            .take(4)
                            .map { (category, bytes) -> categoryShortLabel(category) to bytes },
                        onOpenHistory = { showHistory = true }
                    )
                }

                else -> {
                    Column(Modifier.fillMaxSize()) {
                        FolderHeader(
                            tab = tab,
                            crumbs = breadcrumbs(root, currentDir),
                            onCrumb = vm::openPath,
                            searchOpen = searchOpen || query.isNotBlank(),
                            onSearchOpen = { open ->
                                searchOpen = open
                                if (!open) {
                                    query = ""
                                    vm.setGlobalSearch(false)
                                }
                            },
                            query = query,
                            onQuery = { query = it },
                            globalSearch = globalSearch,
                            onGlobalSearch = vm::setGlobalSearch,
                            sort = sort,
                            onSort = { sort = it; vm.saveSortMode(tab, currentDir, it) },
                            view = viewMode,
                            onView = { viewMode = it },
                            summary = "${baseFiles.size} elementów · " +
                                formatSize(baseFiles.filterNot { it.isDirectory }.sumOf { it.size }),
                            onRefresh = vm::refresh,
                            onPickRoot = { folderPicker.launch(null) }
                        )

                        CategoryRow(selected = category, source = baseFiles, onSelected = { category = it })

                        AnimatedVisibility(clipboard != null && tab == Tab.FILES) {
                            ClipboardCard(payload = clipboard, onPaste = vm::paste, onCancel = vm::clearClipboard)
                        }

                        val fileAreaModifier = Modifier
                            .fillMaxSize()
                            .padding(top = 4.dp)
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

                        val onItemClick = { item: FileItem ->
                            if (selectionMode) selectedUris = toggleSelection(selectedUris, item)
                            else {
                                openItem(
                                    vm,
                                    item,
                                    onOpenFile = { preview = it },
                                    onInstallApk = { apkToInstall = it }
                                )
                                if (item.isDirectory) {
                                    tab = Tab.FILES; query = ""; category = FileCategory.ALL
                                }
                            }
                        }

                        Box(fileAreaModifier) {
                            if (visibleFiles.isEmpty()) {
                                EmptyFolder(filtered = query.isNotBlank() || category != FileCategory.ALL, tab = tab)
                            } else if (viewMode == ViewMode.LIST) {
                                LazyColumn(contentPadding = PaddingValues(bottom = 88.dp)) {
                                    items(visibleFiles, key = { it.uri.toString() }) { item ->
                                        FileRow(
                                            item = item,
                                            vm = vm,
                                            favorite = item.uri.toString() in favoriteUris,
                                            selected = item.uri.toString() in selectedUris,
                                            selectionMode = selectionMode,
                                            onClick = { onItemClick(item) },
                                            onLongClick = { selectedUris = toggleSelection(selectedUris, item) },
                                            onMenu = { selectedUris = setOf(item.uri.toString()) }
                                        )
                                    }
                                }
                            } else {
                                LazyVerticalGrid(
                                    columns = GridCells.Fixed(gridColumns),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                    contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 88.dp)
                                ) {
                                    gridItems(visibleFiles, key = { it.uri.toString() }) { item ->
                                        FileTile(
                                            item = item,
                                            vm = vm,
                                            favorite = item.uri.toString() in favoriteUris,
                                            selected = item.uri.toString() in selectedUris,
                                            selectionMode = selectionMode,
                                            onClick = { onItemClick(item) },
                                            onLongClick = { selectedUris = toggleSelection(selectedUris, item) }
                                        )
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

        if (dualPane) {
            SecondaryPane(
                dir = secondaryDir,
                files = secondaryFiles,
                selectionCount = selectedItems.size,
                onOpen = { item ->
                    if (item.isDirectory) vm.openDirectoryInSecondary(item) else preview = item
                },
                onBack = vm::secondaryGoBack,
                onCopyHere = { vm.sendToOtherPane(selectedItems, move = false); selectedUris = emptySet() },
                onMoveHere = { vm.sendToOtherPane(selectedItems, move = true); selectedUris = emptySet() },
                onSwap = vm::swapPanes,
                onClose = { vm.setDualPane(false) },
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
        }
        }
    }

    preview?.let { item ->
        // Stan przeglądarki (PDF, DXF, DWG) powyżej obu kontenerów: po obrocie
        // dokument przechodzi między arkuszem a pełnym ekranem bez utraty miejsca.
        val sheetState = if (SheetDocuments.canOpen(item.name)) {
            rememberSheetViewerState(item.uri) { ctx -> SheetDocuments.open(ctx, vm.prepareDocument(item), item.name) }
        } else null
        // Zapis komentarzy wraca do pliku, z którego przyszedł dokument —
        // także na serwer sieciowy.
        val saveSheet: suspend (File) -> Unit = { file -> vm.replaceContent(item, file) }
        val landscape = LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        if (sheetState != null && landscape) {
            val favorite = item.uri.toString() in favoriteUris
            SheetViewerDialog(
                state = sheetState,
                onDismiss = { preview = null },
                onSave = saveSheet,
                title = item.name,
                actions = listOf(
                    ViewerAction(
                        if (favorite) "Usuń z ulubionych" else "Dodaj do ulubionych",
                        if (favorite) Icons.Default.Star else Icons.Default.StarBorder
                    ) { vm.toggleFavorite(item.uri) },
                    ViewerAction("Udostępnij", Icons.Default.Share) {
                        appScope.launch { shareItems(context, listOf(item), vm) }
                    },
                    ViewerAction("Otwórz w innej aplikacji", Icons.Default.OpenInNew) {
                        appScope.launch { openExternal(context, item, vm) }
                    },
                    ViewerAction("Właściwości", Icons.Default.Info) { preview = null; details = item }
                )
            )
            return@let
        }
        PreviewSheet(
            viewerState = sheetState,
            onSaveSheet = saveSheet,
            item = item,
            vm = vm,
            favorite = item.uri.toString() in favoriteUris,
            // Rodzeństwo bierzemy z aktualnie widocznej listy, więc przewijanie
            // respektuje wybrane sortowanie, filtr i wyniki wyszukiwania.
            siblings = visibleFiles.filter { it.mimeType.startsWith("image/") },
            onNavigate = { preview = it; vm.addRecent(it.uri) },
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
            text = {
                Text(
                    "Elementy trafią do kosza na tym samym nośniku, więc nie zwolnią miejsca " +
                        "od razu — za to można je przywrócić."
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.delete(itemsToDelete); deleteItems = emptyList() }) { Text("Do kosza") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { deleteItems = emptyList() }) { Text("Anuluj") }
                    TextButton(onClick = {
                        vm.deleteForever(itemsToDelete); deleteItems = emptyList()
                    }) { Text("Usuń trwale") }
                }
            }
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

    // Instalator jest osobnym procesem, wiec plik musi najpierw trafic do
    // naszego cache'u i zostac wystawiony przez FileProvidera -- dotyczy to
    // takze plikow z FTP, SMB i chmury.
    apkToInstall?.let { apk ->
        LaunchedEffect(apk.uri) {
            val problem = runCatching { installApk(context, vm.materializeUri(apk)) }
                .getOrElse { it.message ?: "Nie udało się przygotować pliku APK." }
            problem?.let { snackbarHostState.showSnackbar(it) }
            apkToInstall = null
        }
    }

    pendingConflict?.let { conflict ->
        ConflictDialog(conflict) { vm.resolveConflict(it) }
    }

    if (showQueue) {
        OperationQueueSheet(
            operations = operations,
            onPause = vm::pauseOperation,
            onResume = vm::resumeOperation,
            onCancel = vm::cancelOperation,
            onClearFinished = vm::clearFinishedOperations,
            onDismiss = { showQueue = false }
        )
    }

    if (showHistory) {
        OperationHistorySheet(
            history = operationHistory,
            onUndo = { vm.undo(it); showHistory = false },
            onClear = vm::clearOperationHistory,
            onDismiss = { showHistory = false }
        )
    }

    editedAccount?.let { (account, isNew) ->
        AccountEditorSheet(
            initial = account,
            isNew = isNew,
            testResult = accountTestResult,
            testing = testingAccount,
            onTest = vm::testAccount,
            onSave = vm::saveAccount,
            onDelete = vm::deleteAccount,
            onDismiss = { editedAccount = null; vm.clearAccountTest() },
            signInAvailable = vm.googleSignIn.available,
            onGoogleSignIn = { target ->
                pendingGoogleAccount = target
                runCatching { googleSignInLauncher.launch(vm.googleSignIn.authorizationIntent(context)) }
                    .onFailure {
                        appScope.launch {
                            snackbarHostState.showSnackbar(
                                "Nie udało się otworzyć okna logowania Google."
                            )
                        }
                    }
            }
        )
    }

    if (showDiscovery) {
        DiscoverySheet(
            scanning = scanning,
            hosts = discovered,
            onUse = { host ->
                showDiscovery = false
                editedAccount = vm.newAccountDraft(RemoteType.SMB).copy(
                    label = host.name ?: host.address,
                    host = host.address
                ) to true
            },
            onDismiss = { showDiscovery = false }
        )
    }

    if (showAnalysis) {
        StorageAnalysisSheet(
            analysis = analysis,
            duplicates = duplicates,
            busy = analyzing,
            onFindDuplicates = vm::findDuplicates,
            onOpen = { item -> showAnalysis = false; vm.revealParent(item) },
            onDismiss = { showAnalysis = false }
        )
    }

    if (showTrash) {
        TrashSheet(
            entries = trashEntries,
            onRestore = vm::restoreFromTrash,
            onEmpty = { vm.emptyTrash() },
            onDismiss = { showTrash = false }
        )
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
    trashCount: Int,
    onOpenTrash: () -> Unit,
    indexProgress: pl.arma.files.index.IndexProgress,
    indexedCount: Int,
    globalQuery: String,
    onGlobalQuery: (String) -> Unit,
    onBuildIndex: () -> Unit,
    onCancelIndex: () -> Unit,
    onAnalyze: () -> Unit,
    onCategory: (pl.arma.files.index.IndexCategory) -> Unit,
    onUnpin: (String) -> Unit,
    onMovePinned: (Int, Int) -> Unit,
    pinnedStats: Map<String, Pair<Int, Long>>,
    onOpenPinnedInNewTab: (PinnedFolder) -> Unit,
    searchResults: List<FileItem>,
    onOpenResult: (FileItem) -> Unit,
    onRevealResult: (FileItem) -> Unit,
    storageUsage: List<Pair<String, Long>>,
    onOpenHistory: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {

        if (!allFilesAccess) {
            item {
                Surface(
                    modifier = Modifier.padding(horizontal = 12.dp).padding(top = 8.dp).fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
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
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionTitle("Przypięte foldery", Modifier.weight(1f))
                // Same ikony — trzy akcje z podpisami nie mieściły się obok
                // nagłówka i „Kosz" był ucinany przy krawędzi.
                IconButton(onClick = onAnalyze) {
                    Icon(Icons.Default.PieChart, "Analiza pamięci", tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = onOpenHistory) {
                    Icon(Icons.Default.History, "Historia operacji", tint = MaterialTheme.colorScheme.primary)
                }
                TrashButton(count = trashCount, onClick = onOpenTrash)
            }
        }
        item {
            PinnedFolderRow(
                pinned = pinned,
                stats = pinnedStats,
                onOpen = onOpenPinned,
                onOpenInNewTab = onOpenPinnedInNewTab,
                onUnpin = onUnpin,
                onMove = onMovePinned,
                onAddFolder = onAddFolder
            )
        }
        item {
            CategorySearchRow(
                indexed = indexedCount,
                progress = indexProgress,
                query = globalQuery,
                onQuery = onGlobalQuery,
                onPick = onCategory,
                onBuild = onBuildIndex,
                onCancel = onCancelIndex,
                onRefresh = onBuildIndex
            )
        }

        // Wyniki pojawiają się pod paskiem, w miejscu pozostałych sekcji.
        // Zamiana całego ekranu zabierałaby pole, w którym trwa pisanie.
        if (searchResults.isNotEmpty()) {
            item {
                Text(
                    "Znaleziono ${searchResults.size}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                )
            }
            items(searchResults, key = { it.uri.toString() }) { item ->
                SearchResultRow(
                    item = item,
                    onOpen = { onOpenResult(item) },
                    onReveal = { onRevealResult(item) }
                )
            }
            return@LazyColumn
        }

        item {
            SectionTitle("Ten komputer", Modifier.padding(horizontal = 16.dp).padding(top = 6.dp))
        }
        items(storages, key = { it.id }) { storage ->
            DeviceStorageCard(storage = storage, usage = storageUsage, onClick = { onOpenStorage(storage) })
        }

        if (connectedSources.isNotEmpty()) {
            item {
                SectionTitle("Połączone zasoby", Modifier.padding(horizontal = 16.dp).padding(top = 6.dp))
            }
            items(connectedSources, key = { "source_${it.type.name}" }) { source ->
                ConnectedSourceCard(source = source, onClick = { onOpenSource(source) })
            }
        }
    }
}

/** Wiersz wyniku wyszukiwania: nazwa, ścieżka i skok do katalogu. */
/** Nagłówek sekcji — drobny, żeby nie konkurował z treścią pod nim. */
@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}

@Composable
private fun SearchResultRow(item: FileItem, onOpen: () -> Unit, onReveal: () -> Unit) {
    Surface(
        onClick = onOpen,
        modifier = Modifier.padding(horizontal = 8.dp).fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = Color.Transparent
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (item.isDirectory) Icons.Default.Folder else fileIcon(item),
                null,
                Modifier.size(24.dp),
                tint = fileTint(item)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                Text(
                    item.uri.path.orEmpty().substringBeforeLast('/').removePrefix("/storage/emulated/0"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (!item.isDirectory) {
                Text(
                    formatSize(item.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onReveal) {
                Icon(Icons.Default.FolderOpen, "Pokaż w katalogu", Modifier.size(20.dp))
            }
        }
    }
}

private fun categoryShortLabel(category: pl.arma.files.index.IndexCategory): String = when (category) {
    pl.arma.files.index.IndexCategory.IMAGE -> "Obrazy"
    pl.arma.files.index.IndexCategory.VIDEO -> "Filmy"
    pl.arma.files.index.IndexCategory.AUDIO -> "Audio"
    pl.arma.files.index.IndexCategory.DOCUMENT -> "Dokumenty"
    pl.arma.files.index.IndexCategory.ARCHIVE -> "Archiwa"
    pl.arma.files.index.IndexCategory.APK -> "APK"
    pl.arma.files.index.IndexCategory.OTHER -> "Inne"
}

@Composable
private fun DeviceStorageCard(
    storage: DeviceStorage,
    usage: List<Pair<String, Long>>,
    onClick: () -> Unit
) {
    val used = (storage.totalBytes - storage.freeBytes).coerceAtLeast(0L)
    val fraction = if (storage.totalBytes > 0) (used.toFloat() / storage.totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
    Surface(
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 12.dp).fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Icon(
                    if (storage.removable) Icons.Default.SdStorage else Icons.Default.Smartphone,
                    null,
                    Modifier.padding(9.dp).size(24.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(storage.label, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (storage.removable) {
                        // Etykieta nośnika pochodzi z opisu systemowego, więc karta SD
                        // i pendrive USB same się nazywają; chip mówi tylko tyle, ile
                        // wiemy na pewno — że nośnik jest wymienny.
                        AssistChip(onClick = {}, label = { Text("Wymienna") }, leadingIcon = { Icon(Icons.Default.Memory, null, Modifier.size(15.dp)) })
                    }
                }
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)))
                if (usage.isNotEmpty()) {
                    // Rozbicie pochodzi z indeksu, więc pojawia się dopiero po
                    // jego zbudowaniu — bez niego nie mamy skąd wziąć kategorii.
                    Text(
                        usage.joinToString(" · ") { (label, bytes) -> "$label ${formatSize(bytes)}" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    if (storage.totalBytes > 0) {
                        "Zajęte ${formatSize(storage.totalBytes - storage.freeBytes)} · " +
                            "${formatSize(storage.freeBytes)} wolne z ${formatSize(storage.totalBytes)}"
                    } else storage.rootUri.path.orEmpty(),
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
        modifier = Modifier.padding(horizontal = 12.dp).fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                Icon(sourceIcon(source.type), null, Modifier.padding(9.dp).size(22.dp), tint = MaterialTheme.colorScheme.primary)
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


/**
 * Rozbija bieżącą ścieżkę na klikalne człony, licząc od korzenia źródła.
 * Poza korzeniem nie schodzimy — użytkownik nie ma tam dostępu.
 */
private fun breadcrumbs(root: Uri?, current: Uri?): List<Pair<String, Uri>> {
    if (current == null) return emptyList()
    val rootPath = root?.path.orEmpty().trimEnd('/')
    val currentPath = current.path.orEmpty().trimEnd('/')
    val rootName = folderLabel(root)

    if (root == null || !currentPath.startsWith(rootPath)) {
        return listOf(folderLabel(current) to current)
    }
    val rest = currentPath.removePrefix(rootPath).trim('/')
    val crumbs = mutableListOf(rootName to root)
    var path = rootPath
    rest.split('/').filter { it.isNotBlank() }.forEach { segment ->
        path = "$path/$segment"
        crumbs += segment to current.buildUpon().path(path).build()
    }
    return crumbs
}

@Composable
private fun LocationStrip(
    crumbs: List<Pair<String, Uri>>,
    onCrumb: (Uri) -> Unit,
    modifier: Modifier = Modifier
) {
    val scroll = rememberScrollState()
    // Przy głębokiej ścieżce interesuje nas koniec, nie początek.
    LaunchedEffect(crumbs.size) { scroll.animateScrollTo(scroll.maxValue) }

    Row(modifier.horizontalScroll(scroll), verticalAlignment = Alignment.CenterVertically) {
        crumbs.forEachIndexed { index, (name, uri) ->
            if (index > 0) {
                Icon(
                    Icons.Default.ChevronRight,
                    null,
                    Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.outline
                )
            }
            val last = index == crumbs.lastIndex
            Text(
                name,
                style = if (last) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                fontWeight = if (last) FontWeight.SemiBold else FontWeight.Normal,
                color = if (last) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = !last) { onCrumb(uri) }
                    .padding(horizontal = 6.dp, vertical = 6.dp)
            )
        }
    }
}

/**
 * Jeden niski pasek nad listą: ścieżka i akcje folderu. Zastępuje zielony
 * nagłówek ze statystykami, osobny wiersz ścieżki i pole wyszukiwania, które
 * razem zabierały połowę ekranu. Liczba i rozmiar plików są w menu.
 */
@Composable
private fun FolderHeader(
    tab: Tab,
    crumbs: List<Pair<String, Uri>>,
    onCrumb: (Uri) -> Unit,
    searchOpen: Boolean,
    onSearchOpen: (Boolean) -> Unit,
    query: String,
    onQuery: (String) -> Unit,
    globalSearch: Boolean,
    onGlobalSearch: (Boolean) -> Unit,
    sort: SortMode,
    onSort: (SortMode) -> Unit,
    view: ViewMode,
    onView: (ViewMode) -> Unit,
    summary: String,
    onRefresh: () -> Unit,
    onPickRoot: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().height(52.dp).padding(start = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (searchOpen) {
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.weight(1f).height(40.dp)
            ) {
                Row(Modifier.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Search, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text(
                                when {
                                    globalSearch -> "Szukaj w całej pamięci"
                                    tab == Tab.FILES -> "Szukaj w tym folderze"
                                    else -> "Szukaj plików"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                        BasicTextField(
                            value = query,
                            onValueChange = onQuery,
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth().focusRequester(focus)
                        )
                    }
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQuery("") }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Close, "Wyczyść", Modifier.size(18.dp))
                        }
                    }
                }
            }
            IconButton(onClick = { onGlobalSearch(!globalSearch) }) {
                Icon(
                    if (globalSearch) Icons.Default.TravelExplore else Icons.Default.ManageSearch,
                    if (globalSearch) "Szukaj tylko tutaj" else "Szukaj w całej pamięci",
                    tint = if (globalSearch) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            TextButton(onClick = { onSearchOpen(false) }) { Text("Anuluj") }
            return@Row
        }

        if (tab == Tab.FILES) {
            LocationStrip(crumbs, onCrumb, Modifier.weight(1f))
        } else {
            Text(
                if (tab == Tab.RECENT) "Ostatnio otwierane" else "Ulubione",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f).padding(horizontal = 6.dp)
            )
        }

        val iconTint = MaterialTheme.colorScheme.onSurfaceVariant
        IconButton(onClick = { onSearchOpen(true) }) { Icon(Icons.Default.Search, "Szukaj", tint = iconTint) }
        var sortMenu by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { sortMenu = true }) { Icon(Icons.Default.SwapVert, "Sortuj", tint = iconTint) }
            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                SortMode.entries.forEach { mode ->
                    DropdownMenuItem(
                        text = { Text(sortLabel(mode)) },
                        leadingIcon = { if (sort == mode) Icon(Icons.Default.Check, null) },
                        onClick = { onSort(mode); sortMenu = false }
                    )
                }
            }
        }
        IconButton(onClick = { onView(if (view == ViewMode.LIST) ViewMode.GRID else ViewMode.LIST) }) {
            Icon(if (view == ViewMode.LIST) Icons.Default.GridView else Icons.Default.ViewList, "Zmień widok", tint = iconTint)
        }
        var more by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { more = true }) { Icon(Icons.Default.MoreVert, "Więcej", tint = iconTint) }
            DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                Text(
                    summary,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                HorizontalDivider()
                if (tab == Tab.FILES) {
                    DropdownMenuItem(
                        text = { Text("Odśwież") },
                        leadingIcon = { Icon(Icons.Default.Refresh, null) },
                        onClick = { more = false; onRefresh() }
                    )
                }
                DropdownMenuItem(
                    text = { Text("Otwórz folder przez SAF") },
                    leadingIcon = { Icon(Icons.Default.FolderOpen, null) },
                    onClick = { more = false; onPickRoot() }
                )
            }
        }
    }
}

@Composable
private fun CategoryRow(selected: FileCategory, source: List<FileItem>, onSelected: (FileCategory) -> Unit) {
    val counts = remember(source) {
        FileCategory.entries.associateWith { category -> source.count { matchesCategory(it, category) } }
    }
    // Puste kategorie tylko zabierają miejsce w rzędzie; wybrana zostaje,
    // żeby dało się z niej wyjść, nawet gdy filtr nic nie znajduje.
    val shown = FileCategory.entries.filter { it == FileCategory.ALL || it == selected || (counts[it] ?: 0) > 0 }
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
    ) {
        items(shown, key = { it.name }) { category ->
            val active = selected == category
            Surface(
                onClick = { onSelected(category) },
                shape = CircleShape,
                color = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                border = if (active) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .5f)) else null
            ) {
                Row(
                    Modifier.padding(start = 10.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(categoryIcon(category), null, Modifier.size(16.dp), tint = categoryColor(category))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        categoryLabel(category),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (active) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        "${counts[category] ?: 0}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun categoryColor(category: FileCategory): Color {
    val colors = LocalFileTypeColors.current
    return when (category) {
        FileCategory.ALL -> MaterialTheme.colorScheme.primary
        FileCategory.FOLDERS -> colors.folder
        FileCategory.IMAGES -> colors.image
        FileCategory.VIDEO -> colors.video
        FileCategory.AUDIO -> colors.audio
        FileCategory.DOCUMENTS -> colors.document
        FileCategory.ARCHIVES -> colors.archive
    }
}

@Composable
private fun ClipboardCard(payload: ClipboardPayload?, onPaste: () -> Unit, onCancel: () -> Unit) {
    if (payload == null) return
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
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
    // Płaski wiersz bez karty: obramowanie każdego pliku zabierało miejsce
    // i męczyło wzrok, a podział niosą teraz kolorowe ikony typów.
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FileVisual(item, Modifier.size(40.dp), thumbnail = true, vm = vm, crop = true)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                meta(item),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
        if (favorite) {
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Default.Star, null, Modifier.size(16.dp), tint = LocalFileTypeColors.current.folder)
        }
        if (selectionMode) {
            Checkbox(checked = selected, onCheckedChange = { onClick() })
        } else {
            IconButton(onClick = onMenu) {
                Icon(Icons.Default.MoreVert, "Opcje", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(14.dp),
        border = if (selected) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .5f)) else null,
        modifier = Modifier.aspectRatio(.9f).combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Column(Modifier.padding(6.dp)) {
            Box(Modifier.fillMaxWidth().weight(1f)) {
                FileVisual(item, Modifier.fillMaxSize(), thumbnail = true, vm = vm)
                if (favorite) {
                    Surface(
                        modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surface.copy(alpha = .88f)
                    ) {
                        Icon(Icons.Default.Star, null, Modifier.padding(4.dp).size(14.dp), tint = LocalFileTypeColors.current.folder)
                    }
                }
                if (selectionMode) {
                    Checkbox(checked = selected, onCheckedChange = { onClick() }, modifier = Modifier.align(Alignment.TopStart))
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                item.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 2.dp)
            )
            if (!item.isDirectory) {
                Text(
                    formatSize(item.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun FileVisual(
    item: FileItem,
    modifier: Modifier,
    thumbnail: Boolean,
    vm: FileManagerViewModel? = null,
    // W małej ikonie wiersza dopasowany obraz byłby paskiem kilku pikseli;
    // pełny kadr zostaje w siatce i podglądzie.
    crop: Boolean = false
) {
    val tint = fileTint(item)
    val rasterImage = thumbnail && item.mimeType.startsWith("image/") && !item.name.endsWith(".svg", true) && !item.mimeType.contains("svg")
    val svgImage = thumbnail && item.mimeType.startsWith("image/") && (item.name.endsWith(".svg", true) || item.mimeType.contains("svg"))

    if (rasterImage && vm != null) {
        var bitmap by remember(item.uri, item.lastModified) { mutableStateOf<Bitmap?>(null) }
        var failed by remember(item.uri, item.lastModified) { mutableStateOf(false) }
        LaunchedEffect(item.uri, item.lastModified) {
            failed = false
            val key = ThumbnailCache.key(item.uri.toString(), item.lastModified, item.size)
            val cached = ThumbnailCache.get(key)
            if (cached != null) {
                bitmap = cached
                return@LaunchedEffect
            }
            // Pobranie kilkudziesięciu megabajtów z serwera po to, by pokazać
            // kafelek wielkości paznokcia, kosztuje więcej, niż daje.
            if (vm.isRemote(item.uri) && item.size > REMOTE_THUMBNAIL_LIMIT) {
                failed = true
                return@LaunchedEffect
            }
            bitmap = runCatching {
                ThumbnailCache.throttled { vm.decodeImage(item, targetMaxSide = 640) }
            }
                .onSuccess { decoded -> ThumbnailCache.put(key, decoded) }
                .onFailure { failed = true }
                .getOrNull()
        }
        Surface(modifier = modifier, shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                bitmap?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = item.name,
                        modifier = if (crop) Modifier.fillMaxSize() else Modifier.fillMaxSize().padding(4.dp),
                        contentScale = if (crop) ContentScale.Crop else ContentScale.Fit
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
        Surface(modifier = modifier, shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            AsyncImage(
                model = uri,
                contentDescription = item.name,
                modifier = Modifier.fillMaxSize().padding(4.dp),
                contentScale = ContentScale.Fit
            )
        }
    } else {
        if (item.isDirectory) {
            // Folder bez podkładu — sama ikona jest wyraźna, a lista od razu
            // dzieli się na foldery i pliki.
            Box(modifier, contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Folder, null, Modifier.fillMaxSize(.9f), tint = tint)
            }
        } else {
            Surface(modifier = modifier, shape = RoundedCornerShape(10.dp), color = tint.copy(alpha = .16f)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(fileIcon(item), null, Modifier.fillMaxSize(.55f), tint = tint)
                }
            }
        }
    }
}

@Composable
private fun fileTint(item: FileItem): Color {
    val colors = LocalFileTypeColors.current
    val ext = item.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return when {
        item.isDirectory -> colors.folder
        item.mimeType.startsWith("image/") -> colors.image
        item.mimeType.startsWith("video/") -> colors.video
        item.mimeType.startsWith("audio/") -> colors.audio
        isPdf(item) -> colors.pdf
        isApk(item) -> colors.apk
        isArchive(item) -> colors.archive
        ext in CAD_EXTENSIONS -> colors.cad
        ext in SHEET_EXTENSIONS -> colors.sheet
        ext in CODE_EXTENSIONS || item.mimeType.contains("json") -> colors.code
        ext in DOCUMENT_EXTENSIONS || isText(item) -> colors.document
        else -> colors.other
    }
}

private fun fileIcon(item: FileItem): ImageVector {
    val ext = item.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return when {
        item.isDirectory -> Icons.Default.Folder
        item.mimeType.startsWith("image/") -> Icons.Default.Image
        item.mimeType.startsWith("video/") -> Icons.Default.Movie
        item.mimeType.startsWith("audio/") -> Icons.Default.AudioFile
        isPdf(item) -> Icons.Default.PictureAsPdf
        isApk(item) -> Icons.Default.Android
        isArchive(item) -> Icons.Default.FolderZip
        ext in CAD_EXTENSIONS -> Icons.Default.Architecture
        ext in SHEET_EXTENSIONS -> Icons.Default.TableChart
        ext in CODE_EXTENSIONS || item.mimeType.contains("json") -> Icons.Default.DataObject
        isText(item) || ext in DOCUMENT_EXTENSIONS -> Icons.Default.Description
        else -> Icons.Default.InsertDriveFile
    }
}

private val CAD_EXTENSIONS = setOf("dwg", "dxf", "step", "stp", "iges", "igs", "stl", "3mf", "fcstd", "skp", "ifc", "obj", "glb", "gltf")
private val SHEET_EXTENSIONS = setOf("xls", "xlsx", "ods", "csv", "tsv")
private val DOCUMENT_EXTENSIONS = setOf("doc", "docx", "odt", "rtf", "ppt", "pptx", "odp", "epub")
private val CODE_EXTENSIONS = setOf(
    "json", "yaml", "yml", "xml", "html", "htm", "css", "js", "ts", "kt", "kts", "java",
    "py", "sh", "c", "cpp", "h", "rs", "go", "sql", "toml", "ini", "gradle"
)

private fun isArchive(item: FileItem): Boolean =
    isZip(item) || item.name.substringAfterLast('.', "").lowercase(Locale.ROOT) in setOf("rar", "7z", "tar", "gz", "tgz", "xz", "bz2")

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
    onDelete: () -> Unit,
    canPin: Boolean,
    onPin: () -> Unit,
    onOpenInNewTab: () -> Unit
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
                    if (canPin) DropdownMenuItem(text = { Text("Przypnij do „Mój komputer”") }, leadingIcon = { Icon(Icons.Default.PushPin, null) }, onClick = { more = false; onPin() })
                    if (canPin) DropdownMenuItem(text = { Text("Otwórz w nowej karcie") }, leadingIcon = { Icon(Icons.Default.Tab, null) }, onClick = { more = false; onOpenInNewTab() })
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
    siblings: List<FileItem>,
    onNavigate: (FileItem) -> Unit,
    onDismiss: () -> Unit,
    onFavorite: () -> Unit,
    onDetails: () -> Unit,
    onShare: () -> Unit,
    onOpenExternal: () -> Unit,
    viewerState: SheetViewerState? = null,
    onSaveSheet: (suspend (File) -> Unit)? = null
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

    // Podgląd otwiera się od razu rozwinięty do góry. Bez tego arkusz startuje
    // w połowie ekranu i trzeba go dociągać palcem, żeby zobaczyć zawartość.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // Pełna szerokość: domyślny limit 640 dp zostawiał w poziomie pasy po
        // bokach, a rysunek dostawał ułamek ekranu.
        sheetMaxWidth = androidx.compose.ui.unit.Dp.Unspecified,
        modifier = Modifier.fillMaxHeight(.96f)
    ) {
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
                viewerState != null -> {
                    SheetViewer(state = viewerState, modifier = Modifier.fillMaxWidth().weight(1f), onSave = onSaveSheet)
                }
                item.mimeType.startsWith("image/") -> {
                    val position = siblings.indexOfFirst { it.uri == item.uri }
                    val step: (Int) -> Unit = { delta ->
                        val next = siblings.getOrNull(position + delta)
                        if (next != null) onNavigate(next)
                    }
                    if (siblings.size > 1 && position >= 0) {
                        Text(
                            "${position + 1} z ${siblings.size} · przesuń w bok, aby zmienić zdjęcie",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (item.name.endsWith(".svg", true) || item.mimeType.contains("svg")) {
                        SvgImagePreview(item = item, vm = vm, modifier = Modifier.fillMaxWidth().weight(1f))
                    } else {
                        NativeImagePreview(
                            item = item,
                            vm = vm,
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            onNavigate = step,
                            canNavigate = siblings.size > 1,
                            positionLabel = if (position >= 0) "${position + 1} / ${siblings.size}" else ""
                        )
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
                        TextViewer(
                            item = item,
                            vm = vm,
                            modifier = Modifier.fillMaxWidth().weight(1f)
                        )
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
private fun NativeImagePreview(
    item: FileItem,
    vm: FileManagerViewModel,
    modifier: Modifier = Modifier,
    onNavigate: (Int) -> Unit = {},
    canNavigate: Boolean = false,
    positionLabel: String = ""
) {
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
                            .pointerInput(item.uri, canNavigate) {
                                if (!canNavigate) return@pointerInput
                                var dragged = 0f
                                detectHorizontalDragGestures(
                                    onDragStart = { dragged = 0f },
                                    onDragEnd = {
                                        // Przewijamy tylko przy nieprzybliżonym
                                        // obrazie — inaczej gest kolidowałby z
                                        // przesuwaniem powiększonego kadru.
                                        if (scale <= 1.01f && kotlin.math.abs(dragged) > 120f) {
                                            onNavigate(if (dragged < 0) 1 else -1)
                                        }
                                    }
                                ) { change, amount ->
                                    if (scale <= 1.01f) {
                                        dragged += amount
                                        change.consume()
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
            FullscreenBitmapViewer(
                image = image,
                name = item.name,
                onDismiss = { fullscreen = false },
                onNavigate = onNavigate,
                canNavigate = canNavigate,
                position = positionLabel
            )
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
private fun FullscreenBitmapViewer(
    image: Bitmap,
    name: String,
    onDismiss: () -> Unit,
    onNavigate: (Int) -> Unit = {},
    canNavigate: Boolean = false,
    position: String = ""
) {
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
                        .pointerInput(name, canNavigate) {
                            if (!canNavigate) return@pointerInput
                            var dragged = 0f
                            detectHorizontalDragGestures(
                                onDragStart = { dragged = 0f },
                                onDragEnd = {
                                    if (scale <= 1.01f && kotlin.math.abs(dragged) > 120f) {
                                        onNavigate(if (dragged < 0) 1 else -1)
                                    }
                                }
                            ) { change, amount ->
                                if (scale <= 1.01f) {
                                    dragged += amount
                                    change.consume()
                                }
                            }
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

                if (position.isNotBlank()) {
                    Surface(
                        modifier = Modifier.align(Alignment.TopStart)
                            .windowInsetsPadding(WindowInsets.safeDrawing)
                            .padding(12.dp),
                        shape = RoundedCornerShape(18.dp),
                        color = Color.Black.copy(alpha = .58f)
                    ) {
                        Text(
                            position,
                            color = Color.White,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
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

private fun openItem(
    vm: FileManagerViewModel,
    item: FileItem,
    onOpenFile: (FileItem) -> Unit,
    onInstallApk: (FileItem) -> Unit = {}
) {
    when {
        item.isDirectory -> vm.openDirectory(item)
        // APK nie ma sensownego podglądu — kliknięcie ma po prostu instalować,
        // tak jak w systemowym menedżerze plików.
        isApk(item) -> {
            vm.addRecent(item.uri)
            onInstallApk(item)
        }
        else -> {
            vm.addRecent(item.uri)
            onOpenFile(item)
        }
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
        date.ifBlank { "Folder" }
    } else {
        if (date.isBlank()) formatSize(item.size) else "${formatSize(item.size)}  ·  $date"
    }
}

private fun formatDate(timestamp: Long): String {
    if (timestamp <= 0) return ""
    return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestamp))
}

/** Powyżej tego rozmiaru nie ściągamy zdalnego pliku dla samej miniatury. */
private const val REMOTE_THUMBNAIL_LIMIT = 12L * 1024 * 1024

private fun isPdf(item: FileItem): Boolean = item.mimeType == "application/pdf" || item.name.endsWith(".pdf", true)

private fun isZip(item: FileItem): Boolean = item.mimeType == "application/zip" || item.name.endsWith(".zip", true)

private fun isText(item: FileItem): Boolean =
    !item.isDirectory && pl.arma.files.text.TextProfile.isTextFile(item.name, item.mimeType)

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
