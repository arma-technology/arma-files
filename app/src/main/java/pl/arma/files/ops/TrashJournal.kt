package pl.arma.files.ops

/**
 * Wpis kosza: gdzie plik leży teraz i skąd pochodzi.
 * [originalParent] jest potrzebny, bo przy przywracaniu katalog źródłowy
 * mógł już nie istnieć i trzeba go odtworzyć.
 */
data class TrashEntry(
    val id: String,
    val name: String,
    val trashUri: String,
    val originalUri: String,
    val originalParent: String,
    val size: Long,
    val isDirectory: Boolean,
    val deletedAt: Long
)

/**
 * Serializacja dziennika kosza. Własny format zamiast JSON-a, bo `org.json`
 * jest klasą Androida — trzymając to w czystym Kotlinie można przetestować
 * odczyt i zapis zwykłym testem jednostkowym, bez Robolectrica.
 *
 * Jedna linia to jeden wpis, pola rozdzielone tabulatorem. Znaki specjalne są
 * escape'owane, bo nazwa pliku w Linuksie może zawierać tabulator i nową linię.
 */
object TrashJournal {

    private const val SEPARATOR = '\t'
    private const val VERSION_PREFIX = "#v1"

    fun encode(entries: List<TrashEntry>): String = buildString {
        append(VERSION_PREFIX).append('\n')
        entries.forEach { entry ->
            append(
                listOf(
                    entry.id,
                    entry.name,
                    entry.trashUri,
                    entry.originalUri,
                    entry.originalParent,
                    entry.size.toString(),
                    entry.isDirectory.toString(),
                    entry.deletedAt.toString()
                ).joinToString(SEPARATOR.toString()) { Escaping.escape(it) }
            )
            append('\n')
        }
    }

    /** Linie uszkodzone są pomijane — lepiej stracić jeden wpis niż cały kosz. */
    fun decode(text: String): List<TrashEntry> = text
        .lineSequence()
        .filter { it.isNotBlank() && !it.startsWith('#') }
        .mapNotNull { line ->
            val parts = line.split(SEPARATOR)
            if (parts.size != 8) return@mapNotNull null
            runCatching {
                TrashEntry(
                    id = Escaping.unescape(parts[0]),
                    name = Escaping.unescape(parts[1]),
                    trashUri = Escaping.unescape(parts[2]),
                    originalUri = Escaping.unescape(parts[3]),
                    originalParent = Escaping.unescape(parts[4]),
                    size = Escaping.unescape(parts[5]).toLong(),
                    isDirectory = Escaping.unescape(parts[6]).toBooleanStrict(),
                    deletedAt = Escaping.unescape(parts[7]).toLong()
                )
            }.getOrNull()
        }
        .toList()

}
