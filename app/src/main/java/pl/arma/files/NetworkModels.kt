package pl.arma.files

import android.net.Uri

enum class SourceType { LOCAL, GOOGLE_DRIVE, ONEDRIVE, DROPBOX, FTP, SMB }

data class StorageSource(
    val type: SourceType,
    val label: String,
    val enabled: Boolean,
    val configured: Boolean,
    val rootUri: Uri?,
    val subtitle: String = "",
    /**
     * Konto sieciowe, z którego pochodzi to źródło. Null dla pamięci lokalnej
     * i dysków chmurowych podpiętych przez SAF. Dzięki niemu na liście może
     * stać kilka połączeń tego samego typu — „NAS Dom" obok „NAS Firma".
     */
    val accountId: String? = null
)

data class FtpConfig(
    val enabled: Boolean = false,
    val host: String = "",
    val port: Int = 21,
    val username: String = "",
    val password: String = "",
    val rootPath: String = "/",
    val ftps: Boolean = false
)

data class SmbConfig(
    val enabled: Boolean = false,
    val host: String = "",
    val port: Int = 445,
    val share: String = "",
    val rootPath: String = "",
    val domain: String = "",
    val username: String = "",
    val password: String = ""
)
