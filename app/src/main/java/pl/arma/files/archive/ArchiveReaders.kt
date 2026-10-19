package pl.arma.files.archive

import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.model.FileHeader
import org.apache.commons.compress.PasswordRequiredException
import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.archivers.ArchiveStreamFactory
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.compressors.CompressorStreamFactory
import org.apache.commons.compress.compressors.brotli.BrotliCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale
import pl.arma.files.R
import pl.arma.files.text
import pl.arma.libarchive.LibArchive

/** Wpis archiwum tak, jak go podaje format; [path] już oczyszczona. */
internal data class RawEntry(
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val modified: Long,
    val encrypted: Boolean,
    /** Uchwyt formatu: nagłówek ZIP, wpis 7z albo numer wpisu w strumieniu. */
    val ref: Any?
)

/** Archiwum otwarte do czytania. Nie jest bezpieczne dla wielu wątków naraz. */
internal interface ArchiveReader : Closeable {
    fun entries(): List<RawEntry>

    /**
     * Strumień treści wpisu. Jego zamknięcie nie zamyka czytnika — kolejny
     * wpis można otworzyć tym samym czytnikiem.
     */
    fun open(entry: RawEntry): InputStream
}

/** [NATIVE] to formaty czytane przez libarchive: RAR, RAR5, ISO, CAB, LHA, XAR. */
internal enum class ArchiveKind { ZIP, SEVEN_Z, NATIVE, TAR_LIKE, SINGLE, UNKNOWN }

/** Wynik rozpoznania formatu po zawartości. */
internal data class Detected(val kind: ArchiveKind, val compressor: String? = null)

/** Nie da się czytać bez hasła albo podane jest złe. */
class ArchivePasswordException(val wrong: Boolean) : IOException(if (wrong) "wrong password" else "password required")

internal object ArchiveDetector {

    fun detect(file: File, name: String): Detected {
        val head = ByteArray(8)
        val read = FileInputStream(file).use { it.readNBytesCompat(head) }
        fun starts(vararg bytes: Int) = read >= bytes.size && bytes.indices.all { head[it] == bytes[it].toByte() }
        when {
            starts(0x50, 0x4B, 0x03, 0x04) || starts(0x50, 0x4B, 0x05, 0x06) || starts(0x50, 0x4B, 0x07, 0x08) ->
                return Detected(ArchiveKind.ZIP)
            starts(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C) -> return Detected(ArchiveKind.SEVEN_Z)
            starts(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07) -> return Detected(ArchiveKind.NATIVE)
        }
        val compressor = BufferedInputStream(FileInputStream(file)).use { input ->
            runCatching { CompressorStreamFactory.detect(input) }.getOrNull()
        } ?: if (ArchiveFormats.extension(name) == "br" || name.lowercase(Locale.ROOT).endsWith(".tar.br")) "br" else null

        if (compressor != null) {
            val inner = runCatching {
                decompressed(file, compressor).use { input ->
                    ArchiveStreamFactory.detect(BufferedInputStream(input))
                }
            }.getOrNull()
            return Detected(if (inner != null) ArchiveKind.TAR_LIKE else ArchiveKind.SINGLE, compressor)
        }
        val plain = BufferedInputStream(FileInputStream(file)).use { input ->
            runCatching { ArchiveStreamFactory.detect(input) }.getOrNull()
        }
        if (plain != null) return Detected(ArchiveKind.TAR_LIKE)
        // Reszta (ISO, CAB, LHA, XAR) — jeśli libarchive znajdzie choć jeden wpis.
        val native = runCatching { LibArchive.open(file.absolutePath).use { it.next() != null } }.getOrDefault(false)
        return Detected(if (native) ArchiveKind.NATIVE else ArchiveKind.UNKNOWN)
    }

    fun decompressed(file: File, compressor: String): InputStream {
        val raw = BufferedInputStream(FileInputStream(file))
        return try {
            if (compressor == "br") BrotliCompressorInputStream(raw)
            else CompressorStreamFactory().createCompressorInputStream(compressor, raw, true)
        } catch (e: Exception) {
            raw.close()
            throw e
        }
    }

    private fun InputStream.readNBytesCompat(buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val count = read(buffer, total, buffer.size - total)
            if (count <= 0) break
            total += count
        }
        return total
    }
}

/** Oczyszczenie ścieżki z archiwum: bez „..”, „.”, ukośników na początku. */
internal fun cleanArchivePath(name: String): String? {
    val parts = name.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
    if (parts.isEmpty() || parts.any { it == ".." }) return null
    return parts.joinToString("/")
}

/** Strumień, którego zamknięcie nie zamyka źródła — dla wpisów czytanych po kolei. */
private class EntryStream(input: InputStream, private val onClose: () -> Unit) : FilterInputStream(input) {
    private var closed = false
    override fun close() {
        if (!closed) {
            closed = true
            onClose()
        }
    }
}

// --- ZIP ---------------------------------------------------------------------

internal class ZipReader(file: File, private val password: CharArray?) : ArchiveReader {
    private val zip = ZipFile(file).apply {
        // Nazwy bez flagi UTF-8 dekodujemy sami (patrz [decodeName]); ISO-8859-1
        // przenosi bajty jeden do jednego.
        charset = Charsets.ISO_8859_1
        if (password != null) setPassword(password)
    }
    private val legacy = legacyCharset()

    override fun entries(): List<RawEntry> = zip.fileHeaders.mapNotNull { header ->
        val path = cleanArchivePath(decodeName(header)) ?: return@mapNotNull null
        RawEntry(
            path = path,
            isDirectory = header.isDirectory,
            size = header.uncompressedSize.coerceAtLeast(0),
            modified = header.lastModifiedTimeEpoch,
            encrypted = header.isEncrypted,
            ref = header
        )
    }

    override fun open(entry: RawEntry): InputStream = try {
        zip.getInputStream(entry.ref as FileHeader)
    } catch (e: ZipException) {
        if (entry.encrypted) throw ArchivePasswordException(wrong = e.type == ZipException.Type.WRONG_PASSWORD || password != null)
        throw IOException(e.message, e)
    }

    /**
     * Nazwa z flagą UTF-8 albo poprawna jako UTF-8 — w UTF-8. Inaczej w
     * stronie kodowej DOS-u właściwej dla języka telefonu: tak zapisuje ZIP
     * Eksplorator Windows, a polskie litery w CP852 wyglądałyby jak „ą”→„Ą”.
     */
    private fun decodeName(header: FileHeader): String {
        val name = header.fileName
        // zip4j mógł już zdekodować nazwę z flagą UTF-8 mimo ustawionego kodowania.
        if (name.any { it.code > 0xFF }) return name
        val bytes = name.toByteArray(Charsets.ISO_8859_1)
        if (header.isFileNameUTF8Encoded) return String(bytes, Charsets.UTF_8)
        return strictUtf8(bytes) ?: String(bytes, legacy)
    }

    override fun close() = zip.close()
}

private fun strictUtf8(bytes: ByteArray): String? = try {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
} catch (_: CharacterCodingException) {
    null
}

private fun legacyCharset(): Charset {
    val name = when (Locale.getDefault().language) {
        "pl", "cs", "sk", "sl", "hr", "hu", "bs", "sr", "sq", "ro" -> "IBM852"
        "ru", "be", "uk", "bg", "mk" -> "IBM866"
        "lt", "lv", "et" -> "IBM775"
        "de", "nl" -> "IBM850"
        else -> "IBM437"
    }
    return runCatching { Charset.forName(name) }.getOrElse { Charsets.ISO_8859_1 }
}

// --- 7z ----------------------------------------------------------------------

internal class SevenZReader(file: File, private val password: CharArray?) : ArchiveReader {
    private val seven: SevenZFile = try {
        SevenZFile.builder().setFile(file).apply { if (password != null) setPassword(password) }.get()
    } catch (e: PasswordRequiredException) {
        throw ArchivePasswordException(wrong = false)
    } catch (e: IOException) {
        // Złe hasło do zaszyfrowanych nagłówków daje śmieci zamiast spisu.
        if (password != null) throw ArchivePasswordException(wrong = true) else throw e
    }

    override fun entries(): List<RawEntry> = seven.entries.mapNotNull { entry ->
        if (entry.isAntiItem) return@mapNotNull null
        val path = cleanArchivePath(entry.name ?: return@mapNotNull null) ?: return@mapNotNull null
        RawEntry(
            path = path,
            isDirectory = entry.isDirectory,
            size = entry.size.coerceAtLeast(0),
            modified = if (entry.hasLastModifiedDate) entry.lastModifiedDate.time else 0L,
            encrypted = entry.contentMethods?.any { it.method == SevenZMethod.AES256SHA256 } == true,
            ref = entry
        )
    }

    override fun open(entry: RawEntry): InputStream {
        val stream = seven.getInputStream(entry.ref as SevenZArchiveEntry)
        return object : FilterInputStream(stream) {
            override fun read(): Int = guarded(entry) { super.read() }
            override fun read(b: ByteArray, off: Int, len: Int): Int = guarded(entry) { super.read(b, off, len) }
        }
    }

    private inline fun <T> guarded(entry: RawEntry, block: () -> T): T = try {
        block()
    } catch (e: PasswordRequiredException) {
        throw ArchivePasswordException(wrong = false)
    } catch (e: IOException) {
        if (entry.encrypted && password != null && e !is ArchivePasswordException) throw ArchivePasswordException(wrong = true)
        throw e
    }

    override fun close() = seven.close()
}

// --- tar, ar, cpio, arj (także skompresowane) ------------------------------

/**
 * Archiwa czytane tylko po kolei. Trzyma otwarty strumień („kursor”): wpis
 * dalej w archiwum dostaje się, przewijając, a nie dekompresując od początku —
 * rozpakowanie całego tar.gz nie staje się przez to kwadratowe.
 */
internal class StreamReader(private val file: File, private val compressor: String?) : ArchiveReader {
    private var cursor: ArchiveInputStream<*>? = null
    private var position = -1

    private fun openArchive(): ArchiveInputStream<*> {
        val input = if (compressor != null) BufferedInputStream(ArchiveDetector.decompressed(file, compressor))
        else BufferedInputStream(FileInputStream(file))
        return try {
            ArchiveStreamFactory().createArchiveInputStream<ArchiveInputStream<*>>(input)
        } catch (e: Exception) {
            input.close()
            throw IOException(e.message, e)
        }
    }

    override fun entries(): List<RawEntry> = openArchive().use { archive ->
        val out = mutableListOf<RawEntry>()
        var index = 0
        while (true) {
            val entry = archive.nextEntry ?: break
            val current = index++
            if (entry is TarArchiveEntry && (entry.isSymbolicLink || entry.isLink)) continue
            val path = cleanArchivePath(entry.name) ?: continue
            out += RawEntry(
                path = path,
                isDirectory = entry.isDirectory,
                size = entry.size.coerceAtLeast(0),
                modified = entry.lastModifiedDate?.time ?: 0L,
                encrypted = false,
                ref = current
            )
        }
        out
    }

    override fun open(entry: RawEntry): InputStream {
        val wanted = entry.ref as Int
        var archive = cursor
        if (archive == null || position >= wanted) {
            archive?.close()
            archive = openArchive()
            cursor = archive
            position = -1
        }
        while (position < wanted) {
            if (archive.nextEntry == null) throw IOException("entry not found: ${entry.path}")
            position++
        }
        return EntryStream(archive) {}
    }

    override fun close() {
        cursor?.close()
        cursor = null
    }
}

// --- jeden skompresowany plik (gz, bz2, xz…) --------------------------------

internal class SingleReader(private val file: File, private val compressor: String, private val fallbackName: String) : ArchiveReader {
    override fun entries(): List<RawEntry> {
        // Gzip zapisuje oryginalną nazwę i datę w nagłówku.
        val (name, modified) = if (compressor == CompressorStreamFactory.GZIP) {
            runCatching {
                GzipCompressorInputStream(BufferedInputStream(FileInputStream(file))).use {
                    // Nagłówek gzip zapisuje sekundy.
                    it.metaData.fileName to it.metaData.modificationTime * 1000
                }
            }.getOrNull() ?: (null to 0L)
        } else null to 0L
        val clean = name?.let(::cleanArchivePath)?.substringAfterLast('/') ?: fallbackName
        return listOf(RawEntry(clean, false, 0L, modified, false, 0))
    }

    override fun open(entry: RawEntry): InputStream = ArchiveDetector.decompressed(file, compressor)

    override fun close() = Unit
}

// --- RAR, ISO, CAB, LHA, XAR (libarchive) -----------------------------------

/** Jak [StreamReader]: libarchive czyta po kolei, więc też trzyma kursor. */
internal class NativeReader(private val file: File) : ArchiveReader {
    private var cursor: LibArchive? = null
    private var position = -1
    private val legacy = legacyCharset()

    private fun openArchive() = LibArchive.open(file.absolutePath)

    override fun entries(): List<RawEntry> = openArchive().use { archive ->
        val out = mutableListOf<RawEntry>()
        var index = 0
        while (true) {
            val entry = archive.next() ?: break
            val current = index++
            if (!entry.isDirectory && !entry.isFile) continue
            val name = strictUtf8(entry.name) ?: String(entry.name, legacy)
            val path = cleanArchivePath(name) ?: continue
            out += RawEntry(path, entry.isDirectory, entry.size.coerceAtLeast(0), entry.modified, entry.encrypted, current)
        }
        out
    }

    override fun open(entry: RawEntry): InputStream {
        // Szyfrowanych RAR libarchive nie odczyta — hasło nic by nie dało.
        if (entry.encrypted) throw IOException(text(R.string.err_archive_rar))
        val wanted = entry.ref as Int
        var archive = cursor
        if (archive == null || position >= wanted) {
            archive?.close()
            archive = openArchive()
            cursor = archive
            position = -1
        }
        while (position < wanted) {
            if (archive.next() == null) throw IOException("entry not found: ${entry.path}")
            position++
        }
        val data = archive.data()
        return object : FilterInputStream(data) {
            override fun read(): Int = mapped { data.read() }
            override fun read(b: ByteArray, off: Int, len: Int): Int = mapped { data.read(b, off, len) }
            override fun close() = Unit
        }
    }

    /** RAR5 nie zawsze oznacza szyfrowanie w nagłówku — wychodzi ono dopiero przy odczycie. */
    private inline fun <T> mapped(block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        if (e.message.orEmpty().contains("ncrypt")) throw IOException(text(R.string.err_archive_rar), e)
        throw e
    }

    override fun close() {
        cursor?.close()
        cursor = null
    }
}
