package pl.arma.files

import android.app.usage.StorageStatsManager
import android.content.Context
import android.os.Process
import android.os.storage.StorageManager
import java.io.File

/** Części miejsca zajmowanego przez aplikację — każdą da się zobaczyć osobno, a większość wyczyścić. */
enum class StoragePart(val clearable: Boolean) {
    INDEX(true),
    DWG(true),
    PREVIEWS(true),
    ARCHIVES(true),
    OTHER_CACHE(true),
    OFFLINE(true),
    TRASH(true),
    SETTINGS(false),
    APP(false),
    OTHER(false)
}

data class StorageReport(val parts: Map<StoragePart, Long>) {
    val total: Long get() = parts.values.sum()
    operator fun get(part: StoragePart): Long = parts[part] ?: 0L
}

/**
 * Pomiar i czyszczenie miejsca zajmowanego przez aplikację. Kosz leży na
 * nośnikach użytkownika, a kopie offline mogą leżeć w osobnym folderze —
 * liczymy je, bo to też miejsce zajęte przez ARMA Files.
 */
object StorageUsage {

    private val dwgDirs = listOf("dwg_dxf")
    private val previewDirs = listOf("doc_preview", "image_preview", "remote_files")
    private val archiveDirs = listOf("archives", "archive_out")

    /** Katalogi robocze otwartych PDF — w użyciu, nie czyścimy ich. */
    private const val PDF_WORK = "pdf_work"

    private fun sizeOf(file: File): Long =
        if (!file.exists()) 0L else file.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    private fun cacheDirs(context: Context, names: List<String>) = names.map { File(context.cacheDir, it) }

    fun measure(app: ArmaFilesApp, trashBytes: Long): StorageReport {
        val context: Context = app
        val known = setOf<String>() + dwgDirs + previewDirs + archiveDirs
        val index = app.index.fileSize(context)
        val dwg = cacheDirs(context, dwgDirs).sumOf(::sizeOf)
        val previews = cacheDirs(context, previewDirs).sumOf(::sizeOf)
        val archives = cacheDirs(context, archiveDirs).sumOf(::sizeOf)
        val otherCache = context.cacheDir.listFiles().orEmpty().filter { it.name !in known }.sumOf(::sizeOf) +
            sizeOf(context.codeCacheDir)
        val offline = app.offline.entries.value.sumOf { sizeOf(File(it.dir)) }
        val offlineInternal = app.offline.entries.value
            .filter { File(it.dir).path.startsWith(context.filesDir.path) }
            .sumOf { sizeOf(File(it.dir)) }
        val dataDir = File(context.applicationInfo.dataDir)
        val settings = sizeOf(File(dataDir, "shared_prefs")) +
            context.filesDir.listFiles().orEmpty().filter { it.name != "offline" }.sumOf(::sizeOf)

        // Rozmiar programu i całość danych z systemowych statystyk; „inne” to to,
        // czego nie umiemy przypisać (np. pliki systemowe bibliotek).
        var appBytes = 0L
        var other = 0L
        runCatching {
            val stats = context.getSystemService(StorageStatsManager::class.java)
                .queryStatsForPackage(StorageManager.UUID_DEFAULT, context.packageName, Process.myUserHandle())
            appBytes = stats.appBytes
            val assigned = index + dwg + previews + archives + otherCache + offlineInternal + settings
            other = (stats.dataBytes - assigned).coerceAtLeast(0L)
        }
        return StorageReport(
            mapOf(
                StoragePart.INDEX to index,
                StoragePart.DWG to dwg,
                StoragePart.PREVIEWS to previews,
                StoragePart.ARCHIVES to archives,
                StoragePart.OTHER_CACHE to otherCache,
                StoragePart.OFFLINE to offline,
                StoragePart.TRASH to trashBytes,
                StoragePart.SETTINGS to settings,
                StoragePart.APP to appBytes,
                StoragePart.OTHER to other
            )
        )
    }

    /** Czyści jedną część; kosz opróżnia ViewModel (przez kolejkę operacji). */
    fun clear(app: ArmaFilesApp, part: StoragePart) {
        val context: Context = app
        fun wipe(dirs: List<File>) = dirs.forEach { dir -> dir.listFiles()?.forEach { it.deleteRecursively() } }
        when (part) {
            StoragePart.INDEX -> {
                app.index.clearAndCompact()
                app.indexAutomation.forget()
            }
            StoragePart.DWG -> wipe(cacheDirs(context, dwgDirs))
            StoragePart.PREVIEWS -> wipe(cacheDirs(context, previewDirs))
            StoragePart.ARCHIVES -> wipe(cacheDirs(context, archiveDirs))
            StoragePart.OTHER_CACHE -> {
                val known = setOf(PDF_WORK) + dwgDirs + previewDirs + archiveDirs
                context.cacheDir.listFiles()?.filter { it.name !in known }?.forEach { it.deleteRecursively() }
                context.codeCacheDir.listFiles()?.forEach { it.deleteRecursively() }
            }
            StoragePart.OFFLINE -> app.offline.remove(app.offline.entries.value.map { it.id })
            else -> Unit
        }
    }
}
