package pl.arma.files

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class FileManagerViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = FileRepository(app)
    private val prefs = StoragePrefs(app)

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
        prefs.treeUri?.let { tree ->
            runCatching {
                val doc = repo.treeToDocumentUri(tree)
                _root.value = doc
                _currentDir.value = doc
                refresh()
            }.onFailure { _error.value = "Zapamiętana lokalizacja nie jest już dostępna." }
        }
    }

    fun setTree(tree: Uri) {
        runCatching {
            repo.persistTreePermission(tree)
            prefs.treeUri = tree
            val rootDoc = repo.treeToDocumentUri(tree)
            _root.value = rootDoc
            _currentDir.value = rootDoc
            navStack.clear()
            refresh()
        }.onFailure { _error.value = it.message ?: "Nie udało się uzyskać dostępu." }
    }

    fun refresh() {
        val dir = _currentDir.value ?: return
        viewModelScope.launch {
            runCatching { repo.listChildren(dir) }
                .onSuccess { _files.value = it }
                .onFailure { _error.value = "Nie można odczytać katalogu: ${it.message}" }
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

    fun clearClipboard() {
        _clipboard.value = null
    }

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

    fun clearError() { _error.value = null }
    fun clearNotice() { _notice.value = null }

    fun itemFromUri(uri: Uri): FileItem? {
        val name = repo.displayName(uri) ?: uri.lastPathSegment ?: "plik"
        val mime = getApplication<Application>().contentResolver.getType(uri) ?: guessMime(name)
        return FileItem(uri, name, mime, 0, 0, mime == DIRECTORY_MIME)
    }

    private fun loadSavedCollections() {
        _favoriteUris.value = prefs.favorites()
        viewModelScope.launch {
            _recentFiles.value = prefs.recents().mapNotNull { runCatching { repo.itemInfo(it) }.getOrNull() }
            _favoriteFiles.value = prefs.favorites().map(Uri::parse).mapNotNull { runCatching { repo.itemInfo(it) }.getOrNull() }
        }
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

    private fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "txt", "log", "ini", "cfg", "conf", "kt", "kts", "java", "c", "cpp", "h", "hpp", "py", "sh" -> "text/plain"
        "md", "markdown" -> "text/markdown"
        "json" -> "application/json"
        "yaml", "yml" -> "application/yaml"
        "xml" -> "application/xml"
        "svg" -> "image/svg+xml"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "pdf" -> "application/pdf"
        "zip" -> "application/zip"
        "html", "htm" -> "text/html"
        "css" -> "text/css"
        "js" -> "text/javascript"
        else -> "application/octet-stream"
    }
}
