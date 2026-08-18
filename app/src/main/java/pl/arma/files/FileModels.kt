package pl.arma.files

import android.net.Uri

const val DIRECTORY_MIME = "vnd.android.document/directory"

data class FileItem(
    val uri: Uri,
    val name: String,
    val mimeType: String,
    val size: Long,
    val lastModified: Long,
    val isDirectory: Boolean
)

enum class SortMode { NAME, DATE, SIZE, TYPE }
enum class ViewMode { LIST, GRID }
enum class Tab { FILES, RECENT, FAVORITES, SETTINGS }
enum class FileCategory { ALL, FOLDERS, IMAGES, VIDEO, AUDIO, DOCUMENTS, ARCHIVES }

/** Jedna zakładka katalogu w pasku kart. */
data class TabInfo(
    val id: String,
    val title: String,
    val active: Boolean,
    /** Karta pokazywana w drugim panelu w trybie dwupanelowym. */
    val secondary: Boolean = false,
    /** Źródło otwarte w karcie — decyduje o ikonie na pasku. */
    val source: SourceType = SourceType.LOCAL,
    /** Karta stoi na ekranie „Mój komputer", bez otwartego katalogu. */
    val atHome: Boolean = false
)

/** Wynik analizy zajętości pamięci. */
data class StorageAnalysis(
    val stats: pl.arma.files.index.IndexStats? = null,
    val usageByCategory: Map<pl.arma.files.index.IndexCategory, Long> = emptyMap(),
    val largestFiles: List<FileItem> = emptyList(),
    val largestFolders: List<pl.arma.files.index.FolderUsage> = emptyList(),
    val oldFiles: List<FileItem> = emptyList()
)

/** Zbiór plików o potwierdzonej identycznej zawartości. */
data class DuplicateSet(
    val size: Long,
    val files: List<FileItem>
)

data class ClipboardPayload(
    val items: List<FileItem>,
    val cut: Boolean
)

data class DeviceStorage(
    val id: String,
    val label: String,
    val rootUri: Uri,
    val totalBytes: Long,
    val freeBytes: Long,
    val removable: Boolean
)

data class PinnedFolder(
    val id: String,
    val label: String,
    val rootUri: Uri,
    val subtitle: String
)
