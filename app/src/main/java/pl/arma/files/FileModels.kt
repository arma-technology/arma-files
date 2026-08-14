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
enum class Tab { FILES, RECENT, FAVORITES }
data class ClipboardItem(val item: FileItem, val cut: Boolean)
