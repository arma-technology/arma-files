package pl.arma.files

import java.util.Locale

fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.getDefault(), "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.getDefault(), "%.1f MB", mb)
    val gb = mb / 1024.0
    if (gb < 1024) return String.format(Locale.getDefault(), "%.1f GB", gb)
    return String.format(Locale.getDefault(), "%.1f TB", gb / 1024.0)
}

/** Czas pozostały w formie „2 min 15 s"; null gdy nie da się oszacować. */
fun formatRemaining(seconds: Long?): String? {
    if (seconds == null) return null
    return when {
        seconds < 60 -> "$seconds s"
        seconds < 3600 -> "${seconds / 60} min ${seconds % 60} s"
        else -> "${seconds / 3600} godz ${(seconds % 3600) / 60} min"
    }
}
