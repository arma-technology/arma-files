package pl.arma.libarchive

import java.io.Closeable
import java.io.IOException
import java.io.InputStream

/**
 * Archiwum czytane przez libarchive, wpis po wpisie. Nie jest bezpieczne
 * dla wielu wątków naraz.
 */
class LibArchive private constructor(private var handle: Long) : Closeable {

    /** Wpis, na którym stoi archiwum po [next]. */
    class Entry(
        /** Nazwa w bajtach — UTF-8 albo strona kodowa archiwum, rozpoznaje wołający. */
        val name: ByteArray,
        val size: Long,
        val modified: Long,
        val isDirectory: Boolean,
        val isFile: Boolean,
        val encrypted: Boolean
    )

    /** Przechodzi do następnego wpisu; null na końcu archiwum. */
    fun next(): Entry? {
        check(handle != 0L) { "closed" }
        if (!nativeNext(handle)) return null
        val type = nativeType(handle)
        return Entry(
            name = nativeName(handle) ?: ByteArray(0),
            size = nativeSize(handle),
            modified = nativeModified(handle),
            isDirectory = type == TYPE_DIRECTORY,
            isFile = type == TYPE_FILE,
            encrypted = nativeEncrypted(handle)
        )
    }

    /** Treść bieżącego wpisu; kończy się razem z nim. Zamknięcie nie zamyka archiwum. */
    fun data(): InputStream = object : InputStream() {
        private val single = ByteArray(1)
        override fun read(): Int = if (read(single, 0, 1) <= 0) -1 else single[0].toInt() and 0xFF
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (off < 0 || len < 0 || off + len > b.size) throw IndexOutOfBoundsException()
            check(handle != 0L) { "closed" }
            return nativeRead(handle, b, off, len)
        }
    }

    /** Nazwa formatu według libarchive, np. „RAR5”, „ISO9660”. */
    val format: String? get() = if (handle != 0L) nativeFormat(handle) else null

    override fun close() {
        if (handle != 0L) {
            nativeClose(handle)
            handle = 0L
        }
    }

    companion object {
        private const val TYPE_FILE = 0x8000
        private const val TYPE_DIRECTORY = 0x4000

        init {
            System.loadLibrary("archivejni")
        }

        @Throws(IOException::class)
        fun open(path: String, password: String? = null): LibArchive = LibArchive(nativeOpen(path, password))

        @JvmStatic private external fun nativeOpen(path: String, passphrase: String?): Long
        @JvmStatic private external fun nativeNext(handle: Long): Boolean
        @JvmStatic private external fun nativeName(handle: Long): ByteArray?
        @JvmStatic private external fun nativeSize(handle: Long): Long
        @JvmStatic private external fun nativeModified(handle: Long): Long
        @JvmStatic private external fun nativeType(handle: Long): Int
        @JvmStatic private external fun nativeEncrypted(handle: Long): Boolean
        @JvmStatic private external fun nativeRead(handle: Long, buffer: ByteArray, offset: Int, length: Int): Int
        @JvmStatic private external fun nativeFormat(handle: Long): String?
        @JvmStatic private external fun nativeClose(handle: Long)
    }
}
