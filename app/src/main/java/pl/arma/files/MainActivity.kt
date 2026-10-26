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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
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
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
import pl.arma.files.archive.ArchiveFormat
import pl.arma.files.archive.ArchiveFormats
import pl.arma.files.archive.ArchiveRepository
import pl.arma.files.net.RemoteAccount
import pl.arma.files.net.RemoteType
import pl.arma.files.ui.theme.ArmaFilesTheme
import pl.arma.files.ui.theme.LocalFileTypeColors
import pl.arma.files.ui.theme.LocalArmaStyle
import pl.arma.files.ui.theme.ArmaStyle
import pl.arma.files.ui.theme.PlexMono
import androidx.compose.foundation.selection.selectable
import pl.arma.viewer.SheetDocuments
import pl.arma.viewer.image.FullscreenImageViewer
import pl.arma.viewer.image.FullscreenSvgViewer
import pl.arma.viewer.image.ImageViewer
import pl.arma.viewer.image.SvgImageViewer
import pl.arma.viewer.sheet.SheetViewer
import pl.arma.viewer.sheet.SheetViewerDialog
import pl.arma.viewer.sheet.SheetViewerState
import pl.arma.viewer.sheet.ViewerAction
import pl.arma.viewer.sheet.rememberSheetViewerState
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation

class MainActivity : ComponentActivity() {
    private val vm: FileManagerViewModel by viewModels()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }
    // Uri razem z chwilą otwarcia: ten sam plik otwarty z zewnątrz drugi raz
    // to ten sam Uri, a podgląd ma się otworzyć mimo to.
    private val externalOpen = mutableStateOf<Pair<Uri, Long>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState != null) vm.onLocaleChanged()
        externalOpen.value = intent?.data?.let { it to System.nanoTime() }
        setContent {
            val open = externalOpen.value
            ArmaFilesTheme {
                // Orbita działań rysuje się nad całym oknem, gest zaczyna się w liście.
                val orbit = remember { OrbitController() }
                androidx.compose.runtime.CompositionLocalProvider(LocalOrbit provides orbit) {
                    Box(Modifier.fillMaxSize()) {
                        FileManagerApp(vm, open?.first, open?.second)
                        OrbitOverlay(orbit)
                    }
                }
            }
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

    override fun onStart() {
        super.onStart()
        vm.setForeground(true)
    }

    override fun onStop() {
        super.onStop()
        vm.setForeground(false)
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
    val inArchive = ArchiveRepository.isArchive(currentDir)
    val offlineEntries by vm.offlineEntries.collectAsStateWithLifecycle()
    val offlineProgress by vm.offlineProgress.collectAsStateWithLifecycle()
    val offlineSince by vm.offlineSince.collectAsStateWithLifecycle()
    val searchRemote by vm.searchRemote.collectAsStateWithLifecycle()
    val searchStatus by vm.searchStatus.collectAsStateWithLifecycle()
    var conflictsOf by remember { mutableStateOf<String?>(null) }
    val secondaryFiles by vm.secondaryFiles.collectAsStateWithLifecycle()
    val secondaryDir by vm.secondaryDir.collectAsStateWithLifecycle()
    val accountTestResult by vm.accountTestResult.collectAsStateWithLifecycle()
    val testingAccount by vm.testingAccount.collectAsStateWithLifecycle()
    val discovered by vm.discovered.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()

    var tab by rememberSaveable { mutableStateOf(Tab.FILES) }
    var query by remember { mutableStateOf("") }
    // Sortowanie należy do folderu: wraca przy każdym wejściu, także po restarcie.
    var sort by remember(tab, currentDir) { mutableStateOf(vm.sortMode(tab, currentDir)) }
    var searchOpen by remember { mutableStateOf(false) }
    var viewMode by rememberSaveable { mutableStateOf(ViewMode.LIST) }
    var gridColumns by rememberSaveable { mutableIntStateOf(2) }
    var category by remember { mutableStateOf(FileCategory.ALL) }
    var selectedUris by remember { mutableStateOf<Set<String>>(emptySet()) }
    var preview by remember { mutableStateOf<FileItem?>(null) }
    // Szybki podgląd po przytrzymaniu i puszczeniu pliku.
    var peekItem by remember { mutableStateOf<FileItem?>(null) }
    val orbit = LocalOrbit.current
    val shelfItems by vm.shelf.collectAsStateWithLifecycle()
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
    var reopenAnalysis by remember { mutableStateOf(false) }
    var analysisSection by remember { mutableIntStateOf(0) }
    // Do kosza domyślnie wszystkie kopie poza najstarszą — ta jest zwykle oryginałem.
    var duplicateMarks by remember(duplicates) {
        mutableStateOf(duplicates.flatMap { set -> set.files.sortedBy { it.lastModified }.drop(1) }.map { it.uri.toString() }.toSet())
    }
    LaunchedEffect(preview) {
        if (preview == null && reopenAnalysis) {
            reopenAnalysis = false
            showAnalysis = true
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val appScope = rememberCoroutineScope()
    var pendingCloudSource by remember { mutableStateOf<SourceType?>(null) }
    var pendingLocalUri by remember { mutableStateOf<Uri?>(null) }

    // Powiadomienia (postęp kopiowania, rozbieżności offline) na Androidzie 13+
    // wymagają zgody; pytamy przy pierwszym oznaczeniu czegoś jako offline.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    fun askForNotifications() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }
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

    LaunchedEffect(initialUri, openRequest) {
        if (initialUri != null) {
            vm.itemFromUri(initialUri)?.let {
                preview = it
                vm.addRecent(it)
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
            pl.arma.files.ops.OperationKind.COPY -> R.string.undo_copied
            pl.arma.files.ops.OperationKind.EXTRACT -> R.string.undo_extracted
            pl.arma.files.ops.OperationKind.MOVE -> R.string.undo_moved
            pl.arma.files.ops.OperationKind.TRASH -> R.string.undo_trashed
            else -> R.string.undo_done
        }
        val skipped = if (record.skipped > 0) context.getString(R.string.undo_skipped_suffix, record.skipped) else ""
        val result = snackbarHostState.showSnackbar(
            message = context.getString(what, record.itemCount) + skipped,
            actionLabel = context.getString(R.string.common_undo),
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

    // Miniatury filmów w kolejności wyświetlania, zanim kafelki o nie poproszą.
    LaunchedEffect(visibleFiles) {
        context.armaApp.videoThumbnails.prefetch(visibleFiles)
    }

    val selectedItems = remember(baseFiles, selectedUris) {
        baseFiles.filter { it.uri.toString() in selectedUris }
    }
    val selectionMode = selectedUris.isNotEmpty()

    // Wynik gestu orbity: puszczenie bez ruchu = szybki podgląd, ruch = akcja z koła.
    val onOrbit: (OrbitResult) -> Unit = { result ->
        when (result) {
            is OrbitResult.Peek -> peekItem = result.item
            is OrbitResult.Action -> {
                val item = result.item
                when (result.action) {
                    OrbitAction.SHARE -> appScope.launch { shareItems(context, listOf(item), vm) }
                    OrbitAction.SHELF -> vm.addToShelf(listOf(item))
                    OrbitAction.RENAME -> renameItem = item
                    OrbitAction.DELETE -> deleteItems = listOf(item)
                    OrbitAction.SELECT -> selectedUris = toggleSelection(selectedUris, item)
                    OrbitAction.DETAILS -> details = item
                }
            }
        }
    }
    val computerHome = tab == Tab.FILES && currentDir == null

    BackHandler(
        enabled = preview != null || details != null || selectionMode || tab != Tab.FILES || vm.canGoBack() ||
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
                    canExtract = selectedItems.size == 1 && !selectedItems.first().isDirectory &&
                        ArchiveFormats.canOpenAsArchive(selectedItems.first().name) || inArchive,
                    canBrowse = selectedItems.size == 1 && !selectedItems.first().isDirectory &&
                        ArchiveFormats.canOpenAsArchive(selectedItems.first().name),
                    canExtractToOther = dualPane,
                    readOnly = inArchive,
                    canOffline = selectedItems.isNotEmpty() && selectedItems.all { vm.isRemote(it.uri) },
                    allOffline = selectedItems.isNotEmpty() && selectedItems.all { item -> offlineEntries.any { it.source == item.uri.toString() } },
                    onOffline = { remove ->
                        if (remove) vm.removeOffline(selectedItems) else {
                            askForNotifications()
                            vm.makeOffline(selectedItems)
                        }
                        selectedUris = emptySet()
                    },
                    onBrowse = { selectedItems.firstOrNull()?.let(vm::openArchive); selectedUris = emptySet() },
                    onExtractToOther = {
                        if (inArchive) vm.extractItems(selectedItems, toOtherPane = true)
                        else selectedItems.firstOrNull()?.let { vm.extractArchive(it, toOtherPane = true) }
                        selectedUris = emptySet()
                    },
                    onClose = { selectedUris = emptySet() },
                    onSelectAll = { selectedUris = visibleFiles.map { it.uri.toString() }.toSet() },
                    onFavorite = { vm.toggleFavorites(selectedItems); selectedUris = emptySet() },
                    onShelf = { vm.addToShelf(selectedItems); selectedUris = emptySet() },
                    onCopy = {
                        vm.setClipboard(selectedItems, cut = false)
                        selectedUris = emptySet(); tab = Tab.FILES
                    },
                    onCut = {
                        vm.setClipboard(selectedItems, cut = true)
                        selectedUris = emptySet(); tab = Tab.FILES
                    },
                    onArchive = { archiveItems = selectedItems },
                    onExtract = {
                        if (inArchive) vm.extractItems(selectedItems)
                        else selectedItems.firstOrNull()?.let { vm.extractArchive(it) }
                        selectedUris = emptySet()
                    },
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
                // Szuflada nad nawigacją, gdy coś w niej leży.
                AnimatedVisibility(shelfItems.isNotEmpty() && !selectionMode) {
                    ShelfBar(
                        items = shelfItems,
                        vm = vm,
                        canDrop = tab == Tab.FILES && currentDir != null && !inArchive,
                        onDrop = vm::dropShelf,
                        onRemove = vm::removeFromShelf,
                        onClear = vm::clearShelf
                    )
                }
                if (!selectionMode) {
                    ArmaNavBar {
                    NavItem(Tab.FILES, tab, Icons.Default.PhoneAndroid, stringResource(R.string.nav_home)) {
                        tab = Tab.FILES; query = ""; category = FileCategory.ALL; vm.showHome()
                    }
                    NavItem(Tab.RECENT, tab, Icons.Default.Schedule, stringResource(R.string.nav_recent)) {
                        tab = Tab.RECENT; query = ""; category = FileCategory.ALL
                    }
                    NavItem(Tab.FAVORITES, tab, Icons.Default.Star, stringResource(R.string.nav_favorites)) {
                        tab = Tab.FAVORITES; query = ""; category = FileCategory.ALL
                    }
                        NavItem(Tab.SETTINGS, tab, Icons.Default.Tune, stringResource(R.string.nav_settings)) {
                            tab = Tab.SETTINGS; query = ""; category = FileCategory.ALL
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            if (tab == Tab.FILES && inArchive && !selectionMode) {
                // Wnętrze archiwum jest tylko do odczytu — zamiast „Nowy”
                // rozpakowanie całości obok archiwum.
                FloatingActionButton(
                    onClick = {
                        val dir = currentDir ?: return@FloatingActionButton
                        val root = dir.buildUpon().path("/").build()
                        vm.extractArchive(FileItem(root, vm.rootLabel(root).orEmpty(), DIRECTORY_MIME, 0, 0, true))
                    },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) { Icon(Icons.Default.Unarchive, stringResource(R.string.archive_extract_all)) }
            } else if (tab == Tab.FILES && currentDir != null && !selectionMode) {
                // Sama ikona — rozszerzony przycisk z napisem zasłaniał
                // menu ostatniego widocznego wiersza.
                FloatingActionButton(
                    onClick = { createDialog = "choose" },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) { Icon(Icons.Default.Add, stringResource(R.string.fab_new)) }
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
                        onDisconnect = vm::disconnectSource,
                        onEditAccount = { editedAccount = it to false },
                        onAccountEnabled = { account, on -> vm.setAccountEnabled(account.id, on) },
                        onAddAccount = { editedAccount = vm.newAccountDraft() to true },
                        onDiscover = { showDiscovery = true; vm.discoverLan() },
                        trashBytes = trashEntries.sumOf { it.size },
                        onEmptyTrash = vm::emptyTrash,
                        indexProgress = indexProgress,
                        indexedFiles = analysis.stats?.files ?: 0,
                        onBuildIndex = vm::buildIndex,
                        onCancelIndex = vm::cancelIndexing,
                        onStorageChanged = vm::analyzeStorage
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
                        onDisconnectSource = vm::disconnectSource,
                        onDisableSource = { source -> source.accountId?.let { vm.setAccountEnabled(it, false) } },
                        onManageSources = { tab = Tab.SETTINGS; query = ""; category = FileCategory.ALL },
                        offlineEntries = offlineEntries,
                        offlineProgress = offlineProgress,
                        onOpenOffline = { entry ->
                            if (entry.isDirectory) vm.openOfflineFolder(entry)
                            else appScope.launch { vm.offlineItem(entry)?.let { preview = it; vm.addRecent(it) } }
                        },
                        onRemoveOffline = vm::removeOfflineEntry,
                        onConflicts = { conflictsOf = it.id },
                        onSyncOffline = vm::syncOffline,
                        onGrantAccess = ::requestBroadStorageAccess,
                        onAddFolder = { folderPicker.launch(null) },
                        trashCount = trashEntries.size,
                        onOpenTrash = { showTrash = true },
                        indexProgress = indexProgress,
                        indexedCount = analysis.stats?.files ?: 0,
                        hasRemote = remoteAccounts.any { it.enabled },
                        searchRemote = searchRemote,
                        onSearchRemote = vm::setSearchRemote,
                        searchingIn = searchStatus.where,
                        searchLocation = vm::searchLocation,
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
                            when {
                                item.isDirectory -> vm.openDirectory(item)
                                ArchiveFormats.canBrowse(item.name) -> vm.openArchive(item)
                                else -> preview = item
                            }
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
                            crumbs = breadcrumbs(root, currentDir, vm::rootLabel, vm::archiveSource),
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
                            summary = pluralStringResource(R.plurals.n_items, baseFiles.size, baseFiles.size) + " · " +
                                formatSize(baseFiles.filterNot { it.isDirectory }.sumOf { it.size }),
                            onRefresh = vm::refresh,
                            onPickRoot = { folderPicker.launch(null) },
                            files = baseFiles
                        )

                        CategoryRow(selected = category, source = baseFiles, onSelected = { category = it })

                        AnimatedVisibility(clipboard != null && tab == Tab.FILES) {
                            ClipboardCard(payload = clipboard, onPaste = vm::paste, onCancel = vm::clearClipboard)
                        }

                        AnimatedVisibility(offlineSince != null && tab == Tab.FILES) {
                            OfflineBanner(since = offlineSince ?: 0L)
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
                                if (item.isDirectory || ArchiveFormats.canBrowse(item.name)) {
                                    tab = Tab.FILES; query = ""; category = FileCategory.ALL
                                }
                            }
                        }

                        // Pozycja listy zapamiętana dla folderu w tej karcie: po powrocie
                        // z podfolderu albo archiwum lista stoi tam, gdzie ją zostawiono.
                        // Stan żyje poza samą listą, bo ta znika na czas wczytywania.
                        val paneId = tabs.firstOrNull { it.active }?.id.orEmpty()
                        // Ostatnio widziane pliki każdego folderu — warstwa pod bieżącym arkuszem.
                        val layerCache = remember {
                            // Najwyżej 20 ostatnich folderów — duże foldery to tysiące wpisów.
                            object : LinkedHashMap<String, List<FileItem>>(16, .75f, true) {
                                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<FileItem>>) = size > 20
                            }
                        }
                        LaunchedEffect(currentDir, visibleFiles) { layerCache[currentDir.toString()] = visibleFiles }
                        val depthLayer = remember(currentDir, visibleFiles, tab) {
                            DepthLayer(currentDir, visibleFiles, if (tab == Tab.FILES) vm.navDepth() else 0, vm.backTarget())
                        }
                        DepthStack(
                            layer = depthLayer,
                            behind = { parent -> parent?.let { layerCache[it.toString()] } },
                            vm = vm,
                            onBack = { vm.goBack() },
                            modifier = fileAreaModifier
                        ) { layer ->
                        val layerDir = layer.dir
                        val layerFiles = layer.files
                        val savedScroll = remember(paneId, layerDir) { vm.scrollPosition(paneId, layerDir) }
                        val listState = remember(paneId, layerDir) { LazyListState(savedScroll.first, savedScroll.second) }
                        val gridState = remember(paneId, layerDir) { LazyGridState(savedScroll.first, savedScroll.second) }
                        // Wartości z chwili kompozycji — w onDispose folder byłby już nowym folderem.
                        val shownDir = layerDir
                        val shownMode = viewMode
                        DisposableEffect(paneId, shownDir, shownMode) {
                            onDispose {
                                val state = if (shownMode == ViewMode.LIST) listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
                                else gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset
                                vm.saveScrollPosition(paneId, shownDir, state.first, state.second)
                            }
                        }

                        Box(Modifier.fillMaxSize()) {
                            if (layerFiles.isEmpty()) {
                                EmptyFolder(filtered = query.isNotBlank() || category != FileCategory.ALL, tab = tab)
                            } else if (viewMode == ViewMode.LIST) {
                                LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 88.dp)) {
                                    items(layerFiles, key = { it.uri.toString() }) { item ->
                                        FileRow(
                                            item = item,
                                            vm = vm,
                                            favorite = item.uri.toString() in favoriteUris,
                                            selected = item.uri.toString() in selectedUris,
                                            selectionMode = selectionMode,
                                            onClick = { onItemClick(item) },
                                            onLongClick = { selectedUris = toggleSelection(selectedUris, item) },
                                            onMenu = { selectedUris = setOf(item.uri.toString()) },
                                            interaction = Modifier.orbitGestures(item, orbit, selectionMode, { onItemClick(item) }, { selectedUris = toggleSelection(selectedUris, item) }, onOrbit)
                                        )
                                    }
                                }
                            } else {
                                LazyVerticalGrid(
                                    state = gridState,
                                    columns = GridCells.Fixed(gridColumns),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                    contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 88.dp)
                                ) {
                                    gridItems(layerFiles, key = { it.uri.toString() }) { item ->
                                        FileTile(
                                            item = item,
                                            vm = vm,
                                            favorite = item.uri.toString() in favoriteUris,
                                            selected = item.uri.toString() in selectedUris,
                                            selectionMode = selectionMode,
                                            onClick = { onItemClick(item) },
                                            onLongClick = { selectedUris = toggleSelection(selectedUris, item) },
                                            interaction = Modifier.orbitGestures(item, orbit, selectionMode, { onItemClick(item) }, { selectedUris = toggleSelection(selectedUris, item) }, onOrbit)
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
                            Text(stringResource(R.string.common_processing), fontWeight = FontWeight.Medium)
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
                    when {
                        item.isDirectory -> vm.openDirectoryInSecondary(item)
                        ArchiveFormats.canBrowse(item.name) -> vm.openArchive(item, inSecondary = true)
                        else -> preview = item
                    }
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

    peekItem?.let { item ->
        QuickPeek(
            item = item,
            vm = vm,
            actions = PeekActions(
                open = { target ->
                    peekItem = null
                    openItem(vm, target, onOpenFile = { preview = it }, onInstallApk = { apkToInstall = it })
                    if (target.isDirectory || ArchiveFormats.canBrowse(target.name)) { tab = Tab.FILES; query = ""; category = FileCategory.ALL }
                },
                share = { target -> appScope.launch { shareItems(context, listOf(target), vm) } },
                shelf = { target -> vm.addToShelf(listOf(target)) },
                rename = { target -> renameItem = target },
                delete = { target -> deleteItems = listOf(target) }
            ),
            onDismiss = { peekItem = null }
        )
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
                        if (favorite) stringResource(R.string.fav_remove) else stringResource(R.string.fav_add),
                        if (favorite) Icons.Default.Star else Icons.Default.StarBorder
                    ) { vm.toggleFavorite(item) },
                    ViewerAction(stringResource(R.string.common_share), Icons.Default.Share) {
                        appScope.launch { shareItems(context, listOf(item), vm) }
                    },
                    ViewerAction(stringResource(R.string.open_in_other_app), Icons.Default.OpenInNew) {
                        appScope.launch { openExternal(context, item, vm) }
                    },
                    ViewerAction(stringResource(R.string.common_properties), Icons.Default.Info) { preview = null; details = item }
                )
            )
            return@let
        }
        // Film z telefonu: od razu odtwarzacz na całym ekranie, a pozostałe filmy
        // z widocznej listy są kolejką. Filmy z serwerów — na razie jak dotąd.
        if (item.mimeType.startsWith("video/") && (item.uri.scheme == "file" || item.uri.scheme == "content")) {
            val videos = visibleFiles.filter { it.mimeType.startsWith("video/") && (it.uri.scheme == "file" || it.uri.scheme == "content") }
                .ifEmpty { listOf(item) }
            val clips = videos.map { pl.arma.viewer.video.VideoClip(it.uri, it.name, "${it.uri}|${it.size}") }
            pl.arma.viewer.video.FullscreenVideoPlayer(
                playlist = clips,
                startIndex = videos.indexOfFirst { it.uri == item.uri }.coerceAtLeast(0),
                onDismiss = { preview = null },
                onCurrentChanged = { index -> videos.getOrNull(index)?.let(vm::addRecent) }
            )
            return@let
        }
        // Obraz w poziomie też od razu na całym ekranie, narzędzia na nim.
        if (landscape && item.mimeType.startsWith("image/")) {
            val images = visibleFiles.filter { it.mimeType.startsWith("image/") }
            LandscapeImageViewer(
                item = item,
                vm = vm,
                siblings = images,
                onNavigate = { preview = it; vm.addRecent(it) },
                onDismiss = { preview = null }
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
            onNavigate = { preview = it; vm.addRecent(it) },
            onDismiss = { preview = null },
            onFavorite = { vm.toggleFavorite(item) },
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
            title = { Text(stringResource(R.string.err_operation_title)) },
            text = { Text(it) }
        )
    }

    when (createDialog) {
        "choose" -> ChoiceDialog(
            onDismiss = { createDialog = null },
            onFolder = { createDialog = "folder" },
            onFile = { createDialog = "file" }
        )
        "folder" -> NameDialog(stringResource(R.string.new_folder), stringResource(R.string.folder_name), onDismiss = { createDialog = null }) {
            vm.createFolder(it); createDialog = null
        }
        "file" -> NameDialog(stringResource(R.string.new_file), stringResource(R.string.file_name_hint), onDismiss = { createDialog = null }) {
            vm.createFile(it); createDialog = null
        }
    }

    renameItem?.let { item ->
        NameDialog(stringResource(R.string.common_rename), item.name, item.name, onDismiss = { renameItem = null }) {
            vm.rename(item, it); renameItem = null
        }
    }

    if (deleteItems.isNotEmpty()) {
        val itemsToDelete = deleteItems
        AlertDialog(
            onDismissRequest = { deleteItems = emptyList() },
            icon = { Icon(Icons.Default.DeleteOutline, null) },
            title = { Text(if (itemsToDelete.size == 1) stringResource(R.string.delete_one_title, itemsToDelete.first().name) else pluralStringResource(R.plurals.delete_n_title, itemsToDelete.size, itemsToDelete.size)) },
            text = {
                Text(stringResource(R.string.delete_text))
            },
            confirmButton = {
                TextButton(onClick = { vm.delete(itemsToDelete); deleteItems = emptyList() }) { Text(stringResource(R.string.delete_to_trash)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { deleteItems = emptyList() }) { Text(stringResource(R.string.common_cancel)) }
                    TextButton(onClick = {
                        vm.deleteForever(itemsToDelete); deleteItems = emptyList()
                    }) { Text(stringResource(R.string.delete_forever)) }
                }
            }
        )
    }

    if (archiveItems.isNotEmpty()) {
        val itemsToArchive = archiveItems
        CreateArchiveDialog(
            initial = if (itemsToArchive.size == 1) {
                itemsToArchive.first().let { if (it.isDirectory) it.name else it.name.substringBeforeLast('.', it.name) }
            } else stringResource(R.string.zip_default_name),
            onDismiss = { archiveItems = emptyList() }
        ) { name, format, password ->
            vm.createArchive(itemsToArchive, name, format, password)
            archiveItems = emptyList()
            selectedUris = emptySet()
        }
    }

    val passwordRequest by vm.passwordRequest.collectAsStateWithLifecycle()
    passwordRequest?.let { request ->
        ArchivePasswordDialog(
            name = request.name,
            wrong = request.wrong,
            onDismiss = vm::dismissArchivePassword,
            onConfirm = vm::submitArchivePassword
        )
    }

    val identityChange by vm.identityChange.collectAsStateWithLifecycle()
    identityChange?.let { change -> IdentityChangeDialog(change, onTrust = vm::trustIdentity, onReject = vm::rejectIdentity) }

    conflictsOf?.let { id ->
        val entry = offlineEntries.firstOrNull { it.id == id }
        if (entry == null || entry.conflicts.isEmpty()) conflictsOf = null
        else ConflictsDialog(entry, onChoice = { conflict, choice -> vm.resolveOfflineConflict(entry, conflict, choice) }, onDismiss = { conflictsOf = null })
    }

    val cancelRequest by vm.cancelRequest.collectAsStateWithLifecycle()
    if (cancelRequest != null) {
        AlertDialog(
            onDismissRequest = vm::dismissCancel,
            icon = { Icon(Icons.Default.Unarchive, null) },
            title = { Text(stringResource(R.string.extract_cancel_title)) },
            text = { Text(stringResource(R.string.extract_cancel_text)) },
            confirmButton = { TextButton(onClick = vm::confirmCancel) { Text(stringResource(R.string.extract_cancel_confirm)) } },
            dismissButton = { TextButton(onClick = vm::dismissCancel) { Text(stringResource(R.string.extract_cancel_continue)) } }
        )
    }

    // Instalator jest osobnym procesem, wiec plik musi najpierw trafic do
    // naszego cache'u i zostac wystawiony przez FileProvidera -- dotyczy to
    // takze plikow z FTP, SMB i chmury.
    apkToInstall?.let { apk ->
        LaunchedEffect(apk.uri) {
            val problem = runCatching { installApk(context, vm.materializeUri(apk)) }
                .getOrElse { it.message ?: context.getString(R.string.err_apk_prepare) }
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
            onDismiss = { editedAccount = null; vm.clearAccountTest() }
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
            onOpen = { item -> showAnalysis = false; analysisSection = 0; vm.revealParent(item) },
            onPreview = { item ->
                // Podgląd nad arkuszem by go nie przykrył — arkusz wraca po zamknięciu podglądu.
                showAnalysis = false
                reopenAnalysis = true
                preview = item
            },
            onDeleteDuplicates = { deleteItems = it },
            location = vm::locationLabel,
            vm = vm,
            marked = duplicateMarks,
            onMarked = { duplicateMarks = it },
            initialSection = analysisSection,
            onSection = { analysisSection = it },
            onDismiss = { showAnalysis = false; analysisSection = 0 }
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
    onDisconnectSource: (StorageSource) -> Unit,
    onDisableSource: (StorageSource) -> Unit,
    onManageSources: () -> Unit,
    offlineEntries: List<OfflineStore.Entry>,
    offlineProgress: Map<String, OfflineStore.Progress>,
    onOpenOffline: (OfflineStore.Entry) -> Unit,
    onRemoveOffline: (OfflineStore.Entry) -> Unit,
    onConflicts: (OfflineStore.Entry) -> Unit,
    onSyncOffline: () -> Unit,
    onGrantAccess: () -> Unit,
    onAddFolder: () -> Unit,
    trashCount: Int,
    onOpenTrash: () -> Unit,
    indexProgress: pl.arma.files.index.IndexProgress,
    indexedCount: Int,
    hasRemote: Boolean,
    searchRemote: Boolean,
    onSearchRemote: (Boolean) -> Unit,
    searchingIn: String?,
    searchLocation: (FileItem) -> String,
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
                            Text(stringResource(R.string.access_full_title), fontWeight = FontWeight.SemiBold)
                            Text(
                                stringResource(R.string.access_full_text),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Button(onClick = onGrantAccess) { Text(stringResource(R.string.common_turn_on)) }
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionTitle(stringResource(R.string.home_pinned), Modifier.weight(1f))
                // Same ikony — trzy akcje z podpisami nie mieściły się obok
                // nagłówka i „Kosz" był ucinany przy krawędzi.
                IconButton(onClick = onAnalyze) {
                    Icon(Icons.Default.PieChart, stringResource(R.string.analysis_title), tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = onOpenHistory) {
                    Icon(Icons.Default.History, stringResource(R.string.history_title), tint = MaterialTheme.colorScheme.primary)
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
                onRefresh = onBuildIndex,
                hasRemote = hasRemote,
                searchRemote = searchRemote,
                onSearchRemote = onSearchRemote,
                searchingIn = searchingIn
            )
        }

        // Wyniki pojawiają się pod paskiem, w miejscu pozostałych sekcji.
        // Zamiana całego ekranu zabierałaby pole, w którym trwa pisanie.
        if (searchResults.isNotEmpty()) {
            item {
                Text(
                    stringResource(R.string.search_found, searchResults.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                )
            }
            items(searchResults, key = { it.uri.toString() }) { item ->
                SearchResultRow(
                    item = item,
                    location = searchLocation(item),
                    onOpen = { onOpenResult(item) },
                    onReveal = { onRevealResult(item) }
                )
            }
            return@LazyColumn
        }

        item {
            SectionTitle(stringResource(R.string.home_this_computer), Modifier.padding(horizontal = 16.dp).padding(top = 6.dp))
        }
        items(storages, key = { it.id }) { storage ->
            DeviceStorageCard(storage = storage, usage = storageUsage, onClick = { onOpenStorage(storage) })
        }

        if (connectedSources.isNotEmpty()) {
            item {
                SectionTitle(stringResource(R.string.home_connected), Modifier.padding(horizontal = 16.dp).padding(top = 6.dp))
            }
            // Klucz z konta, nie z typu: dwa połączenia SMB (dom, firma) dawały
            // ten sam klucz i ekran główny wywracał aplikację przy starcie.
            items(connectedSources, key = { "source_${it.type.name}_${it.accountId ?: it.rootUri}" }) { source ->
                ConnectedSourceCard(
                    source = source,
                    onClick = { onOpenSource(source) },
                    onDisconnect = { onDisconnectSource(source) },
                    onDisable = { onDisableSource(source) },
                    onManage = onManageSources
                )
            }
        }

        if (offlineEntries.isNotEmpty()) {
            item {
                Row(
                    Modifier.padding(start = 16.dp, end = 8.dp).padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SectionTitle(stringResource(R.string.offline_title), Modifier.weight(1f))
                    TextButton(onClick = onSyncOffline, enabled = offlineProgress.isEmpty()) {
                        Icon(Icons.Default.Sync, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.offline_sync))
                    }
                }
            }
            items(offlineEntries, key = { "offline_${it.id}" }) { entry ->
                OfflineEntryRow(
                    entry, offlineProgress[entry.id],
                    onOpen = { onOpenOffline(entry) },
                    onRemove = { onRemoveOffline(entry) },
                    onConflicts = { onConflicts(entry) }
                )
            }
        }
    }
}

@Composable
private fun OfflineEntryRow(
    entry: OfflineStore.Entry,
    progress: OfflineStore.Progress?,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
    onConflicts: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    Surface(
        onClick = onOpen,
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (entry.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                null,
                tint = if (entry.isDirectory) LocalFileTypeColors.current.folder else MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val status = when {
                    progress != null -> stringResource(R.string.offline_syncing, formatSize(progress.bytes))
                    entry.error != null -> stringResource(R.string.offline_failed, entry.error)
                    entry.lastSync > 0 -> stringResource(R.string.offline_synced, formatDate(entry.lastSync), formatSize(entry.bytes))
                    else -> stringResource(R.string.offline_never)
                }
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (entry.error != null && progress == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (progress != null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                if (entry.conflicts.isNotEmpty()) {
                    TextButton(onClick = onConflicts, contentPadding = PaddingValues(horizontal = 0.dp)) {
                        Icon(Icons.Default.Warning, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            pluralStringResource(R.plurals.offline_conflicts, entry.conflicts.size, entry.conflicts.size),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.common_options)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.offline_remove)) },
                        leadingIcon = { Icon(Icons.Default.CloudOff, null) },
                        onClick = { menu = false; onRemove() }
                    )
                }
            }
        }
    }
}

/** Rozbieżne wersje plików offline: obie wersje i wybór, którą zachować. */
@Composable
private fun ConflictsDialog(
    entry: OfflineStore.Entry,
    onChoice: (OfflineStore.Conflict, OfflineStore.Choice) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Warning, null) },
        title = { Text(stringResource(R.string.offline_conflicts_title)) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                items(entry.conflicts, key = { it.path }) { conflict ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(conflict.path, fontWeight = FontWeight.SemiBold)
                        val server = if (conflict.remoteDeleted) stringResource(R.string.offline_version_deleted)
                        else OfflineNotifier.describe(context, conflict.remoteSize, conflict.remoteModified)
                        Text(stringResource(R.string.offline_server_version, server), style = MaterialTheme.typography.bodySmall)
                        Text(
                            stringResource(R.string.offline_phone_version, OfflineNotifier.describe(context, conflict.localSize, conflict.localModified)),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = { onChoice(conflict, OfflineStore.Choice.REMOTE) }, contentPadding = PaddingValues(horizontal = 10.dp)) {
                                Text(stringResource(R.string.offline_keep_remote))
                            }
                            OutlinedButton(onClick = { onChoice(conflict, OfflineStore.Choice.LOCAL) }, contentPadding = PaddingValues(horizontal = 10.dp)) {
                                Text(stringResource(R.string.offline_keep_local))
                            }
                            OutlinedButton(onClick = { onChoice(conflict, OfflineStore.Choice.BOTH) }, contentPadding = PaddingValues(horizontal = 10.dp)) {
                                Text(stringResource(R.string.offline_keep_both))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } }
    )
}

/** Serwer przedstawił się innym kluczem albo certyfikatem — decyzja użytkownika. */
@Composable
private fun IdentityChangeDialog(change: pl.arma.files.net.ServerIdentityChange, onTrust: () -> Unit, onReject: () -> Unit) {
    val ssh = change.kind == pl.arma.files.net.ServerIdentityChange.Kind.SSH_KEY
    AlertDialog(
        onDismissRequest = onReject,
        icon = { Icon(Icons.Default.GppMaybe, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(stringResource(if (ssh) R.string.identity_title_ssh else R.string.identity_title_tls)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.identity_text, change.server))
                Text(stringResource(R.string.identity_old), style = MaterialTheme.typography.labelLarge)
                Text(change.oldFingerprint ?: "—", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.identity_new), style = MaterialTheme.typography.labelLarge)
                Text(change.newFingerprint, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onTrust) { Text(stringResource(R.string.identity_trust)) } },
        dismissButton = { TextButton(onClick = onReject) { Text(stringResource(R.string.identity_reject)) } }
    )
}

/** Lista z kopii offline, bo serwer nie odpowiada. */
@Composable
private fun OfflineBanner(since: Long) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp).fillMaxWidth()
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.CloudOff, null, Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.offline_banner, if (since > 0) formatDate(since) else "—"),
                style = MaterialTheme.typography.bodySmall
            )
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
private fun SearchResultRow(item: FileItem, location: String, onOpen: () -> Unit, onReveal: () -> Unit) {
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
                    location,
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
                Icon(Icons.Default.FolderOpen, stringResource(R.string.show_in_folder), Modifier.size(20.dp))
            }
        }
    }
}

private fun categoryShortLabel(category: pl.arma.files.index.IndexCategory): String = when (category) {
    pl.arma.files.index.IndexCategory.IMAGE -> text(R.string.category_images)
    pl.arma.files.index.IndexCategory.VIDEO -> text(R.string.category_videos)
    pl.arma.files.index.IndexCategory.AUDIO -> text(R.string.category_audio)
    pl.arma.files.index.IndexCategory.DOCUMENT -> text(R.string.category_documents)
    pl.arma.files.index.IndexCategory.ARCHIVE -> text(R.string.category_archives)
    pl.arma.files.index.IndexCategory.APK -> "APK"
    pl.arma.files.index.IndexCategory.OTHER -> text(R.string.category_other)
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
                    when {
                        storage.usb -> Icons.Default.Usb
                        storage.removable -> Icons.Default.SdStorage
                        else -> Icons.Default.Smartphone
                    },
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
                        // Nazwę nośnika podaje system; chip mówi, czym jest.
                        AssistChip(
                            onClick = {},
                            label = { Text(stringResource(if (storage.usb) R.string.storage_usb else R.string.storage_removable)) },
                            leadingIcon = { Icon(if (storage.usb) Icons.Default.Usb else Icons.Default.Memory, null, Modifier.size(15.dp)) }
                        )
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
                        stringResource(R.string.storage_usage, formatSize(storage.totalBytes - storage.freeBytes), formatSize(storage.freeBytes), formatSize(storage.totalBytes))
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
private fun ConnectedSourceCard(
    source: StorageSource,
    onClick: () -> Unit,
    onDisconnect: () -> Unit,
    onDisable: () -> Unit,
    onManage: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
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
                Text(source.subtitle.ifBlank { stringResource(R.string.source_attached) }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // Folder SAF (także Dropbox i inne chmury) się odłącza; konto
            // sieciowe wyłącza albo edytuje w ustawieniach, gdzie da się je też usunąć.
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.common_options)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (source.accountId == null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.source_disconnect)) },
                            leadingIcon = { Icon(Icons.Default.LinkOff, null) },
                            onClick = { menu = false; onDisconnect() }
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.source_disable)) },
                            leadingIcon = { Icon(Icons.Default.VisibilityOff, null) },
                            onClick = { menu = false; onDisable() }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.source_manage)) },
                        leadingIcon = { Icon(Icons.Default.Settings, null) },
                        onClick = { menu = false; onManage() }
                    )
                }
            }
        }
    }
}


/**
 * Rozbija bieżącą ścieżkę na klikalne człony, licząc od korzenia źródła.
 * Poza korzeniem nie schodzimy — użytkownik nie ma tam dostępu.
 */
private fun breadcrumbs(
    root: Uri?,
    current: Uri?,
    rootLabel: (Uri) -> String?,
    archiveSource: (Uri) -> Uri? = { null }
): List<Pair<String, Uri>> {
    if (current == null) return emptyList()
    // W archiwum: ścieżka do pliku archiwum, potem jego wnętrze. Człon
    // z nazwą archiwum prowadzi do jego korzenia, a nie do pliku.
    if (ArchiveRepository.isArchive(current)) {
        val archiveRoot = current.buildUpon().path("/").build()
        val source = archiveSource(current)
        val outer = source?.let { breadcrumbs(root, it, rootLabel, archiveSource) }
            ?.dropLast(1)
            ?.takeIf { source.scheme == "file" || ArchiveRepository.isArchive(source) || RemoteRepository.isRemoteScheme(source.scheme) }
            .orEmpty()
        val crumbs = outer.toMutableList()
        crumbs += (rootLabel(archiveRoot) ?: folderLabel(archiveRoot)) to archiveRoot
        var path = ""
        ArchiveRepository.innerPath(current).split('/').filter { it.isNotBlank() }.forEach { segment ->
            path = "$path/$segment"
            crumbs += segment to current.buildUpon().path(path).build()
        }
        return crumbs
    }
    val rootPath = root?.path.orEmpty().trimEnd('/')
    val currentPath = current.path.orEmpty().trimEnd('/')
    val rootName = root?.let(rootLabel) ?: folderLabel(root)

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
    onPickRoot: () -> Unit,
    files: List<FileItem> = emptyList()
) {
    Column(Modifier.fillMaxWidth()) {
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
                                    globalSearch -> stringResource(R.string.search_all_storage)
                                    tab == Tab.FILES -> stringResource(R.string.search_this_folder)
                                    else -> stringResource(R.string.search_files)
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
                            Icon(Icons.Default.Close, stringResource(R.string.common_clear), Modifier.size(18.dp))
                        }
                    }
                }
            }
            IconButton(onClick = { onGlobalSearch(!globalSearch) }) {
                Icon(
                    if (globalSearch) Icons.Default.TravelExplore else Icons.Default.ManageSearch,
                    if (globalSearch) stringResource(R.string.search_here_only) else stringResource(R.string.search_all_storage),
                    tint = if (globalSearch) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            TextButton(onClick = { onSearchOpen(false) }) { Text(stringResource(R.string.common_cancel)) }
            return@Row
        }

        // Wstecz do folderu nadrzędnego — tytuł i ścieżka stoją pod paskiem.
        if (tab == Tab.FILES && crumbs.size > 1) {
            IconButton(onClick = { onCrumb(crumbs[crumbs.lastIndex - 1].second) }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.panes_back), tint = MaterialTheme.colorScheme.onSurface)
            }
        }
        Spacer(Modifier.weight(1f))

        val iconTint = MaterialTheme.colorScheme.onSurfaceVariant
        IconButton(onClick = { onSearchOpen(true) }) { Icon(Icons.Default.Search, stringResource(R.string.common_search), tint = iconTint) }
        var sortMenu by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { sortMenu = true }) { Icon(Icons.Default.SwapVert, stringResource(R.string.sort), tint = iconTint) }
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
            Icon(if (view == ViewMode.LIST) Icons.Default.GridView else Icons.Default.ViewList, stringResource(R.string.change_view), tint = iconTint)
        }
        var more by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { more = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.common_more), tint = iconTint) }
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
                        text = { Text(stringResource(R.string.common_refresh)) },
                        leadingIcon = { Icon(Icons.Default.Refresh, null) },
                        onClick = { more = false; onRefresh() }
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.open_folder_saf)) },
                    leadingIcon = { Icon(Icons.Default.FolderOpen, null) },
                    onClick = { more = false; onPickRoot() }
                )
            }
        }
    }
    if (!searchOpen) HeaderTitle(tab, crumbs, onCrumb, files)
    }
}

/**
 * Tytuł jak w makiecie: ścieżka drobnym pismem maszynowym (każdy człon
 * prowadzi do swojego folderu) i duża nazwa bieżącego folderu; przy
 * folderze ze zdjęciami — kropki jego kolorów.
 */
@Composable
private fun HeaderTitle(tab: Tab, crumbs: List<Pair<String, Uri>>, onCrumb: (Uri) -> Unit, files: List<FileItem>) {
    val style = LocalArmaStyle.current
    val title = when (tab) {
        Tab.FILES -> crumbs.lastOrNull()?.first.orEmpty()
        Tab.RECENT -> stringResource(R.string.recent_title)
        else -> stringResource(R.string.nav_favorites)
    }
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, bottom = 6.dp)) {
        if (tab == Tab.FILES && crumbs.size > 1) {
            val scroll = rememberScrollState()
            LaunchedEffect(crumbs.size) { scroll.animateScrollTo(scroll.maxValue) }
            Row(Modifier.horizontalScroll(scroll), verticalAlignment = Alignment.CenterVertically) {
                crumbs.dropLast(1).forEachIndexed { index, (name, uri) ->
                    if (index > 0) Text(" / ", style = MonoCaption, color = style.muted)
                    Text(
                        name.uppercase(),
                        style = MonoCaption,
                        color = style.muted,
                        maxLines = 1,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { onCrumb(uri) }.padding(vertical = 4.dp)
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                fontSize = 34.sp,
                lineHeight = 38.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-1).sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (style.folderColors && tab == Tab.FILES) FolderColorDots(files, Modifier.padding(start = 12.dp))
        }
    }
}

private val MonoCaption = androidx.compose.ui.text.TextStyle(fontFamily = PlexMono, fontSize = 11.sp, letterSpacing = .4.sp)

/** Do czterech kolorów z miniatur zdjęć folderu — tych, które już są w pamięci. */
@Composable
private fun FolderColorDots(files: List<FileItem>, modifier: Modifier) {
    var colors by remember(files) { mutableStateOf<List<Color>>(emptyList()) }
    LaunchedEffect(files) {
        val media = files.filter { it.mimeType.startsWith("image/") || it.mimeType.startsWith("video/") }.take(16)
        if (media.isEmpty()) return@LaunchedEffect
        // Miniatury dochodzą w tle — kilka podejść, aż będzie z czego liczyć.
        repeat(4) {
            kotlinx.coroutines.delay(700)
            val found = withContext(Dispatchers.Default) {
                media.mapNotNull { item ->
                    ThumbnailCache.get(ThumbnailCache.key(item.uri.toString(), item.lastModified, item.size))?.let { bitmap ->
                        val one = Bitmap.createScaledBitmap(bitmap, 1, 1, true)
                        Color(one.getPixel(0, 0))
                    }
                }
            }
            val distinct = mutableListOf<Color>()
            for (c in found) {
                if (distinct.none { d -> kotlin.math.abs(d.red - c.red) + kotlin.math.abs(d.green - c.green) + kotlin.math.abs(d.blue - c.blue) < .25f }) distinct += c
                if (distinct.size == 4) break
            }
            colors = distinct
            if (distinct.size >= 3) return@LaunchedEffect
        }
    }
    if (colors.isEmpty()) return
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        colors.forEach { c -> Box(Modifier.size(14.dp).clip(CircleShape).background(c)) }
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
    val style = LocalArmaStyle.current
    // Zakładki z podkreśleniem zamiast pigułek — jak w makiecie „Wspólny język”.
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 2.dp)
    ) {
        items(shown, key = { it.name }) { category ->
            val active = selected == category
            Column(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .selectable(selected = active, role = androidx.compose.ui.semantics.Role.Tab) { onSelected(category) }
                    .padding(top = 6.dp)
            ) {
                Text(
                    "${categoryLabel(category)} ${counts[category] ?: 0}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (active) style.text else style.muted,
                    maxLines = 1
                )
                Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(2.dp).background(if (active) style.accent else Color.Transparent))
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
                Text(if (payload.cut) stringResource(R.string.clip_to_move) else stringResource(R.string.clip_to_copy), fontWeight = FontWeight.SemiBold)
                Text(pluralStringResource(R.plurals.n_items, payload.items.size, payload.items.size), style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) }
            Button(onClick = onPaste) { Text(stringResource(R.string.common_paste)) }
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
    onMenu: () -> Unit,
    interaction: Modifier? = null
) {
    // Płaski wiersz bez karty: obramowanie każdego pliku zabierało miejsce
    // i męczyło wzrok, a podział niosą teraz kolorowe ikony typów.
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .then(interaction ?: Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick))
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
        val offlineEntries by vm.offlineEntries.collectAsStateWithLifecycle()
        if (offlineEntries.any { it.source == item.uri.toString() }) {
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Default.OfflinePin, stringResource(R.string.offline_make), Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        }
        if (favorite) {
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Default.Star, null, Modifier.size(16.dp), tint = LocalFileTypeColors.current.folder)
        }
        if (selectionMode) {
            Checkbox(checked = selected, onCheckedChange = { onClick() })
        } else {
            IconButton(onClick = onMenu) {
                Icon(Icons.Default.MoreVert, stringResource(R.string.common_options), Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
    onLongClick: () -> Unit,
    interaction: Modifier? = null
) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(14.dp),
        border = if (selected) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .5f)) else null,
        modifier = Modifier.aspectRatio(.9f).then(interaction ?: Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick))
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
internal fun FileVisual(
    item: FileItem,
    modifier: Modifier,
    thumbnail: Boolean,
    vm: FileManagerViewModel? = null,
    // W małej ikonie wiersza dopasowany obraz byłby paskiem kilku pikseli;
    // pełny kadr zostaje w siatce i podglądzie.
    crop: Boolean = false
) {
    val tint = fileTint(item)
    // Żywe karty folderów i miniatury mówiące więcej niż ikona — tylko dla
    // plików w telefonie; zdalne i z chmury SAF zostają przy ikonie.
    if (thumbnail && vm != null && LocalFiles.isLocal(item.uri)) {
        val ext = item.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val icon: @Composable () -> Unit = { FileIconTile(item, Modifier.fillMaxSize()) }
        when {
            item.isDirectory -> { FolderLiveCard(item, vm, modifier, compact = crop); return }
            isPdf(item) && item.size in 1..PDF_THUMB_LIMIT -> { PdfPageThumb(item, modifier, icon); return }
            item.mimeType.startsWith("audio/") -> { AudioArtThumb(item, modifier, icon); return }
            !crop && ext in Syntax.CODE_EXTENSIONS && item.size in 1..CODE_THUMB_LIMIT -> { CodeThumb(item, modifier); return }
        }
    }
    val rasterImage = thumbnail && item.mimeType.startsWith("image/") && !item.name.endsWith(".svg", true) && !item.mimeType.contains("svg")
    val svgImage = thumbnail && item.mimeType.startsWith("image/") && (item.name.endsWith(".svg", true) || item.mimeType.contains("svg"))
    val videoThumbnails = LocalContext.current.armaApp.videoThumbnails
    val video = thumbnail && vm != null && videoThumbnails.supports(item)

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
            if ((vm.isRemote(item.uri) || vm.isArchive(item.uri)) && item.size > REMOTE_THUMBNAIL_LIMIT) {
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
    } else if (video) {
        val key = ThumbnailCache.key(item.uri.toString(), item.lastModified, item.size)
        var bitmap by remember(item.uri, item.lastModified) { mutableStateOf(ThumbnailCache.get(key)) }
        var failed by remember(item.uri, item.lastModified) { mutableStateOf(false) }
        LaunchedEffect(item.uri, item.lastModified) {
            if (bitmap != null) return@LaunchedEffect
            bitmap = videoThumbnails.load(item)
            failed = bitmap == null
        }
        Surface(modifier = modifier, shape = RoundedCornerShape(10.dp), color = if (bitmap == null) tint.copy(alpha = .16f) else MaterialTheme.colorScheme.surfaceContainerHigh) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val shown = bitmap
                if (shown != null) {
                    Image(
                        bitmap = shown.asImageBitmap(),
                        contentDescription = item.name,
                        modifier = if (crop) Modifier.fillMaxSize() else Modifier.fillMaxSize().padding(4.dp),
                        contentScale = if (crop) ContentScale.Crop else ContentScale.Fit
                    )
                    // Znak odtwarzania odróżnia film od zdjęcia.
                    Surface(shape = CircleShape, color = Color.Black.copy(alpha = .45f), modifier = Modifier.fillMaxSize(.42f).widthIn(max = 36.dp).aspectRatio(1f)) {
                        Icon(Icons.Default.PlayArrow, null, Modifier.padding(2.dp), tint = Color.White)
                    }
                } else {
                    // Do czasu zdekodowania (albo gdy się nie da) — zwykła ikona filmu.
                    Icon(Icons.Default.Movie, null, Modifier.fillMaxSize(.55f), tint = tint)
                }
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
            FileIconTile(item, modifier)
        }
    }
}

/** Zwykła ikona typu pliku na kolorowym tle. */
@Composable
private fun FileIconTile(item: FileItem, modifier: Modifier) {
    val tint = fileTint(item)
    Surface(modifier = modifier, shape = RoundedCornerShape(10.dp), color = tint.copy(alpha = .16f)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(fileIcon(item), null, Modifier.fillMaxSize(.55f), tint = tint)
        }
    }
}

private const val PDF_THUMB_LIMIT = 60L * 1024 * 1024
private const val CODE_THUMB_LIMIT = 5L * 1024 * 1024

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

internal fun fileIcon(item: FileItem): ImageVector {
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
    isZip(item) || ArchiveFormats.canBrowse(item.name)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MultiSelectionTopBar(
    count: Int,
    canRename: Boolean,
    canExtract: Boolean,
    canBrowse: Boolean,
    canExtractToOther: Boolean,
    readOnly: Boolean,
    canOffline: Boolean,
    allOffline: Boolean,
    onOffline: (remove: Boolean) -> Unit,
    onBrowse: () -> Unit,
    onExtractToOther: () -> Unit,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onFavorite: () -> Unit,
    onShelf: () -> Unit,
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
        title = { Text(stringResource(R.string.selected_count, count), fontWeight = FontWeight.SemiBold) },
        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Default.Close, stringResource(R.string.viewer_close_app)) } },
        actions = {
            IconButton(onClick = onFavorite) { Icon(Icons.Default.StarBorder, stringResource(R.string.nav_favorites)) }
            // Szuflada zbiera zaznaczenie z wielu folderów — w odróżnieniu od kopiowania.
            IconButton(onClick = onShelf) { Icon(Icons.Default.Inbox, stringResource(R.string.shelf_add)) }
            IconButton(onClick = onCopy) { Icon(Icons.Default.ContentCopy, stringResource(R.string.common_copy)) }
            if (!readOnly) IconButton(onClick = onCut) { Icon(Icons.Default.ContentCut, stringResource(R.string.common_cut)) }
            Box {
                IconButton(onClick = { more = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.common_more)) }
                DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.select_all)) }, leadingIcon = { Icon(Icons.Default.SelectAll, null) }, onClick = { more = false; onSelectAll() })
                    if (!readOnly) DropdownMenuItem(text = { Text(stringResource(R.string.zip_pack)) }, leadingIcon = { Icon(Icons.Default.FolderZip, null) }, onClick = { more = false; onArchive() })
                    if (canOffline) DropdownMenuItem(
                        text = { Text(stringResource(if (allOffline) R.string.offline_remove else R.string.offline_make)) },
                        leadingIcon = { Icon(if (allOffline) Icons.Default.CloudOff else Icons.Default.OfflinePin, null) },
                        onClick = { more = false; onOffline(allOffline) }
                    )
                    if (canBrowse) DropdownMenuItem(text = { Text(stringResource(R.string.archive_browse)) }, leadingIcon = { Icon(Icons.Default.FolderOpen, null) }, onClick = { more = false; onBrowse() })
                    if (canExtract) DropdownMenuItem(text = { Text(stringResource(R.string.zip_extract_here)) }, leadingIcon = { Icon(Icons.Default.Unarchive, null) }, onClick = { more = false; onExtract() })
                    if (canExtract && canExtractToOther) DropdownMenuItem(text = { Text(stringResource(R.string.archive_extract_other_pane)) }, leadingIcon = { Icon(Icons.Default.Unarchive, null) }, onClick = { more = false; onExtractToOther() })
                    if (canRename && !readOnly) DropdownMenuItem(text = { Text(stringResource(R.string.common_rename)) }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { more = false; onRename() })
                    if (canRename) DropdownMenuItem(text = { Text(stringResource(R.string.common_properties)) }, leadingIcon = { Icon(Icons.Default.Info, null) }, onClick = { more = false; onDetails() })
                    if (canPin) DropdownMenuItem(text = { Text(stringResource(R.string.pin_to_home)) }, leadingIcon = { Icon(Icons.Default.PushPin, null) }, onClick = { more = false; onPin() })
                    if (canPin) DropdownMenuItem(text = { Text(stringResource(R.string.pinned_open_new_tab)) }, leadingIcon = { Icon(Icons.Default.Tab, null) }, onClick = { more = false; onOpenInNewTab() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.common_share)) }, leadingIcon = { Icon(Icons.Default.Share, null) }, onClick = { more = false; onShare() })
                    if (!readOnly) {
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text(stringResource(R.string.common_delete)) }, leadingIcon = { Icon(Icons.Default.DeleteOutline, null) }, onClick = { more = false; onDelete() })
                    }
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
                IconButton(onClick = onFavorite) { Icon(if (favorite) Icons.Default.Star else Icons.Default.StarBorder, stringResource(R.string.nav_favorites)) }
                IconButton(onClick = onDetails) { Icon(Icons.Default.Info, stringResource(R.string.common_properties)) }
                IconButton(onClick = onShare) { Icon(Icons.Default.Share, stringResource(R.string.common_share)) }
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
                            stringResource(R.string.image_position, position + 1, siblings.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (item.name.endsWith(".svg", true) || item.mimeType.contains("svg")) {
                        var svg by remember(item.uri, item.lastModified) { mutableStateOf<Uri?>(null) }
                        LaunchedEffect(item.uri, item.lastModified) { svg = runCatching { vm.prepareImageUri(item) }.getOrNull() }
                        SvgImageViewer(model = svg, name = item.name, modifier = Modifier.fillMaxWidth().weight(1f))
                    } else {
                        ImageViewer(
                            key = item.uri to item.lastModified,
                            name = item.name,
                            load = { vm.decodeImage(item, targetMaxSide = 4096) },
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            onNavigate = step,
                            canNavigate = siblings.size > 1,
                            positionLabel = if (position >= 0) "${position + 1} / ${siblings.size}" else "",
                            hasNeighbor = { delta -> position >= 0 && siblings.getOrNull(position + delta) != null }
                        )
                    }
                }
                // Arkusz (xlsx, CSV) jako tabela. CSV wciąż da się edytować jako tekst
                // przez „Otwórz w innej aplikacji” — tu tylko do oglądania.
                pl.arma.viewer.table.Spreadsheets.canOpen(item.name) -> {
                    pl.arma.viewer.table.SpreadsheetViewer(
                        name = item.name,
                        key = item.uri to item.lastModified,
                        load = { pl.arma.viewer.table.Spreadsheets.read(vm.prepareDocument(item), item.name) },
                        modifier = Modifier.fillMaxWidth().weight(1f)
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
                            Text(stringResource(R.string.save_changes))
                        }
                    } else {
                        pl.arma.viewer.text.TextViewer(
                            name = item.name,
                            key = item.uri to item.lastModified,
                            load = { vm.readDocument(item) },
                            modifier = Modifier.fillMaxWidth().weight(1f)
                        )
                        // Plik w archiwum można tylko czytać.
                        if (!vm.isArchive(item.uri)) FilledTonalButton(onClick = { editing = true }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Edit, null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.accounts_edit))
                        }
                    }
                }
                else -> {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            FileVisual(item, Modifier.size(92.dp), thumbnail = false)
                            Text(stringResource(R.string.unsupported_preview), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Button(onClick = onOpenExternal) {
                                Icon(Icons.Default.OpenInNew, null)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.common_open))
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
private fun LandscapeImageViewer(
    item: FileItem,
    vm: FileManagerViewModel,
    siblings: List<FileItem>,
    onNavigate: (FileItem) -> Unit,
    onDismiss: () -> Unit
) {
    val position = siblings.indexOfFirst { it.uri == item.uri }
    val step: (Int) -> Unit = { delta -> siblings.getOrNull(position + delta)?.let(onNavigate) }
    val label = if (siblings.size > 1 && position >= 0) "${item.name} · ${position + 1} / ${siblings.size}" else item.name
    if (item.name.endsWith(".svg", true) || item.mimeType.contains("svg")) {
        var uri by remember(item.uri) { mutableStateOf<Uri?>(null) }
        LaunchedEffect(item.uri) { uri = runCatching { vm.prepareImageUri(item) }.getOrNull() }
        uri?.let { FullscreenSvgViewer(model = it, name = item.name, onDismiss = onDismiss) }
        return
    }
    var bitmap by remember(item.uri, item.lastModified) { mutableStateOf<Bitmap?>(null) }
    var error by remember(item.uri, item.lastModified) { mutableStateOf<String?>(null) }
    LaunchedEffect(item.uri, item.lastModified) {
        error = null
        bitmap = runCatching { vm.decodeImage(item, targetMaxSide = 4096) }
            .onFailure { error = it.message ?: text(R.string.err_image_open) }
            .getOrNull()
    }
    FullscreenImageViewer(
        image = bitmap,
        error = error,
        name = item.name,
        onDismiss = onDismiss,
        onNavigate = step,
        canNavigate = siblings.size > 1 && position >= 0,
        position = label,
        hasNeighbor = { delta -> position >= 0 && siblings.getOrNull(position + delta) != null }
    )
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
                .onFailure { checksumError = it.message ?: text(R.string.err_checksum) }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FileVisual(item, Modifier.size(58.dp), thumbnail = true, vm = vm)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(if (item.isDirectory) stringResource(R.string.detail_folder) else formatSize(item.size), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider()
            DetailRow(stringResource(R.string.detail_type), item.mimeType)
            DetailRow(stringResource(R.string.detail_modified), formatDate(item.lastModified))
            if (!item.isDirectory) DetailRow(stringResource(R.string.detail_size), "${formatSize(item.size)} (${String.format(Locale.getDefault(), "%,d", item.size)} B)")
            DetailRow("URI", item.uri.toString(), monospace = true)
            if (!item.isDirectory) {
                DetailRow("SHA-256", checksum ?: checksumError ?: stringResource(R.string.detail_computing), monospace = true)
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
        title = { Text(stringResource(R.string.create_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(onClick = onFolder, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.CreateNewFolder, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.detail_folder))
                }
                FilledTonalButton(onClick = onFile, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.NoteAdd, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.create_file))
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
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CreateArchiveDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String, ArchiveFormat, String?) -> Unit
) {
    var name by remember(initial) { mutableStateOf(initial) }
    var format by rememberSaveable { mutableStateOf(ArchiveFormat.ZIP) }
    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.FolderZip, null) },
        title = { Text(stringResource(R.string.zip_create_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.zip_name)) },
                    suffix = { Text(".${format.extension}") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                )
                Text(stringResource(R.string.archive_format), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ArchiveFormat.entries.forEach { option ->
                        FilterChip(
                            selected = option == format,
                            onClick = { format = option },
                            label = { Text(option.extension.uppercase(Locale.ROOT)) }
                        )
                    }
                }
                if (format.supportsPassword) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.archive_password_optional)) },
                        supportingText = { Text(stringResource(R.string.archive_password_aes)) },
                        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { visible = !visible }) {
                                Icon(if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    )
                } else {
                    Text(
                        stringResource(R.string.archive_no_password),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                onConfirm(name.trim(), format, password.takeIf { format.supportsPassword && it.isNotEmpty() })
            }) { Text(stringResource(R.string.zip_pack)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } }
    )
}

@Composable
private fun ArchivePasswordDialog(name: String, wrong: Boolean, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var password by remember(name, wrong) { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Lock, null) },
        title = { Text(stringResource(R.string.archive_password_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    singleLine = true,
                    isError = wrong,
                    label = { Text(stringResource(R.string.archive_password)) },
                    supportingText = if (wrong) {
                        { Text(stringResource(R.string.archive_password_wrong)) }
                    } else null,
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { visible = !visible }) {
                            Icon(if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                )
            }
        },
        confirmButton = { TextButton(enabled = password.isNotEmpty(), onClick = { onConfirm(password) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } }
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
                Text(stringResource(R.string.welcome_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    stringResource(R.string.welcome_text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(onClick = onPick, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.FolderOpen, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.welcome_pick))
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
            val text = if (filtered) stringResource(R.string.empty_filtered) else when (tab) {
                Tab.FILES -> stringResource(R.string.empty_folder)
                Tab.RECENT -> stringResource(R.string.empty_recent)
                Tab.FAVORITES -> stringResource(R.string.empty_favorites)
                Tab.SETTINGS -> stringResource(R.string.empty_settings)
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
    val style = LocalArmaStyle.current
    val active = value == selected
    val underline by androidx.compose.animation.core.animateDpAsState(if (active) 28.dp else 0.dp, label = "navUnderline")
    // Napis z podkreśleniem akcentem, jak w makiecie „Wspólny język”; ikona tylko dla czytnika ekranu.
    Column(
        Modifier
            .weight(1f)
            .clip(RoundedCornerShape(ArmaStyle.ControlRadius))
            .selectable(selected = active, role = androidx.compose.ui.semantics.Role.Tab, onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = if (active) style.text else style.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Box(Modifier.padding(top = 6.dp).width(underline).height(2.dp).background(style.accent))
    }
}

/** Dolna nawigacja: cienka kreska u góry i cztery napisy — bez pigułek Material. */
@Composable
private fun ArmaNavBar(content: @Composable RowScope.() -> Unit) {
    val style = LocalArmaStyle.current
    Column(Modifier.background(MaterialTheme.colorScheme.background).navigationBarsPadding()) {
        HorizontalDivider(color = style.hairline)
        Row(Modifier.fillMaxWidth().height(60.dp), verticalAlignment = Alignment.CenterVertically, content = content)
    }
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
            vm.addRecent(item)
            onInstallApk(item)
        }
        ArchiveFormats.canBrowse(item.name) -> {
            vm.addRecent(item)
            vm.openArchive(item)
        }
        else -> {
            vm.addRecent(item)
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
    SortMode.NAME -> text(R.string.sort_name)
    SortMode.DATE -> text(R.string.sort_date)
    SortMode.SIZE -> text(R.string.sort_size)
    SortMode.TYPE -> text(R.string.sort_type)
}

private fun categoryLabel(category: FileCategory) = when (category) {
    FileCategory.ALL -> text(R.string.filter_all)
    FileCategory.FOLDERS -> text(R.string.filter_folders)
    FileCategory.IMAGES -> text(R.string.category_images)
    FileCategory.VIDEO -> text(R.string.filter_video)
    FileCategory.AUDIO -> text(R.string.category_audio)
    FileCategory.DOCUMENTS -> text(R.string.category_documents)
    FileCategory.ARCHIVES -> text(R.string.category_archives)
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
    FileCategory.ARCHIVES -> !item.isDirectory && (isZip(item) || ArchiveFormats.canBrowse(item.name))
    FileCategory.DOCUMENTS -> !item.isDirectory && !item.mimeType.startsWith("image/") && !item.mimeType.startsWith("video/") && !item.mimeType.startsWith("audio/") && !isZip(item) && !ArchiveFormats.canBrowse(item.name)
}

private fun folderLabel(uri: Uri?): String {
    if (uri == null) return text(R.string.folder_label_files)
    if (uri.scheme == RemoteRepository.FTP_SCHEME) return uri.path.orEmpty().trim('/').substringAfterLast('/').ifBlank { "FTP" }
    if (uri.scheme == RemoteRepository.SMB_SCHEME) return uri.path.orEmpty().trim('/').substringAfterLast('/').ifBlank { "SMB / Samba" }
    if (uri.scheme == "file") return uri.path?.trimEnd('/')?.substringAfterLast('/')?.ifBlank { text(R.string.folder_label_storage) } ?: text(R.string.folder_label_storage)
    return runCatching {
        val id = DocumentsContract.getDocumentId(uri)
        id.substringAfterLast('/').substringAfterLast(':').ifBlank { text(R.string.panes_root) }
    }.getOrDefault(uri.lastPathSegment ?: text(R.string.folder_label_folder))
}

internal fun meta(item: FileItem): String {
    val date = formatDate(item.lastModified)
    return if (item.isDirectory) {
        date.ifBlank { text(R.string.detail_folder) }
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
    !item.isDirectory && pl.arma.viewer.text.TextProfile.isTextFile(item.name, item.mimeType)

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
    runCatching { context.startActivity(Intent.createChooser(intent, context.getString(R.string.common_share))) }
}

private suspend fun openExternal(context: Context, item: FileItem, vm: FileManagerViewModel) {
    if (item.isDirectory) return
    val uri = vm.materializeUri(item)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, item.mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }
    runCatching { context.startActivity(intent) }
        .onFailure { runCatching { context.startActivity(Intent.createChooser(intent, context.getString(R.string.open_with, item.name))) } }
}
