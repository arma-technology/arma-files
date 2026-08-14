package pl.arma.files

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
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

    private val _deviceStorages = MutableStateFlow<List<DeviceStorage>>(emptyList())
    val deviceStorages: StateFlow<List<DeviceStorage>> = _deviceStorages.asStateFlow()

    private val _pinnedFolders = MutableStateFlow<List<PinnedFolder>>(emptyList())
    val pinnedFolders: StateFlow<List<PinnedFolder>> = _pinnedFolders.asStateFlow()

    private val _allFilesAccess = MutableStateFlow(hasBroadStorageAccess())
    val allFilesAccess: StateFlow<Boolean> = _allFilesAccess.asStateFlow()

    private val navStack = ArrayDeque<Uri>()

    init {
        loadSavedCollections()
        updateSources()
        refreshDeviceLocations()
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
        val previous = navStack.removeLastOrNull()
        if (previous != null) {
            _currentDir.value = previous
            refresh()
            return true
        }
        if (_currentDir.value != null) {
            goComputerHome()
            return true
        }
        return false
    }

    fun goComputerHome() {
        navStack.clear()
        _files.value = emptyList()
        _currentDir.value = null
        _root.value = null
        _currentSource.value = SourceType.LOCAL
        refreshDeviceLocations()
    }

    fun openLocalLocation(uri: Uri) {
        if (uri.scheme == "file" && !hasBroadStorageAccess()) {
            _error.value = "ARMA Files potrzebuje dostępu do wszystkich plików, aby otworzyć pamięć urządzenia lub kartę SD."
            return
        }
        _currentSource.value = SourceType.LOCAL
        _root.value = uri
        _currentDir.value = uri
        navStack.clear()
        refresh()
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
            repo.createFolder(parent, validatedName(name)) ?: error("Nie udało się utworzyć folderu.")
            refresh()
        }
    }

    fun createFile(name: String) {
        val parent = _currentDir.value ?: return
        launchOperation(success = "Utworzono plik") {
            val safe = validatedName(name)
            repo.createFile(parent, safe, guessMime(safe)) ?: error("Nie udało się utworzyć pliku.")
            refresh()
        }
    }

    fun rename(item: FileItem, name: String) {
        launchOperation(success = "Zmieniono nazwę") {
            val newUri = repo.rename(item, validatedName(name))
            if (newUri != null) prefs.replaceUri(item.uri, newUri)
            refresh()
            loadSavedCollections()
        }
    }

    private fun validatedName(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isBlank()) error("Nazwa nie może być pusta.")
        if (trimmed == "." || trimmed == "..") error("Nieprawidłowa nazwa.")
        if (trimmed.any { it == '/' || it == '\\' }) error("Nazwa nie może zawierać \"/\" ani \"\\\".")
        return trimmed
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
                ?: throw IllegalArgumentException("Android nie potrafi zdekodować tego obrazu.")
        }
    }

    suspend fun prepareImageUri(item: FileItem): Uri = withContext(Dispatchers.IO) {
        if (item.uri.scheme == "content" || item.uri.scheme == "file") return@withContext item.uri
        val file = preparePreviewFile(item, "image_preview")
        FileProvider.getUriForFile(getApplication(), "${getApplication<Application>().packageName}.files", file)
    }

    private fun preparePreviewFile(item: FileItem, folderName: String): File {
        if (item.uri.scheme == "file") return File(item.uri.path ?: error("Brak ścieżki pliku."))
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

    suspend fun preparePdf(item: FileItem): File = withContext(Dispatchers.IO) {
        preparePreviewFile(item, "pdf_preview")
    }

    suspend fun pdfPageCount(file: File): Int = withContext(Dispatchers.IO) {
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = try {
            PdfRenderer(descriptor)
        } catch (error: Throwable) {
            runCatching { descriptor.close() }
            throw error
        }
        renderer.use { it.pageCount }
    }

    suspend fun renderPdfPage(file: File, pageIndex: Int, targetWidth: Int = 1600): Bitmap = withContext(Dispatchers.IO) {
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = try {
            PdfRenderer(descriptor)
        } catch (error: Throwable) {
            runCatching { descriptor.close() }
            throw error
        }
        renderer.use { pdf ->
            require(pageIndex in 0 until pdf.pageCount) { "Nieprawidłowy numer strony PDF." }
            pdf.openPage(pageIndex).use { page ->
                val width = targetWidth.coerceAtLeast(720)
                val pageWidth = page.width.coerceAtLeast(1)
                val height = (width.toFloat() * page.height / pageWidth).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(AndroidColor.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            }
        }
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
                    volume.isPrimary -> "Pamięć wbudowana"
                    volume.isRemovable -> volume.getDescription(context).ifBlank { "Karta pamięci" }
                    else -> volume.getDescription(context).ifBlank { "Pamięć ${index + 1}" }
                }
                roots[dir.absolutePath] = DeviceStorage(
                    id = dir.absolutePath,
                    label = label,
                    rootUri = Uri.fromFile(dir),
                    totalBytes = total,
                    freeBytes = free,
                    removable = volume.isRemovable
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
                    label = if (index == 0) "Pamięć wbudowana" else "Karta pamięci",
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
                label = "Pamięć wbudowana",
                rootUri = Uri.fromFile(dir),
                totalBytes = stat?.totalBytes ?: 0L,
                freeBytes = stat?.availableBytes ?: 0L,
                removable = false
            )
        }
        _deviceStorages.value = roots.values.toList()

        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        _pinnedFolders.value = listOf(
            PinnedFolder("downloads", "Pobrane", Uri.fromFile(downloads), downloads.absolutePath),
            PinnedFolder("pictures", "Zdjęcia", Uri.fromFile(pictures), pictures.absolutePath)
        )
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
            StorageSource(SourceType.LOCAL, "Folder lokalny", true, localTree != null, doc(localTree), "Dostęp przez Android SAF"),
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
