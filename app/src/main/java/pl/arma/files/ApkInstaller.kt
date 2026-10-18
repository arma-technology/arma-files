package pl.arma.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

const val APK_MIME = "application/vnd.android.package-archive"

fun isApk(item: FileItem): Boolean =
    item.mimeType == APK_MIME || item.name.endsWith(".apk", ignoreCase = true)

/**
 * Uruchamia systemowy instalator dla pliku APK.
 *
 * Plik musi być podany jako `content://` z FileProvidera — instalator jest
 * osobnym procesem i nie ma dostępu do `file://` ani do naszego cache'u.
 * Zwraca komunikat błędu albo null, gdy instalator ruszył.
 */
fun installApk(context: Context, uri: Uri): String? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
        !context.packageManager.canRequestPackageInstalls()
    ) {
        // Android wymaga zgody „instalowanie nieznanych aplikacji" nadanej
        // konkretnej aplikacji; bez niej instalator w ogóle się nie pokaże.
        val settings = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (runCatching { context.startActivity(settings) }.isSuccess) {
            context.getString(R.string.apk_allow)
        } else {
            context.getString(R.string.apk_unsupported)
        }
    }

    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, APK_MIME)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return if (runCatching { context.startActivity(intent) }.isSuccess) {
        null
    } else {
        context.getString(R.string.apk_failed)
    }
}
