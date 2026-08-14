package pl.arma.files

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class FileRepository(private val context: Context) {
    private val resolver: ContentResolver get() = context.contentResolver
    private val networkPrefs = NetworkPrefs(context)
    private val remote = RemoteRepository(networkPrefs)

    suspend fun listChildren(parent: Uri): List<FileItem> = withContext(Dispatchers.IO) {
        listChildrenBlocking(parent)
    }

    fun treeToDocumentUri(treeUri: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))

    suspend fun createFolder(parent: Uri, name: String): Uri? = withContext(Dispatchers.IO) {
        createFolderBlocking(parent, name)
    }

    suspend fun createFile(parent: Uri, name: String, mime: String = "text/plain"): Uri? = withContext(Dispatchers.IO) {
        createFileBlocking(parent, name, mime)
    }

    suspend fun rename(item: FileItem, newName: String): Uri? = withContext(Dispatchers.IO) {
        when {
            remote.isRemote(item.uri) -> remote.rename(item, newName)
            item.uri.scheme == "file" -> {
                val source = fileFor(item.uri)
                val target = File(source.parentFile ?: return@withContext null, newName)
                if (source.renameTo(target)) Uri.fromFile(target) else null
            }
            else -> DocumentsContract.renameDocument(resolver, item.uri, newName)
        }
    }

    suspend fun delete(item: FileItem): Boolean = withContext(Dispatchers.IO) { deleteBlocking(item) }

    suspend fun deleteAll(items: List<FileItem>): Int = withContext(Dispatchers.IO) {
        var deleted = 0
        items.forEach { if (runCatching { deleteBlocking(it) }.getOrDefault(false)) deleted++ }
        deleted
    }

    suspend fun readText(uri: Uri, maxBytes: Int = 2_000_000): String = withContext(Dispatchers.IO) {
        openInput(uri).use { input ->
            val out = StringBuilder()
            val buffer = ByteArray(32 * 1024)
            var total = 0
            while (total < maxBytes) {
                val wanted = minOf(buffer.size, maxBytes - total)
                val count = input.read(buffer, 0, wanted)
                if (count <= 0) break
                out.append(buffer.copyOf(count).toString(Charsets.UTF_8))
                total += count
            }
            out.toString()
        }
    }

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
                ?: throw IOException("Nie udało się skopiować: ${source.name}")
        }
        created
    }

    suspend fun createZip(items: List<FileItem>, targetParent: Uri, archiveName: String): Uri = withContext(Dispatchers.IO) {
        val safeName = archiveName.trim().let { if (it.endsWith(".zip", true)) it else "$it.zip" }
        val zipUri = createFileBlocking(targetParent, safeName, "application/zip")
            ?: throw IOException("Nie udało się utworzyć archiwum.")
        try {
            openOutput(zipUri).use { raw ->
                ZipOutputStream(BufferedOutputStream(raw)).use { zip ->
                    items.forEach { addToZip(zip, it, "") }
                }
            }
            zipUri
        } catch (e: Exception) {
            runCatching { itemInfoBlocking(zipUri)?.let(::deleteBlocking) }
            throw e
        }
    }

    suspend fun extractZip(zipItem: FileItem, targetParent: Uri): Uri = withContext(Dispatchers.IO) {
        val base = zipItem.name.substringBeforeLast('.', zipItem.name).ifBlank { "Rozpakowane" }
        val outputRoot = createFolderBlocking(targetParent, base)
            ?: throw IOException("Nie udało się utworzyć katalogu docelowego.")
        val directories = mutableMapOf("" to outputRoot)

        openInput(zipItem.uri).use { raw ->
            ZipInputStream(BufferedInputStream(raw)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val cleanPath = sanitizeZipPath(entry.name)
                    if (cleanPath.isNotBlank()) {
                        if (entry.isDirectory) {
                            ensureDirectoryPath(outputRoot, cleanPath.trimEnd('/'), directories)
                        } else {
                            val parentPath = cleanPath.substringBeforeLast('/', "")
                            val fileName = cleanPath.substringAfterLast('/')
                            val parent = ensureDirectoryPath(outputRoot, parentPath, directories)
                            val outUri = createFileBlocking(parent, fileName, guessMime(fileName))
                                ?: throw IOException("Nie udało się utworzyć: $fileName")
                            openOutput(outUri).use { out -> zip.copyTo(out) }
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        outputRoot
    }

    suspend fun sha256(uri: Uri): String = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        openInput(uri).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    suspend fun itemInfo(uri: Uri): FileItem? = withContext(Dispatchers.IO) { itemInfoBlocking(uri) }

    fun displayName(uri: Uri): String? {
        if (remote.isRemote(uri)) return uri.lastPathSegment
        if (uri.scheme == "file") return fileFor(uri).name
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
        return resolver.query(uri, projection, null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }

    fun persistTreePermission(treeUri: Uri) {
        val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
        val readWrite = read or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            resolver.takePersistableUriPermission(treeUri, readWrite)
        } catch (_: SecurityException) {
            resolver.takePersistableUriPermission(treeUri, read)
        }
    }

    fun remoteRoot(type: SourceType): Uri? = when (type) {
        SourceType.FTP -> RemoteRepository.ftpRoot()
        SourceType.SMB -> RemoteRepository.smbRoot()
        else -> null
    }

    fun openInput(uri: Uri): InputStream = when {
        remote.isRemote(uri) -> remote.openInput(uri)
        uri.scheme == "file" -> FileInputStream(fileFor(uri))
        else -> resolver.openInputStream(uri) ?: throw IOException("Nie można otworzyć pliku do odczytu.")
    }

    fun openOutput(uri: Uri): OutputStream = when {
        remote.isRemote(uri) -> remote.openOutput(uri)
        uri.scheme == "file" -> FileOutputStream(fileFor(uri), false)
        else -> resolver.openOutputStream(uri, "w") ?: throw IOException("Nie można otworzyć pliku do zapisu.")
    }

    private fun deleteBlocking(item: FileItem): Boolean = when {
        remote.isRemote(item.uri) -> remote.delete(item)
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
                if (copyInternal(child, newDir) == null) throw IOException("Nie udało się skopiować: ${child.name}")
            }
            newDir
        } else {
            val newFile = createFileBlocking(targetParent, source.name, source.mimeType) ?: return null
            openInput(source.uri).use { from -> openOutput(newFile).use { to -> from.copyTo(to) } }
            newFile
        }
    }

    private fun addToZip(zip: ZipOutputStream, item: FileItem, prefix: String) {
        val entryName = if (prefix.isBlank()) item.name else "$prefix/${item.name}"
        if (item.isDirectory) {
            zip.putNextEntry(ZipEntry("$entryName/"))
            zip.closeEntry()
            listChildrenBlocking(item.uri).forEach { child -> addToZip(zip, child, entryName) }
        } else {
            zip.putNextEntry(ZipEntry(entryName))
            openInput(item.uri).use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }

    private fun sanitizeZipPath(path: String): String {
        val normalized = path.replace('\\', '/').trimStart('/')
        val parts = normalized.split('/').filter { it.isNotBlank() && it != "." }
        if (parts.any { it == ".." }) throw IOException("Archiwum zawiera niebezpieczną ścieżkę.")
        return parts.joinToString("/") + if (normalized.endsWith('/')) "/" else ""
    }

    private fun ensureDirectoryPath(root: Uri, path: String, cache: MutableMap<String, Uri>): Uri {
        if (path.isBlank()) return root
        var current = root
        var built = ""
        path.split('/').filter { it.isNotBlank() }.forEach { part ->
            built = if (built.isBlank()) part else "$built/$part"
            current = cache[built] ?: createFolderBlocking(current, part)
                ?.also { cache[built] = it }
                ?: throw IOException("Nie udało się utworzyć folderu: $part")
        }
        return current
    }

    private fun createFolderBlocking(parent: Uri, name: String): Uri? = when {
        remote.isRemote(parent) -> remote.createFolder(parent, name)
        parent.scheme == "file" -> {
            val child = uniqueChild(fileFor(parent), name, directory = true)
            if (child.mkdirs()) Uri.fromFile(child) else null
        }
        else -> DocumentsContract.createDocument(resolver, parent, DIRECTORY_MIME, name)
    }

    private fun createFileBlocking(parent: Uri, name: String, mime: String): Uri? = when {
        remote.isRemote(parent) -> remote.createFile(parent, name)
        parent.scheme == "file" -> {
            val child = uniqueChild(fileFor(parent), name, directory = false)
            child.parentFile?.mkdirs()
            if (child.createNewFile()) Uri.fromFile(child) else null
        }
        else -> DocumentsContract.createDocument(resolver, parent, mime, name)
    }

    private fun listChildrenBlocking(parent: Uri): List<FileItem> {
        if (remote.isRemote(parent)) return remote.listChildren(parent)
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
                    name = c.getString(nameIx) ?: "(bez nazwy)",
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
        if (remote.isRemote(uri)) return remote.itemInfo(uri)
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

    private fun fileFor(uri: Uri): File = File(uri.path ?: throw IOException("Nieprawidłowa ścieżka lokalna."))

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
