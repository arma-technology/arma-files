package pl.arma.files

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import pl.arma.files.index.FileIndexDb
import pl.arma.files.index.IndexCategory
import pl.arma.files.index.IndexProgress
import pl.arma.files.index.IndexedFile
import pl.arma.files.net.AccountStore
import pl.arma.files.net.DiscoveredHost
import pl.arma.files.net.LanScanner
import pl.arma.files.net.RemoteAccount
import pl.arma.files.net.RemoteType
import pl.arma.files.ops.ConflictDecision
import pl.arma.files.ops.Operation
import pl.arma.files.ops.PaneRules
import pl.arma.files.archive.ArchiveFormat
import pl.arma.files.archive.ArchiveFormats
import pl.arma.files.archive.ArchivePasswordException
import pl.arma.files.archive.ArchiveRepository
import pl.arma.files.ops.OperationKind
import pl.arma.files.ops.OperationRecord
import pl.arma.files.ops.OperationStatus
import pl.arma.files.ops.PendingConflict
import pl.arma.files.ops.Reorder
import pl.arma.files.ops.TrashEntry
import pl.arma.viewer.text.TextDocument
import pl.arma.viewer.text.TextReader

/** Nazwy domyślnych kafelków zapisane w ustawieniach od pierwszych wersji. */
private const val DOWNLOADS_KEY = "Pobrane"
private const val PHOTOS_KEY = "Zdjęcia"
/** Ile miejsc pamięta Wstecz w jednej karcie. */
private const val NAV_HISTORY = 50

class FileManagerViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = (app as ArmaFilesApp).repository
    private val prefs = StoragePrefs(app)
    private val networkPrefs = NetworkPrefs(app)
    private val engine = (app as ArmaFilesApp).engine
    private val trash = (app as ArmaFilesApp).trash
    private val accounts = AccountStore(app)
    private val index = (app as ArmaFilesApp).index
    private val indexer = (app as ArmaFilesApp).indexer

    val indexProgress: StateFlow<IndexProgress> = indexer.progress

    private val _searchResults = MutableStateFlow<List<FileItem>>(emptyList())
    val searchResults: StateFlow<List<FileItem>> = _searchResults.asStateFlow()

    private val _globalSearch = MutableStateFlow(false)
    val globalSearch: StateFlow<Boolean> = _globalSearch.asStateFlow()

    private val _analysis = MutableStateFlow(StorageAnalysis())
    val analysis: StateFlow<StorageAnalysis> = _analysis.asStateFlow()

    private val _duplicates = MutableStateFlow<List<DuplicateSet>>(emptyList())
    val duplicates: StateFlow<List<DuplicateSet>> = _duplicates.asStateFlow()

    private val _analyzing = MutableStateFlow(false)
    val analyzing: StateFlow<Boolean> = _analyzing.asStateFlow()

    private val _remoteAccounts = MutableStateFlow<List<RemoteAccount>>(emptyList())
    val remoteAccounts: StateFlow<List<RemoteAccount>> = _remoteAccounts.asStateFlow()

    private val _accountTestResult = MutableStateFlow<AccountTestResult?>(null)
    val accountTestResult: StateFlow<AccountTestResult?> = _accountTestResult.asStateFlow()

    private val _testingAccount = MutableStateFlow(false)
    val testingAccount: StateFlow<Boolean> = _testingAccount.asStateFlow()

    private val _discovered = MutableStateFlow<List<DiscoveredHost>>(emptyList())
    val discovered: StateFlow<List<DiscoveredHost>> = _discovered.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    /** Kolejka i historia pochodzą z silnika żyjącego w procesie aplikacji. */
    val operations: StateFlow<List<Operation>> = engine.operations
    val pendingConflict: StateFlow<PendingConflict?> = engine.pendingConflict
    val operationHistory: StateFlow<List<OperationRecord>> = engine.history

    private val _trashEntries = MutableStateFlow<List<TrashEntry>>(emptyList())
    val trashEntries: StateFlow<List<TrashEntry>> = _trashEntries.asStateFlow()

    private val _files = MutableStateFlow<List<FileItem>>(emptyList())
    val files: StateFlow<List<FileItem>> = _files.asStateFlow()

    private val _recentFiles = MutableStateFlow<List<FileItem>>(emptyList())
    val recentFiles: StateFlow<List<FileItem>> = _recentFiles.asStateFlow()

    private val _favoriteFiles = MutableStateFlow<List<FileItem>>(emptyList())
    val favoriteFiles: StateFlow<List<FileItem>> = _favoriteFiles.asStateFlow()

    private val _favoriteUris = MutableStateFlow<Set<String>>(emptySet())
    val favoriteUris: StateFlow<Set<String>> = _favoriteUris.asStateFlow()

    private val _currentDir = MutableStateFlow<Uri?>(null)
    val currentDir: StateFlow<Uri?> = _currentDir.asStateFlow()

    private val _root = MutableStateFlow<Uri?>(null)
    val root: StateFlow<Uri?> = _root.asStateFlow()

    private val _currentSource = MutableStateFlow(SourceType.LOCAL)
    val currentSource: StateFlow<SourceType> = _currentSource.asStateFlow()

    private val _sources = MutableStateFlow<List<StorageSource>>(emptyList())
    val sources: StateFlow<List<StorageSource>> = _sources.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    /** Archiwum czekające na hasło; po jego podaniu ponawiamy [ArchivePasswordRequest.retry]. */
    data class ArchivePasswordRequest(val root: Uri, val name: String, val wrong: Boolean, val retry: () -> Unit)

    private val _passwordRequest = MutableStateFlow<ArchivePasswordRequest?>(null)
    val passwordRequest: StateFlow<ArchivePasswordRequest?> = _passwordRequest.asStateFlow()

    /** Rozpakowywanie wstrzymane przyciskiem „Anuluj”, czeka na potwierdzenie. */
    data class CancelRequest(val operationId: String, val wasPaused: Boolean)

    private val _cancelRequest = MutableStateFlow<CancelRequest?>(null)
    val cancelRequest: StateFlow<CancelRequest?> = _cancelRequest.asStateFlow()

    private val offlineStore = (app as ArmaFilesApp).offline
    val offlineEntries: StateFlow<List<OfflineStore.Entry>> = offlineStore.entries
    val offlineProgress: StateFlow<Map<String, OfflineStore.Progress>> = offlineStore.progress

    /** Folder bieżącej karty pokazany z kopii offline — czas tej kopii; null, gdy z serwera. */
    private val _offlineSince = MutableStateFlow<Long?>(null)
    val offlineSince: StateFlow<Long?> = _offlineSince.asStateFlow()

    fun makeOffline(items: List<FileItem>) {
        val remote = items.filter { RemoteRepository.isRemoteScheme(it.uri.scheme) }
        if (remote.isEmpty()) {
            _error.value = text(R.string.offline_only_remote)
            return
        }
        offlineStore.add(remote)
        _notice.value = text(R.string.offline_added)
    }

    fun removeOffline(items: List<FileItem>) = offlineStore.removeUris(items.map { it.uri })

    fun removeOfflineEntry(entry: OfflineStore.Entry) = offlineStore.remove(listOf(entry.id))

    fun syncOffline() = offlineStore.syncAll()

    fun resolveOfflineConflict(entry: OfflineStore.Entry, conflict: OfflineStore.Conflict, choice: OfflineStore.Choice) =
        offlineStore.resolve(entry.id, conflict.path, choice)

    /** Zmieniony klucz SSH albo certyfikat TLS serwera, czekający na decyzję. */
    val identityChange: StateFlow<pl.arma.files.net.ServerIdentityChange?> = pl.arma.files.net.ServerIdentityAlerts.pending

    fun trustIdentity() {
        val change = identityChange.value ?: return
        pl.arma.files.net.ServerIdentityAlerts.dismiss()
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { change.trust() } }
                .onFailure { _error.value = it.message }
                .onSuccess {
                    refresh()
                    offlineStore.syncAll()
                }
        }
    }

    fun rejectIdentity() = pl.arma.files.net.ServerIdentityAlerts.dismiss()

    /** Otwiera wpis offline: folder w karcie (z serwera albo z kopii), plik w podglądzie. */
    fun openOfflineFolder(entry: OfflineStore.Entry) = openRemoteFolder(entry.uri)

    /** Folder na serwerze w bieżącej karcie, ze ścieżką liczoną od korzenia konta. */
    fun openRemoteFolder(uri: Uri) {
        val source = _sources.value.firstOrNull { it.rootUri?.scheme == uri.scheme && it.rootUri?.authority == uri.authority }
        val pane = active()
        if (pane.currentDir == uri) return
        pane.rememberPlace()
        pane.source = source?.type ?: SourceType.SMB
        pane.root = source?.rootUri ?: uri.buildUpon().path("/").build()
        pane.currentDir = uri
        refreshPane(pane)
    }

    suspend fun offlineItem(entry: OfflineStore.Entry): FileItem? = withContext(Dispatchers.IO) { repo.itemInfo(entry.uri) }

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _clipboard = MutableStateFlow<ClipboardPayload?>(null)
    val clipboard: StateFlow<ClipboardPayload?> = _clipboard.asStateFlow()

    /**
     * Szuflada: pliki zbierane z różnych folderów (i różnych źródeł), które
     * potem wyciąga się jednym ruchem w folderze docelowym. W odróżnieniu od
     * schowka nie zastępuje się przy każdym dodaniu i przeżywa zamknięcie
     * aplikacji.
     */
    private val shelfStore = ShelfStore(app)
    private val _shelf = MutableStateFlow(shelfStore.load())
    val shelf: StateFlow<List<FileItem>> = _shelf.asStateFlow()

    private val _deviceStorages = MutableStateFlow<List<DeviceStorage>>(emptyList())
    val deviceStorages: StateFlow<List<DeviceStorage>> = _deviceStorages.asStateFlow()

    private val _pinnedFolders = MutableStateFlow<List<PinnedFolder>>(emptyList())
    val pinnedFolders: StateFlow<List<PinnedFolder>> = _pinnedFolders.asStateFlow()

    private val _allFilesAccess = MutableStateFlow(hasBroadStorageAccess())
    val allFilesAccess: StateFlow<Boolean> = _allFilesAccess.asStateFlow()

    /**
     * Jeden panel: własny katalog, własna historia cofania i własna lista
     * plików. Zakładki i tryb dwupanelowy to ten sam mechanizm — karta widoczna
     * po lewej jest aktywna, a druga (opcjonalnie) pokazywana obok.
     */
    private class Pane(val id: String) {
        var root: Uri? = null
        var currentDir: Uri? = null
        var source: SourceType = SourceType.LOCAL
        var files: List<FileItem> = emptyList()
        /** Historia „wstecz”: poprzednie miejsca razem ze źródłem, także ekran główny. */
        val navStack = ArrayDeque<NavEntry>()
        /** Czas synchronizacji kopii offline, gdy lista pochodzi z niej, a nie z serwera. */
        var offlineSince: Long? = null
    }

    /** Miejsce w historii karty; [dir] == null to ekran „Moje urządzenie”. */
    private data class NavEntry(val root: Uri?, val source: SourceType, val dir: Uri?)

    /**
     * Zapamiętuje bieżące miejsce przed przejściem gdzie indziej — Wstecz ma
     * wracać tam, skąd się przyszło (do wyników, przypiętego folderu, ekranu
     * głównego), a nie do folderu nadrzędnego.
     */
    private fun Pane.rememberPlace() {
        val here = NavEntry(root, source, currentDir)
        if (navStack.lastOrNull() == here) return
        navStack.addLast(here)
        while (navStack.size > NAV_HISTORY) navStack.removeFirst()
    }

    /** Usuwa z historii wszystkich kart miejsca, które przestały istnieć. */
    private fun forgetPlaces(gone: (Uri) -> Boolean) {
        panes.forEach { pane -> pane.navStack.removeAll { entry -> listOfNotNull(entry.root, entry.dir).any(gone) } }
    }

    private val panes = mutableListOf(Pane(java.util.UUID.randomUUID().toString()))
    private var activePaneId: String = panes.first().id
    private var secondaryPaneId: String? = null

    private val _tabs = MutableStateFlow<List<TabInfo>>(emptyList())
    val tabs: StateFlow<List<TabInfo>> = _tabs.asStateFlow()

    private val _secondaryFiles = MutableStateFlow<List<FileItem>>(emptyList())
    val secondaryFiles: StateFlow<List<FileItem>> = _secondaryFiles.asStateFlow()

    private val _secondaryDir = MutableStateFlow<Uri?>(null)
    val secondaryDir: StateFlow<Uri?> = _secondaryDir.asStateFlow()

    private val _dualPane = MutableStateFlow(false)
    val dualPane: StateFlow<Boolean> = _dualPane.asStateFlow()

    private fun active(): Pane = panes.first { it.id == activePaneId }

    private fun secondary(): Pane? = secondaryPaneId?.let { id -> panes.firstOrNull { it.id == id } }

    private fun paneTitle(pane: Pane): String =
        pane.currentDir?.let(::rootLabel) ?: tabTitle(pane.currentDir?.path)

    /**
     * Nazwa katalogu, który jest początkiem miejsca: nośnik zamiast „0”
     * z /storage/emulated/0, konto zamiast „/” serwera. Null dla zwykłych folderów.
     */
    fun rootLabel(uri: Uri): String? {
        _deviceStorages.value.firstOrNull { it.rootUri == uri }?.let { return it.label }
        if (RemoteRepository.isRemoteScheme(uri.scheme) && uri.path.orEmpty().trim('/').isEmpty()) {
            return _remoteAccounts.value.firstOrNull { it.id == uri.authority }?.label?.ifBlank { null }
        }
        if (ArchiveRepository.isArchive(uri) && ArchiveRepository.innerPath(uri).isEmpty()) {
            return repo.archives.archiveName(uri)
        }
        return null
    }

    private fun tabTitle(path: String?) = PaneRules.tabTitle(path, text(R.string.home_title), text(R.string.panes_root))

    /**
     * Po zmianie języka aktywność powstaje od nowa, a ten ViewModel zostaje —
     * teksty wyliczone wcześniej (tytuły kart, źródła) trzeba przeliczyć.
     */
    fun onLocaleChanged() {
        syncPanes()
        updateSources()
        refreshDeviceLocations()
    }

    /** Przepisuje stan aktywnego panelu do przepływów, z których czyta ekran. */
    private fun syncPanes() {
        val current = active()
        _currentDir.value = current.currentDir
        _root.value = current.root
        _currentSource.value = current.source
        _files.value = current.files
        _offlineSince.value = current.offlineSince

        val other = secondary()
        _secondaryFiles.value = other?.files.orEmpty()
        _secondaryDir.value = other?.currentDir
        _dualPane.value = other != null

        _tabs.value = panes.map { pane ->
            TabInfo(
                id = pane.id,
                title = paneTitle(pane),
                active = pane.id == activePaneId,
                secondary = pane.id == secondaryPaneId,
                source = pane.source,
                atHome = pane.currentDir == null
            )
        }
        updateWatchers()
    }

    /** Pozycje list (pierwszy widoczny element i przesunięcie) dla folderów w kartach. */
    private val scrollPositions = object : LinkedHashMap<String, Pair<Int, Int>>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Int, Int>>?) = size > 200
    }

    fun scrollPosition(paneId: String, dir: Uri?): Pair<Int, Int> =
        dir?.let { scrollPositions["$paneId|$it"] } ?: (0 to 0)

    fun saveScrollPosition(paneId: String, dir: Uri?, index: Int, offset: Int) {
        if (dir == null || paneId.isEmpty()) return
        scrollPositions["$paneId|$dir"] = index to offset
    }

    private val watcher = DirectoryWatcher(app, repo)

    /** Obserwowany folder każdego widocznego panelu. */
    private val watches = mutableMapOf<String, Pair<Uri, Job>>()
    private var foreground = true

    /**
     * Obserwujemy tylko foldery widoczne na ekranie i tylko wtedy, gdy
     * aplikacja jest na wierzchu — w tle nie ma po co trzymać połączeń
     * z serwerami ani budzić telefonu odpytywaniem.
     */
    private fun updateWatchers() {
        val wanted = if (!foreground) emptyMap() else listOfNotNull(active(), secondary())
            .mapNotNull { pane -> pane.currentDir?.let { pane.id to it } }
            .toMap()
        watches.entries.removeAll { (id, watch) ->
            (wanted[id] != watch.first).also { stale -> if (stale) watch.second.cancel() }
        }
        wanted.forEach { (id, dir) ->
            if (id in watches) return@forEach
            val job = viewModelScope.launch {
                @OptIn(kotlinx.coroutines.FlowPreview::class)
                watcher.changes(dir).debounce(400).collect { quietRefresh(id, dir) }
            }
            watches[id] = dir to job
        }
    }

    /** Odświeżenie bez wskaźnika zajętości i bez komunikatu o błędzie — lista zmienia się tylko, gdy coś się zmieniło. */
    private suspend fun quietRefresh(paneId: String, dir: Uri) {
        val pane = panes.firstOrNull { it.id == paneId } ?: return
        if (pane.currentDir != dir) return
        val fresh = runCatching { repo.listChildren(dir) }.getOrNull() ?: return
        val since = if (repo.servedOffline(dir)) offlineStore.syncedAt(dir) ?: 0L else null
        if (pane.currentDir == dir && (fresh != pane.files || since != pane.offlineSince)) {
            pane.files = fresh
            pane.offlineSince = since
            syncPanes()
        }
    }

    /** Aplikacja na wierzchu: od razu sprawdza widoczne foldery i wznawia obserwację. */
    fun setForeground(value: Boolean) {
        if (foreground == value) return
        foreground = value
        updateWatchers()
        if (value) {
            listOfNotNull(active(), secondary()).forEach { pane ->
                val dir = pane.currentDir ?: return@forEach
                viewModelScope.launch { quietRefresh(pane.id, dir) }
            }
        }
    }

    /** Otwiera nową kartę na ekranie „Moje urządzenie" i przełącza się na nią. */
    fun openTab() {
        val pane = Pane(java.util.UUID.randomUUID().toString())
        panes += pane
        activePaneId = pane.id
        syncPanes()
    }

    /** Otwiera wskazany katalog w nowej karcie, nie ruszając bieżącej. */
    fun openInNewTab(item: FileItem) {
        if (!item.isDirectory) return
        val source = active()
        val pane = Pane(java.util.UUID.randomUUID().toString())
        pane.root = source.root
        pane.source = source.source
        pane.currentDir = item.uri
        panes += pane
        activePaneId = pane.id
        refreshPane(pane)
    }

    /** Zamyka wszystkie karty poza wskazaną. */
    fun closeOtherTabs(id: String) {
        if (panes.none { it.id == id }) return
        panes.retainAll { it.id == id }
        activePaneId = id
        secondaryPaneId = null
        syncPanes()
    }

    /** Otwiera drugą kartę na tym samym katalogu — wygodne przy porównywaniu. */
    fun duplicateTab(id: String) {
        val source = panes.firstOrNull { it.id == id } ?: return
        val copy = Pane(java.util.UUID.randomUUID().toString())
        copy.root = source.root
        copy.source = source.source
        copy.currentDir = source.currentDir
        source.navStack.forEach(copy.navStack::addLast)
        panes.add(panes.indexOf(source) + 1, copy)
        activePaneId = copy.id
        refreshPane(copy)
    }

    /** Przypina katalog otwarty w karcie do ekranu „Moje urządzenie". */
    fun pinTabFolder(id: String) {
        val pane = panes.firstOrNull { it.id == id } ?: return
        val dir = pane.currentDir ?: run {
            _error.value = text(R.string.err_tab_no_folder)
            return
        }
        viewModelScope.launch {
            val item = runCatching { repo.itemInfo(dir) }.getOrNull()
                ?: FileItem(dir, tabTitle(dir.path), DIRECTORY_MIME, 0, 0, true)
            pinFolder(item)
        }
    }

    fun closeTab(id: String) {
        val nextActive = PaneRules.activeAfterClose(panes.map { it.id }, activePaneId, id) ?: return
        panes.removeAll { it.id == id }
        if (secondaryPaneId == id) secondaryPaneId = null
        activePaneId = nextActive
        syncPanes()
    }

    fun selectTab(id: String) {
        if (panes.none { it.id == id }) return
        if (secondaryPaneId == id) {
            // Karta stoi już w drugim panelu — zamieniamy strony zamiast gasić
            // podział, bo zniknięcie panelu po tapnięciu byłoby zaskoczeniem.
            swapPanes()
            return
        }
        activePaneId = id
        syncPanes()
        if (active().files.isEmpty() && active().currentDir != null) refresh()
    }

    /**
     * Włącza drugi panel. Gdy nie ma innej karty, tworzy ją — dzięki temu tryb
     * dwupanelowy działa od razu, bez ręcznego zakładania zakładek.
     */
    fun setDualPane(enabled: Boolean) {
        if (!enabled) {
            secondaryPaneId = null
            syncPanes()
            return
        }
        val candidate = panes.firstOrNull { it.id != activePaneId }
            ?: Pane(java.util.UUID.randomUUID().toString()).also { pane ->
                // Świeży panel dostaje katalog z lewej strony, żeby od razu
                // było co przeciągać — pusty panel wymuszałby szukanie karty.
                val current = active()
                pane.root = current.root
                pane.source = current.source
                pane.currentDir = current.currentDir
                panes += pane
            }
        secondaryPaneId = candidate.id
        syncPanes()
        refreshPane(candidate)
    }

    fun swapPanes() {
        val other = secondaryPaneId ?: return
        secondaryPaneId = activePaneId
        activePaneId = other
        syncPanes()
    }

    /** Kopiuje lub przenosi zaznaczone elementy do katalogu drugiego panelu. */
    fun sendToOtherPane(items: List<FileItem>, move: Boolean) {
        val target = secondary()?.currentDir
        if (target == null) {
            _error.value = text(R.string.err_pane_no_folder)
            return
        }
        if (items.isEmpty()) return
        if (ArchiveRepository.isArchive(target)) {
            _error.value = text(R.string.err_archive_read_only)
            return
        }
        val kind = when {
            items.all { ArchiveRepository.isArchive(it.uri) } -> OperationKind.EXTRACT
            move -> OperationKind.MOVE
            else -> OperationKind.COPY
        }
        enqueue(kind, items, target)
    }

    fun openDirectoryInSecondary(item: FileItem) {
        val pane = secondary() ?: return
        pane.rememberPlace()
        pane.currentDir = item.uri
        refreshPane(pane)
    }

    fun secondaryGoBack() {
        val pane = secondary() ?: return
        val previous = pane.navStack.removeLastOrNull() ?: return
        restorePlace(pane, previous)
    }

    init {
        syncPanes()
        loadSavedCollections()
        updateSources()
        refreshDeviceLocations()
        loadTrash()
        // Same statystyki indeksu to jedno zapytanie agregujące — tanie, a
        // dzięki temu ekran główny od razu wie, czy indeks w ogóle istnieje.
        analyzeStorage()
        // Statystyki indeksu (liczba plików, analiza) odświeżamy po każdym
        // zakończonym budowaniu — także tym uruchomionym w tle.
        viewModelScope.launch {
            indexer.progress.map { it.finishedAt }.distinctUntilChanged().drop(1).collect { analyzeStorage() }
        }
    }

    fun setTree(tree: Uri) {
        runCatching {
            repo.persistTreePermission(tree)
            prefs.treeUri = tree
            updateSources()
            openSource(SourceType.LOCAL)
        }.onFailure { _error.value = it.message ?: text(R.string.err_access_failed) }
    }

    fun setCloudTree(type: SourceType, tree: Uri) {
        if (type !in setOf(SourceType.GOOGLE_DRIVE, SourceType.ONEDRIVE, SourceType.DROPBOX)) return
        runCatching {
            repo.persistTreePermission(tree)
            networkPrefs.setCloudTree(type, tree)
            networkPrefs.setEnabled(type, true)
            updateSources()
            openSource(type)
        }.onFailure { _error.value = it.message ?: text(R.string.err_attach_failed) }
    }

    fun setSourceEnabled(type: SourceType, enabled: Boolean) {
        // Zostaje tylko dla źródeł chmurowych podpiętych przez SAF —
        // FTP, SFTP, SMB i WebDAV są kontami i mają własny przełącznik.
        if (type == SourceType.LOCAL) return
        networkPrefs.setEnabled(type, enabled)
        afterSourceDisabled(enabled) { it.type == type }
    }

    /**
     * Odłącza folder podpięty przez SAF — lokalny albo z aplikacji chmury
     * (Dropbox, Drive, OneDrive) — i oddaje uprawnienie do niego. Konta
     * sieciowe mają własne usuwanie w ustawieniach.
     */
    fun disconnectSource(source: StorageSource) {
        val cloud = source.type in setOf(SourceType.GOOGLE_DRIVE, SourceType.ONEDRIVE, SourceType.DROPBOX)
        val tree = when {
            source.type == SourceType.LOCAL -> prefs.treeUri
            cloud -> networkPrefs.cloudTree(source.type)
            else -> null
        } ?: return
        // Katalog do opuszczenia trzeba znać, zanim źródło zniknie z listy.
        val leaving = source.rootUri
        if (cloud) {
            networkPrefs.setCloudTree(source.type, null)
            networkPrefs.setEnabled(source.type, false)
        } else {
            prefs.treeUri = null
        }
        val stillUsed = prefs.treeUri == tree ||
            listOf(SourceType.GOOGLE_DRIVE, SourceType.ONEDRIVE, SourceType.DROPBOX).any { networkPrefs.cloudTree(it) == tree }
        if (!stillUsed) repo.releaseTreePermission(tree)
        updateSources()
        loadSavedCollections()
        if (leaving != null) forgetPlaces { it.toString().startsWith(leaving.toString()) }
        if (leaving != null && _root.value == leaving) goComputerHome()
        // Chmura po nazwie usługi; folder lokalny po nazwie folderu, o ile
        // dostawca ją podał (Dropbox jej nie podaje — zostaje „Podłączony”).
        val name = if (cloud) source.label
        else source.subtitle.takeIf { it.isNotBlank() && it != text(R.string.source_attached) } ?: source.label
        _notice.value = text(R.string.source_disconnected, name)
    }

    /** Włącza lub wyłącza jedno konkretne połączenie sieciowe. */
    fun setAccountEnabled(id: String, enabled: Boolean) {
        val account = accounts.get(id) ?: return
        accounts.save(account.copy(enabled = enabled))
        afterSourceDisabled(enabled) { it.accountId == id }
    }

    /**
     * Po wyłączeniu źródła trzeba uciec z jego katalogu, jeśli akurat w nim
     * jesteśmy — inaczej lista pokazywałaby zawartość zasobu, który dla
     * aplikacji już nie istnieje.
     */
    private fun afterSourceDisabled(enabled: Boolean, matches: (StorageSource) -> Boolean) {
        updateSources()
        loadSavedCollections()
        if (enabled) return
        val current = _root.value
        val leaving = _sources.value.firstOrNull(matches)?.rootUri
        if (leaving != null) forgetPlaces { it.toString().startsWith(leaving.toString()) }
        if (current == null || leaving == null || current != leaving) return

        val fallback = _sources.value.firstOrNull { it.type == SourceType.LOCAL && it.configured }
            ?: _sources.value.firstOrNull { it.enabled && it.configured }
        if (fallback != null) openSource(fallback) else goComputerHome()
    }



    /** Zapisuje dowolne konto sieciowe — także nowe, spoza pary FTP/SMB. */
    fun saveAccount(account: RemoteAccount) {
        // Zmiana adresu albo hasła unieważnia otwarte sesje, więc zamykamy je,
        // zanim ktokolwiek sięgnie po nie z puli.
        accounts.get(account.id)?.let(repo::disconnectRemote)
        accounts.save(account)
        updateSources()
    }

    fun newAccountDraft(type: RemoteType = RemoteType.SMB): RemoteAccount = RemoteAccount(
        id = RemoteAccount.newId(),
        type = type,
        label = "",
        host = "",
        port = RemoteAccount.defaultPort(type, secure = false),
        username = ""
    )

    /** Sprawdza połączenie bez zapisywania konta. */
    fun testAccount(account: RemoteAccount) {
        _testingAccount.value = true
        _accountTestResult.value = null
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { repo.probeAccount(account) }
            }
            _testingAccount.value = false
            _accountTestResult.value = result.fold(
                onSuccess = { count -> AccountTestResult(true, text(R.string.accounts_test_ok, plural(R.plurals.n_items, count, count))) },
                onFailure = { AccountTestResult(false, it.message ?: text(R.string.accounts_test_failed)) }
            )
        }
    }

    fun clearAccountTest() {
        _accountTestResult.value = null
        _testingAccount.value = false
    }

    /** Szuka serwerów SMB w sieci lokalnej. */
    fun discoverLan() {
        _scanning.value = true
        _discovered.value = emptyList()
        viewModelScope.launch {
            val found = runCatching { LanScanner.findSmbHosts() }.getOrDefault(emptyList())
            _discovered.value = found
            _scanning.value = false
            if (found.isEmpty()) _notice.value = text(R.string.discovery_none_notice)
        }
    }

    fun deleteAccount(id: String) {
        accounts.get(id)?.let(repo::disconnectRemote)
        accounts.delete(id)
        forgetPlaces { it.authority == id && RemoteRepository.isRemoteScheme(it.scheme) }
        // Usunięte konto może być otwarte w kilku kartach naraz.
        panes.filter { it.root?.authority == id }.forEach { pane ->
            pane.navStack.clear()
            pane.files = emptyList()
            pane.currentDir = null
            pane.root = null
            pane.source = SourceType.LOCAL
        }
        syncPanes()
        updateSources()
        loadSavedCollections()
    }

    /** Otwiera konkretne źródło — istotne, gdy jest kilka połączeń tego typu. */
    fun openSource(source: StorageSource) {
        val rootUri = source.rootUri
        if (!source.configured || rootUri == null) {
            _error.value = text(R.string.err_needs_config)
            return
        }
        val pane = active()
        if (pane.currentDir != rootUri) pane.rememberPlace()
        pane.source = source.type
        pane.root = rootUri
        pane.currentDir = rootUri
        refreshPane(pane)
    }

    fun testSource(type: SourceType) {
        val source = _sources.value.firstOrNull { it.type == type }
        val rootUri = source?.rootUri
        if (source == null || !source.configured || rootUri == null) {
            _error.value = text(R.string.err_configure_first)
            return
        }
        launchOperation {
            val count = repo.listChildren(rootUri).size
            _notice.value = text(R.string.accounts_test_ok, plural(R.plurals.n_items, count, count))
        }
    }

    fun openSource(type: SourceType) {
        val source = _sources.value.firstOrNull { it.type == type } ?: return
        if (!source.enabled && type != SourceType.LOCAL) return
        val rootUri = source.rootUri
        if (!source.configured || rootUri == null) {
            _error.value = when (type) {
                SourceType.LOCAL -> text(R.string.err_pick_local_first)
                SourceType.FTP -> text(R.string.err_configure_ftp)
                SourceType.SMB -> text(R.string.err_configure_smb)
                else -> text(R.string.err_attach_in_settings)
            }
            return
        }
        val pane = active()
        if (pane.currentDir != rootUri) pane.rememberPlace()
        pane.source = type
        pane.root = rootUri
        pane.currentDir = rootUri
        refreshPane(pane)
    }

    fun refresh() {
        refreshPane(active())
        secondary()?.let(::refreshPane)
    }

    private fun refreshPane(pane: Pane) {
        val dir = pane.currentDir ?: run { syncPanes(); return }
        viewModelScope.launch {
            _busy.value = true
            runCatching { repo.listChildren(dir) }
                .onSuccess {
                    pane.files = it
                    pane.offlineSince = if (repo.servedOffline(dir)) offlineStore.syncedAt(dir) ?: 0L else null
                }
                .onFailure {
                    if (it is ArchivePasswordException) askPassword(dir, it.wrong) { refreshPane(pane) }
                    else if (!pl.arma.files.net.ServerIdentityAlerts.isIdentityChange(it)) {
                        _error.value = text(R.string.err_cannot_read_folder, it.message.orEmpty())
                    }
                }
            _busy.value = false
            syncPanes()
        }
    }

    fun openDirectory(item: FileItem) {
        val pane = active()
        pane.rememberPlace()
        pane.currentDir = item.uri
        refreshPane(pane)
    }

    fun goBack(): Boolean {
        val pane = active()
        val previous = pane.navStack.removeLastOrNull()
        if (previous != null) {
            restorePlace(pane, previous)
            return true
        }
        if (pane.currentDir != null) {
            goComputerHome()
            return true
        }
        return false
    }

    /** Czy Wstecz ma dokąd wrócić w bieżącej karcie. */
    fun canGoBack(): Boolean = active().let { it.navStack.isNotEmpty() || it.currentDir != null }

    private fun restorePlace(pane: Pane, place: NavEntry) {
        pane.root = place.root
        pane.source = place.source
        pane.currentDir = place.dir
        if (place.dir == null) {
            pane.files = emptyList()
            pane.offlineSince = null
            syncPanes()
            refreshDeviceLocations()
        } else {
            refreshPane(pane)
        }
    }

    /** Ekran „Moje urządzenie” z paska nawigacji — Wstecz wraca potem do folderu. */
    fun showHome() {
        val pane = active()
        if (pane.currentDir == null) return
        pane.rememberPlace()
        pane.files = emptyList()
        pane.currentDir = null
        pane.root = null
        pane.source = SourceType.LOCAL
        syncPanes()
        refreshDeviceLocations()
    }

    fun goComputerHome() {
        val pane = active()
        pane.navStack.clear()
        pane.files = emptyList()
        pane.currentDir = null
        pane.root = null
        pane.source = SourceType.LOCAL
        syncPanes()
        refreshDeviceLocations()
    }

    fun openLocalLocation(uri: Uri) {
        if (uri.scheme == "file" && !hasBroadStorageAccess()) {
            _error.value = text(R.string.err_needs_all_files)
            return
        }
        val pane = active()
        if (pane.currentDir == uri) return
        pane.rememberPlace()
        pane.source = SourceType.LOCAL
        pane.root = uri
        pane.currentDir = uri
        refreshPane(pane)
    }

    /** Skok do wskazanego członu ścieżki z paska nawigacji. */
    fun openPath(uri: Uri) {
        val pane = active()
        if (pane.currentDir == uri) return
        pane.rememberPlace()
        pane.currentDir = uri
        refreshPane(pane)
    }

    fun goRoot() {
        val pane = active()
        pane.root?.let {
            if (pane.currentDir == it) return
            pane.rememberPlace()
            pane.currentDir = it
            refreshPane(pane)
        }
    }

    fun createFolder(name: String) {
        val parent = _currentDir.value ?: return
        launchOperation(success = text(R.string.notice_folder_created)) {
            repo.createFolder(parent, validatedName(name)) ?: error(text(R.string.err_folder_create))
            refresh()
        }
    }

    fun createFile(name: String) {
        val parent = _currentDir.value ?: return
        launchOperation(success = text(R.string.notice_file_created)) {
            val safe = validatedName(name)
            repo.createFile(parent, safe, guessMime(safe)) ?: error(text(R.string.err_file_create))
            refresh()
        }
    }

    fun rename(item: FileItem, name: String) {
        launchOperation(success = text(R.string.notice_renamed)) {
            val newUri = repo.rename(item, validatedName(name))
            if (newUri != null) {
                prefs.replaceUri(item.uri, newUri)
                prefs.saveMeta(item.copy(uri = newUri, name = newUri.lastPathSegment ?: item.name))
            }
            refresh()
            loadSavedCollections()
        }
    }

    private fun validatedName(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isBlank()) error(text(R.string.err_name_empty))
        if (trimmed == "." || trimmed == "..") error(text(R.string.err_name_invalid))
        if (trimmed.any { it == '/' || it == '\\' }) error(text(R.string.err_name_slash))
        return trimmed
    }

    /**
     * Przenosi do kosza, a gdy dla danego elementu kosza nie da się wskazać
     * (np. drzewo SAF bez zapisywalnego korzenia) — usuwa trwale.
     * Elementy grupowane są po nośniku, bo kosz musi leżeć na tym samym co plik.
     */
    fun delete(items: List<FileItem>) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            val groups = items.groupBy { trashRootFor(it.uri)?.toString() }
            groups.forEach { (rootUri, group) ->
                val trashDir = rootUri?.let { root ->
                    runCatching { repo.ensureFolder(Uri.parse(root), TrashStore.TRASH_DIR) }.getOrNull()
                }
                if (trashDir == null) {
                    enqueue(OperationKind.DELETE, group, null)
                } else {
                    enqueue(OperationKind.TRASH, group, trashDir, group)
                }
            }
        }
    }

    /**
     * Korzeń, w którym powstanie kosz dla danego elementu. Dla pamięci i
     * zasobów sieciowych wskazuje go [TrashStore]; dla folderów podpiętych
     * przez SAF korzeniem jest katalog samego drzewa, którego identyfikator
     * niesie w sobie URI dokumentu.
     */
    private fun trashRootFor(uri: Uri): Uri? =
        TrashStore.volumeRootFor(uri) ?: safTreeRoot(uri)

    private fun safTreeRoot(uri: Uri): Uri? {
        if (uri.scheme != "content") return null
        return runCatching {
            DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
        }.getOrNull()
    }

    /** Usuwa bezpowrotnie, z pominięciem kosza. */
    fun deleteForever(items: List<FileItem>) {
        if (items.isEmpty()) return
        enqueue(OperationKind.DELETE, items, null)
    }

    fun emptyTrash() {
        viewModelScope.launch {
            val entries = trash.entries()
            if (entries.isEmpty()) {
                _notice.value = text(R.string.notice_trash_empty)
                return@launch
            }
            val items = entries.mapNotNull { entry ->
                runCatching { repo.itemInfo(Uri.parse(entry.trashUri)) }.getOrNull()
            }
            if (items.isEmpty()) {
                trash.clear()
                loadTrash()
                return@launch
            }
            // Dziennik czyścimy dopiero po potwierdzonym usunięciu — inaczej
            // nieudane opróżnianie zostawiłoby pliki w koszu bez wpisów, czyli
            // niewidoczne i niemożliwe do przywrócenia.
            val id = enqueue(OperationKind.DELETE, items, null)
            if (awaitRecord(id)?.status == OperationStatus.COMPLETED) {
                trash.clear()
                loadTrash()
            }
        }
    }

    /** Przywraca elementy z kosza do katalogów, z których pochodziły. */
    fun restoreFromTrash(entries: List<TrashEntry>) {
        if (entries.isEmpty()) return
        viewModelScope.launch {
            entries.groupBy { it.originalParent }.forEach { (parent, group) ->
                val items = group.mapNotNull { entry ->
                    runCatching { repo.itemInfo(Uri.parse(entry.trashUri)) }.getOrNull()
                }
                if (items.isEmpty()) return@forEach
                val id = enqueue(OperationKind.RESTORE, items, Uri.parse(parent))
                awaitRecord(id)?.let { record ->
                    if (record.status == OperationStatus.COMPLETED) {
                        trash.remove(group.map { it.id })
                        loadTrash()
                    }
                }
            }
        }
    }

    fun loadTrash() {
        _trashEntries.value = trash.entries().sortedByDescending { it.deletedAt }
    }

    /**
     * Cofa ostatnią operację: kopiowanie przez usunięcie kopii, przeniesienie
     * przez odwrotne przeniesienie, kosz przez przywrócenie.
     */
    fun undo(record: OperationRecord) {
        if (!record.undoable) return
        viewModelScope.launch {
            when (record.kind) {
                OperationKind.COPY, OperationKind.EXTRACT -> {
                    val copies = record.pairs.mapNotNull { (_, destination) ->
                        runCatching { repo.itemInfo(Uri.parse(destination)) }.getOrNull()
                    }
                    if (copies.isNotEmpty()) enqueue(OperationKind.DELETE, copies, null)
                }
                OperationKind.MOVE, OperationKind.TRASH -> {
                    record.pairs.groupBy { (source, _) -> source.substringBeforeLast('/') }
                        .forEach { (parent, pairs) ->
                            val moved = pairs.mapNotNull { (_, destination) ->
                                runCatching { repo.itemInfo(Uri.parse(destination)) }.getOrNull()
                            }
                            if (moved.isNotEmpty()) {
                                enqueue(OperationKind.RESTORE, moved, Uri.parse(parent))
                            }
                        }
                    if (record.kind == OperationKind.TRASH) {
                        trash.remove(trash.entries().filter { entry ->
                            record.pairs.any { it.second == entry.trashUri }
                        }.map { it.id })
                        loadTrash()
                    }
                }
                else -> Unit
            }
        }
    }

    /**
     * Dodaje zadanie do kolejki i pilnuje, żeby po jego zakończeniu odświeżyć
     * widok. [trashSources] podane tylko dla operacji kosza — z nich powstają
     * wpisy dziennika po potwierdzonym przeniesieniu.
     */
    private fun enqueue(
        kind: OperationKind,
        items: List<FileItem>,
        target: Uri?,
        trashSources: List<FileItem>? = null
    ): String {
        val id = engine.enqueue(kind, items, target)
        // Start z widocznego ekranu, bo tylko wtedy Android 12+ pozwala
        // podnieść usługę pierwszoplanową.
        FileOperationService.start(getApplication())
        viewModelScope.launch {
            val record = awaitRecord(id) ?: return@launch
            if (kind == OperationKind.TRASH && record.status == OperationStatus.COMPLETED) {
                val byUri = trashSources.orEmpty().associateBy { it.uri.toString() }
                trash.add(record.pairs.mapNotNull { (source, destination) ->
                    val item = byUri[source] ?: return@mapNotNull null
                    TrashEntry(
                        id = java.util.UUID.randomUUID().toString(),
                        name = item.name,
                        trashUri = destination,
                        originalUri = source,
                        originalParent = source.substringBeforeLast('/'),
                        size = item.size,
                        isDirectory = item.isDirectory,
                        deletedAt = System.currentTimeMillis()
                    )
                })
                loadTrash()
            }
            record.error?.let { _error.value = it }
            refresh()
            loadSavedCollections()
        }
        return id
    }

    private suspend fun awaitRecord(id: String): OperationRecord? =
        engine.history.first { history -> history.any { it.id == id } }.firstOrNull { it.id == id }

    fun setClipboard(items: List<FileItem>, cut: Boolean) {
        if (items.isNotEmpty()) {
            // Z archiwum można tylko kopiować — źródła nie da się usunąć.
            val cut = cut && items.none { ArchiveRepository.isArchive(it.uri) }
            _clipboard.value = ClipboardPayload(items.distinctBy { it.uri.toString() }, cut)
            _notice.value = if (cut) text(R.string.notice_selected_move, items.size) else text(R.string.notice_copied, items.size)
        }
    }

    fun clearClipboard() { _clipboard.value = null }

    fun addToShelf(items: List<FileItem>) {
        if (items.isEmpty()) return
        val known = _shelf.value.map { it.uri.toString() }.toSet()
        val added = items.filter { it.uri.toString() !in known }
        _shelf.value = _shelf.value + added
        shelfStore.save(_shelf.value)
        _notice.value = text(R.string.shelf_added, added.size)
    }

    fun removeFromShelf(item: FileItem) {
        _shelf.value = _shelf.value.filterNot { it.uri == item.uri }
        shelfStore.save(_shelf.value)
    }

    fun clearShelf() {
        _shelf.value = emptyList()
        shelfStore.save(emptyList())
    }

    /** Czy bieżący folder przyjmie zawartość szuflady. */
    fun canDropHere(): Boolean = _currentDir.value?.let { !ArchiveRepository.isArchive(it) } == true

    /**
     * Wyciąga szufladę do bieżącego folderu. Wpisy z archiwów zawsze się
     * wypakowuje (źródła nie da się usunąć), resztę kopiuje albo przenosi.
     */
    fun dropShelf(move: Boolean) {
        val items = _shelf.value
        if (items.isEmpty()) return
        val target = writableDir() ?: run { _error.value = text(R.string.shelf_no_target); return }
        val (packed, plain) = items.partition { ArchiveRepository.isArchive(it.uri) }
        if (packed.isNotEmpty()) enqueue(OperationKind.EXTRACT, packed, target)
        if (plain.isNotEmpty()) enqueue(if (move) OperationKind.MOVE else OperationKind.COPY, plain, target)
        clearShelf()
    }

    /**
     * Zawartość folderu bez wchodzenia do niego — do żywych kart i szybkiego
     * podglądu. Foldery najpierw, potem pliki od najnowszych.
     */
    suspend fun peekFolder(item: FileItem): List<FileItem> =
        repo.listChildren(item.uri).sortedWith(compareBy<FileItem> { !it.isDirectory }.thenByDescending { it.lastModified })

    /** Korzeń archiwum do zajrzenia bez otwierania; hasło → [ArchivePasswordException]. */
    suspend fun peekArchive(item: FileItem): List<FileItem> = withContext(Dispatchers.IO) {
        val root = repo.archives.rootOf(item.uri, item.name)
        repo.archives.ensureReadable(root)
        repo.listChildren(root).sortedWith(compareBy<FileItem> { !it.isDirectory }.thenBy { it.name.lowercase() })
    }

    suspend fun listArchiveFolder(uri: Uri): List<FileItem> =
        repo.listChildren(uri).sortedWith(compareBy<FileItem> { !it.isDirectory }.thenBy { it.name.lowercase() })

    /** Wyciąga pojedyncze wpisy z archiwum do folderu, w którym leży archiwum. */
    fun extractFromArchive(entries: List<FileItem>) {
        if (entries.isEmpty()) return
        val target = writableDir() ?: return
        enqueue(OperationKind.EXTRACT, entries, target)
    }

    /** Folder, do którego prowadzi „wstecz” — warstwa pod bieżącą w widoku głębi. */
    fun backTarget(): Uri? = active().navStack.lastOrNull()?.dir

    /** Ile poziomów „wstecz” ma bieżący folder — głębokość warstwy. */
    fun navDepth(): Int = active().navStack.size

    fun paste() {
        val clip = _clipboard.value ?: return
        val target = writableDir() ?: return
        val fromArchive = clip.items.all { ArchiveRepository.isArchive(it.uri) }
        enqueue(
            kind = when {
                fromArchive -> OperationKind.EXTRACT
                clip.cut -> OperationKind.MOVE
                else -> OperationKind.COPY
            },
            items = clip.items,
            target = target
        )
        if (clip.cut) _clipboard.value = null
    }

    fun pauseOperation(id: String) = engine.pause(id)
    fun resumeOperation(id: String) = engine.resume(id)
    /**
     * Rozpakowywanie przed przerwaniem wstrzymujemy i pytamy — przerwanie
     * usuwa wszystko, co zdążyło się wypakować.
     */
    fun cancelOperation(id: String) {
        val operation = engine.operations.value.firstOrNull { it.id == id }
        if (operation?.kind == OperationKind.EXTRACT && operation.status in setOf(OperationStatus.RUNNING, OperationStatus.PAUSED)) {
            val wasPaused = operation.status == OperationStatus.PAUSED
            if (!wasPaused) engine.pause(id)
            _cancelRequest.value = CancelRequest(id, wasPaused)
        } else {
            engine.cancel(id)
        }
    }

    fun confirmCancel() {
        val request = _cancelRequest.value ?: return
        _cancelRequest.value = null
        engine.cancel(request.operationId)
    }

    fun dismissCancel() {
        val request = _cancelRequest.value ?: return
        _cancelRequest.value = null
        if (!request.wasPaused) engine.resume(request.operationId)
    }
    fun clearFinishedOperations() = engine.clearFinished()
    fun clearOperationHistory() = engine.clearHistory()
    fun resolveConflict(decision: ConflictDecision) = engine.resolveConflict(decision)

    fun createArchive(items: List<FileItem>, name: String, format: ArchiveFormat, password: String?) {
        val parent = writableDir() ?: return
        if (items.isEmpty()) return
        launchOperation(success = text(R.string.notice_zip_created)) {
            repo.createArchive(items, parent, name, format, password?.takeIf { it.isNotEmpty() }?.toCharArray())
            refresh()
        }
    }

    fun isArchive(uri: Uri?): Boolean = ArchiveRepository.isArchive(uri)

    /** Bieżący folder, o ile da się w nim zapisywać — wnętrze archiwum nie. */
    private fun writableDir(): Uri? {
        val dir = _currentDir.value ?: return null
        if (ArchiveRepository.isArchive(dir)) {
            _error.value = text(R.string.err_archive_read_only)
            return null
        }
        return dir
    }

    /** Wchodzi do archiwum jak do folderu. */
    fun openArchive(item: FileItem, inSecondary: Boolean = false) {
        val root = repo.archives.rootOf(item.uri, item.name)
        viewModelScope.launch {
            _busy.value = true
            try {
                withContext(Dispatchers.IO) { repo.archives.ensureReadable(root) }
                val folder = FileItem(root, item.name, DIRECTORY_MIME, 0, 0, true)
                if (inSecondary) openDirectoryInSecondary(folder) else openDirectory(folder)
            } catch (e: ArchivePasswordException) {
                askPassword(root, e.wrong) { openArchive(item, inSecondary) }
            } catch (e: Exception) {
                _error.value = e.message ?: text(R.string.err_archive_format)
            } finally {
                _busy.value = false
            }
        }
    }

    /**
     * Rozpakowuje całe archiwum do folderu o jego nazwie — obok archiwum,
     * a gdy jesteśmy w jego wnętrzu, do folderu, z którego do niego weszliśmy.
     * [toOtherPane] kieruje je do drugiego panelu.
     */
    fun extractArchive(item: FileItem, toOtherPane: Boolean = false) {
        val target = if (toOtherPane) {
            secondary()?.currentDir ?: run { _error.value = text(R.string.err_pane_no_folder); return }
        } else {
            val pane = active()
            (listOfNotNull(pane.currentDir) + pane.navStack.reversed().mapNotNull { it.dir })
                .firstOrNull { !ArchiveRepository.isArchive(it) }
                ?: run { _error.value = text(R.string.err_archive_read_only); return }
        }
        if (ArchiveRepository.isArchive(target)) {
            _error.value = text(R.string.err_archive_read_only)
            return
        }
        val root = if (ArchiveRepository.isArchive(item.uri) && ArchiveRepository.innerPath(item.uri).isEmpty()) item.uri
        else repo.archives.rootOf(item.uri, item.name)
        val name = ArchiveFormats.baseName(repo.archives.archiveName(root) ?: item.name)
        viewModelScope.launch {
            // Spis tar.xz czy tar.gz wymaga zdekompresowania całego archiwum —
            // przy dużym archiwum to dziesiątki sekund, więc pokazujemy zajętość.
            _busy.value = true
            try {
                withContext(Dispatchers.IO) { repo.archives.ensureReadable(root) }
                enqueue(OperationKind.EXTRACT, listOf(FileItem(root, name, DIRECTORY_MIME, 0, 0, true)), target)
            } catch (e: ArchivePasswordException) {
                askPassword(root, e.wrong) { extractArchive(item, toOtherPane) }
            } catch (e: Exception) {
                _error.value = e.message ?: text(R.string.err_archive_format)
            } finally {
                _busy.value = false
            }
        }
    }

    /** Wypakowuje zaznaczone wpisy archiwum — tam, skąd weszliśmy do archiwum, albo do drugiego panelu. */
    fun extractItems(items: List<FileItem>, toOtherPane: Boolean = false) {
        if (items.isEmpty()) return
        val target = if (toOtherPane) {
            secondary()?.currentDir ?: run { _error.value = text(R.string.err_pane_no_folder); return }
        } else {
            active().navStack.reversed().mapNotNull { it.dir }.firstOrNull { !ArchiveRepository.isArchive(it) }
                ?: run { _error.value = text(R.string.err_archive_read_only); return }
        }
        if (ArchiveRepository.isArchive(target)) {
            _error.value = text(R.string.err_archive_read_only)
            return
        }
        enqueue(OperationKind.EXTRACT, items, target)
    }

    /** Plik archiwum, w którym leży [uri]. */
    fun archiveSource(uri: Uri): Uri? = repo.archives.sourceOf(uri)

    private fun askPassword(uri: Uri, wrong: Boolean, retry: () -> Unit) {
        val root = Uri.Builder().scheme(ArchiveRepository.SCHEME).authority(uri.authority).path("/").build()
        _passwordRequest.value = ArchivePasswordRequest(root, repo.archives.archiveName(root).orEmpty(), wrong, retry)
    }

    fun submitArchivePassword(password: String) {
        val request = _passwordRequest.value ?: return
        _passwordRequest.value = null
        repo.archives.setPassword(request.root, password.toCharArray())
        request.retry()
    }

    fun dismissArchivePassword() {
        val request = _passwordRequest.value ?: return
        _passwordRequest.value = null
        // Spis zaszyfrowanego 7z bez hasła jest pusty — nie zostajemy w nim.
        if (ArchiveRepository.isArchive(active().currentDir) && active().currentDir?.authority == request.root.authority) goBack()
    }

    fun sortMode(tab: Tab, dir: Uri?): SortMode =
        prefs.sortMode(sortKey(tab, dir)) ?: defaultSortFor(dir.takeIf { tab == Tab.FILES })

    /** Wybór równy domyślnemu nie jest zapisywany, żeby nie puchły preferencje. */
    fun saveSortMode(tab: Tab, dir: Uri?, mode: SortMode) {
        val default = defaultSortFor(dir.takeIf { tab == Tab.FILES })
        prefs.setSortMode(sortKey(tab, dir), mode.takeIf { it != default })
    }

    fun addRecent(item: FileItem) {
        prefs.saveMeta(item)
        prefs.addRecent(item.uri)
        loadSavedCollections()
    }

    fun toggleFavorite(item: FileItem) {
        prefs.saveMeta(item)
        prefs.toggleFavorite(item.uri)
        loadSavedCollections()
    }

    fun toggleFavorites(items: List<FileItem>) {
        items.forEach { prefs.saveMeta(it); prefs.toggleFavorite(it.uri) }
        loadSavedCollections()
    }

    suspend fun readText(uri: Uri) = repo.readText(uri)

    /** Wczytuje plik do czytnika: kodowanie, obcięcie i spis treści. */
    suspend fun readDocument(item: FileItem): TextDocument = withContext(Dispatchers.IO) {
        val bytes = repo.readBytes(item.uri, TextReader.DEFAULT_LIMIT)
        if (TextReader.looksBinary(bytes)) throw pl.arma.viewer.text.NotTextException()
        TextReader.read(bytes, totalBytes = item.size, limit = TextReader.DEFAULT_LIMIT)
    }
    suspend fun writeText(uri: Uri, text: String) = repo.writeText(uri, text)
    suspend fun sha256(uri: Uri) = repo.sha256(uri)

    suspend fun materializeUri(item: FileItem): Uri = withContext(Dispatchers.IO) {
        if (item.uri.scheme == "content") return@withContext item.uri
        val cache = File(getApplication<Application>().cacheDir, "remote_files").apply { mkdirs() }
        val safe = item.name.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(90).ifBlank { "file" }
        val file = File(cache, "${item.uri.toString().hashCode()}_$safe")
        repo.openInput(item.uri).use { input -> file.outputStream().use { output -> input.copyTo(output) } }
        getApplication<ArmaFilesApp>().cacheJanitor.trimAsync()
        FileProvider.getUriForFile(
            getApplication(),
            "${getApplication<Application>().packageName}.files",
            file
        )
    }

    suspend fun decodeImage(item: FileItem, targetMaxSide: Int = 2400): Bitmap = withContext(Dispatchers.IO) {
        val file = preparePreviewFile(item, "image_preview")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(file)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val sourceWidth = info.size.width.coerceAtLeast(1)
                val sourceHeight = info.size.height.coerceAtLeast(1)
                val maxSide = maxOf(sourceWidth, sourceHeight)
                if (maxSide > targetMaxSide) {
                    val ratio = targetMaxSide.toFloat() / maxSide.toFloat()
                    decoder.setTargetSize(
                        (sourceWidth * ratio).toInt().coerceAtLeast(1),
                        (sourceHeight * ratio).toInt().coerceAtLeast(1)
                    )
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > targetMaxSide * 2) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
            BitmapFactory.decodeFile(file.absolutePath, options)
                ?: throw IllegalArgumentException(text(R.string.err_image_decode))
        }
    }

    suspend fun prepareImageUri(item: FileItem): Uri = withContext(Dispatchers.IO) {
        if (item.uri.scheme == "content" || item.uri.scheme == "file") return@withContext item.uri
        val file = preparePreviewFile(item, "image_preview")
        FileProvider.getUriForFile(getApplication(), "${getApplication<Application>().packageName}.files", file)
    }

    private fun preparePreviewFile(item: FileItem, folderName: String): File {
        if (item.uri.scheme == "file") return File(item.uri.path ?: error(text(R.string.err_no_path)))
        val cache = File(getApplication<Application>().cacheDir, folderName).apply { mkdirs() }
        val safe = item.name.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(90).ifBlank { "preview.bin" }
        val signature = "${item.uri}|${item.size}|${item.lastModified}".hashCode()
        val file = File(cache, "${signature}_$safe")
        val metadataKnown = item.size > 0 && item.lastModified > 0
        if (!metadataKnown || !file.exists() || file.length() == 0L || (item.size > 0 && file.length() != item.size)) {
            val temp = File(cache, "${file.name}.part")
            runCatching { temp.delete() }
            repo.openInput(item.uri).use { input -> temp.outputStream().buffered().use { output -> input.copyTo(output) } }
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
            getApplication<ArmaFilesApp>().cacheJanitor.trimAsync()
        } else {
            // Czas użycia, nie pobrania — limit wieku liczy się od ostatniego otwarcia.
            file.setLastModified(System.currentTimeMillis())
        }
        return file
    }

    /** Plik lokalny dla przeglądarki PDF/CAD; z sieci pobierany do pamięci podręcznej. */
    suspend fun prepareDocument(item: FileItem): File = withContext(Dispatchers.IO) {
        preparePreviewFile(item, "doc_preview")
    }

    /**
     * Nadpisuje plik treścią z [source] (np. PDF z dodanymi komentarzami).
     * Najpierw kopia do kosza nie jest potrzebna — przeglądarka zapisuje tylko
     * na wyraźne polecenie, a dokument zachowuje wszystko poza dodanymi adnotacjami.
     */
    suspend fun replaceContent(item: FileItem, source: File) = withContext(Dispatchers.IO) {
        repo.openOutput(item.uri).use { output -> source.inputStream().use { it.copyTo(output) } }
        withContext(Dispatchers.Main) { refresh() }
    }

    fun refreshDeviceLocations() {
        _allFilesAccess.value = hasBroadStorageAccess()
        val context = getApplication<Application>()
        val storageManager = context.getSystemService(StorageManager::class.java)
        val roots = linkedMapOf<String, DeviceStorage>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            storageManager.storageVolumes.forEachIndexed { index, volume ->
                val dir = volume.directory ?: return@forEachIndexed
                if (!dir.exists()) return@forEachIndexed
                val stat = runCatching { StatFs(dir.absolutePath) }.getOrNull()
                val total = stat?.totalBytes ?: 0L
                val free = stat?.availableBytes ?: 0L
                val label = when {
                    volume.isPrimary -> text(R.string.storage_internal)
                    volume.isRemovable -> volume.getDescription(context).ifBlank { text(R.string.storage_sd) }
                    else -> volume.getDescription(context).ifBlank { text(R.string.storage_n, index + 1) }
                }
                roots[dir.absolutePath] = DeviceStorage(
                    id = dir.absolutePath,
                    label = label,
                    rootUri = Uri.fromFile(dir),
                    totalBytes = total,
                    freeBytes = free,
                    removable = volume.isRemovable,
                    usb = volume.isRemovable && isUsbVolume(volume.getDescription(context))
                )
            }
        } else {
            val candidates = context.getExternalFilesDirs(null).mapNotNull { appDir ->
                var root: File? = appDir
                repeat(4) { root = root?.parentFile }
                root
            }
            candidates.distinctBy { it.absolutePath }.forEachIndexed { index, dir ->
                val stat = runCatching { StatFs(dir.absolutePath) }.getOrNull()
                roots[dir.absolutePath] = DeviceStorage(
                    id = dir.absolutePath,
                    label = if (index == 0) text(R.string.storage_internal) else text(R.string.storage_sd),
                    rootUri = Uri.fromFile(dir),
                    totalBytes = stat?.totalBytes ?: 0L,
                    freeBytes = stat?.availableBytes ?: 0L,
                    removable = index != 0
                )
            }
        }

        if (roots.isEmpty()) {
            val dir = Environment.getExternalStorageDirectory()
            val stat = runCatching { StatFs(dir.absolutePath) }.getOrNull()
            roots[dir.absolutePath] = DeviceStorage(
                id = dir.absolutePath,
                label = text(R.string.storage_internal),
                rootUri = Uri.fromFile(dir),
                totalBytes = stat?.totalBytes ?: 0L,
                freeBytes = stat?.availableBytes ?: 0L,
                removable = false
            )
        }
        val previous = _deviceStorages.value
        _deviceStorages.value = roots.values.toList()
        syncPanes()
        onVolumesChanged(previous, _deviceStorages.value)

        loadPinned()
    }

    /**
     * System nie mówi wprost, że nośnik to pendrive — zostaje opis woluminu,
     * który dla dysków USB zawiera „USB” w każdym języku („Dysk USB SanDisk”).
     */
    private fun isUsbVolume(description: String): Boolean = description.contains("USB", ignoreCase = true)

    /**
     * Po włożeniu nośnika — krótka informacja; po wyjęciu karty otwarte na nim
     * są zamykane, bo każda operacja kończyłaby się błędem odczytu.
     */
    private fun onVolumesChanged(previous: List<DeviceStorage>, current: List<DeviceStorage>) {
        if (previous.isEmpty()) return
        val currentIds = current.map { it.id }.toSet()
        val removed = previous.filter { it.id !in currentIds }
        val added = current.filter { storage -> previous.none { it.id == storage.id } }
        added.firstOrNull()?.let { _notice.value = text(R.string.notice_volume_mounted, it.label) }
        if (removed.isEmpty()) return

        fun onRemoved(uri: Uri?): Boolean {
            val path = uri?.takeIf { it.scheme == "file" }?.path ?: return false
            return removed.any { path == it.id || path.startsWith(it.id + "/") }
        }
        for (pane in panes.toList()) {
            if (!onRemoved(pane.currentDir) && !onRemoved(pane.root)) continue
            if (panes.size > 1) {
                closeTab(pane.id)
            } else {
                pane.currentDir = null
                pane.root = null
                pane.navStack.clear()
                pane.files = emptyList()
            }
        }
        forgetPlaces(::onRemoved)
        if (onRemoved(_clipboard.value?.items?.firstOrNull()?.uri)) _clipboard.value = null
        _searchResults.value = _searchResults.value.filterNot { onRemoved(it.uri) }
        syncPanes()
        _notice.value = text(R.string.notice_volume_removed, removed.first().label)
    }

    /**
     * Indeks pamięta pliki z pendrive'a także po jego wyjęciu; w wynikach
     * pokazujemy tylko to, co leży na podłączonych nośnikach.
     */
    private fun onPresentVolume(uri: Uri): Boolean {
        val path = uri.takeIf { it.scheme == "file" }?.path ?: return true
        return _deviceStorages.value.any { path == it.id || path.startsWith(it.id + "/") }
    }

    /** Włożenie i wyjęcie karty SD albo pendrive'a, gdy aplikacja jest otwarta. */
    private val volumeWatcher: Any? = run {
        val context = getApplication<Application>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            object : StorageManager.StorageVolumeCallback() {
                override fun onStateChanged(volume: android.os.storage.StorageVolume) {
                    viewModelScope.launch { refreshDeviceLocations() }
                }
            }.also { callback ->
                context.getSystemService(StorageManager::class.java)
                    .registerStorageVolumeCallback(context.mainExecutor, callback)
            }
        } else {
            object : android.content.BroadcastReceiver() {
                override fun onReceive(c: android.content.Context, intent: android.content.Intent) {
                    refreshDeviceLocations()
                }
            }.also { receiver ->
                val filter = android.content.IntentFilter().apply {
                    addAction(android.content.Intent.ACTION_MEDIA_MOUNTED)
                    addAction(android.content.Intent.ACTION_MEDIA_UNMOUNTED)
                    addAction(android.content.Intent.ACTION_MEDIA_REMOVED)
                    addAction(android.content.Intent.ACTION_MEDIA_BAD_REMOVAL)
                    addDataScheme("file")
                }
                context.registerReceiver(receiver, filter)
            }
        }
    }

    override fun onCleared() {
        val context = getApplication<Application>()
        when (val watcher = volumeWatcher) {
            is android.content.BroadcastReceiver -> runCatching { context.unregisterReceiver(watcher) }
            null -> Unit
            else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching {
                    context.getSystemService(StorageManager::class.java)
                        .unregisterStorageVolumeCallback(watcher as StorageManager.StorageVolumeCallback)
                }
            }
        }
        super.onCleared()
    }

    private val _pinnedStats = MutableStateFlow<Map<String, Pair<Int, Long>>>(emptyMap())
    /** Liczba plików i zajętość przypiętych folderów, prosto z indeksu. */
    val pinnedStats: StateFlow<Map<String, Pair<Int, Long>>> = _pinnedStats.asStateFlow()

    private fun loadPinnedStats() {
        viewModelScope.launch {
            val stats = withContext(Dispatchers.IO) {
                // Zero przy braku indeksu wyglądało jak pusty folder; wtedy
                // karta pokazuje ścieżkę.
                _pinnedFolders.value.associate { folder ->
                    val uri = folder.rootUri.toString()
                    uri to (index.countUnder(uri) to index.bytesUnder(uri))
                }.filterValues { (count, _) -> count > 0 }
            }
            _pinnedStats.value = stats
        }
    }

    private fun loadPinned() {
        val stored = migratePicturesToCamera(
            prefs.pinned() ?: defaultPinned().also { defaults ->
                prefs.setPinned(defaults.map { it.rootUri.toString() to it.label })
            }.map { it.rootUri.toString() to it.label }
        )

        _pinnedFolders.value = stored.map { (uri, label) ->
            val parsed = Uri.parse(uri)
            PinnedFolder(
                id = uri,
                label = displayPinnedLabel(uri, label),
                rootUri = parsed,
                subtitle = parsed.path ?: label
            )
        }
        loadPinnedStats()
    }

    /**
     * Kafelek „Zdjęcia" wskazywał wcześniej katalog Pictures. Użytkownicy z
     * poprzednich wersji mają go zapisanego, więc przestawiamy go raz na
     * zdjęcia z aparatu — ale tylko wtedy, gdy nadal wskazuje stare miejsce
     * i nie został przez nich świadomie zmieniony.
     */
    private fun migratePicturesToCamera(stored: List<Pair<String, String>>): List<Pair<String, String>> {
        val pictures = Uri.fromFile(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        ).toString()
        if (stored.none { it.first == pictures && it.second == PHOTOS_KEY }) return stored

        val camera = defaultPinned().first { it.label == PHOTOS_KEY }
        val migrated = stored.map { entry ->
            if (entry.first == pictures && entry.second == PHOTOS_KEY) {
                camera.rootUri.toString() to camera.label
            } else {
                entry
            }
        }
        prefs.setPinned(migrated)
        return migrated
    }

    /**
     * Domyślne kafelki zapisane są pod polskimi nazwami z pierwszych wersji —
     * to klucze, nie napisy; na ekranie pokazujemy je w języku aplikacji.
     */
    private fun displayPinnedLabel(uri: String, label: String): String {
        val default = defaultPinned().firstOrNull { it.rootUri.toString() == uri && it.label == label } ?: return label
        return when (default.label) {
            DOWNLOADS_KEY -> text(R.string.pinned_downloads)
            PHOTOS_KEY -> text(R.string.pinned_photos)
            else -> label
        }
    }

    private fun defaultPinned(): List<PinnedFolder> {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        // „Zdjęcia" to zdjęcia z aparatu, czyli DCIM/Camera. Katalog Pictures
        // zbiera głównie obrazy zapisane przez inne aplikacje, więc jako
        // domyślne miejsce po kliknięciu był mylący. Gdy producent nie tworzy
        // podkatalogu Camera, wracamy do samego DCIM.
        val dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
        val camera = File(dcim, "Camera").takeIf { it.isDirectory } ?: dcim
        return listOf(
            PinnedFolder(downloads.absolutePath, DOWNLOADS_KEY, Uri.fromFile(downloads), downloads.absolutePath),
            PinnedFolder(camera.absolutePath, PHOTOS_KEY, Uri.fromFile(camera), camera.absolutePath)
        )
    }

    /** Przypina bieżący katalog albo wskazany folder do ekranu „Moje urządzenie". */
    fun pinFolder(item: FileItem) {
        val current = prefs.pinned().orEmpty()
        val uri = item.uri.toString()
        if (current.any { it.first == uri }) {
            _notice.value = text(R.string.notice_already_pinned)
            return
        }
        prefs.setPinned(current + (uri to item.name))
        loadPinned()
        _notice.value = text(R.string.notice_pinned, item.name)
    }

    fun unpinFolder(id: String) {
        prefs.setPinned(prefs.pinned().orEmpty().filterNot { it.first == id })
        loadPinned()
    }

    /** Zmiana kolejności przypiętych kafelków po przeciągnięciu. */
    fun movePinned(fromIndex: Int, toIndex: Int) {
        val current = prefs.pinned().orEmpty()
        val reordered = Reorder.move(current, fromIndex, toIndex)
        if (reordered === current) return
        prefs.setPinned(reordered)
        loadPinned()
    }

    private fun hasBroadStorageAccess(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return Environment.isExternalStorageManager()
        val context = getApplication<Application>()
        val read = ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val write = ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
        return read && write
    }

    fun clearError() { _error.value = null }
    fun clearNotice() { _notice.value = null }

    fun isRemote(uri: Uri): Boolean = RemoteRepository.isRemoteScheme(uri.scheme)

    fun itemFromUri(uri: Uri): FileItem? {
        val name = repo.displayName(uri) ?: uri.lastPathSegment ?: text(R.string.common_file)
        val mime = getApplication<Application>().contentResolver.getType(uri) ?: guessMime(name)
        // Plik otwarty z innej aplikacji: bez rozmiaru nagłówek podglądu pokazywał „0 B”.
        val (size, modified) = repo.localSizeAndDate(uri)
        return FileItem(uri, name, mime, size, modified, mime == DIRECTORY_MIME)
    }

    private fun updateSources() {
        val remoteAccounts = accounts.all().also { _remoteAccounts.value = it }
        val localTree = prefs.treeUri
        val googleTree = networkPrefs.cloudTree(SourceType.GOOGLE_DRIVE)
        val oneDriveTree = networkPrefs.cloudTree(SourceType.ONEDRIVE)
        val dropboxTree = networkPrefs.cloudTree(SourceType.DROPBOX)

        fun doc(tree: Uri?): Uri? = tree?.let { runCatching { repo.treeToDocumentUri(it) }.getOrNull() }
        _sources.value = listOf(
            // Nazwa podpiętego folderu: „Folder lokalny” bywa folderem z Dropboxa
            // czy Drive’a, a sam opis „przez SAF” nie mówił, którym.
            StorageSource(SourceType.LOCAL, text(R.string.source_local_folder), true, localTree != null, doc(localTree), if (localTree != null) attachedLabel(localTree) else text(R.string.source_local_folder_hint)),
            StorageSource(SourceType.GOOGLE_DRIVE, "Google Drive", networkPrefs.isEnabled(SourceType.GOOGLE_DRIVE), googleTree != null, doc(googleTree), attachedLabel(googleTree)),
            StorageSource(SourceType.ONEDRIVE, "OneDrive", networkPrefs.isEnabled(SourceType.ONEDRIVE), oneDriveTree != null, doc(oneDriveTree), attachedLabel(oneDriveTree)),
            StorageSource(SourceType.DROPBOX, "Dropbox", networkPrefs.isEnabled(SourceType.DROPBOX), dropboxTree != null, doc(dropboxTree), attachedLabel(dropboxTree)),
        ) + remoteAccounts.map { account ->
            StorageSource(
                type = if (account.type == RemoteType.SMB) SourceType.SMB else SourceType.FTP,
                label = account.label.ifBlank { account.type.name },
                enabled = account.enabled,
                configured = account.configured,
                rootUri = repo.remoteRoot(account),
                subtitle = if (account.configured) account.summary else text(R.string.source_not_configured),
                accountId = account.id
            )
        }
    }

    /**
     * Nazwa faktycznie podpiętego katalogu zamiast samego „Podłączony" —
     * dzięki temu widać, czy wybrano cały dysk („Mój dysk"), czy jeden
     * podfolder. Sam SAF nie pozwala zapytać dostawcy o listę korzeni, więc
     * to, co da się podpiąć, zależy od aplikacji dostawcy.
     */
    private fun attachedLabel(tree: Uri?): String {
        if (tree == null) return text(R.string.source_pick_folder)
        val name = runCatching { repo.displayName(repo.treeToDocumentUri(tree)) }.getOrNull()
        return name?.takeIf { it.isNotBlank() } ?: text(R.string.source_attached)
    }

    /** Uruchamia budowanie indeksu dla wszystkich nośników urządzenia. */
    fun buildIndex() {
        if (!hasBroadStorageAccess()) {
            _error.value = text(R.string.err_index_needs_access)
            return
        }
        val roots = _deviceStorages.value.map { it.rootUri }
        if (roots.isEmpty()) {
            _error.value = text(R.string.err_index_no_storage)
            return
        }
        indexer.start(roots)
    }

    fun cancelIndexing() = indexer.cancel()

    fun setGlobalSearch(enabled: Boolean) {
        _globalSearch.value = enabled
        if (!enabled) {
            searchJob?.cancel()
            lastQuery = ""
            _searchStatus.value = SearchStatus(null)
            _searchResults.value = emptyList()
        }
    }

    /** Stan trwającego wyszukiwania: gdzie właśnie szukamy (null = koniec). */
    data class SearchStatus(val where: String?, val liveLocal: Boolean = false)

    private val _searchStatus = MutableStateFlow(SearchStatus(null))
    val searchStatus: StateFlow<SearchStatus> = _searchStatus.asStateFlow()

    private val _searchRemote = MutableStateFlow(prefs.searchRemote)
    val searchRemote: StateFlow<Boolean> = _searchRemote.asStateFlow()

    private var searchJob: Job? = null
    private var lastQuery = ""

    fun setSearchRemote(enabled: Boolean) {
        _searchRemote.value = enabled
        prefs.searchRemote = enabled
        if (lastQuery.isNotBlank()) searchEverywhere(lastQuery)
    }

    /**
     * Szuka w całym urządzeniu, a nie tylko w bieżącym katalogu. Z indeksem
     * odpowiedź jest natychmiastowa; bez niego pliki są przeglądane na żywo
     * (wolniej, ale indeks nie jest warunkiem). Opcjonalnie przeszukuje też
     * włączone zasoby sieciowe — folder po folderze, z limitami, żeby wielki
     * NAS nie zajmował wyszukiwania w nieskończoność.
     */
    fun searchEverywhere(query: String) {
        searchJob?.cancel()
        lastQuery = query
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            _searchStatus.value = SearchStatus(null)
            return
        }
        searchJob = viewModelScope.launch {
            delay(250) // pisanie kolejnych liter anuluje poprzednie szukanie
            val matcher = NameMatcher(query)
            val found = java.util.Collections.synchronizedList(mutableListOf<FileItem>())
            var lastPublish = 0L
            fun publish(force: Boolean = false) {
                val now = System.currentTimeMillis()
                if (force || now - lastPublish > 300) {
                    lastPublish = now
                    _searchResults.value = synchronized(found) { found.toList() }
                }
            }
            try {
                val indexed = withContext(Dispatchers.IO) { runCatching { index.stats().files }.getOrDefault(0) }
                if (indexed > 0) {
                    found += withContext(Dispatchers.IO) { index.search(query) }.map(::toFileItem).filter { onPresentVolume(it.uri) }
                    publish(force = true)
                } else {
                    _searchStatus.value = SearchStatus(text(R.string.search_status_device), liveLocal = true)
                    withContext(Dispatchers.IO) { searchLocal(matcher, found) { publish() } }
                    publish(force = true)
                }
                if (_searchRemote.value) {
                    _remoteAccounts.value.filter { it.enabled }.forEach { account ->
                        _searchStatus.value = SearchStatus(account.label)
                        withContext(Dispatchers.IO) { searchRemote(repo.remoteRoot(account), matcher, found) { publish() } }
                        publish(force = true)
                    }
                }
            } finally {
                publish(force = true)
                if (searchJob == coroutineContext[Job]) _searchStatus.value = SearchStatus(null)
            }
        }
    }

    private suspend fun searchLocal(matcher: NameMatcher, found: MutableList<FileItem>, onFound: () -> Unit) {
        val context = kotlin.coroutines.coroutineContext
        _deviceStorages.value.mapNotNull { it.rootUri.path?.let(::File) }.forEach { root ->
            root.walkTopDown()
                .onEnter { dir -> context.isActive && !dir.name.startsWith(TrashStore.TRASH_DIR) && dir.path != "${root.path}/Android/data" && dir.path != "${root.path}/Android/obb" }
                .forEach { file ->
                    context.ensureActive()
                    if (found.size >= SEARCH_LIMIT) return
                    if (file != root && matcher.matches(file.name)) {
                        found += FileItem(
                            Uri.fromFile(file), file.name,
                            if (file.isDirectory) DIRECTORY_MIME else guessMime(file.name),
                            if (file.isFile) file.length() else 0L, file.lastModified(), file.isDirectory
                        )
                        onFound()
                    }
                }
        }
    }

    private suspend fun searchRemote(root: Uri, matcher: NameMatcher, found: MutableList<FileItem>, onFound: () -> Unit) {
        val context = kotlin.coroutines.coroutineContext
        val started = System.currentTimeMillis()
        val queue = ArrayDeque(listOf(root))
        var visited = 0
        while (queue.isNotEmpty() && visited < REMOTE_SEARCH_DIRS && found.size < SEARCH_LIMIT &&
            System.currentTimeMillis() - started < REMOTE_SEARCH_MS
        ) {
            context.ensureActive()
            val dir = queue.removeFirst()
            visited++
            val children = runCatching { repo.listChildren(dir) }.getOrNull() ?: continue
            children.forEach { child ->
                if (matcher.matches(child.name)) {
                    found += child
                    onFound()
                }
                if (child.isDirectory && child.name != TrashStore.TRASH_DIR) queue += child.uri
            }
        }
    }

    /** Gdzie leży wynik: ścieżka w pamięci albo nazwa konta i ścieżka na serwerze. */
    fun searchLocation(item: FileItem): String {
        val parent = item.uri.path.orEmpty().substringBeforeLast('/')
        if (RemoteRepository.isRemoteScheme(item.uri.scheme)) {
            val label = _remoteAccounts.value.firstOrNull { it.id == item.uri.authority }?.label ?: item.uri.authority.orEmpty()
            return "$label: ${parent.ifEmpty { "/" }}"
        }
        return parent.removePrefix("/storage/emulated/0")
    }

    /** Kategoria licząca się dla całej pamięci, nie tylko dla otwartego katalogu. */
    fun browseCategory(category: IndexCategory) {
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) { index.byCategory(category) }
            _searchResults.value = found.map(::toFileItem).filter { onPresentVolume(it.uri) }
            _globalSearch.value = true
        }
    }

    fun analyzeStorage() {
        _analyzing.value = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val monthsAgo = System.currentTimeMillis() - 180L * 24 * 60 * 60 * 1000
                StorageAnalysis(
                    usageByCategory = index.usageByCategory(),
                    stats = index.stats(),
                    largestFiles = index.largestFiles(30).map(::toFileItem).filter { onPresentVolume(it.uri) },
                    largestFolders = index.largestFolders(20).filter { onPresentVolume(Uri.parse(it.parentUri)) },
                    oldFiles = index.olderThan(monthsAgo, 30).map(::toFileItem).filter { onPresentVolume(it.uri) }
                )
            }
            _analysis.value = result
            _analyzing.value = false
        }
    }

    /**
     * Szuka duplikatów: najpierw po rozmiarze, potem porównując SHA-256.
     * Sam rozmiar to za mało na potwierdzenie, a liczenie skrótu wszystkiego
     * byłoby nieproporcjonalnie drogie — stąd dwa etapy.
     */
    fun findDuplicates() {
        _analyzing.value = true
        viewModelScope.launch {
            val sets = withContext(Dispatchers.IO) {
                index.duplicateCandidates(minSize = 4096, limit = 60).flatMap { group ->
                    val byHash = mutableMapOf<String, MutableList<FileItem>>()
                    group.files.forEach { candidate ->
                        val item = toFileItem(candidate)
                        val hash = runCatching { repo.sha256Blocking(item.uri) }.getOrNull() ?: return@forEach
                        byHash.getOrPut(hash) { mutableListOf() } += item
                    }
                    byHash.values
                        .filter { it.size > 1 }
                        .map { DuplicateSet(size = group.size, files = it) }
                }
            }
            _duplicates.value = sets
            _analyzing.value = false
        }
    }

    private fun toFileItem(entry: IndexedFile) = FileItem(
        uri = Uri.parse(entry.uri),
        name = entry.name,
        mimeType = if (entry.isDirectory) DIRECTORY_MIME else entry.mime,
        size = entry.size,
        lastModified = entry.modified,
        isDirectory = entry.isDirectory
    )

    /** Otwiera katalog, w którym leży wskazany plik z wyników wyszukiwania. */
    fun revealParent(item: FileItem) {
        val parent = item.uri.toString().substringBeforeLast('/')
        _globalSearch.value = false
        _searchResults.value = emptyList()
        if (RemoteRepository.isRemoteScheme(item.uri.scheme)) openRemoteFolder(Uri.parse(parent))
        else openLocalLocation(Uri.parse(parent))
    }

    private fun loadSavedCollections() {
        _favoriteUris.value = prefs.favorites()
        viewModelScope.launch {
            _recentFiles.value = prefs.recents()
                .filter(::isSavedUriSourceEnabled)
                .mapNotNull { savedItem(it) }
            _favoriteFiles.value = prefs.favorites()
                .map(Uri::parse)
                .filter(::isSavedUriSourceEnabled)
                .mapNotNull { savedItem(it) }
            prefs.pruneMeta()
            fillMissingMeta()
        }
    }

    /** Wpisy z serwera albo z archiwum nie wymagają połączenia — mamy zapisane metadane. */
    private fun needsNetwork(uri: Uri) = RemoteRepository.isRemoteScheme(uri.scheme) || ArchiveRepository.isArchive(uri)

    private suspend fun savedItem(uri: Uri): FileItem? =
        if (needsNetwork(uri)) prefs.meta(uri) ?: FileItem(uri, uri.lastPathSegment ?: uri.toString(), guessMime(uri.lastPathSegment.orEmpty()), 0, 0, false)
        else runCatching { repo.itemInfo(uri) }.getOrNull()

    private var metaMigrated = false

    /**
     * Wpisy zapisane przed zapamiętywaniem metadanych — raz, w tle, pytamy
     * o nie serwer, żeby folder nie udawał pliku. Później już nigdy.
     */
    private fun fillMissingMeta() {
        if (metaMigrated) return
        metaMigrated = true
        val missing = (prefs.recents() + prefs.favorites().map(Uri::parse)).distinct()
            .filter { needsNetwork(it) && prefs.meta(it) == null && isSavedUriSourceEnabled(it) }
        if (missing.isEmpty()) return
        viewModelScope.launch {
            var found = false
            missing.forEach { uri ->
                runCatching { repo.itemInfo(uri) }.getOrNull()?.let { prefs.saveMeta(it); found = true }
            }
            if (found) loadSavedCollections()
        }
    }

    private fun isSavedUriSourceEnabled(uri: Uri): Boolean {
        // Wpis w Ostatnich/Ulubionych należy do konkretnego konta, więc pytamy
        // o to konto, a nie o „czy FTP jest w ogóle włączony". Konto usunięte
        // oznacza wpis do ukrycia.
        if (RemoteRepository.isRemoteScheme(uri.scheme)) {
            return accounts.get(uri.authority)?.enabled == true
        }
        if (uri.scheme != "content") return true

        val cloudTypes = listOf(SourceType.GOOGLE_DRIVE, SourceType.ONEDRIVE, SourceType.DROPBOX)
        for (type in cloudTypes) {
            val tree = networkPrefs.cloudTree(type) ?: continue
            val sameTree = runCatching {
                uri.authority == tree.authority &&
                    DocumentsContract.getTreeDocumentId(uri) == DocumentsContract.getTreeDocumentId(tree)
            }.getOrDefault(false)
            if (sameTree) return networkPrefs.isEnabled(type)
        }
        return true
    }

    private fun launchOperation(success: String? = null, block: suspend () -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            try {
                block()
                if (success != null) _notice.value = success
            } catch (e: Exception) {
                _error.value = e.message ?: text(R.string.err_operation_failed)
            } finally {
                _busy.value = false
            }
        }
    }

    private companion object {
        const val SEARCH_LIMIT = 500
        const val REMOTE_SEARCH_DIRS = 3_000
        const val REMOTE_SEARCH_MS = 90_000L
    }
}
