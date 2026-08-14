package pl.arma.files

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class FileManagerViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = FileRepository(app)
    private val prefs = StoragePrefs(app)
    private val networkPrefs = NetworkPrefs(app)

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

    private val _ftpConfig = MutableStateFlow(networkPrefs.ftp())
    val ftpConfig: StateFlow<FtpConfig> = _ftpConfig.asStateFlow()

    private val _smbConfig = MutableStateFlow(networkPrefs.smb())
    val smbConfig: StateFlow<SmbConfig> = _smbConfig.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _clipboard = MutableStateFlow<ClipboardPayload?>(null)
    val clipboard: StateFlow<ClipboardPayload?> = _clipboard.asStateFlow()

    private val navStack = ArrayDeque<Uri>()

    init {
        loadSavedCollections()
        updateSources()
        val initial = _sources.value.firstOrNull { it.type == SourceType.LOCAL && it.configured }
            ?: _sources.value.firstOrNull { it.enabled && it.configured }
        if (initial != null) openSource(initial.type)
    }

    fun setTree(tree: Uri) {
        runCatching {
            repo.persistTreePermission(tree)
            prefs.treeUri = tree
            updateSources()
            openSource(SourceType.LOCAL)
        }.onFailure { _error.value = it.message ?: "Nie udało się uzyskać dostępu." }
    }

    fun setCloudTree(type: SourceType, tree: Uri) {
        if (type !in setOf(SourceType.GOOGLE_DRIVE, SourceType.ONEDRIVE, SourceType.DROPBOX)) return
        runCatching {
            repo.persistTreePermission(tree)
            networkPrefs.setCloudTree(type, tree)
            networkPrefs.setEnabled(type, true)
            updateSources()
            openSource(type)
        }.onFailure { _error.value = it.message ?: "Nie udało się podłączyć źródła." }
    }

    fun setSourceEnabled(type: SourceType, enabled: Boolean) {
        if (type == SourceType.LOCAL) return
        networkPrefs.setEnabled(type, enabled)
        updateSources()
        loadSavedCollections()
        if (!enabled && _currentSource.value == type) {
            val fallback = _sources.value.firstOrNull { it.type == SourceType.LOCAL && it.configured }
                ?: _sources.value.firstOrNull { it.enabled && it.configured }
            if (fallback != null) openSource(fallback.type) else {
                _files.value = emptyList()
                _currentDir.value = null
                _root.value = null
            }
        }
    }

    fun saveFtp(config: FtpConfig) {
        networkPrefs.saveFtp(config)
        _ftpConfig.value = networkPrefs.ftp()
        updateSources()
    }

    fun saveSmb(config: SmbConfig) {
        networkPrefs.saveSmb(config)
        _smbConfig.value = networkPrefs.smb()
        updateSources()
    }

    fun testSource(type: SourceType) {
        val source = _sources.value.firstOrNull { it.type == type }
        val rootUri = source?.rootUri
        if (source == null || !source.configured || rootUri == null) {
            _error.value = "Najpierw skonfiguruj źródło."
            return
        }
        launchOperation {
            val count = repo.listChildren(rootUri).size
            _notice.value = "Połączenie działa • $count elementów w katalogu startowym"
        }
    }

    fun openSource(type: SourceType) {
        val source = _sources.value.firstOrNull { it.type == type } ?: return
        if (!source.enabled && type != SourceType.LOCAL) return
        val rootUri = source.rootUri
        if (!source.configured || rootUri == null) {
            _error.value = when (type) {
                SourceType.LOCAL -> "Najpierw wybierz katalog pamięci urządzenia."
                SourceType.FTP -> "Skonfiguruj połączenie FTP w Ustawieniach."
                SourceType.SMB -> "Skonfiguruj udział SMB w Ustawieniach."
                else -> "Podłącz to źródło w Ustawieniach."
            }
            return
        }
        _currentSource.value = type
        _root.value = rootUri
        _currentDir.value = rootUri
        navStack.clear()
        refresh()
    }

    fun refresh() {
        val dir = _currentDir.value ?: return
        viewModelScope.launch {
            _busy.value = true
            runCatching { repo.listChildren(dir) }
                .onSuccess { _files.value = it }
                .onFailure { _error.value = "Nie można odczytać katalogu: ${it.message}" }
            _busy.value = false
        }
    }

    fun openDirectory(item: FileItem) {
        _currentDir.value?.let(navStack::addLast)
        _currentDir.value = item.uri
        refresh()
    }

    fun goBack(): Boolean {
        val previous = navStack.removeLastOrNull() ?: return false
        _currentDir.value = previous
        refresh()
        return true
    }

    fun goRoot() {
        _root.value?.let {
            _currentDir.value = it
            navStack.clear()
            refresh()
        }
    }

    fun createFolder(name: String) {
        val parent = _currentDir.value ?: return
        launchOperation(success = "Utworzono folder") {
            repo.createFolder(parent, name.trim()) ?: error("Nie udało się utworzyć folderu.")
            refresh()
        }
    }

    fun createFile(name: String) {
        val parent = _currentDir.value ?: return
        launchOperation(success = "Utworzono plik") {
            repo.createFile(parent, name.trim(), guessMime(name)) ?: error("Nie udało się utworzyć pliku.")
            refresh()
        }
    }

    fun rename(item: FileItem, name: String) {
        launchOperation(success = "Zmieniono nazwę") {
            val newUri = repo.rename(item, name.trim())
            if (newUri != null) prefs.replaceUri(item.uri, newUri)
            refresh()
            loadSavedCollections()
        }
    }

    fun delete(items: List<FileItem>) {
        if (items.isEmpty()) return
        launchOperation(success = if (items.size == 1) "Usunięto element" else "Usunięto ${items.size} elementów") {
            val deleted = repo.deleteAll(items)
            refresh()
            loadSavedCollections()
            if (deleted != items.size) error("Usunięto $deleted z ${items.size} elementów.")
        }
    }

    fun setClipboard(items: List<FileItem>, cut: Boolean) {
        if (items.isNotEmpty()) {
            _clipboard.value = ClipboardPayload(items.distinctBy { it.uri.toString() }, cut)
            _notice.value = if (cut) "Wybrano do przeniesienia: ${items.size}" else "Skopiowano do schowka: ${items.size}"
        }
    }

    fun clearClipboard() { _clipboard.value = null }

    fun paste() {
        val clip = _clipboard.value ?: return
        val target = _currentDir.value ?: return
        launchOperation(success = if (clip.cut) "Przeniesiono ${clip.items.size} elementów" else "Wklejono ${clip.items.size} elementów") {
            val copied = repo.copyAll(clip.items, target)
            if (clip.cut) {
                clip.items.zip(copied).forEach { (source, destination) ->
                    if (!repo.delete(source)) error("Kopia powstała, ale nie udało się usunąć: ${source.name}")
                    prefs.replaceUri(source.uri, destination)
                }
                _clipboard.value = null
            }
            refresh()
            loadSavedCollections()
        }
    }

    fun createArchive(items: List<FileItem>, name: String) {
        val parent = _currentDir.value ?: return
        if (items.isEmpty()) return
        launchOperation(success = "Utworzono archiwum ZIP") {
            repo.createZip(items, parent, name)
            refresh()
        }
    }

    fun extractArchive(item: FileItem) {
        val parent = _currentDir.value ?: return
        launchOperation(success = "Rozpakowano ${item.name}") {
            repo.extractZip(item, parent)
            refresh()
        }
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
    suspend fun writeText(uri: Uri, text: String) = repo.writeText(uri, text)
    suspend fun sha256(uri: Uri) = repo.sha256(uri)

    suspend fun materializeUri(item: FileItem): Uri = withContext(Dispatchers.IO) {
        if (item.uri.scheme == "content") return@withContext item.uri
        if (item.uri.scheme == "file") return@withContext item.uri
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

    fun clearError() { _error.value = null }
    fun clearNotice() { _notice.value = null }

    fun itemFromUri(uri: Uri): FileItem? {
        val name = repo.displayName(uri) ?: uri.lastPathSegment ?: "plik"
        val mime = getApplication<Application>().contentResolver.getType(uri) ?: guessMime(name)
        return FileItem(uri, name, mime, 0, 0, mime == DIRECTORY_MIME)
    }

    private fun updateSources() {
        val ftp = networkPrefs.ftp().also { _ftpConfig.value = it }
        val smb = networkPrefs.smb().also { _smbConfig.value = it }
        val localTree = prefs.treeUri
        val googleTree = networkPrefs.cloudTree(SourceType.GOOGLE_DRIVE)
        val oneDriveTree = networkPrefs.cloudTree(SourceType.ONEDRIVE)
        val dropboxTree = networkPrefs.cloudTree(SourceType.DROPBOX)

        fun doc(tree: Uri?): Uri? = tree?.let { runCatching { repo.treeToDocumentUri(it) }.getOrNull() }
        _sources.value = listOf(
            StorageSource(SourceType.LOCAL, "Urządzenie", true, localTree != null, doc(localTree), "Pamięć lokalna"),
            StorageSource(SourceType.GOOGLE_DRIVE, "Google Drive", networkPrefs.isEnabled(SourceType.GOOGLE_DRIVE), googleTree != null, doc(googleTree), if (googleTree != null) "Podłączony" else "Wymaga wybrania folderu"),
            StorageSource(SourceType.ONEDRIVE, "OneDrive", networkPrefs.isEnabled(SourceType.ONEDRIVE), oneDriveTree != null, doc(oneDriveTree), if (oneDriveTree != null) "Podłączony" else "Wymaga wybrania folderu"),
            StorageSource(SourceType.DROPBOX, "Dropbox", networkPrefs.isEnabled(SourceType.DROPBOX), dropboxTree != null, doc(dropboxTree), if (dropboxTree != null) "Podłączony" else "Wymaga wybrania folderu"),
            StorageSource(SourceType.FTP, if (ftp.ftps) "FTPS" else "FTP", ftp.enabled, ftp.host.isNotBlank(), repo.remoteRoot(SourceType.FTP), if (ftp.host.isBlank()) "Brak konfiguracji" else "${ftp.host}:${ftp.port}"),
            StorageSource(SourceType.SMB, "SMB / Samba", smb.enabled, smb.host.isNotBlank() && smb.share.isNotBlank(), repo.remoteRoot(SourceType.SMB), if (smb.host.isBlank()) "Brak konfiguracji" else "${smb.host}/${smb.share}")
        )
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
        if (uri.scheme == RemoteRepository.FTP_SCHEME) return networkPrefs.isEnabled(SourceType.FTP)
        if (uri.scheme == RemoteRepository.SMB_SCHEME) return networkPrefs.isEnabled(SourceType.SMB)
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
                _error.value = e.message ?: "Operacja nie powiodła się."
            } finally {
                _busy.value = false
            }
        }
    }
}
