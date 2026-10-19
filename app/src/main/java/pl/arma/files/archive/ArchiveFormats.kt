package pl.arma.files.archive

import java.util.Locale

/** Rozpoznawanie archiwów po nazwie — do decyzji, co robi dotknięcie pliku. */
object ArchiveFormats {

    /** Podwójne rozszerzenia sprawdzane przed pojedynczymi. */
    private val compound = listOf(
        ".tar.gz", ".tar.bz2", ".tar.xz", ".tar.zst", ".tar.lz4", ".tar.lzma",
        ".tar.br", ".tar.z", ".tar.sz"
    )

    /** Pliki, które dotknięte otwierają się jak folder. */
    private val browsable = setOf(
        "zip", "zipx", "jar", "war", "ear", "cbz",
        "7z", "cb7",
        "tar", "cbt", "tgz", "taz", "tbz", "tbz2", "txz", "tzst", "tlz", "tlz4",
        "gz", "bz2", "xz", "lzma", "zst", "lz4", "br", "z", "sz",
        "ar", "deb", "cpio", "arj",
        "rar", "cbr", "iso", "cab", "lzh", "lha", "xar"
    )

    /**
     * Formaty, które w środku są archiwum ZIP, ale mają własne przeznaczenie
     * (instalacja, dokument) — do przeglądania tylko na żądanie.
     */
    private val zipBased = setOf(
        "apk", "aab", "apks", "apkm", "xapk",
        "docx", "xlsx", "pptx", "docm", "xlsm", "pptm",
        "odt", "ods", "odp", "odg", "epub", "kmz", "3mf", "vsdx", "whl", "nupkg", "xpi"
    )

    fun extension(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.ROOT)

    fun canBrowse(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return compound.any(lower::endsWith) || extension(name) in browsable
    }

    /** Czy można zajrzeć do środka — wszystko, co [canBrowse], i formaty oparte na ZIP. */
    fun canOpenAsArchive(name: String): Boolean = canBrowse(name) || extension(name) in zipBased

    /** Nazwa bez rozszerzenia archiwum: „projekt.tar.gz” → „projekt”. */
    fun baseName(name: String): String {
        val lower = name.lowercase(Locale.ROOT)
        compound.firstOrNull(lower::endsWith)?.let { return name.dropLast(it.length).ifBlank { name } }
        return name.substringBeforeLast('.', name).ifBlank { name }
    }

    /**
     * Nazwa pliku w archiwum jednoplikowym (np. `.gz` bez tar), gdy nagłówek
     * jej nie podaje: „raport.csv.gz” → „raport.csv”, „dane.tgz” → „dane.tar”.
     */
    fun innerName(name: String): String = when (extension(name)) {
        "tgz", "taz", "tbz", "tbz2", "txz", "tzst", "tlz", "tlz4" -> baseName(name) + ".tar"
        else -> name.substringBeforeLast('.', name).ifBlank { name }
    }
}
