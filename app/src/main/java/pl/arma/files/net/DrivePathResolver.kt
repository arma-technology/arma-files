package pl.arma.files.net

/**
 * Tłumaczy ścieżki na identyfikatory Drive.
 *
 * Reszta aplikacji posługuje się ścieżkami (`/Zdjęcia/2026`), a Drive wyłącznie
 * identyfikatorami. Zamiana wymaga przejścia od korzenia katalog po katalogu,
 * więc wyniki trzymamy w pamięci — bez tego otwarcie jednego folderu oznaczałoby
 * tyle zapytań do API, ile ma poziomów zagnieżdżenia, przy każdej operacji.
 *
 * Rozwiązywanie jest wstrzykiwane, dzięki czemu logikę cache'u da się
 * przetestować bez sieci.
 */
class DrivePathResolver(
    private val listChildren: (parentId: String) -> List<DriveFile>
) {
    private val cache = HashMap<String, String>()
    private val lock = Any()

    init {
        synchronized(lock) { cache["/"] = ROOT_ID }
    }

    /** Identyfikator katalogu lub pliku pod daną ścieżką; null gdy nie istnieje. */
    fun resolve(path: String): String? {
        val normalized = RemotePaths.normalize(path)
        synchronized(lock) { cache[normalized] }?.let { return it }

        val segments = normalized.split('/').filter { it.isNotBlank() }
        var currentPath = "/"
        var currentId = ROOT_ID

        segments.forEach { segment ->
            val childPath = if (currentPath == "/") "/$segment" else "$currentPath/$segment"
            val cached = synchronized(lock) { cache[childPath] }
            val id = cached ?: listChildren(currentId)
                .firstOrNull { it.name == segment }
                ?.id
                ?: return null
            synchronized(lock) { cache[childPath] = id }
            currentPath = childPath
            currentId = id
        }
        return currentId
    }

    fun remember(path: String, id: String) {
        synchronized(lock) { cache[RemotePaths.normalize(path)] = id }
    }

    /**
     * Usuwa z pamięci ścieżkę i wszystko pod nią — po zmianie nazwy albo
     * usunięciu identyfikatory dzieci przestają odpowiadać starym ścieżkom.
     */
    fun invalidate(path: String) {
        val normalized = RemotePaths.normalize(path)
        synchronized(lock) {
            if (normalized == "/") {
                cache.keys.retainAll { it == "/" }
                return
            }
            cache.keys.removeAll { it == normalized || it.startsWith("$normalized/") }
        }
    }

    fun clear() {
        synchronized(lock) {
            cache.clear()
            cache["/"] = ROOT_ID
        }
    }

    /** Liczba zapamiętanych ścieżek — na potrzeby testów. */
    fun cachedCount(): Int = synchronized(lock) { cache.size }

    companion object {
        /** Drive przyjmuje „root" jako alias identyfikatora katalogu głównego. */
        const val ROOT_ID = "root"
    }
}
