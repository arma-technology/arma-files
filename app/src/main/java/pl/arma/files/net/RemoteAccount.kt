package pl.arma.files.net

import pl.arma.files.ops.Escaping

enum class RemoteType { FTP, SFTP, SMB, WEBDAV }

/**
 * Jedno połączenie sieciowe. W odróżnieniu od poprzedniej wersji, w której
 * istniała dokładnie jedna konfiguracja FTP i jedna SMB, kont może być wiele —
 * „NAS Dom", „NAS Firma", „Backup" obok siebie.
 *
 * [id] trafia do URI jako autorytet (`armasmb://<id>/ścieżka`), dzięki czemu
 * zapisane ulubione i ostatnio używane pliki wiedzą, z którego serwera
 * pochodzą. Z tego powodu identyfikator musi być stabilny i bezpieczny w URI.
 */
data class RemoteAccount(
    val id: String,
    val type: RemoteType,
    val label: String,
    val host: String,
    val port: Int,
    val username: String,
    val password: String = "",
    val rootPath: String = "",
    val domain: String = "",
    val share: String = "",
    /** FTPS dla FTP, HTTPS dla WebDAV. Dla SFTP bez znaczenia — zawsze szyfrowany. */
    val secure: Boolean = false,
    val enabled: Boolean = true
) {
    val configured: Boolean
        get() = host.isNotBlank() && (type != RemoteType.SMB || share.isNotBlank())

    /** Podpis do pokazania na liście źródeł. */
    val summary: String
        get() = when (type) {
            RemoteType.SMB -> "$host/$share"
            RemoteType.WEBDAV -> "${if (secure) "https" else "http"}://$host$rootPath"
            else -> "$host:$port"
        }

    companion object {
        fun defaultPort(type: RemoteType, secure: Boolean): Int = when (type) {
            RemoteType.FTP -> 21
            RemoteType.SFTP -> 22
            RemoteType.SMB -> 445
            RemoteType.WEBDAV -> if (secure) 443 else 80
        }

        /**
         * Identyfikatory starych, pojedynczych konfiguracji. Zachowujemy je przy
         * migracji, żeby zapisane wcześniej URI w Ostatnich i Ulubionych
         * (`armaftp://ftp/...`) nadal wskazywały to samo konto.
         */
        const val LEGACY_FTP_ID = "ftp"
        const val LEGACY_SMB_ID = "smb"

        /** Identyfikator ma trafić do autorytetu URI, więc tylko znaki bezpieczne. */
        fun newId(): String = "a" + java.util.UUID.randomUUID().toString().replace("-", "").take(12)
    }
}

/**
 * Zapis listy kont w jednej linii na konto. Hasła trzymane są osobno, w
 * [pl.arma.files.SecretStore], więc tutaj nigdy nie trafiają — ta lista ląduje
 * w zwykłych SharedPreferences.
 */
object AccountCodec {

    private const val SEPARATOR = '\t'
    private const val FIELDS = 11

    fun encode(accounts: List<RemoteAccount>): String = accounts.joinToString("\n") { account ->
        listOf(
            account.id,
            account.type.name,
            account.label,
            account.host,
            account.port.toString(),
            account.username,
            account.rootPath,
            account.domain,
            account.share,
            account.secure.toString(),
            account.enabled.toString()
        ).joinToString(SEPARATOR.toString()) { Escaping.escape(it) }
    }

    fun decode(text: String): List<RemoteAccount> = text
        .lineSequence()
        .filter { it.isNotBlank() }
        .mapNotNull { line ->
            val parts = line.split(SEPARATOR)
            if (parts.size != FIELDS) return@mapNotNull null
            runCatching {
                RemoteAccount(
                    id = Escaping.unescape(parts[0]),
                    type = RemoteType.valueOf(Escaping.unescape(parts[1])),
                    label = Escaping.unescape(parts[2]),
                    host = Escaping.unescape(parts[3]),
                    port = Escaping.unescape(parts[4]).toInt(),
                    username = Escaping.unescape(parts[5]),
                    rootPath = Escaping.unescape(parts[6]),
                    domain = Escaping.unescape(parts[7]),
                    share = Escaping.unescape(parts[8]),
                    secure = Escaping.unescape(parts[9]).toBooleanStrict(),
                    enabled = Escaping.unescape(parts[10]).toBooleanStrict()
                )
            }.getOrNull()
        }
        .toList()
}
