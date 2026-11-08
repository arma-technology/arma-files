package pl.arma.files.index

/**
 * Kategoria zapisywana w indeksie. Liczona raz, przy indeksowaniu, żeby
 * zapytania „pokaż wszystkie filmy w pamięci" były zwykłym filtrem po
 * kolumnie zamiast dopasowywania typów MIME w locie.
 */
enum class IndexCategory {
    IMAGE, VIDEO, AUDIO, DOCUMENT, ARCHIVE, APK, OTHER;

    companion object {
        fun of(name: String, mime: String): IndexCategory {
            val extension = name.substringAfterLast('.', "").lowercase()
            return when {
                extension == "apk" || mime == "application/vnd.android.package-archive" -> APK
                mime.startsWith("image/") -> IMAGE
                mime.startsWith("video/") -> VIDEO
                mime.startsWith("audio/") -> AUDIO
                extension in ARCHIVE_EXTENSIONS -> ARCHIVE
                mime.startsWith("text/") ||
                    mime == "application/pdf" ||
                    extension in DOCUMENT_EXTENSIONS -> DOCUMENT
                else -> OTHER
            }
        }

        private val ARCHIVE_EXTENSIONS =
            setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "iso")

        private val DOCUMENT_EXTENSIONS =
            setOf("doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "rtf", "epub", "csv")
    }
}

/** Jeden wpis indeksu. */
data class IndexedFile(
    val uri: String,
    val parentUri: String,
    val name: String,
    val size: Long,
    val modified: Long,
    val isDirectory: Boolean,
    val mime: String,
    val category: IndexCategory
)

/**
 * Jeden kafelek mapy pamięci: podfolder (z sumą wszystkiego pod nim) albo plik.
 * [segment] to człon adresu file:// — zakodowany, jak w kolumnie uri.
 */
data class MapEntry(
    val segment: String,
    val isDirectory: Boolean,
    val bytes: Long,
    val files: Int,
    val category: IndexCategory
)

/** Katalog wraz z sumą rozmiarów tego, co w nim leży — dla analizatora. */
data class FolderUsage(
    val parentUri: String,
    val totalBytes: Long,
    val fileCount: Int
)

/** Grupa plików o identycznym rozmiarze — kandydaci na duplikaty. */
data class DuplicateGroup(
    val size: Long,
    val files: List<IndexedFile>
)

data class IndexStats(
    val files: Int,
    val folders: Int,
    val totalBytes: Long,
    val updatedAt: Long
)
