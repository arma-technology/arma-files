package pl.arma.files

import android.content.Context
import android.net.Uri

class StoragePrefs(context: Context) {
    private val prefs = context.getSharedPreferences("storage", Context.MODE_PRIVATE)

    /** Czy wyszukiwanie obejmuje też zasoby sieciowe. */
    var searchRemote: Boolean
        get() = prefs.getBoolean("search_remote", false)
        set(value) { prefs.edit().putBoolean("search_remote", value).apply() }

    var treeUri: Uri?
        get() = prefs.getString("tree_uri", null)?.let(Uri::parse)
        set(value) { prefs.edit().putString("tree_uri", value?.toString()).apply() }

    fun recents(): List<Uri> = prefs.getString("recents_list", "")
        .orEmpty().lineSequence().filter { it.isNotBlank() }.map(Uri::parse).toList()

    fun favorites(): Set<String> = prefs.getStringSet("favorites", emptySet()).orEmpty()

    fun addRecent(uri: Uri) {
        val current = recents().map(Uri::toString).toMutableList()
        current.remove(uri.toString())
        current.add(0, uri.toString())
        prefs.edit().putString("recents_list", current.take(40).joinToString("\n")).apply()
    }

    fun toggleFavorite(uri: Uri) {
        val set = prefs.getStringSet("favorites", emptySet()).orEmpty().toMutableSet()
        if (!set.add(uri.toString())) set.remove(uri.toString())
        prefs.edit().putStringSet("favorites", set).apply()
    }

    /**
     * Przypięte foldery jako pary „URI\tetykieta", w kolejności ustawionej
     * przez użytkownika. Brak klucza oznacza, że listy jeszcze nie tknięto —
     * wtedy ViewModel zasiewa ją domyślnymi folderami.
     */
    fun pinned(): List<Pair<String, String>>? {
        val raw = prefs.getString("pinned_folders", null) ?: return null
        return raw.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size == 2) parts[0] to parts[1] else null
            }
            .toList()
    }

    fun setPinned(entries: List<Pair<String, String>>) {
        prefs.edit()
            .putString("pinned_folders", entries.joinToString("\n") { "${it.first}\t${it.second}" })
            .apply()
    }

    /** Sortowanie wybrane dla folderu; null, gdy zostało domyślne. */
    fun sortMode(key: String): SortMode? =
        prefs.getString("sort:$key", null)?.let { runCatching { SortMode.valueOf(it) }.getOrNull() }

    fun setSortMode(key: String, mode: SortMode?) {
        prefs.edit().apply {
            if (mode == null) remove("sort:$key") else putString("sort:$key", mode.name)
        }.apply()
    }

    /**
     * Nazwa, rozmiar i data elementu z Ostatnich/Ulubionych. Dzięki nim listy
     * pokazują się od razu, bez łączenia z serwerem przy każdym starcie —
     * a przy niedostępnym serwerze bez czekania na limit czasu.
     */
    fun saveMeta(item: FileItem) {
        val value = listOf(item.name, item.size, item.lastModified, item.isDirectory, item.mimeType).joinToString("\t")
        prefs.edit().putString("meta:${item.uri}", value).apply()
    }

    fun meta(uri: Uri): FileItem? {
        val parts = prefs.getString("meta:$uri", null)?.split('\t') ?: return null
        if (parts.size != 5) return null
        return FileItem(uri, parts[0], parts[4], parts[1].toLongOrNull() ?: 0, parts[2].toLongOrNull() ?: 0, parts[3] == "true")
    }

    /** Metadane wpisów, których nie ma już ani w Ostatnich, ani w Ulubionych. */
    fun pruneMeta() {
        val keep = (recents().map(Uri::toString) + favorites()).toSet()
        val stale = prefs.all.keys.filter { it.startsWith("meta:") && it.removePrefix("meta:") !in keep }
        if (stale.isNotEmpty()) prefs.edit().apply { stale.forEach(::remove) }.apply()
    }

    fun replaceUri(old: Uri, new: Uri) {
        val oldValue = old.toString()
        val newValue = new.toString()

        val recentValues = recents().map(Uri::toString).map { if (it == oldValue) newValue else it }
        val favoriteValues = favorites().toMutableSet().apply {
            if (remove(oldValue)) add(newValue)
        }

        prefs.edit()
            .putString("recents_list", recentValues.distinct().take(40).joinToString("\n"))
            .putStringSet("favorites", favoriteValues)
            .apply()
    }
}
