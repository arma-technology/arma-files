package pl.arma.files.ops

import android.net.Uri
import pl.arma.files.DIRECTORY_MIME
import pl.arma.files.FileItem
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * System plików w pamięci. Klucz to pełny URI jako łańcuch, a relacja
 * rodzic–dziecko wynika z prefiksu ścieżki — wystarczająco, żeby sprawdzić
 * logikę silnika bez dotykania dysku.
 */
class FakeOps : FileOps {
    val files = LinkedHashMap<String, ByteArray>()
    val dirs = LinkedHashSet<String>()

    /** Gdy true, [fastMove] przenosi przez podmianę klucza, bez czytania bajtów. */
    var fastMoveSupported = false

    /** Nazwa pliku, przy którym odczyt ma wysypać operację — do testów przerwania. */
    var failReadFor: String? = null

    var bytesRead = 0L
        private set

    fun addDir(uri: String) { dirs += uri }

    fun addFile(uri: String, content: String) { files[uri] = content.toByteArray() }

    fun exists(uri: String): Boolean = uri in files || uri in dirs

    fun content(uri: String): String? = files[uri]?.toString(Charsets.UTF_8)

    private fun parentOf(uri: String): String = uri.substringBeforeLast('/')

    private fun nameOf(uri: String): String = uri.substringAfterLast('/')

    private fun itemFor(uri: String): FileItem = FileItem(
        uri = Uri.parse(uri),
        name = nameOf(uri),
        mimeType = if (uri in dirs) DIRECTORY_MIME else "application/octet-stream",
        size = files[uri]?.size?.toLong() ?: 0L,
        lastModified = 0L,
        isDirectory = uri in dirs
    )

    override suspend fun listChildren(parent: Uri): List<FileItem> {
        val prefix = parent.toString()
        return (dirs + files.keys)
            .filter { parentOf(it) == prefix }
            .map { itemFor(it) }
    }

    override suspend fun createFolder(parent: Uri, name: String): Uri? {
        val uri = "$parent/$name"
        dirs += uri
        return Uri.parse(uri)
    }

    override suspend fun createFile(parent: Uri, name: String, mime: String): Uri? {
        val uri = "$parent/$name"
        files[uri] = ByteArray(0)
        return Uri.parse(uri)
    }

    override suspend fun delete(item: FileItem): Boolean {
        val uri = item.uri.toString()
        val removed = files.remove(uri) != null || dirs.remove(uri)
        // Kasowanie katalogu usuwa też to, co w nim zostało.
        files.keys.filter { it.startsWith("$uri/") }.forEach { files.remove(it) }
        dirs.filter { it.startsWith("$uri/") }.forEach { dirs.remove(it) }
        return removed
    }

    override fun openInput(uri: Uri): InputStream {
        val key = uri.toString()
        failReadFor?.let { if (key.endsWith(it)) throw IOException("Symulowany błąd odczytu: $it") }
        val data = files[key] ?: throw IOException("Brak pliku: $key")
        bytesRead += data.size
        return ByteArrayInputStream(data)
    }

    override fun openOutput(uri: Uri): OutputStream {
        val key = uri.toString()
        return object : ByteArrayOutputStream() {
            override fun close() {
                files[key] = toByteArray()
                super.close()
            }
        }
    }

    override suspend fun fastMove(item: FileItem, targetParent: Uri, newName: String): Uri? {
        if (!fastMoveSupported) return null
        val source = item.uri.toString()
        val target = "$targetParent/$newName"
        val data = files.remove(source) ?: return null
        files[target] = data
        return Uri.parse(target)
    }
}
