package pl.arma.files

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FileRepository(private val context: Context) {
    private val resolver: ContentResolver get() = context.contentResolver

    suspend fun listChildren(parent: Uri): List<FileItem> = withContext(Dispatchers.IO) {
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
                val id = c.getString(idIx)
                val mime = c.getString(mimeIx) ?: "application/octet-stream"
                out += FileItem(
                    uri = DocumentsContract.buildDocumentUriUsingTree(parent, id),
                    name = c.getString(nameIx) ?: "(bez nazwy)",
                    mimeType = mime,
                    size = if (c.isNull(sizeIx)) 0 else c.getLong(sizeIx),
                    lastModified = if (c.isNull(dateIx)) 0 else c.getLong(dateIx),
                    isDirectory = mime == DIRECTORY_MIME
                )
            }
        }
        out
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

    suspend fun readText(uri: Uri, maxBytes: Int = 2_000_000): String = withContext(Dispatchers.IO) {
        resolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(maxBytes)
            val count = input.read(buffer)
            if (count <= 0) "" else buffer.copyOf(count).toString(Charsets.UTF_8)
        } ?: ""
    }

    suspend fun writeText(uri: Uri, text: String) = withContext(Dispatchers.IO) {
        resolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(text) }
    }

    suspend fun copyRecursively(source: FileItem, targetParent: Uri): Uri? = withContext(Dispatchers.IO) {
        copyInternal(source, targetParent)
    }

    private fun copyInternal(source: FileItem, targetParent: Uri): Uri? {
        return if (source.isDirectory) {
            val newDir = DocumentsContract.createDocument(resolver, targetParent, DIRECTORY_MIME, source.name)
                ?: return null
            val children = listChildrenBlocking(source.uri)
            children.forEach { child ->
                if (copyInternal(child, newDir) == null) {
                    throw java.io.IOException("Nie udało się skopiować: ${child.name}")
                }
            }
            newDir
        } else {
            val newFile = DocumentsContract.createDocument(resolver, targetParent, source.mimeType, source.name)
                ?: return null
            val input = resolver.openInputStream(source.uri)
                ?: throw java.io.IOException("Nie można odczytać: ${source.name}")
            val output = resolver.openOutputStream(newFile, "w")
                ?: throw java.io.IOException("Nie można zapisać: ${source.name}")
            input.use { from -> output.use { to -> from.copyTo(to) } }
            newFile
        }
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
            while (c.moveToNext()) {
                val mime = c.getString(2) ?: "application/octet-stream"
                out += FileItem(
                    uri = DocumentsContract.buildDocumentUriUsingTree(parent, c.getString(0)),
                    name = c.getString(1) ?: "(bez nazwy)",
                    mimeType = mime,
                    size = if (c.isNull(3)) 0 else c.getLong(3),
                    lastModified = if (c.isNull(4)) 0 else c.getLong(4),
                    isDirectory = mime == DIRECTORY_MIME
                )
            }
        }
        return out
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
}
