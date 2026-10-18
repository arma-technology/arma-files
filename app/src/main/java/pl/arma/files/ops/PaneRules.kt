package pl.arma.files.ops

/**
 * Reguły zarządzania kartami, wydzielone z ViewModelu, żeby dało się je
 * sprawdzić bez Androida. Same przejścia są proste, ale łatwo się w nich
 * pomylić przy zamykaniu karty aktywnej albo tej stojącej w drugim panelu.
 */
object PaneRules {

    /**
     * Która karta ma stać się aktywna po zamknięciu [closing].
     * Zwraca null, gdy zamknięcie jest niedozwolone — ostatniej karty nie
     * zamykamy, bo aplikacja zostałaby bez widoku.
     */
    fun activeAfterClose(ids: List<String>, activeId: String, closing: String): String? {
        if (ids.size <= 1 || closing !in ids) return null
        if (closing != activeId) return activeId
        val index = ids.indexOf(closing)
        val remaining = ids.filterNot { it == closing }
        return remaining[index.coerceAtMost(remaining.lastIndex)]
    }

    /**
     * Nazwa karty na podstawie ścieżki; null oznacza ekran „Mój komputer".
     * Obie nazwy zastępcze podaje wołający, w języku aplikacji.
     */
    fun tabTitle(path: String?, home: String, root: String): String {
        if (path == null) return home
        return path.trimEnd('/')
            .substringAfterLast('/')
            .substringAfterLast(':')
            .ifBlank { root }
    }
}
