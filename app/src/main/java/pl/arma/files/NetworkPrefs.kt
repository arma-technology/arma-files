package pl.arma.files

import android.content.Context
import android.net.Uri

/**
 * Ustawienia źródeł chmurowych podpiętych przez SAF.
 *
 * FTP, SFTP, SMB i WebDAV nie są już tutaj — mają własne konta w
 * [pl.arma.files.net.AccountStore], bo połączeń każdego typu może być wiele.
 */
class NetworkPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("network_sources", Context.MODE_PRIVATE)

    fun isEnabled(type: SourceType): Boolean = when (type) {
        SourceType.LOCAL -> true
        else -> prefs.getBoolean("${type.name}_enabled", false)
    }

    fun setEnabled(type: SourceType, enabled: Boolean) {
        if (type != SourceType.LOCAL) prefs.edit().putBoolean("${type.name}_enabled", enabled).apply()
    }

    fun cloudTree(type: SourceType): Uri? {
        require(type in setOf(SourceType.GOOGLE_DRIVE, SourceType.ONEDRIVE, SourceType.DROPBOX))
        return prefs.getString("${type.name}_tree", null)?.let(Uri::parse)
    }

    fun setCloudTree(type: SourceType, uri: Uri?) {
        require(type in setOf(SourceType.GOOGLE_DRIVE, SourceType.ONEDRIVE, SourceType.DROPBOX))
        prefs.edit().putString("${type.name}_tree", uri?.toString()).apply()
    }
}
