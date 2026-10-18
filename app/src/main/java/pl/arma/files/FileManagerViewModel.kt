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
import kotlinx.coroutines.flow.MutableStateFlow
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
import pl.arma.files.ops.OperationKind
import pl.arma.files.ops.OperationRecord
import pl.arma.files.ops.OperationStatus
import pl.arma.files.ops.PendingConflict
import pl.arma.files.ops.Reorder
import pl.arma.files.ops.TrashEntry
import pl.arma.files.text.TextDocument
import pl.arma.files.text.TextReader

/** Nazwy domyślnych kafelków zapisane w ustawieniach od pierwszych wersji. */
private const val DOWNLOADS_KEY = "Pobrane"
private const val PHOTOS_KEY = "Zdjęcia"

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

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _clipboard = MutableStateFlow<ClipboardPayload?>(null)
    val clipboard: StateFlow<ClipboardPayload?> = _clipboard.asStateFlow()

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
        val navStack = ArrayDeque<Uri>()
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
    }

    /** Otwiera nową kartę na ekranie „Mój komputer" i przełącza się na nią. */
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

    /** Przypina katalog otwarty w karcie do ekranu „Mój komputer". */
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
        enqueue(if (move) OperationKind.MOVE else OperationKind.COPY, items, target)
    }

    fun openDirectoryInSecondary(item: FileItem) {
        val pane = secondary() ?: return
        pane.currentDir?.let(pane.navStack::addLast)
        pane.currentDir = item.uri
        refreshPane(pane)
    }

    fun secondaryGoBack() {
        val pane = secondary() ?: return
        val previous = pane.navStack.removeLastOrNull() ?: return
        pane.currentDir = previous
        refreshPane(pane)
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
        pane.source = source.type
        pane.root = rootUri
        pane.currentDir = rootUri
        pane.navStack.clear()
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
        pane.source = type
        pane.root = rootUri
        pane.currentDir = rootUri
        pane.navStack.clear()
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
                .onSuccess { pane.files = it }
                .onFailure { _error.value = text(R.string.err_cannot_read_folder, it.message.orEmpty()) }
            _busy.value = false
            syncPanes()
        }
    }

    fun openDirectory(item: FileItem) {
        val pane = active()
        pane.currentDir?.let(pane.navStack::addLast)
        pane.currentDir = item.uri
        refreshPane(pane)
    }

    fun goBack(): Boolean {
        val pane = active()
        val previous = pane.navStack.removeLastOrNull()
        if (previous != null) {
            pane.currentDir = previous
            refreshPane(pane)
            return true
        }
        if (pane.currentDir != null) {
            goComputerHome()
            return true
        }
        return false
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
        pane.source = SourceType.LOCAL
        pane.root = uri
        pane.currentDir = uri
        pane.navStack.clear()
        refreshPane(pane)
    }

    /** Skok do wskazanego członu ścieżki z paska nawigacji. */
    fun openPath(uri: Uri) {
        val pane = active()
        if (pane.currentDir == uri) return
        pane.currentDir?.let(pane.navStack::addLast)
        pane.currentDir = uri
        refreshPane(pane)
    }

    fun goRoot() {
        val pane = active()
        pane.root?.let {
            pane.currentDir = it
            pane.navStack.clear()
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
            if (newUri != null) prefs.replaceUri(item.uri, newUri)
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
                OperationKind.COPY -> {
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
            _clipboard.value = ClipboardPayload(items.distinctBy { it.uri.toString() }, cut)
            _notice.value = if (cut) text(R.string.notice_selected_move, items.size) else text(R.string.notice_copied, items.size)
        }
    }

    fun clearClipboard() { _clipboard.value = null }

    fun paste() {
        val clip = _clipboard.value ?: return
        val target = _currentDir.value ?: return
        enqueue(
            kind = if (clip.cut) OperationKind.MOVE else OperationKind.COPY,
            items = clip.items,
            target = target
        )
        if (clip.cut) _clipboard.value = null
    }

    fun pauseOperation(id: String) = engine.pause(id)
    fun resumeOperation(id: String) = engine.resume(id)
    fun cancelOperation(id: String) = engine.cancel(id)
    fun clearFinishedOperations() = engine.clearFinished()
    fun clearOperationHistory() = engine.clearHistory()
    fun resolveConflict(decision: ConflictDecision) = engine.resolveConflict(decision)

    fun createArchive(items: List<FileItem>, name: String) {
        val parent = _currentDir.value ?: return
        if (items.isEmpty()) return
        launchOperation(success = text(R.string.notice_zip_created)) {
            repo.createZip(items, parent, name)
            refresh()
        }
    }

    fun extractArchive(item: FileItem) {
        val parent = _currentDir.value ?: return
        launchOperation(success = text(R.string.notice_unzipped, item.name)) {
            repo.extractZip(item, parent)
            refresh()
        }
    }

    fun sortMode(tab: Tab, dir: Uri?): SortMode =
        prefs.sortMode(sortKey(tab, dir)) ?: defaultSortFor(dir.takeIf { tab == Tab.FILES })

    /** Wybór równy domyślnemu nie jest zapisywany, żeby nie puchły preferencje. */
    fun saveSortMode(tab: Tab, dir: Uri?, mode: SortMode) {
        val default = defaultSortFor(dir.takeIf { tab == Tab.FILES })
        prefs.setSortMode(sortKey(tab, dir), mode.takeIf { it != default })
    }

    fun addRecent(uri: Uri) {
        prefs.addRecent(uri)
        loadSavedCollections()
    }

    fun toggleFavorite(uri: Uri) {
        prefs.toggleFavorite(uri)
        loadSavedCollections()
    }

    fun toggleFavorites(items: List<FileItem>) {
        items.forEach { prefs.toggleFavorite(it.uri) }
        loadSavedCollections()
    }

    suspend fun readText(uri: Uri) = repo.readText(uri)

    /** Wczytuje plik do czytnika: kodowanie, obcięcie i spis treści. */
    suspend fun readDocument(item: FileItem): TextDocument = withContext(Dispatchers.IO) {
        val bytes = repo.readBytes(item.uri, TextReader.DEFAULT_LIMIT)
        if (TextReader.looksBinary(bytes)) {
            throw IllegalArgumentException(text(R.string.err_not_text))
        }
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

    /** Przypina bieżący katalog albo wskazany folder do ekranu „Mój komputer". */
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
            StorageSource(SourceType.LOCAL, text(R.string.source_local_folder), true, localTree != null, doc(localTree), text(R.string.source_local_folder_hint)),
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
        if (!enabled) _searchResults.value = emptyList()
    }

    /** Szuka w całym zindeksowanym urządzeniu, a nie tylko w bieżącym katalogu. */
    fun searchEverywhere(query: String) {
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            return
        }
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) { index.search(query) }
            _searchResults.value = found.map(::toFileItem).filter { onPresentVolume(it.uri) }
        }
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
        openLocalLocation(Uri.parse(parent))
    }

    private fun loadSavedCollections() {
        _favoriteUris.value = prefs.favorites()
        viewModelScope.launch {
            _recentFiles.value = prefs.recents()
                .filter(::isSavedUriSourceEnabled)
                .mapNotNull { runCatching { repo.itemInfo(it) }.getOrNull() }
            _favoriteFiles.value = prefs.favorites()
                .map(Uri::parse)
                .filter(::isSavedUriSourceEnabled)
                .mapNotNull { runCatching { repo.itemInfo(it) }.getOrNull() }
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
}
