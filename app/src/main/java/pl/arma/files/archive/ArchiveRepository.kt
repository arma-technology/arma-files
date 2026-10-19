package pl.arma.files.archive

import android.content.Context
import android.net.Uri
import pl.arma.files.DIRECTORY_MIME
import pl.arma.files.FileItem
import pl.arma.files.R
import pl.arma.files.guessMime
import pl.arma.files.text
import java.io.File
import java.io.FileNotFoundException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Zawartość archiwów jako foldery tylko do odczytu.
 *
 * Adres wpisu: `armaarc://<id>/ścieżka/w/archiwum`. Identyfikator wskazuje
 * plik archiwum (lokalny, z sieci albo z innego archiwum) zapamiętany
 * w [registry], więc adres przeżywa ponowne uruchomienie — w kartach,
 * Ulubionych i Ostatnich. Archiwum spoza pamięci telefonu jest najpierw
 * kopiowane do pamięci podręcznej, bo ZIP i 7z wymagają dostępu swobodnego.
 */
class ArchiveRepository(
    context: Context,
    private val openSource: (Uri) -> InputStream,
    /** Rozmiar i data źródła — do wykrycia, że archiwum się zmieniło. */
    private val sourceSignature: (Uri) -> String,
    /** Plik lokalny, gdy źródło nim jest — wtedy bez kopiowania. */
    private val localFile: (Uri) -> File?
) {
    companion object {
        const val SCHEME = "armaarc"

        fun isArchive(uri: Uri?): Boolean = uri?.scheme == SCHEME

        /** Ścieżka wewnątrz archiwum, bez ukośników na brzegach; "" dla korzenia. */
        fun innerPath(uri: Uri): String = uri.path.orEmpty().trim('/')

        private const val OPEN_LIMIT = 4
    }

    private val registry = context.getSharedPreferences("archives", Context.MODE_PRIVATE)
    private val cacheDir = File(context.cacheDir, "archives")
    private val passwords = ConcurrentHashMap<String, CharArray>()

    private class Index(entries: List<RawEntry>) {
        class Node(val path: String, val name: String, val isDirectory: Boolean, val raw: RawEntry?)

        val nodes = LinkedHashMap<String, Node>()
        val children = HashMap<String, MutableList<Node>>()
        val encrypted = entries.any { it.encrypted }

        init {
            children[""] = mutableListOf()
            entries.forEach { add(it.path, it.isDirectory, it) }
        }

        private fun add(path: String, isDirectory: Boolean, raw: RawEntry?) {
            val existing = nodes[path]
            if (existing != null) {
                // Katalog dodany niejawnie dostaje dane z własnego wpisu, a plik
                // powtórzony (tar dopuszcza dopisywanie) — nowszą wersję.
                if (existing.isDirectory != isDirectory || raw == null) return
                if (isDirectory && existing.raw != null) return
                val node = Node(path, existing.name, isDirectory, raw)
                nodes[path] = node
                children[parentOf(path)]?.replaceAll { if (it.path == path) node else it }
                return
            }
            val parent = parentOf(path)
            if (parent.isNotEmpty() && nodes[parent]?.isDirectory != true) add(parent, true, null)
            val node = Node(path, path.substringAfterLast('/'), isDirectory, raw)
            nodes[path] = node
            children.getOrPut(parent) { mutableListOf() } += node
            if (isDirectory) children.getOrPut(path) { mutableListOf() }
        }

        private fun parentOf(path: String) = path.substringBeforeLast('/', "")
    }

    private inner class Opened(val id: String, val signature: String, val file: File, val detected: Detected, val name: String) {
        var index: Index? = null
        var primary: ArchiveReader? = null
        var busy = false
        var password: CharArray? = null

        fun newReader(): ArchiveReader = when (detected.kind) {
            ArchiveKind.ZIP -> ZipReader(file, passwords[id])
            ArchiveKind.SEVEN_Z -> SevenZReader(file, passwords[id])
            ArchiveKind.TAR_LIKE -> StreamReader(file, detected.compressor)
            ArchiveKind.SINGLE -> SingleReader(file, detected.compressor!!, ArchiveFormats.innerName(name))
            ArchiveKind.NATIVE -> NativeReader(file)
            ArchiveKind.UNKNOWN -> throw IOException(text(R.string.err_archive_format))
        }

        /** Czytnik do wyłącznego użytku; [release] oddaje go albo zamyka. */
        fun acquire(): ArchiveReader = synchronized(this) {
            if (password !== passwords[id]) {
                // Nowe hasło — czytnik ze starym nie odczyta treści.
                if (!busy) primary?.close()
                primary = null
                password = passwords[id]
            }
            if (!busy) {
                busy = true
                primary ?: runCatching { newReader() }
                    .onFailure { busy = false }
                    .getOrThrow()
                    .also { primary = it }
            } else newReader()
        }

        fun release(reader: ArchiveReader) = synchronized(this) {
            if (reader === primary) busy = false else runCatching { reader.close() }
        }

        fun close() = synchronized(this) {
            runCatching { primary?.close() }
            primary = null
        }
    }

    private val opened = object : LinkedHashMap<String, Opened>(8, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Opened>): Boolean =
            (size > OPEN_LIMIT).also { if (it) eldest.value.close() }
    }

    /** Korzeń archiwum [source]; [name] to nazwa pliku (do rozpoznania formatu). */
    fun rootOf(source: Uri, name: String): Uri {
        val id = idFor(source)
        if (registry.getString("src_$id", null) != source.toString() || registry.getString("name_$id", null) != name) {
            registry.edit().putString("src_$id", source.toString()).putString("name_$id", name).apply()
        }
        return Uri.Builder().scheme(SCHEME).authority(id).path("/").build()
    }

    fun sourceOf(uri: Uri): Uri? = registry.getString("src_${uri.authority}", null)?.let(Uri::parse)

    /** Nazwa pliku archiwum — tytuł korzenia w kartach i ścieżce. */
    fun archiveName(uri: Uri): String? = registry.getString("name_${uri.authority}", null)

    fun setPassword(uri: Uri, password: CharArray) {
        passwords[uri.authority ?: return] = password
    }

    private fun idFor(source: Uri): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(source.toString().toByteArray())
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    private fun open(uri: Uri): Opened {
        val id = uri.authority ?: throw FileNotFoundException(uri.toString())
        val source = sourceOf(uri) ?: throw FileNotFoundException(text(R.string.err_archive_gone))
        val name = archiveName(uri).orEmpty()
        val signature = runCatching { sourceSignature(source) }.getOrDefault("")
        synchronized(opened) {
            opened[id]?.let {
                if (it.signature == signature && it.file.exists()) {
                    // Kopia w pamięci podręcznej (nie plik użytkownika!) — świeżo używana.
                    if (it.file.parentFile == cacheDir) it.file.setLastModified(System.currentTimeMillis())
                    return it
                }
                it.close()
                opened.remove(id)
            }
        }
        val file = localFile(source) ?: copyToCache(id, source, name)
        val detected = ArchiveDetector.detect(file, name)
        if (detected.kind == ArchiveKind.UNKNOWN) throw IOException(text(R.string.err_archive_format))
        val created = Opened(id, signature, file, detected, name)
        return synchronized(opened) {
            opened[id]?.takeIf { it.signature == signature } ?: created.also { opened[id] = it }
        }
    }

    private fun copyToCache(id: String, source: Uri, name: String): File {
        cacheDir.mkdirs()
        val target = File(cacheDir, "$id.${ArchiveFormats.extension(name).ifBlank { "bin" }}")
        // Kopie archiwów nieużywanych w tej sesji tylko zajmują miejsce.
        val keep = synchronized(opened) { opened.keys.toSet() } + id
        cacheDir.listFiles()?.forEach { if (it.name.substringBefore('.') !in keep) it.delete() }
        val temp = File(cacheDir, "$id.part")
        openSource(source).use { input -> temp.outputStream().buffered().use { input.copyTo(it) } }
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        return target
    }

    private fun index(archive: Opened): Index {
        synchronized(archive) { archive.index }?.let { return it }
        val reader = archive.acquire()
        val index = try {
            Index(reader.entries())
        } finally {
            archive.release(reader)
        }
        synchronized(archive) { archive.index = index }
        return index
    }

    private fun itemFor(uri: Uri, node: Index.Node): FileItem = FileItem(
        uri = Uri.Builder().scheme(SCHEME).authority(uri.authority).path("/" + node.path).build(),
        name = node.name,
        mimeType = if (node.isDirectory) DIRECTORY_MIME else guessMime(node.name),
        size = node.raw?.size ?: 0L,
        lastModified = node.raw?.modified ?: 0L,
        isDirectory = node.isDirectory
    )

    fun listChildren(parent: Uri): List<FileItem> {
        val index = index(open(parent))
        val path = innerPath(parent)
        if (path.isNotEmpty() && index.nodes[path]?.isDirectory != true) throw FileNotFoundException(path)
        return index.children[path].orEmpty().map { itemFor(parent, it) }
    }

    fun itemInfo(uri: Uri): FileItem? {
        val archive = open(uri)
        val path = innerPath(uri)
        if (path.isEmpty()) return FileItem(uri, ArchiveFormats.baseName(archive.name), DIRECTORY_MIME, 0, 0, true)
        return index(archive).nodes[path]?.let { itemFor(uri, it) }
    }

    fun openInput(uri: Uri): InputStream {
        val archive = open(uri)
        val node = index(archive).nodes[innerPath(uri)] ?: throw FileNotFoundException(innerPath(uri))
        val raw = node.raw?.takeIf { !node.isDirectory } ?: throw IOException(text(R.string.err_open_read))
        if (raw.encrypted && passwords[archive.id] == null) throw ArchivePasswordException(wrong = false)
        val reader = archive.acquire()
        val stream = try {
            reader.open(raw)
        } catch (e: Exception) {
            archive.release(reader)
            throw e
        }
        return object : FilterInputStream(stream) {
            private var closed = false
            override fun close() {
                if (closed) return
                closed = true
                try { super.close() } finally { archive.release(reader) }
            }
        }
    }

    /**
     * Sprawdza, czy archiwum da się czytać: rozpoznaje format, buduje spis
     * i przy szyfrowanych wpisach próbuje odczytać najmniejszy z nich — złe
     * hasło wychodzi od razu, a nie w połowie rozpakowywania.
     */
    fun ensureReadable(uri: Uri) {
        val archive = open(uri)
        val index = index(archive)
        if (!index.encrypted) return
        if (archive.detected.kind == ArchiveKind.NATIVE) throw IOException(text(R.string.err_archive_rar))
        if (passwords[archive.id] == null) throw ArchivePasswordException(wrong = false)
        val sample = index.nodes.values.mapNotNull { it.raw }.filter { it.encrypted && !it.isDirectory }.minByOrNull { it.size } ?: return
        val reader = archive.acquire()
        try {
            reader.open(sample).use { input ->
                val buffer = ByteArray(64 * 1024)
                var total = 0
                while (total < 1_000_000) {
                    val count = input.read(buffer)
                    if (count <= 0) break
                    total += count
                }
            }
        } catch (e: ArchivePasswordException) {
            throw e
        } catch (e: IOException) {
            throw ArchivePasswordException(wrong = true)
        } finally {
            archive.release(reader)
        }
    }

    fun readOnly(): Nothing = throw IOException(text(R.string.err_archive_read_only))
}
