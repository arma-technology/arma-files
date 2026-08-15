package pl.arma.files

import android.content.Context
import android.net.Uri
import pl.arma.files.ops.TrashEntry
import pl.arma.files.ops.TrashJournal
import java.io.File

/**
 * Kosz: pliki leżą w katalogu `.armafiles_trash` na tym samym nośniku co
 * oryginał, a dziennik z ich pochodzeniem trzymany jest w pamięci aplikacji.
 *
 * Katalog kosza musi być na tym samym nośniku, bo inaczej „usunięcie" 4 GB
 * oznaczałoby przepisanie ich na inną kartę zamiast zwykłej zmiany nazwy.
 */
class TrashStore(private val context: Context) {

    private val journal = File(context.filesDir, "trash.journal")

    fun entries(): List<TrashEntry> =
        if (journal.exists()) TrashJournal.decode(journal.readText()) else emptyList()

    fun add(newEntries: List<TrashEntry>) {
        if (newEntries.isEmpty()) return
        write(entries() + newEntries)
    }

    fun remove(ids: Collection<String>) {
        if (ids.isEmpty()) return
        write(entries().filterNot { it.id in ids })
    }

    fun clear() = write(emptyList())

    private fun write(list: List<TrashEntry>) {
        journal.writeText(TrashJournal.encode(list))
    }

    companion object {
        const val TRASH_DIR = ".armafiles_trash"

        /**
         * Korzeń nośnika, na którym leży dany element — do niego trafia katalog
         * kosza. Dla ścieżek lokalnych jest to punkt montowania: `/storage/emulated/0`
         * albo `/storage/XXXX-XXXX`. Dla zasobów sieciowych korzeń udziału.
         * Null oznacza, że kosza nie da się wskazać i trzeba usunąć trwale.
         */
        fun volumeRootFor(uri: Uri): Uri? = when (uri.scheme) {
            "file" -> {
                val path = uri.path.orEmpty()
                val match = Regex("^(/storage/emulated/\\d+|/storage/[^/]+)").find(path)
                match?.let { Uri.fromFile(File(it.value)) }
            }
            RemoteRepository.FTP_SCHEME -> RemoteRepository.ftpRoot()
            RemoteRepository.SMB_SCHEME -> RemoteRepository.smbRoot()
            else -> null
        }
    }
}
