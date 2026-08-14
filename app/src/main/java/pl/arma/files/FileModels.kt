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

data class ClipboardPayload(
    val items: List<FileItem>,
    val cut: Boolean
)
