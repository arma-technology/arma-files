package pl.arma.files

import android.content.Context
import android.net.Uri

class NetworkPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("network_sources", Context.MODE_PRIVATE)
    private val secrets = SecretStore(context)

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

    fun ftp(): FtpConfig = FtpConfig(
        enabled = isEnabled(SourceType.FTP),
        host = prefs.getString("ftp_host", "").orEmpty(),
        port = prefs.getInt("ftp_port", 21),
        username = prefs.getString("ftp_user", "").orEmpty(),
        password = secrets.get("ftp_pass"),
        rootPath = prefs.getString("ftp_root", "/").orEmpty().ifBlank { "/" },
        ftps = prefs.getBoolean("ftp_ftps", false)
    )

    fun saveFtp(config: FtpConfig) {
        prefs.edit()
            .putBoolean("FTP_enabled", config.enabled)
            .putString("ftp_host", config.host.trim())
            .putInt("ftp_port", config.port)
            .putString("ftp_user", config.username)
            .putString("ftp_root", config.rootPath.ifBlank { "/" })
            .putBoolean("ftp_ftps", config.ftps)
            .apply()
        secrets.put("ftp_pass", config.password)
    }

    fun smb(): SmbConfig = SmbConfig(
        enabled = isEnabled(SourceType.SMB),
        host = prefs.getString("smb_host", "").orEmpty(),
        port = prefs.getInt("smb_port", 445),
        share = prefs.getString("smb_share", "").orEmpty(),
        rootPath = prefs.getString("smb_root", "").orEmpty(),
        domain = prefs.getString("smb_domain", "").orEmpty(),
        username = prefs.getString("smb_user", "").orEmpty(),
        password = secrets.get("smb_pass")
    )

    fun saveSmb(config: SmbConfig) {
        prefs.edit()
            .putBoolean("SMB_enabled", config.enabled)
            .putString("smb_host", config.host.trim())
            .putInt("smb_port", config.port)
            .putString("smb_share", config.share.trim().trim('/'))
            .putString("smb_root", config.rootPath.trim().trim('/'))
            .putString("smb_domain", config.domain.trim())
            .putString("smb_user", config.username)
            .apply()
        secrets.put("smb_pass", config.password)
    }
}
