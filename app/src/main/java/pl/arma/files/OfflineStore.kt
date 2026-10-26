package pl.arma.files

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Environment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Pliki i foldery z serwerów trzymane w telefonie, żeby dało się je otworzyć
 * i zmieniać bez zasięgu.
 *
 * Synchronizacja idzie w obie strony. Dla każdego pliku pamiętamy stan
 * z ostatniej synchronizacji (rozmiar i data po obu stronach); zmiana tylko
 * na serwerze trafia do telefonu, zmiana tylko w telefonie — na serwer.
 * Gdy zmieniły się obie wersje, nic nie jest nadpisywane: powstaje
 * [Conflict], a użytkownik w powiadomieniu wybiera, którą wersję zachować.
 *
 * Plik usunięty w telefonie wraca z serwera — usunięcie kopii nie kasuje
 * niczego na serwerze. Plik usunięty na serwerze znika z kopii, chyba że był
 * w telefonie zmieniony (wtedy to też rozbieżność).
 *
 * Co ma kopię, otwiera się z kopii — od razu, bez czekania na serwer — a serwer
 * jest sprawdzany w tle przez [refreshSoon], nie przy każdym dotknięciu.
 */
class OfflineStore(
    private val context: Context,
    private val repo: FileRepository,
    private val scope: CoroutineScope
) {
    data class Entry(
        val id: String,
        val source: String,
        val name: String,
        val isDirectory: Boolean,
        /** Katalog z kopią (w pamięci aplikacji albo w folderze widocznym dla użytkownika). */
        val dir: String,
        val lastSync: Long = 0L,
        val bytes: Long = 0L,
        val error: String? = null,
        val conflicts: List<Conflict> = emptyList()
    ) {
        val uri: Uri get() = Uri.parse(source)
    }

    /** Plik zmieniony po obu stronach od ostatniej synchronizacji. */
    data class Conflict(
        val path: String,
        val remoteSize: Long,
        val remoteModified: Long,
        val localSize: Long,
        val localModified: Long,
        /** Na serwerze plik usunięto, a w telefonie zmieniono. */
        val remoteDeleted: Boolean = false
    ) {
        val name: String get() = path.substringAfterLast('/')
    }

    enum class Choice { REMOTE, LOCAL, BOTH }

    enum class Location { APP, FOLDER }

    /** Postęp trwającej synchronizacji wpisu. */
    data class Progress(val files: Int, val bytes: Long)

    /** Stan pliku przy ostatniej synchronizacji: serwer i telefon. */
    private data class Base(val remoteSize: Long, val remoteModified: Long, val localSize: Long, val localModified: Long)

    private val appRoot = File(context.filesDir, "offline")
    private val registry = File(context.filesDir, "offline.json")
    private val bases = File(context.filesDir, "offline-base")
    private val prefs = context.getSharedPreferences("offline", Context.MODE_PRIVATE)
    private val lock = Mutex()
    val notifier = OfflineNotifier(context)

    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    private val _progress = MutableStateFlow<Map<String, Progress>>(emptyMap())
    val progress: StateFlow<Map<String, Progress>> = _progress.asStateFlow()

    /** Wpisy, w których synchronizacja znalazła nowe rozbieżności — aplikacja pyta o decyzję. */
    private val _newConflicts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val newConflicts: SharedFlow<String> = _newConflicts.asSharedFlow()

    /** Ostatnia próba synchronizacji w tle i liczba kolejnych porażek, dla każdego wpisu. */
    private val attempts = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Int>>()

    init {
        // Po powrocie zasięgu kopie, których nie udało się zsynchronizować, próbują od razu.
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        runCatching {
            connectivity?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    // Nowa sieć to nowa szansa na serwer — bez czekania na koniec przerwy.
                    attempts.clear()
                    if (_entries.value.any { it.error != null }) syncAll(automatic = true)
                }
            })
        }
    }

    // --- ustawienia --------------------------------------------------------------

    var location: Location
        get() = runCatching { Location.valueOf(prefs.getString("location", null) ?: "APP") }.getOrDefault(Location.APP)
        private set(value) { prefs.edit().putString("location", value.name).apply() }

    /** Co ile minut synchronizować w tle; 0 = tylko ręcznie i przy starcie. */
    var intervalMinutes: Int
        get() = prefs.getInt("interval", 60)
        set(value) {
            prefs.edit().putInt("interval", value).apply()
            OfflineSyncWorker.schedule(context, value, wifiOnly)
        }

    var wifiOnly: Boolean
        get() = prefs.getBoolean("wifi_only", true)
        set(value) {
            prefs.edit().putBoolean("wifi_only", value).apply()
            OfflineSyncWorker.schedule(context, intervalMinutes, value)
        }

    /** Folder widoczny dla użytkownika i innych aplikacji. */
    val publicRoot: File get() = File(Environment.getExternalStorageDirectory(), "ARMA Files offline")

    private fun rootFor(location: Location) = if (location == Location.APP) appRoot else publicRoot

    /** Przenosi wszystkie kopie w nowe miejsce. */
    suspend fun moveTo(target: Location) = withContext(Dispatchers.IO) {
        lock.withLock {
            if (target == location) return@withLock
            val root = rootFor(target).apply { mkdirs() }
            if (!root.isDirectory || !root.canWrite()) throw IOException(text(R.string.offline_folder_unavailable))
            _entries.value.forEach { entry ->
                val from = File(entry.dir)
                val to = uniqueDir(root, dirName(entry, target))
                if (from.exists()) {
                    if (!from.renameTo(to)) {
                        from.copyRecursively(to, overwrite = true)
                        from.deleteRecursively()
                    }
                }
                // Daty plików są częścią stanu synchronizacji — kopia musi je zachować.
                restoreDates(entry, to)
                change { list -> list.map { if (it.id == entry.id) it.copy(dir = to.path) else it } }
            }
            location = target
        }
    }

    private fun dirName(entry: Entry, location: Location) =
        if (location == Location.APP) entry.id else entry.name.replace('/', '_').takeIf(LocalNames::isSafe) ?: entry.id

    private fun uniqueDir(root: File, name: String): File {
        var candidate = File(root, name)
        var index = 2
        while (candidate.exists()) candidate = File(root, "$name ($index)").also { index++ }
        return candidate
    }

    private fun restoreDates(entry: Entry, base: File) {
        readBases(entry).forEach { (path, state) ->
            val file = localFor(entry.copy(dir = base.path), path)
            if (file.isFile) file.setLastModified(state.localModified)
        }
    }

    // --- rejestr -----------------------------------------------------------------

    private fun load(): List<Entry> = runCatching {
        val array = JSONArray(registry.readText())
        List(array.length()) { index ->
            val o = array.getJSONObject(index)
            val conflicts = o.optJSONArray("conflicts")?.let { list ->
                List(list.length()) { i ->
                    val c = list.getJSONObject(i)
                    Conflict(c.getString("path"), c.getLong("rs"), c.getLong("rm"), c.getLong("ls"), c.getLong("lm"), c.optBoolean("gone"))
                }
            }.orEmpty()
            val id = o.getString("id")
            Entry(
                id = id,
                source = o.getString("source"),
                name = o.getString("name"),
                isDirectory = o.getBoolean("dir"),
                dir = o.optString("path").ifBlank { File(appRoot, id).path },
                lastSync = o.optLong("sync"),
                bytes = o.optLong("bytes"),
                error = o.optString("error").ifBlank { null },
                conflicts = conflicts
            )
        }
    }.getOrDefault(emptyList())

    private fun save(list: List<Entry>) {
        val array = JSONArray()
        list.forEach { e ->
            array.put(JSONObject().apply {
                put("id", e.id); put("source", e.source); put("name", e.name); put("dir", e.isDirectory)
                put("path", e.dir); put("sync", e.lastSync); put("bytes", e.bytes); put("error", e.error ?: "")
                put("conflicts", JSONArray().apply {
                    e.conflicts.forEach { c ->
                        put(JSONObject().apply {
                            put("path", c.path); put("rs", c.remoteSize); put("rm", c.remoteModified)
                            put("ls", c.localSize); put("lm", c.localModified); put("gone", c.remoteDeleted)
                        })
                    }
                })
            })
        }
        val temp = File(registry.path + ".tmp")
        temp.writeText(array.toString())
        temp.renameTo(registry)
    }

    private fun change(transform: (List<Entry>) -> List<Entry>) = synchronized(this) {
        _entries.update(transform)
        save(_entries.value)
    }

    private fun readBases(entry: Entry): MutableMap<String, Base> = runCatching {
        val o = JSONObject(File(bases, "${entry.id}.json").readText())
        o.keys().asSequence().associateWith { key ->
            val a = o.getJSONArray(key)
            Base(a.getLong(0), a.getLong(1), a.getLong(2), a.getLong(3))
        }.toMutableMap()
    }.getOrDefault(mutableMapOf())

    private fun writeBases(entry: Entry, map: Map<String, Base>) {
        bases.mkdirs()
        val o = JSONObject()
        map.forEach { (path, b) -> o.put(path, JSONArray(listOf(b.remoteSize, b.remoteModified, b.localSize, b.localModified))) }
        File(bases, "${entry.id}.json").writeText(o.toString())
    }

    private fun idFor(source: String): String =
        MessageDigest.getInstance("SHA-1").digest(source.toByteArray()).take(10).joinToString("") { "%02x".format(it) }

    // --- adresy --------------------------------------------------------------------

    /** Wpis obejmujący [uri] (sam wpis albo coś w jego folderze) i ścieżka względna. */
    private fun covering(uri: Uri): Pair<Entry, String>? {
        val path = uri.path.orEmpty().trimEnd('/')
        for (entry in _entries.value) {
            val source = entry.uri
            if (source.scheme != uri.scheme || source.authority != uri.authority) continue
            val sourcePath = source.path.orEmpty().trimEnd('/')
            if (path == sourcePath) return entry to ""
            if (entry.isDirectory && path.startsWith("$sourcePath/")) return entry to path.removePrefix("$sourcePath/")
        }
        return null
    }

    private fun localFor(entry: Entry, relative: String): File {
        val base = if (entry.isDirectory) File(entry.dir) else LocalNames.child(File(entry.dir), entry.name)
        return relative.split('/').filter { it.isNotEmpty() }.fold(base) { dir, name -> LocalNames.child(dir, name) }
    }

    /** Czy [uri] jest zapisany offline sam w sobie (nie tylko jako zawartość folderu). */
    fun isEntry(uri: Uri): Boolean = _entries.value.any { it.source == uri.toString() }

    /** Kopia pliku albo folderu spod [uri], o ile istnieje. */
    fun localFile(uri: Uri): File? = covering(uri)?.let { (entry, relative) -> localFor(entry, relative) }?.takeIf { it.exists() }

    /** Kiedy ostatnio udało się zsynchronizować kopię obejmującą [uri]. */
    fun syncedAt(uri: Uri): Long? = covering(uri)?.first?.lastSync?.takeIf { it > 0 }

    /** Serwer kopii obejmującej [uri] jest poza zasięgiem: brak sieci albo ostatnia synchronizacja go nie dosięgła. */
    fun unreachable(uri: Uri): Boolean {
        val entry = covering(uri)?.first ?: return false
        val network = context.getSystemService(ConnectivityManager::class.java)?.activeNetwork
        return entry.error != null || network == null
    }

    /**
     * Kopia została właśnie pokazana — sprawdza serwer w tle, ale nie przy
     * każdym dotknięciu: po udanej synchronizacji najwcześniej po [FRESH_MS],
     * po nieudanej coraz rzadziej (1, 2, 4… do [MAX_BACKOFF_MS]). Powrót sieci
     * zeruje przerwę.
     *
     * Przez każdą sieć, także przy „Tylko przez Wi-Fi”: to ustawienie dotyczy
     * synchronizacji automatycznej w tle, a tu użytkownik właśnie ogląda
     * folder — bez tego na danych komórkowych kopia nigdy by się nie odświeżyła.
     */
    fun refreshSoon(uri: Uri) {
        val entry = covering(uri)?.first ?: return
        if (context.getSystemService(ConnectivityManager::class.java)?.activeNetwork == null) return
        val now = System.currentTimeMillis()
        val (last, failures) = attempts[entry.id] ?: (0L to 0)
        val wait = if (failures == 0) FRESH_MS else minOf(BACKOFF_MS shl (failures - 1).coerceAtMost(8), MAX_BACKOFF_MS)
        if (now - last < wait) return
        attempts[entry.id] = now to failures
        sync(entry.id)
    }

    /**
     * Zapis do kopii pliku spod [uri]; po zamknięciu strumienia zmiana od razu
     * jedzie na serwer (albo czeka na niego, gdy go nie ma).
     */
    fun writer(uri: Uri): java.io.OutputStream? {
        val (entry, relative) = covering(uri) ?: return null
        val file = localFor(entry, relative).takeIf { it.isFile } ?: return null
        return object : java.io.FileOutputStream(file, false) {
            private var closed = false
            override fun close() {
                super.close()
                if (closed) return
                closed = true
                syncAfterChange(uri)
            }
        }
    }

    private val pending = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    /**
     * Coś w obrębie kopii zmieniło się w aplikacji (zapis, usunięcie, nowy
     * folder, przeniesienie na serwerze) — synchronizacja rusza od razu po
     * ostatniej z serii zmian, bez czekania na przerwę z [refreshSoon]. Gdy
     * poprzednia jeszcze trwa, ta czeka na nią, żeby żadna zmiana nie przepadła.
     */
    fun syncAfterChange(uri: Uri) {
        val entry = covering(uri)?.first ?: return
        pending.remove(entry.id)?.cancel()
        pending[entry.id] = scope.launch {
            kotlinx.coroutines.delay(1_500)
            pending.remove(entry.id)
            attempts.remove(entry.id)
            while (entry.id in _progress.value) kotlinx.coroutines.delay(500)
            runSync(entry.id)
        }
    }

    /** Usunięte na serwerze z aplikacji znika też z kopii — bez czekania na synchronizację. */
    fun deletedOnServer(uri: Uri) {
        localFile(uri)?.deleteRecursively()
        syncAfterChange(uri)
    }

    fun list(parent: Uri): List<FileItem>? {
        val dir = localFile(parent)?.takeIf { it.isDirectory } ?: return null
        return dir.listFiles().orEmpty()
            .filterNot { it.name.endsWith(PART) }
            .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
            .map { item(parent.buildUpon().appendPath(it.name).build(), it) }
    }

    fun info(uri: Uri): FileItem? = localFile(uri)?.let { item(uri, it) }

    private fun item(uri: Uri, file: File) = FileItem(
        uri = uri,
        name = file.name,
        mimeType = if (file.isDirectory) DIRECTORY_MIME else guessMime(file.name),
        size = if (file.isFile) file.length() else 0L,
        lastModified = file.lastModified(),
        isDirectory = file.isDirectory
    )

    // --- dodawanie, usuwanie -------------------------------------------------------

    fun add(items: List<FileItem>) {
        val root = rootFor(location).apply { mkdirs() }
        val fresh = items.filter { !isEntry(it.uri) }.map {
            val draft = Entry(idFor(it.uri.toString()), it.uri.toString(), it.name, it.isDirectory, dir = "")
            draft.copy(dir = uniqueDir(root, dirName(draft, location)).path)
        }
        if (fresh.isEmpty()) return
        change { it + fresh }
        fresh.forEach { sync(it.id) }
    }

    fun remove(ids: Collection<String>) {
        val removed = _entries.value.filter { it.id in ids }
        change { list -> list.filterNot { it.id in ids } }
        removed.forEach { entry -> entry.conflicts.forEach { notifier.cancel(entry, it) } }
        scope.launch {
            removed.forEach {
                File(it.dir).deleteRecursively()
                File(bases, "${it.id}.json").delete()
            }
        }
    }

    fun removeUris(uris: Collection<Uri>) {
        val wanted = uris.map(Uri::toString).toSet()
        remove(_entries.value.filter { it.source in wanted }.map { it.id })
    }

    // --- synchronizacja ----------------------------------------------------------

    /**
     * Synchronizuje wszystkie wpisy. Automatycznie (start, powrót sieci, praca
     * w tle) tylko przy dozwolonej sieci — domyślnie bez limitu danych, czyli Wi-Fi.
     */
    fun syncAll(automatic: Boolean = false) {
        if (automatic && !networkAllowed()) return
        _entries.value.forEach { sync(it.id) }
    }

    /** Dla pracy w tle: synchronizuje i czeka na koniec. */
    suspend fun syncAllNow() {
        if (!networkAllowed()) return
        _entries.value.forEach { runSync(it.id) }
    }

    fun sync(id: String) {
        scope.launch { runSync(id) }
    }

    private suspend fun runSync(id: String) {
        // Sprawdzenie i zajęcie w jednym kroku: dwa wywołania naraz (np. przycisk
        // i powrót sieci) nie synchronizują tego samego wpisu równolegle.
        var claimed = false
        _progress.update { current ->
            claimed = id !in current
            if (claimed) current + (id to Progress(0, 0)) else current
        }
        if (!claimed) return
        try {
            // Wpis może czekać na inną synchronizację, ale nie w nieskończoność —
            // zawieszony serwer nie powinien blokować wszystkiego do restartu.
            val acquired = kotlinx.coroutines.withTimeoutOrNull(LOCK_WAIT_MS) { lock.lock() } != null
            if (!acquired) {
                change { list -> list.map { if (it.id == id) it.copy(error = text(R.string.offline_busy)) else it } }
                return
            }
            try {
                val entry = _entries.value.firstOrNull { it.id == id } ?: return
                val conflicts = mutableListOf<Conflict>()
                val result = runCatching { mirror(entry, conflicts) }
                val previous = entry.conflicts.map { it.path }.toSet()
                change { list ->
                    list.map {
                        if (it.id != id) it
                        else result.fold(
                            onSuccess = { bytes -> it.copy(lastSync = System.currentTimeMillis(), bytes = bytes, error = null, conflicts = conflicts) },
                            onFailure = { error -> it.copy(error = error.message ?: error::class.java.simpleName) }
                        )
                    }
                }
                val failures = attempts[id]?.second ?: 0
                attempts[id] = System.currentTimeMillis() to (if (result.isSuccess) 0 else failures + 1)
                if (result.isSuccess) {
                    val updated = _entries.value.first { it.id == id }
                    // Powiadamiamy tylko o nowych rozbieżnościach; rozwiązane znikają.
                    val fresh = conflicts.filter { it.path !in previous }
                    fresh.forEach { notifier.show(updated, it) }
                    if (fresh.isNotEmpty()) _newConflicts.tryEmit(id)
                    entry.conflicts.filter { old -> conflicts.none { it.path == old.path } }.forEach { notifier.cancel(entry, it) }
                }
            } finally {
                lock.unlock()
            }
        } finally {
            _progress.update { it - id }
        }
    }

    private fun networkAllowed(): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return !wifiOnly || capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** Zwraca łączny rozmiar kopii. */
    private fun mirror(entry: Entry, conflicts: MutableList<Conflict>): Long {
        val states = readBases(entry)
        try {
            return if (entry.isDirectory) {
                File(entry.dir).mkdirs()
                mirrorDir(entry, entry.uri, "", states, conflicts)
            } else {
                File(entry.dir).mkdirs()
                val remote = repo.remoteInfo(entry.uri)
                syncFile(entry, entry.uri, "", remote, states, conflicts)
                localFor(entry, "").takeIf { it.isFile }?.length() ?: 0L
            }
        } finally {
            writeBases(entry, states)
        }
    }

    private fun mirrorDir(entry: Entry, remoteDir: Uri, relative: String, states: MutableMap<String, Base>, conflicts: MutableList<Conflict>): Long {
        val local = localFor(entry, relative).apply { mkdirs() }
        // Nazwa „..” albo z ukośnikiem z listingu serwera nie trafia do kopii w telefonie.
        val children = repo.remoteList(remoteDir).filter { LocalNames.isSafe(it.name) }
        val remoteNames = children.map { it.name }.toSet()
        var total = 0L
        children.forEach { child ->
            val path = if (relative.isEmpty()) child.name else "$relative/${child.name}"
            if (child.isDirectory) {
                total += mirrorDir(entry, child.uri, path, states, conflicts)
            } else {
                syncFile(entry, child.uri, path, child, states, conflicts)
                total += localFor(entry, path).takeIf { it.isFile }?.length() ?: 0L
            }
        }
        // Czego nie ma na serwerze: nowe w telefonie (wysłać) albo usunięte na serwerze.
        local.listFiles()?.filter { it.name !in remoteNames && !it.name.endsWith(PART) }?.forEach { file ->
            val path = if (relative.isEmpty()) file.name else "$relative/${file.name}"
            val known = states.keys.any { it == path || it.startsWith("$path/") }
            if (file.isDirectory) {
                if (known) {
                    // Folder usunięty na serwerze: znika, chyba że coś w nim zmieniono w telefonie.
                    val changed = file.walkTopDown().filter { it.isFile }.any { f ->
                        val p = path + f.path.removePrefix(file.path).replace(File.separatorChar, '/')
                        states[p]?.let { b -> f.length() != b.localSize || f.lastModified() / 1000 != b.localModified / 1000 } ?: true
                    }
                    if (!changed) {
                        file.deleteRecursively()
                        states.keys.removeAll { it == path || it.startsWith("$path/") }
                    } else {
                        file.walkTopDown().filter { it.isFile }.forEach { f ->
                            val p = path + f.path.removePrefix(file.path).replace(File.separatorChar, '/')
                            conflicts += Conflict(p, 0, 0, f.length(), f.lastModified(), remoteDeleted = true)
                        }
                    }
                } else {
                    val created = repo.remoteCreateFolder(remoteDir, file.name)
                    total += mirrorDir(entry, created, path, states, conflicts)
                }
            } else {
                syncFile(entry, Uri.EMPTY, path, null, states, conflicts, remoteParent = remoteDir)
                total += file.takeIf { it.isFile }?.length() ?: 0L
            }
        }
        return total
    }

    /** Jeden plik: pobranie, wysłanie, usunięcie albo rozbieżność. */
    private fun syncFile(
        entry: Entry,
        remoteUri: Uri,
        path: String,
        remote: FileItem?,
        states: MutableMap<String, Base>,
        conflicts: MutableList<Conflict>,
        remoteParent: Uri? = null
    ) {
        val local = localFor(entry, path)
        val base = states[path]
        val hasLocal = local.isFile
        val remoteChanged = remote != null && (base == null || remote.size != base.remoteSize || !sameSecond(remote.lastModified, base.remoteModified))
        val localChanged = hasLocal && (base == null || local.length() != base.localSize || !sameSecond(local.lastModified(), base.localModified))
        when {
            remote != null && !hasLocal -> download(entry, remote, local, path, states)
            remote == null && hasLocal && base == null -> upload(entry, local, path, states, remoteParent ?: return, null)
            remote == null && hasLocal -> {
                if (localChanged) conflicts += Conflict(path, 0, 0, local.length(), local.lastModified(), remoteDeleted = true)
                else { local.delete(); states.remove(path) }
            }
            remote == null -> states.remove(path)
            base == null && local.length() == remote.size && (remote.lastModified <= 0 || sameSecond(local.lastModified(), remote.lastModified)) ->
                states[path] = Base(remote.size, remote.lastModified, local.length(), local.lastModified())
            base == null || (remoteChanged && localChanged) ->
                conflicts += Conflict(path, remote.size, remote.lastModified, local.length(), local.lastModified())
            remoteChanged -> download(entry, remote, local, path, states)
            localChanged -> upload(entry, local, path, states, null, remote.uri)
        }
    }

    private fun sameSecond(a: Long, b: Long) = a / 1000 == b / 1000

    private fun download(entry: Entry, remote: FileItem, target: File, path: String, states: MutableMap<String, Base>) {
        target.parentFile?.mkdirs()
        val temp = File(target.path + PART)
        repo.remoteInput(remote.uri).use { input -> temp.outputStream().buffered().use { input.copyTo(it) } }
        if (target.exists()) target.delete()
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        if (remote.lastModified > 0) target.setLastModified(remote.lastModified)
        states[path] = Base(remote.size, remote.lastModified, target.length(), target.lastModified())
        advance(entry, target.length())
    }

    /** Wysyła kopię z telefonu na serwer: nadpisuje [existing] albo tworzy plik w [parent]. */
    private fun upload(entry: Entry, local: File, path: String, states: MutableMap<String, Base>, parent: Uri?, existing: Uri?) {
        val target = existing ?: repo.remoteCreateFile(parent!!, local.name)
        local.inputStream().use { input -> repo.remoteOutput(target).use { input.copyTo(it) } }
        val after = repo.remoteInfo(target)
        states[path] = Base(after?.size ?: local.length(), after?.lastModified ?: 0L, local.length(), local.lastModified())
        advance(entry, local.length())
    }

    private fun advance(entry: Entry, bytes: Long) {
        _progress.update { map ->
            // Tylko w trwającej synchronizacji. Pobranie przy wyborze wersji
            // ([resolve]) dopisywało tu wpis, którego nikt potem nie usuwał:
            // wpis wisiał jako „Synchronizacja…”, a każda następna była
            // pomijana jako już trwająca.
            val current = map[entry.id] ?: return@update map
            map + (entry.id to Progress(current.files + 1, current.bytes + bytes))
        }
    }

    // --- rozbieżności ----------------------------------------------------------

    /** Wykonuje wybór użytkownika dla rozbieżności [path] we wpisie [entryId]. */
    fun resolve(entryId: String, path: String, choice: Choice) {
        scope.launch {
            lock.withLock {
                val entry = _entries.value.firstOrNull { it.id == entryId } ?: return@withLock
                val conflict = entry.conflicts.firstOrNull { it.path == path } ?: return@withLock
                val states = readBases(entry)
                val result = runCatching { apply(entry, conflict, choice, states) }
                writeBases(entry, states)
                notifier.cancel(entry, conflict)
                change { list ->
                    list.map {
                        if (it.id != entryId) it
                        else result.fold(
                            onSuccess = { _ -> it.copy(conflicts = it.conflicts.filterNot { c -> c.path == path }) },
                            onFailure = { error -> it.copy(error = error.message ?: error::class.java.simpleName) }
                        )
                    }
                }
            }
        }
    }

    private fun apply(entry: Entry, conflict: Conflict, choice: Choice, states: MutableMap<String, Base>) {
        val local = localFor(entry, conflict.path)
        val remoteUri = remoteUriFor(entry, conflict.path)
        val parentUri = if (entry.isDirectory) remoteUriFor(entry, conflict.path.substringBeforeLast('/', ""))
        else entry.uri.buildUpon().path(entry.uri.path.orEmpty().trimEnd('/').substringBeforeLast('/', "")).build()
        when (choice) {
            Choice.REMOTE -> if (conflict.remoteDeleted) {
                local.delete()
                states.remove(conflict.path)
            } else {
                download(entry, repo.remoteInfo(remoteUri) ?: throw IOException(text(R.string.offline_missing)), local, conflict.path, states)
            }
            Choice.LOCAL -> upload(entry, local, conflict.path, states, parentUri, if (conflict.remoteDeleted) null else remoteUri)
            Choice.BOTH -> {
                // Wersja z telefonu dostaje nową nazwę i trafia na serwer obok tej z serwera.
                val renamed = File(local.parentFile, phoneName(local.name, local.parentFile))
                local.renameTo(renamed)
                val renamedPath = conflict.path.substringBeforeLast('/', "").let { if (it.isEmpty()) renamed.name else "$it/${renamed.name}" }
                upload(entry, renamed, renamedPath, states, parentUri, null)
                // Pojedynczy plik offline obejmuje tylko siebie — kopia pod nową nazwą jest już na serwerze.
                if (!entry.isDirectory) {
                    renamed.delete()
                    states.remove(renamedPath)
                }
                if (conflict.remoteDeleted) states.remove(conflict.path)
                else download(entry, repo.remoteInfo(remoteUri) ?: throw IOException(text(R.string.offline_missing)), local, conflict.path, states)
            }
        }
    }

    private fun phoneName(name: String, dir: File?): String {
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty() || it == name) "" else ".$it" }
        val suffix = text(R.string.offline_phone_suffix)
        var candidate = "$base ($suffix)$ext"
        var index = 2
        while (dir != null && File(dir, candidate).exists()) candidate = "$base ($suffix $index)$ext".also { index++ }
        return candidate
    }

    private fun remoteUriFor(entry: Entry, path: String): Uri {
        if (!entry.isDirectory) return entry.uri
        return path.split('/').filter { it.isNotEmpty() }.fold(entry.uri) { uri, name -> uri.buildUpon().appendPath(name).build() }
    }

    private companion object {
        const val PART = ".offline-part"
        const val LOCK_WAIT_MS = 120_000L
        /** Kopia sprawdzona z powodzeniem — następne sprawdzenie w tle najwcześniej po minucie. */
        const val FRESH_MS = 60_000L
        /** Serwer nie odpowiedział — przerwa zaczyna się od minuty i rośnie dwukrotnie… */
        const val BACKOFF_MS = 60_000L
        /** …aż do kwadransa. */
        const val MAX_BACKOFF_MS = 15 * 60_000L
    }
}
