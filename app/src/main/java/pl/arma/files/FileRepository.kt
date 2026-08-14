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
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class FileRepository(private val context: Context) {
    private val resolver: ContentResolver get() = context.contentResolver

    suspend fun listChildren(parent: Uri): List<FileItem> = withContext(Dispatchers.IO) {
        listChildrenBlocking(parent)
    }

    fun treeToDocumentUri(treeUri: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))

    suspend fun createFolder(parent: Uri, name: String): Uri? = withContext(Dispatchers.IO) {
        DocumentsContract.createDocument(resolver, parent, DIRECTORY_MIME, name)
    }

    suspend fun createFile(parent: Uri, name: String, mime: String = "text/plain"): Uri? = withContext(Dispatchers.IO) {
        DocumentsContract.createDocument(resolver, parent, mime, name)
    }

    suspend fun rename(item: FileItem, newName: String): Uri? = withContext(Dispatchers.IO) {
        DocumentsContract.renameDocument(resolver, item.uri, newName)
    }

    suspend fun delete(item: FileItem): Boolean = withContext(Dispatchers.IO) {
        DocumentsContract.deleteDocument(resolver, item.uri)
    }

    suspend fun deleteAll(items: List<FileItem>): Int = withContext(Dispatchers.IO) {
        var deleted = 0
        items.forEach { if (runCatching { DocumentsContract.deleteDocument(resolver, it.uri) }.getOrDefault(false)) deleted++ }
        deleted
    }

    suspend fun readText(uri: Uri, maxBytes: Int = 2_000_000): String = withContext(Dispatchers.IO) {
        resolver.openInputStream(uri)?.use { input ->
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
        } ?: ""
    }

    suspend fun writeText(uri: Uri, text: String) = withContext(Dispatchers.IO) {
        resolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(text) }
            ?: throw IOException("Nie można otworzyć pliku do zapisu.")
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
        val zipUri = DocumentsContract.createDocument(resolver, targetParent, "application/zip", safeName)
            ?: throw IOException("Nie udało się utworzyć archiwum.")
        try {
            resolver.openOutputStream(zipUri, "w")?.use { raw ->
                ZipOutputStream(BufferedOutputStream(raw)).use { zip ->
                    items.forEach { addToZip(zip, it, "") }
                }
            } ?: throw IOException("Nie udało się otworzyć archiwum do zapisu.")
            zipUri
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, zipUri) }
            throw e
        }
    }

    suspend fun extractZip(zipItem: FileItem, targetParent: Uri): Uri = withContext(Dispatchers.IO) {
        val base = zipItem.name.substringBeforeLast('.', zipItem.name).ifBlank { "Rozpakowane" }
        val outputRoot = DocumentsContract.createDocument(resolver, targetParent, DIRECTORY_MIME, base)
            ?: throw IOException("Nie udało się utworzyć katalogu docelowego.")
        val directories = mutableMapOf("" to outputRoot)

        resolver.openInputStream(zipItem.uri)?.use { raw ->
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
                            val mime = guessMime(fileName)
                            val outUri = DocumentsContract.createDocument(resolver, parent, mime, fileName)
                                ?: throw IOException("Nie udało się utworzyć: $fileName")
                            resolver.openOutputStream(outUri, "w")?.use { out -> zip.copyTo(out) }
                                ?: throw IOException("Nie udało się zapisać: $fileName")
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } ?: throw IOException("Nie można otworzyć archiwum.")
        outputRoot
    }

    suspend fun sha256(uri: Uri): String = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        resolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        } ?: throw IOException("Nie można odczytać pliku.")
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    suspend fun itemInfo(uri: Uri): FileItem? = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        resolver.query(uri, projection, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@withContext null
            val name = c.getString(0) ?: uri.lastPathSegment ?: "plik"
            val mime = c.getString(1) ?: resolver.getType(uri) ?: "application/octet-stream"
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

    fun displayName(uri: Uri): String? {
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
        return resolver.query(uri, projection, null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }

    fun persistTreePermission(treeUri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        resolver.takePersistableUriPermission(treeUri, flags)
    }

    private fun copyInternal(source: FileItem, targetParent: Uri): Uri? {
        return if (source.isDirectory) {
            val newDir = DocumentsContract.createDocument(resolver, targetParent, DIRECTORY_MIME, source.name)
                ?: return null
            listChildrenBlocking(source.uri).forEach { child ->
                if (copyInternal(child, newDir) == null) throw IOException("Nie udało się skopiować: ${child.name}")
            }
            newDir
        } else {
            val newFile = DocumentsContract.createDocument(resolver, targetParent, source.mimeType, source.name)
                ?: return null
            resolver.openInputStream(source.uri)?.use { from ->
                resolver.openOutputStream(newFile, "w")?.use { to -> from.copyTo(to) }
                    ?: throw IOException("Nie można zapisać: ${source.name}")
            } ?: throw IOException("Nie można odczytać: ${source.name}")
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
            resolver.openInputStream(item.uri)?.use { it.copyTo(zip) }
                ?: throw IOException("Nie można odczytać: ${item.name}")
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
            current = cache[built] ?: DocumentsContract.createDocument(resolver, current, DIRECTORY_MIME, part)
                ?.also { cache[built] = it }
                ?: throw IOException("Nie udało się utworzyć folderu: $part")
        }
        return current
    }

    private fun listChildrenBlocking(parent: Uri): List<FileItem> {
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
