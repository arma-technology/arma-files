package pl.arma.files.index

import android.content.ContentValues
import android.content.Context
import java.io.File
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Lokalny indeks plików.
 *
 * Świadomie na czystym SQLite, bez Room — schemat to jedna tabela plus tabela
 * FTS, a Room wymagałby dołożenia procesora adnotacji do budowania. Przy tej
 * skali korzyść z generowanego kodu nie równoważy nowej zależności w kompilacji.
 *
 * Nazwy trzymamy dodatkowo w tabeli FTS4, dzięki czemu wyszukiwanie po całym
 * urządzeniu działa od razu, zamiast skanować setki tysięcy wierszy zapytaniem
 * `LIKE '%fraza%'`.
 */
class FileIndexDb(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE files (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uri TEXT NOT NULL UNIQUE,
                parent_uri TEXT NOT NULL,
                name TEXT NOT NULL,
                size INTEGER NOT NULL,
                modified INTEGER NOT NULL,
                is_dir INTEGER NOT NULL,
                mime TEXT NOT NULL,
                category TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_files_parent ON files(parent_uri)")
        db.execSQL("CREATE INDEX idx_files_size ON files(size)")
        db.execSQL("CREATE INDEX idx_files_modified ON files(modified)")
        db.execSQL("CREATE INDEX idx_files_category ON files(category)")
        db.execSQL("CREATE VIRTUAL TABLE files_fts USING fts4(name, tokenize=unicode61)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Indeks jest odtwarzalny ze skanu, więc przy zmianie schematu prościej
        // zbudować go od nowa niż migrować dane.
        db.execSQL("DROP TABLE IF EXISTS files")
        db.execSQL("DROP TABLE IF EXISTS files_fts")
        onCreate(db)
    }

    /** Wstawia lub aktualizuje partię wpisów w jednej transakcji. */
    fun upsert(entries: List<IndexedFile>) {
        if (entries.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            entries.forEach { entry ->
                db.delete("files_fts", "rowid IN (SELECT id FROM files WHERE uri = ?)", arrayOf(entry.uri))
                db.delete("files", "uri = ?", arrayOf(entry.uri))
                val id = db.insert("files", null, values(entry))
                if (id > 0) {
                    db.insert("files_fts", null, ContentValues().apply {
                        put("rowid", id)
                        put("name", entry.name)
                    })
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun values(entry: IndexedFile) = ContentValues().apply {
        put("uri", entry.uri)
        put("parent_uri", entry.parentUri)
        put("name", entry.name)
        put("size", entry.size)
        put("modified", entry.modified)
        put("is_dir", if (entry.isDirectory) 1 else 0)
        put("mime", entry.mime)
        put("category", entry.category.name)
    }

    /** Usuwa poddrzewo — po skasowaniu katalogu albo przed ponownym skanem. */
    fun deleteSubtree(rootUri: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val pattern = "$rootUri/%"
            db.execSQL(
                "DELETE FROM files_fts WHERE rowid IN (SELECT id FROM files WHERE uri = ? OR uri LIKE ?)",
                arrayOf(rootUri, pattern)
            )
            db.delete("files", "uri = ? OR uri LIKE ?", arrayOf(rootUri, pattern))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Usuwa wpisy spod [rootUri], których nie było w ostatnim pełnym skanie —
     * pliki skasowane albo przeniesione od poprzedniego indeksowania.
     * Bez tego indeks tylko by przybywał, a wyszukiwanie zwracałoby pliki,
     * których już nie ma.
     */
    fun prune(rootUri: String, seen: Set<String>): Int {
        val stale = readableDatabase.rawQuery(
            "SELECT id, uri FROM files WHERE uri LIKE ?", arrayOf("$rootUri/%")
        ).use { c ->
            buildList { while (c.moveToNext()) if (c.getString(1) !in seen) add(c.getLong(0)) }
        }
        if (stale.isEmpty()) return 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            stale.chunked(500).forEach { chunk ->
                val ids = chunk.joinToString(",")
                db.execSQL("DELETE FROM files_fts WHERE rowid IN ($ids)")
                db.execSQL("DELETE FROM files WHERE id IN ($ids)")
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return stale.size
    }

    /**
     * Kompaktowanie: scala segmenty wyszukiwania i przepisuje plik bazy bez
     * pustych miejsc po usuniętych wpisach — plik nie trzyma zwolnionego miejsca.
     */
    fun compact() {
        val db = writableDatabase
        runCatching { db.execSQL("INSERT INTO files_fts(files_fts) VALUES('optimize')") }
        runCatching { db.execSQL("VACUUM") }
    }

    /** Rozmiar pliku bazy razem z dziennikiem. */
    fun fileSize(context: Context): Long {
        val main = context.getDatabasePath(DB_NAME)
        return listOf(main, File(main.path + "-journal"), File(main.path + "-wal"), File(main.path + "-shm"))
            .sumOf { if (it.isFile) it.length() else 0L }
    }

    /** Usuwa cały indeks i oddaje miejsce. */
    fun clearAndCompact() {
        clear()
        compact()
    }

    fun clear() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("files_fts", null, null)
            db.delete("files", null, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Szuka po nazwie. FTS dopasowuje początki słów, więc „rap" znajdzie
     * „raport.pdf"; dla fraz w środku wyrazu dokładamy dopasowanie `LIKE`,
     * bo użytkownicy szukają też po fragmencie z środka nazwy.
     */
    fun search(query: String, limit: Int = 300): List<IndexedFile> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return emptyList()
        val token = trimmed.replace("\"", "").replace("*", "")
        if (token.isBlank()) return emptyList()

        val sql = """
            SELECT f.* FROM files f
            WHERE f.id IN (SELECT rowid FROM files_fts WHERE files_fts MATCH ?)
               OR f.name LIKE ? COLLATE NOCASE
            ORDER BY f.is_dir DESC, f.name COLLATE NOCASE
            LIMIT ?
        """.trimIndent()
        return readableDatabase.rawQuery(sql, arrayOf("$token*", "%$token%", limit.toString()))
            .use(::readAll)
    }

    fun byCategory(category: IndexCategory, limit: Int = 500): List<IndexedFile> =
        readableDatabase.rawQuery(
            "SELECT * FROM files WHERE is_dir = 0 AND category = ? ORDER BY size DESC LIMIT ?",
            arrayOf(category.name, limit.toString())
        ).use(::readAll)

    fun largestFiles(limit: Int = 100): List<IndexedFile> =
        readableDatabase.rawQuery(
            "SELECT * FROM files WHERE is_dir = 0 ORDER BY size DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use(::readAll)

    /** Pliki nietknięte od dawna — kandydaci do posprzątania. */
    fun olderThan(timestamp: Long, limit: Int = 100): List<IndexedFile> =
        readableDatabase.rawQuery(
            "SELECT * FROM files WHERE is_dir = 0 AND modified > 0 AND modified < ? ORDER BY modified ASC LIMIT ?",
            arrayOf(timestamp.toString(), limit.toString())
        ).use(::readAll)

    /** Katalogi z sumą rozmiarów bezpośrednio w nich leżących plików. */
    fun largestFolders(limit: Int = 50): List<FolderUsage> =
        readableDatabase.rawQuery(
            """
            SELECT parent_uri, SUM(size) AS total, COUNT(*) AS files
            FROM files WHERE is_dir = 0
            GROUP BY parent_uri
            ORDER BY total DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(limit.toString())
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        FolderUsage(
                            parentUri = cursor.getString(0),
                            totalBytes = cursor.getLong(1),
                            fileCount = cursor.getInt(2)
                        )
                    )
                }
            }
        }

    /**
     * Kandydaci na duplikaty: pliki o identycznym, niezerowym rozmiarze.
     * Porównanie zawartości (SHA-256) robimy dopiero na tej krótkiej liście,
     * bo liczenie skrótu każdego pliku w pamięci byłoby nieproporcjonalnie drogie.
     */
    fun duplicateCandidates(minSize: Long = 1, limit: Int = 200): List<DuplicateGroup> {
        val sizes = readableDatabase.rawQuery(
            """
            SELECT size FROM files
            WHERE is_dir = 0 AND size >= ?
            GROUP BY size HAVING COUNT(*) > 1
            ORDER BY size DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(minSize.toString(), limit.toString())
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) }
        }

        return sizes.map { size ->
            val files = readableDatabase.rawQuery(
                "SELECT * FROM files WHERE is_dir = 0 AND size = ?",
                arrayOf(size.toString())
            ).use(::readAll)
            DuplicateGroup(size, files)
        }.filter { it.files.size > 1 }
    }

    /**
     * Zajętość w rozbiciu na kategorie — do rozkładu „Obrazy · Filmy · Inne"
     * pod kartą nośnika. Liczone w bazie, bo lista plików bywa stutysięczna.
     */
    fun usageByCategory(): Map<IndexCategory, Long> = readableDatabase.rawQuery(
        "SELECT category, SUM(size) FROM files WHERE is_dir = 0 GROUP BY category",
        null
    ).use { cursor ->
        buildMap {
            while (cursor.moveToNext()) {
                val category = runCatching { IndexCategory.valueOf(cursor.getString(0)) }
                    .getOrDefault(IndexCategory.OTHER)
                put(category, (get(category) ?: 0L) + cursor.getLong(1))
            }
        }
    }

    /** Jak [usageByCategory], ale tylko pliki spod [rootUri] — osobno dla każdego nośnika. */
    fun usageByCategoryUnder(rootUri: String): Map<IndexCategory, Long> {
        val prefix = rootUri.trimEnd('/') + "/"
        return readableDatabase.rawQuery(
            // substr zamiast LIKE: „_” i „%” w ścieżce nie są wtedy znakami wzorca.
            "SELECT category, SUM(size) FROM files WHERE is_dir = 0 AND substr(uri, 1, ?) = ? GROUP BY category",
            arrayOf(prefix.length.toString(), prefix)
        ).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    val category = runCatching { IndexCategory.valueOf(cursor.getString(0)) }
                        .getOrDefault(IndexCategory.OTHER)
                    put(category, (get(category) ?: 0L) + cursor.getLong(1))
                }
            }
        }
    }

    /** Liczba plików w katalogu razem z podkatalogami. */
    fun countUnder(parentUri: String): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM files WHERE is_dir = 0 AND (parent_uri = ? OR uri LIKE ?)",
        arrayOf(parentUri, "$parentUri/%")
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

    /** Suma rozmiarów w katalogu razem z podkatalogami. */
    fun bytesUnder(parentUri: String): Long = readableDatabase.rawQuery(
        "SELECT SUM(size) FROM files WHERE is_dir = 0 AND (parent_uri = ? OR uri LIKE ?)",
        arrayOf(parentUri, "$parentUri/%")
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }

    fun stats(): IndexStats = readableDatabase.rawQuery(
        """
        SELECT
            SUM(CASE WHEN is_dir = 0 THEN 1 ELSE 0 END),
            SUM(CASE WHEN is_dir = 1 THEN 1 ELSE 0 END),
            SUM(CASE WHEN is_dir = 0 THEN size ELSE 0 END)
        FROM files
        """.trimIndent(),
        null
    ).use { cursor ->
        if (cursor.moveToFirst()) {
            IndexStats(cursor.getInt(0), cursor.getInt(1), cursor.getLong(2), System.currentTimeMillis())
        } else {
            IndexStats(0, 0, 0, 0)
        }
    }

    /** Czas ostatniej modyfikacji znany indeksowi — do skanu przyrostowego. */
    fun knownModified(parentUri: String): Map<String, Long> =
        readableDatabase.rawQuery(
            "SELECT uri, modified FROM files WHERE parent_uri = ?",
            arrayOf(parentUri)
        ).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) put(cursor.getString(0), cursor.getLong(1))
            }
        }

    private fun readAll(cursor: Cursor): List<IndexedFile> = buildList {
        val uri = cursor.getColumnIndexOrThrow("uri")
        val parent = cursor.getColumnIndexOrThrow("parent_uri")
        val name = cursor.getColumnIndexOrThrow("name")
        val size = cursor.getColumnIndexOrThrow("size")
        val modified = cursor.getColumnIndexOrThrow("modified")
        val isDir = cursor.getColumnIndexOrThrow("is_dir")
        val mime = cursor.getColumnIndexOrThrow("mime")
        val category = cursor.getColumnIndexOrThrow("category")
        while (cursor.moveToNext()) {
            add(
                IndexedFile(
                    uri = cursor.getString(uri),
                    parentUri = cursor.getString(parent),
                    name = cursor.getString(name),
                    size = cursor.getLong(size),
                    modified = cursor.getLong(modified),
                    isDirectory = cursor.getInt(isDir) == 1,
                    mime = cursor.getString(mime),
                    category = runCatching { IndexCategory.valueOf(cursor.getString(category)) }
                        .getOrDefault(IndexCategory.OTHER)
                )
            )
        }
    }

    private companion object {
        const val DB_NAME = "file_index.db"
        const val DB_VERSION = 1
    }
}
