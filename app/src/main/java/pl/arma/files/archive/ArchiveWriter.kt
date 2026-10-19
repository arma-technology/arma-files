package pl.arma.files.archive

import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorOutputStream
import pl.arma.files.FileItem
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Date

/** Formaty, w których aplikacja tworzy archiwa. */
enum class ArchiveFormat(val extension: String, val supportsPassword: Boolean = false) {
    ZIP("zip", supportsPassword = true),
    SEVEN_Z("7z"),
    TAR_GZ("tar.gz"),
    TAR_XZ("tar.xz"),
    TAR_ZST("tar.zst"),
    TAR("tar")
}

object ArchiveWriter {

    /** Skąd brać zawartość pakowanych elementów. */
    interface Source {
        fun children(item: FileItem): List<FileItem>
        fun open(item: FileItem): InputStream
    }

    /**
     * Zapisuje [items] (z podfolderami) jako archiwum. 7z wymaga zapisu
     * z przewijaniem, więc powstaje w [scratch] i dopiero potem trafia do celu.
     */
    fun write(
        format: ArchiveFormat,
        items: List<FileItem>,
        source: Source,
        password: CharArray?,
        scratch: File,
        output: () -> OutputStream
    ) {
        when (format) {
            ArchiveFormat.ZIP -> output().use { writeZip(items, source, password, it) }
            ArchiveFormat.SEVEN_Z -> {
                scratch.mkdirs()
                val temp = File.createTempFile("pack", ".7z", scratch)
                try {
                    writeSevenZ(items, source, temp)
                    output().use { out -> temp.inputStream().use { it.copyTo(out) } }
                } finally {
                    temp.delete()
                }
            }
            else -> output().use { raw ->
                val buffered = BufferedOutputStream(raw)
                val compressed: OutputStream = when (format) {
                    ArchiveFormat.TAR_GZ -> GzipCompressorOutputStream(buffered)
                    // Preset 5 zamiast domyślnego 6: kompresja potrzebuje wtedy
                    // ok. 50 MB pamięci, a nie blisko 100.
                    ArchiveFormat.TAR_XZ -> XZCompressorOutputStream(buffered, 5)
                    ArchiveFormat.TAR_ZST -> ZstdCompressorOutputStream(buffered, 9)
                    else -> buffered
                }
                writeTar(items, source, compressed, scratch)
            }
        }
    }

    /** Elementy razem ze ścieżką w archiwum, katalogi przed swoją zawartością. */
    private fun walk(items: List<FileItem>, source: Source, prefix: String = "", visit: (String, FileItem) -> Unit) {
        items.forEach { item ->
            val path = if (prefix.isEmpty()) item.name else "$prefix/${item.name}"
            visit(path, item)
            if (item.isDirectory) walk(source.children(item), source, path, visit)
        }
    }

    private fun writeZip(items: List<FileItem>, source: Source, password: CharArray?, out: OutputStream) {
        val encrypted = password != null && password.isNotEmpty()
        ZipOutputStream(BufferedOutputStream(out), if (encrypted) password else null).use { zip ->
            walk(items, source) { path, item ->
                val parameters = ZipParameters().apply {
                    fileNameInZip = if (item.isDirectory) "$path/" else path
                    if (item.lastModified > 0) lastModifiedFileTime = item.lastModified
                    if (encrypted && !item.isDirectory) {
                        isEncryptFiles = true
                        encryptionMethod = EncryptionMethod.AES
                        aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                    }
                }
                zip.putNextEntry(parameters)
                if (!item.isDirectory) source.open(item).use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun writeSevenZ(items: List<FileItem>, source: Source, target: File) {
        SevenZOutputFile(target).use { seven ->
            seven.setContentCompression(SevenZMethod.LZMA2)
            walk(items, source) { path, item ->
                val entry = SevenZArchiveEntry().apply {
                    name = path
                    isDirectory = item.isDirectory
                    if (item.lastModified > 0) lastModifiedDate = Date(item.lastModified)
                }
                seven.putArchiveEntry(entry)
                if (!item.isDirectory) {
                    source.open(item).use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count <= 0) break
                            seven.write(buffer, 0, count)
                        }
                    }
                }
                seven.closeArchiveEntry()
            }
        }
    }

    private fun writeTar(items: List<FileItem>, source: Source, out: OutputStream, scratch: File) {
        TarArchiveOutputStream(out, "UTF-8").use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
            walk(items, source) { path, item ->
                if (item.isDirectory) {
                    tar.putArchiveEntry(TarArchiveEntry("$path/").apply { modTime(item) })
                    tar.closeArchiveEntry()
                    return@walk
                }
                // Tar zapisuje rozmiar przed treścią. Gdy źródło go nie zna
                // (część dostawców treści), plik idzie najpierw do bufora na dysku.
                if (item.size > 0) {
                    tar.putArchiveEntry(TarArchiveEntry(path).apply { size = item.size; modTime(item) })
                    source.open(item).use { it.copyTo(tar) }
                } else {
                    scratch.mkdirs()
                    val temp = File.createTempFile("entry", ".bin", scratch)
                    try {
                        source.open(item).use { input -> temp.outputStream().use { input.copyTo(it) } }
                        tar.putArchiveEntry(TarArchiveEntry(path).apply { size = temp.length(); modTime(item) })
                        temp.inputStream().use { it.copyTo(tar) }
                    } finally {
                        temp.delete()
                    }
                }
                tar.closeArchiveEntry()
            }
        }
    }

    private fun TarArchiveEntry.modTime(item: FileItem) {
        if (item.lastModified > 0) setModTime(item.lastModified)
    }
}
