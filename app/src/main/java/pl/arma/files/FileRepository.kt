package pl.arma.files

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import pl.arma.files.archive.ArchiveFormat
import pl.arma.files.archive.ArchiveRepository
import pl.arma.files.archive.ArchiveWriter
import pl.arma.files.net.AccountStore
import pl.arma.files.net.RemoteAccount
import pl.arma.files.ops.FileOps
import java.security.MessageDigest

class FileRepository(private val context: Context) : FileOps {
    private companion object {
        const val OFFLINE_PATIENCE_MS = 5_000L
    }

    private val resolver: ContentResolver get() = context.contentResolver
    private val accounts = AccountStore(context)
    private val remote = RemoteRepository(accounts, java.io.File(context.filesDir, "known_hosts"))
    val archives = ArchiveRepository(
        context,
        openSource = ::openInput,
        sourceSignature = { uri ->
            if (uri.scheme == "file") fileFor(uri).let { "${it.length()}|${it.lastModified()}" }
            else itemInfoBlocking(uri)?.let { "${it.size}|${it.lastModified}" }.orEmpty()
        },
        localFile = { uri -> if (uri.scheme == "file") fileFor(uri) else null }
    )

    /** Kopie offline; ustawiane przez aplikację zaraz po utworzeniu repozytorium. */
    @Volatile
    var offline: OfflineStore? = null

    /** Adresy, których ostatnia lista przyszła z kopii offline, a nie z serwera. */
    private val servedOffline = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun servedOffline(uri: Uri): Boolean = uri.toString() in servedOffline

    private fun networkDown(): Boolean =
        context.getSystemService(android.net.ConnectivityManager::class.java)?.activeNetwork == null

    /**
     * Serwer, a gdy nie odpowiada — kopia offline. Bez żadnej sieci od razu
     * kopia: nie ma sensu czekać na limit czasu połączenia. Gdy kopia jest,
     * a serwer milczy dłużej niż [OFFLINE_PATIENCE_MS], też pokazujemy kopię —
     * zawieszony serwer potrafi nie odpowiadać przez minutę. Odpytywanie
     * folderu wróci do serwera, gdy ten się odezwie.
     */
    private fun <T> remoteOrOffline(
        uri: Uri,
        online: () -> T,
        /** Bez limitu 5 s: spóźnione otwarcie zapisu na serwerze wyzerowałoby tam plik. */
        waitForServer: Boolean = false,
        fromCopy: (OfflineStore) -> T?
    ): T {
        val store = offline
        val hasCopy = store?.localFile(uri) != null
        fun copy(): T? = store?.let { runCatching { fromCopy(it) }.getOrNull() }
        if (hasCopy && networkDown()) {
            copy()?.let { servedOffline += uri.toString(); return it }
        }
        return try {
            val result = if (!hasCopy || waitForServer) online() else {
                val abandoned = java.util.concurrent.atomic.AtomicBoolean(false)
                val task = java.util.concurrent.FutureTask {
                    online().also { late ->
                        // Odpowiedź po czasie: kopia już pokazana, a otwarty
                        // strumień trzymałby połączenie z puli.
                        if (abandoned.get()) (late as? java.io.Closeable)?.let { runCatching { it.close() } }
                    }
                }
                Thread(task, "remote-or-offline").apply { isDaemon = true }.start()
                try {
                    task.get(OFFLINE_PATIENCE_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                } catch (e: java.util.concurrent.TimeoutException) {
                    abandoned.set(true)
                    throw e
                } catch (e: java.util.concurrent.ExecutionException) {
                    throw e.cause ?: e
                }
            }
            servedOffline -= uri.toString()
            result
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val fallback = if (hasCopy) copy() else null
            if (fallback == null) throw e
            servedOffline += uri.toString()
            fallback
        }
    }

    /** Wprost z serwera, z pominięciem kopii — dla synchronizacji offline. */
    fun remoteList(uri: Uri): List<FileItem> = remote.listChildren(uri)

    fun remoteInfo(uri: Uri): FileItem? = remote.itemInfo(uri)

    fun remoteInput(uri: Uri): InputStream = remote.openInput(uri)

    fun remoteOutput(uri: Uri): OutputStream = remote.openOutput(uri)

    fun remoteCreateFile(parent: Uri, name: String): Uri = remote.createFile(parent, name)

    fun remoteCreateFolder(parent: Uri, name: String): Uri = remote.createFolder(parent, name)

    override suspend fun listChildren(parent: Uri): List<FileItem> = withContext(Dispatchers.IO) {
        listChildrenBlocking(parent)
    }

    fun treeToDocumentUri(treeUri: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))

    override suspend fun createFolder(parent: Uri, name: String): Uri? = withContext(Dispatchers.IO) {
        createFolderBlocking(parent, name)
    }

    override suspend fun createFile(parent: Uri, name: String, mime: String): Uri? = withContext(Dispatchers.IO) {
        createFileBlocking(parent, name, mime)
    }

    /**
     * Zwraca istniejący katalog o tej nazwie albo tworzy nowy. Potrzebne dla
     * kosza: zwykłe [createFolder] dla ścieżek lokalnych omija kolizje nazw,
     * więc przy każdym usunięciu powstałby kolejny `.armafiles_trash (2)`.
     */
    suspend fun ensureFolder(parent: Uri, name: String): Uri? = withContext(Dispatchers.IO) {
        listChildrenBlocking(parent)
            .firstOrNull { it.isDirectory && it.name.equals(name, ignoreCase = true) }
            ?.uri
            ?: createFolderBlocking(parent, name)
    }

    /**
     * Przeniesienie bez przepisywania bajtów — udaje się, gdy źródło i cel leżą
     * na tym samym nośniku (lokalnie) albo na tym samym serwerze (FTP/SMB).
     * W pozostałych przypadkach zwraca null i wołający musi skopiować zawartość.
     *
     * Dla SAF zwraca null, bo `DocumentsContract.moveDocument` wymaga URI
     * katalogu źródłowego, którego model danych aplikacji dziś nie przechowuje.
     */
    override suspend fun fastMove(item: FileItem, targetParent: Uri, newName: String): Uri? =
        withContext(Dispatchers.IO) {
            when {
                remote.isRemote(item.uri) && remote.isRemote(targetParent) ->
                    runCatching { remote.move(item, targetParent, newName) }.getOrNull()

                item.uri.scheme == "file" && targetParent.scheme == "file" -> {
                    val source = fileFor(item.uri)
                    val target = File(fileFor(targetParent), newName)
                    if (target.exists()) null
                    else if (source.renameTo(target)) Uri.fromFile(target) else null
                }

                else -> null
            }
        }

    suspend fun rename(item: FileItem, newName: String): Uri? = withContext(Dispatchers.IO) {
        when {
            ArchiveRepository.isArchive(item.uri) -> archives.readOnly()
            remote.isRemote(item.uri) -> remote.rename(item, newName)
            item.uri.scheme == "file" -> {
                val source = fileFor(item.uri)
                val target = File(source.parentFile ?: return@withContext null, newName)
                if (source.renameTo(target)) Uri.fromFile(target) else null
            }
            else -> DocumentsContract.renameDocument(resolver, item.uri, newName)
        }
    }

    override suspend fun delete(item: FileItem): Boolean = withContext(Dispatchers.IO) { deleteBlocking(item) }

    suspend fun deleteAll(items: List<FileItem>): Int = withContext(Dispatchers.IO) {
        var deleted = 0
        items.forEach { if (runCatching { deleteBlocking(it) }.getOrDefault(false)) deleted++ }
        deleted
    }

    /**
     * Wczytuje początek pliku jako bajty.
     *
     * Poprzednia wersja składała tekst, dekodując każdą porcję osobno — znak
     * wielobajtowy rozcięty na granicy porcji rozpadał się wtedy na dwa znaki
     * zastępcze, co przy polskich tekstach psuło co któryś wyraz. Dekodowanie
     * należy do [pl.arma.viewer.text.TextReader], który dostaje całość naraz.
     */
    suspend fun readBytes(uri: Uri, maxBytes: Int = 2_000_000): ByteArray = withContext(Dispatchers.IO) {
        openInput(uri).use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0
            while (total < maxBytes) {
                val wanted = minOf(buffer.size, maxBytes - total)
                val count = input.read(buffer, 0, wanted)
                if (count <= 0) break
                out.write(buffer, 0, count)
                total += count
            }
            out.toByteArray()
        }
    }

    suspend fun readText(uri: Uri, maxBytes: Int = 2_000_000): String =
        pl.arma.viewer.text.TextReader.decode(readBytes(uri, maxBytes)).first

    suspend fun writeText(uri: Uri, text: String) = withContext(Dispatchers.IO) {
        openOutput(uri).bufferedWriter().use { it.write(text) }
    }

    suspend fun copyRecursively(source: FileItem, targetParent: Uri): Uri? = withContext(Dispatchers.IO) {
        copyInternal(source, targetParent)
    }

    suspend fun copyAll(sources: List<FileItem>, targetParent: Uri): List<Uri> = withContext(Dispatchers.IO) {
        val created = mutableListOf<Uri>()
        sources.forEach { source ->
            created += copyInternal(source, targetParent)
                ?: throw IOException(text(R.string.err_copy_failed, source.name))
        }
        created
    }

    /** Pakuje [items] do nowego archiwum w [targetParent]; przy błędzie nie zostawia połowy pliku. */
    suspend fun createArchive(
        items: List<FileItem>,
        targetParent: Uri,
        archiveName: String,
        format: ArchiveFormat,
        password: CharArray?
    ): Uri = withContext(Dispatchers.IO) {
        val trimmed = archiveName.trim()
        val safeName = if (trimmed.endsWith(".${format.extension}", true)) trimmed else "$trimmed.${format.extension}"
        val archiveUri = createFileBlocking(targetParent, safeName, guessMime(safeName))
            ?: throw IOException(text(R.string.err_zip_create))
        try {
            val source = object : ArchiveWriter.Source {
                override fun children(item: FileItem) = listChildrenBlocking(item.uri)
                override fun open(item: FileItem) = openInput(item.uri)
            }
            ArchiveWriter.write(format, items, source, password, File(context.cacheDir, "archive_out")) {
                openOutput(archiveUri)
            }
            archiveUri
        } catch (e: Exception) {
            runCatching { itemInfoBlocking(archiveUri)?.let(::deleteBlocking) }
            throw e
        }
    }

    suspend fun sha256(uri: Uri): String = withContext(Dispatchers.IO) { sha256Blocking(uri) }

    /** Wersja bez przełączania wątku — wołający jest już na Dispatchers.IO. */
    fun sha256Blocking(uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
        openInput(uri).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    suspend fun itemInfo(uri: Uri): FileItem? = withContext(Dispatchers.IO) { itemInfoBlocking(uri) }

    fun displayName(uri: Uri): String? {
        if (remote.isRemote(uri)) return uri.lastPathSegment
        if (ArchiveRepository.isArchive(uri)) return uri.lastPathSegment ?: archives.archiveName(uri)
        if (uri.scheme == "file") return fileFor(uri).name
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
        return resolver.query(uri, projection, null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }

    /** Rozmiar i data bez łączenia z siecią — z pliku albo od dostawcy treści; zera, gdy nieznane. */
    fun localSizeAndDate(uri: Uri): Pair<Long, Long> = runCatching {
        when {
            remote.isRemote(uri) || ArchiveRepository.isArchive(uri) -> 0L to 0L
            uri.scheme == "file" -> fileFor(uri).let { it.length() to it.lastModified() }
            else -> resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) to 0L else null
            } ?: (0L to 0L)
        }
    }.getOrDefault(0L to 0L)

    fun persistTreePermission(treeUri: Uri) {
        val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
        val readWrite = read or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            resolver.takePersistableUriPermission(treeUri, readWrite)
        } catch (_: SecurityException) {
            resolver.takePersistableUriPermission(treeUri, read)
        }
    }

    fun remoteRoot(account: RemoteAccount): Uri = RemoteRepository.rootOf(account)

    /** Obserwacja folderu na serwerze; null, gdy protokół jej nie ma. */
    fun watchRemote(uri: Uri): pl.arma.files.net.RemoteWatch? = if (remote.isRemote(uri)) remote.watch(uri) else null

    fun disconnectRemote(account: RemoteAccount) = remote.disconnect(account)

    /**
     * Sprawdza podane dane logowania, nie zapisując konta — wymaga więc
     * przekazania konta wprost, zamiast szukania go po URI.
     */
    fun probeAccount(account: RemoteAccount): Int =
        RemoteRepository({ id -> account.takeIf { it.id == id } }, java.io.File(context.filesDir, "known_hosts"))
            .listChildren(RemoteRepository.rootOf(account))
            .size

    override fun openInput(uri: Uri): InputStream = when {
        remote.isRemote(uri) -> remoteOrOffline(uri, { remote.openInput(uri) }) { store ->
            store.localFile(uri)?.takeIf { it.isFile }?.let(::FileInputStream)
        }
        ArchiveRepository.isArchive(uri) -> archives.openInput(uri)
        uri.scheme == "file" -> FileInputStream(fileFor(uri))
        else -> resolver.openInputStream(uri) ?: throw IOException(text(R.string.err_open_read))
    }

    override fun openOutput(uri: Uri): OutputStream = when {
        // Bez serwera zapis trafia do kopii offline; synchronizacja wyśle go
        // później albo zgłosi rozbieżność, jeśli plik zmienił się też na serwerze.
        remote.isRemote(uri) -> remoteOrOffline(uri, { remote.openOutput(uri) }, waitForServer = true) { store ->
            store.localFile(uri)?.takeIf { it.isFile }?.let { FileOutputStream(it, false) }
        }
        ArchiveRepository.isArchive(uri) -> archives.readOnly()
        uri.scheme == "file" -> FileOutputStream(fileFor(uri), false)
        else -> resolver.openOutputStream(uri, "w") ?: throw IOException(text(R.string.err_open_write))
    }

    private fun deleteBlocking(item: FileItem): Boolean = when {
        remote.isRemote(item.uri) -> remote.delete(item)
        ArchiveRepository.isArchive(item.uri) -> archives.readOnly()
        item.uri.scheme == "file" -> {
            val file = fileFor(item.uri)
            if (file.isDirectory) file.deleteRecursively() else file.delete()
        }
        else -> DocumentsContract.deleteDocument(resolver, item.uri)
    }

    private fun copyInternal(source: FileItem, targetParent: Uri): Uri? {
        return if (source.isDirectory) {
            val newDir = createFolderBlocking(targetParent, source.name) ?: return null
            listChildrenBlocking(source.uri).forEach { child ->
                if (copyInternal(child, newDir) == null) throw IOException(text(R.string.err_copy_failed, child.name))
            }
            newDir
        } else {
            val newFile = createFileBlocking(targetParent, source.name, source.mimeType) ?: return null
            openInput(source.uri).use { from -> openOutput(newFile).use { to -> from.copyTo(to) } }
            newFile
        }
    }

    private fun createFolderBlocking(parent: Uri, name: String): Uri? = when {
        remote.isRemote(parent) -> remote.createFolder(parent, name)
        ArchiveRepository.isArchive(parent) -> archives.readOnly()
        parent.scheme == "file" -> {
            val child = uniqueChild(fileFor(parent), name, directory = true)
            if (child.mkdirs()) Uri.fromFile(child) else null
        }
        else -> DocumentsContract.createDocument(resolver, parent, DIRECTORY_MIME, name)
    }

    private fun createFileBlocking(parent: Uri, name: String, mime: String): Uri? = when {
        remote.isRemote(parent) -> remote.createFile(parent, name)
        ArchiveRepository.isArchive(parent) -> archives.readOnly()
        parent.scheme == "file" -> {
            val child = uniqueChild(fileFor(parent), name, directory = false)
            child.parentFile?.mkdirs()
            if (child.createNewFile()) Uri.fromFile(child) else null
        }
        else -> DocumentsContract.createDocument(resolver, parent, mime, name)
    }

    private fun listChildrenBlocking(parent: Uri): List<FileItem> {
        if (remote.isRemote(parent)) return remoteOrOffline(parent, { remote.listChildren(parent) }) { it.list(parent) }
        if (ArchiveRepository.isArchive(parent)) return archives.listChildren(parent)
        if (parent.scheme == "file") {
            val dir = fileFor(parent)
            return dir.listFiles().orEmpty()
                .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
                .map(::fileItem)
        }

        val parentId = DocumentsContract.getDocumentId(parent)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(parent, parentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        val out = mutableListOf<FileItem>()
        resolver.query(childrenUri, projection, null, null, null)?.use { c ->
            val idIx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            val dateIx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            while (c.moveToNext()) {
                val mime = c.getString(mimeIx) ?: "application/octet-stream"
                out += FileItem(
                    uri = DocumentsContract.buildDocumentUriUsingTree(parent, c.getString(idIx)),
                    name = c.getString(nameIx) ?: text(R.string.common_unnamed),
                    mimeType = mime,
                    size = if (c.isNull(sizeIx)) 0 else c.getLong(sizeIx),
                    lastModified = if (c.isNull(dateIx)) 0 else c.getLong(dateIx),
                    isDirectory = mime == DIRECTORY_MIME
                )
            }
        }
        return out
    }

    private fun itemInfoBlocking(uri: Uri): FileItem? {
        if (remote.isRemote(uri)) return remoteOrOffline(uri, { remote.itemInfo(uri) }) { it.info(uri) }
        if (ArchiveRepository.isArchive(uri)) return archives.itemInfo(uri)
        if (uri.scheme == "file") return fileItem(fileFor(uri)).takeIf { fileFor(uri).exists() }

        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        return resolver.query(uri, projection, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            val name = c.getString(0) ?: uri.lastPathSegment ?: "plik"
            val mime = c.getString(1) ?: resolver.getType(uri) ?: guessMime(name)
            FileItem(
                uri = uri,
                name = name,
                mimeType = mime,
                size = if (c.isNull(2)) 0 else c.getLong(2),
                lastModified = if (c.isNull(3)) 0 else c.getLong(3),
                isDirectory = mime == DIRECTORY_MIME
            )
        }
    }

    private fun fileItem(file: File): FileItem = FileItem(
        uri = Uri.fromFile(file),
        name = file.name.ifBlank { file.absolutePath },
        mimeType = if (file.isDirectory) DIRECTORY_MIME else guessMime(file.name),
        size = if (file.isFile) file.length() else 0L,
        lastModified = file.lastModified(),
        isDirectory = file.isDirectory
    )

    private fun fileFor(uri: Uri): File = File(uri.path ?: throw IOException(text(R.string.err_local_path)))

    private fun uniqueChild(parent: File, requestedName: String, directory: Boolean): File {
        var candidate = File(parent, requestedName)
        if (!candidate.exists()) return candidate
        val base = if (directory) requestedName else requestedName.substringBeforeLast('.', requestedName)
        val ext = if (directory || !requestedName.contains('.')) "" else ".${requestedName.substringAfterLast('.')}"
        var index = 2
        while (candidate.exists()) {
            candidate = File(parent, "$base ($index)$ext")
            index++
        }
        return candidate
    }
}
