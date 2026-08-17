package pl.arma.files.net

import org.json.JSONObject

/**
 * Plik w Google Drive. Drive nie ma ścieżek — każdy element ma identyfikator,
 * a katalogi to też „pliki", tyle że o specjalnym typie MIME.
 */
data class DriveFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val size: Long,
    val modified: Long
) {
    val isFolder: Boolean get() = mimeType == FOLDER_MIME

    /**
     * Dokumenty Google (Docs, Arkusze, Prezentacje) nie mają zawartości do
     * pobrania — trzeba je eksportować do wybranego formatu. Nie mają też
     * rozmiaru, więc bez tego rozróżnienia pokazywałyby się jako puste pliki.
     */
    val isGoogleDoc: Boolean
        get() = mimeType.startsWith("application/vnd.google-apps.") && !isFolder

    companion object {
        const val FOLDER_MIME = "application/vnd.google-apps.folder"
    }
}

/** Odczyt odpowiedzi Drive API v3. */
object DriveJson {

    fun parseFileList(json: String): Pair<List<DriveFile>, String?> {
        val root = JSONObject(json)
        val array = root.optJSONArray("files") ?: return emptyList<DriveFile>() to null
        val files = buildList {
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let(::parseFile)?.let(::add)
            }
        }
        return files to root.optString("nextPageToken").takeIf { it.isNotBlank() }
    }

    fun parseFile(json: JSONObject): DriveFile? {
        val id = json.optString("id").takeIf { it.isNotBlank() } ?: return null
        val name = json.optString("name").ifBlank { id }
        val mime = json.optString("mimeType")
        return DriveFile(
            id = id,
            name = name,
            mimeType = mime,
            // Drive zwraca rozmiar jako łańcuch i pomija go dla katalogów
            // oraz dokumentów Google.
            size = json.optString("size").toLongOrNull() ?: 0L,
            modified = parseTimestamp(json.optString("modifiedTime"))
        )
    }

    fun parseFile(json: String): DriveFile? = runCatching { parseFile(JSONObject(json)) }.getOrNull()

    /** Drive podaje czas w RFC 3339, np. `2026-08-17T09:15:00.000Z`. */
    fun parseTimestamp(value: String): Long {
        if (value.isBlank()) return 0L
        return runCatching {
            val format = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
            format.timeZone = java.util.TimeZone.getTimeZone("UTC")
            format.parse(value.substringBefore('.').removeSuffix("Z"))?.time ?: 0L
        }.getOrDefault(0L)
    }

    /** Komunikat błędu z odpowiedzi API, jeśli da się go odczytać. */
    fun parseError(json: String): String? = runCatching {
        JSONObject(json).optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
    }.getOrNull()
}
