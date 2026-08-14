package pl.arma.files.ops

/**
 * Czysta logika nadawania unikalnych nazw przy konflikcie. Bez zależności od
 * Androida, dzięki czemu da się ją testować jednostkowo.
 *
 * Konwencja `nazwa (2).rozszerzenie` jest zgodna z `FileRepository.uniqueChild`,
 * żeby nazwy tworzone przez silnik i przez zwykłe tworzenie pliku wyglądały tak samo.
 */
object ConflictResolver {

    /**
     * Zwraca [desired], jeśli nazwa jest wolna; w przeciwnym razie kolejną wolną
     * wersję z licznikiem. Porównanie jest bez uwzględniania wielkości liter,
     * bo SMB i FAT tak właśnie traktują nazwy — inaczej „Plik.txt" nadpisałby
     * „plik.txt" na karcie SD.
     */
    fun uniqueName(desired: String, taken: Set<String>, isDirectory: Boolean = false): String {
        val lowercase = taken.mapTo(HashSet(taken.size)) { it.lowercase() }
        if (desired.lowercase() !in lowercase) return desired

        val (base, ext) = splitName(desired, isDirectory)
        var index = 2
        while (true) {
            val candidate = "$base ($index)$ext"
            if (candidate.lowercase() !in lowercase) return candidate
            index++
        }
    }

    /**
     * Dzieli nazwę na część bazową i rozszerzenie (z kropką).
     * Katalogi nie mają rozszerzenia. Pliki ukryte typu `.gitignore` traktujemy
     * w całości jako nazwę bazową — inaczej powstałoby coś w rodzaju „ (2).gitignore".
     */
    fun splitName(name: String, isDirectory: Boolean = false): Pair<String, String> {
        if (isDirectory) return name to ""
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return name to ""
        return name.substring(0, dot) to name.substring(dot)
    }
}
