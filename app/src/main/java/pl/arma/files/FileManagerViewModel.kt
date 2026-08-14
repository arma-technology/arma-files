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

    private val _currentDir = MutableStateFlow<Uri?>(null)
    val currentDir: StateFlow<Uri?> = _currentDir.asStateFlow()

    private val _root = MutableStateFlow<Uri?>(null)
    val root: StateFlow<Uri?> = _root.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    var clipboard: ClipboardItem? = null
        private set

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
        viewModelScope.launch {
            runCatching { repo.createFolder(parent, name.trim()) }
                .onSuccess { refresh() }
                .onFailure { _error.value = "Nie udało się utworzyć folderu." }
        }
    }

    fun createFile(name: String) {
        val parent = _currentDir.value ?: return
        val mime = guessMime(name)
        viewModelScope.launch {
            runCatching { repo.createFile(parent, name.trim(), mime) }
                .onSuccess { refresh() }
                .onFailure { _error.value = "Nie udało się utworzyć pliku." }
        }
    }

    fun rename(item: FileItem, name: String) {
        viewModelScope.launch {
            runCatching { repo.rename(item, name.trim()) }
                .onSuccess { newUri ->
                    if (newUri != null) prefs.replaceUri(item.uri, newUri)
                    refresh()
                    loadSavedCollections()
                }
                .onFailure { _error.value = "Nie udało się zmienić nazwy." }
        }
    }

    fun delete(item: FileItem) {
        viewModelScope.launch {
            runCatching { repo.delete(item) }
                .onSuccess {
                    refresh()
                    loadSavedCollections()
                }
                .onFailure { _error.value = "Nie udało się usunąć elementu." }
        }
    }

    fun setClipboard(item: FileItem, cut: Boolean) {
        clipboard = ClipboardItem(item, cut)
    }

    fun paste() {
        val clip = clipboard ?: return
        val target = _currentDir.value ?: return
        viewModelScope.launch {
            runCatching {
                val copied = repo.copyRecursively(clip.item, target)
                    ?: error("Nie udało się utworzyć kopii docelowej.")
                if (clip.cut) {
                    if (!repo.delete(clip.item)) error("Kopia powstała, ale nie udało się usunąć źródła.")
                    prefs.replaceUri(clip.item.uri, copied)
                }
                copied
            }.onSuccess {
                if (clip.cut) clipboard = null
                refresh()
                loadSavedCollections()
            }.onFailure { _error.value = "Nie udało się wkleić elementu: ${it.message}" }
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

    fun favorites(): Set<String> = prefs.favorites()

    suspend fun readText(uri: Uri) = repo.readText(uri)
    suspend fun writeText(uri: Uri, text: String) = repo.writeText(uri, text)

    fun clearError() {
        _error.value = null
    }

    fun itemFromUri(uri: Uri): FileItem? {
        val name = repo.displayName(uri) ?: uri.lastPathSegment ?: "plik"
        val mime = getApplication<Application>().contentResolver.getType(uri) ?: guessMime(name)
        return FileItem(uri, name, mime, 0, 0, mime == DIRECTORY_MIME)
    }

    private fun loadSavedCollections() {
        viewModelScope.launch {
            _recentFiles.value = prefs.recents().mapNotNull { runCatching { repo.itemInfo(it) }.getOrNull() }
            _favoriteFiles.value = prefs.favorites().map(Uri::parse).mapNotNull { runCatching { repo.itemInfo(it) }.getOrNull() }
        }
    }

    private fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "txt", "log", "ini", "cfg", "conf" -> "text/plain"
        "md", "markdown" -> "text/markdown"
        "json" -> "application/json"
        "yaml", "yml" -> "application/yaml"
        "xml" -> "application/xml"
        "svg" -> "image/svg+xml"
        "html", "htm" -> "text/html"
        "css" -> "text/css"
        "js" -> "text/javascript"
        "kt", "kts", "java", "c", "cpp", "h", "hpp", "py", "sh" -> "text/plain"
        else -> "application/octet-stream"
    }
}
