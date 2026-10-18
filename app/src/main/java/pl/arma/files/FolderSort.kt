package pl.arma.files

import android.net.Uri

/**
 * Sortowanie folderu, którego użytkownik jeszcze nie zmieniał. Do Pobranych
 * zagląda się po to, co właśnie przyszło, więc tam najnowsze idą na górę;
 * w pozostałych folderach szuka się po nazwie.
 */
fun defaultSortFor(dir: Uri?): SortMode =
    if (dir != null && isDownloadsFolder(dir)) SortMode.DATE else SortMode.NAME

/**
 * Rozpoznaje katalog pobranych po nazwie, a nie po pełnej ścieżce — ten sam
 * katalog bywa otwarty jako `file://`, przez SAF (`primary:Download`) albo
 * na karcie SD, a każda z tych dróg kończy się tą samą nazwą.
 */
fun isDownloadsFolder(dir: Uri): Boolean {
    val name = dir.path.orEmpty().trimEnd('/').substringAfterLast('/').substringAfterLast(':')
    return name.equals("Download", ignoreCase = true) || name.equals("Downloads", ignoreCase = true)
}

/** Klucz zapamiętanego sortowania: folder albo karta bez folderu. */
fun sortKey(tab: Tab, dir: Uri?): String =
    if (tab == Tab.FILES && dir != null) dir.toString() else "tab:${tab.name}"
