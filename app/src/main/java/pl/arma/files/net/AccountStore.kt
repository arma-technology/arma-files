package pl.arma.files.net

import android.content.Context
import pl.arma.files.SecretStore

/**
 * Lista kont sieciowych. Hasła leżą w [SecretStore] (AES-GCM, klucz w
 * Android Keystore), a reszta pól w zwykłych preferencjach.
 *
 * Przy pierwszym uruchomieniu po aktualizacji przenosi tu pojedynczą
 * konfigurację FTP i SMB z poprzednich wersji, zachowując ich stare
 * identyfikatory — inaczej zapisane Ulubione i Ostatnie przestałyby działać.
 */
class AccountStore(context: Context) {

    private val prefs = context.getSharedPreferences("remote_accounts", Context.MODE_PRIVATE)
    private val secrets = SecretStore(context)
    private val legacy = context.getSharedPreferences("network_sources", Context.MODE_PRIVATE)

    @Volatile
    private var cached: List<RemoteAccount>? = null

    /**
     * Lista kont z pamięci podręcznej.
     *
     * Odczyt bez cache'u odszyfrowywał hasło każdego konta przez Android
     * Keystore, a `get(id)` woła się przy każdej operacji na pliku — także
     * przy każdej miniaturze w katalogu. Operacje Keystore idą przez osobny
     * proces systemowy, więc było to odczuwalne przy listowaniu zasobów.
     */
    fun all(): List<RemoteAccount> {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            migrateIfNeeded()
            val loaded = AccountCodec.decode(prefs.getString("accounts", "").orEmpty())
                .map { it.copy(password = secrets.get(passwordKey(it.id))) }
            cached = loaded
            return loaded
        }
    }

    private fun invalidate() {
        synchronized(this) { cached = null }
    }

    fun get(id: String?): RemoteAccount? = id?.let { wanted -> all().firstOrNull { it.id == wanted } }

    fun save(account: RemoteAccount) {
        val current = all().toMutableList()
        val index = current.indexOfFirst { it.id == account.id }
        if (index >= 0) current[index] = account else current += account
        write(current)
        secrets.put(passwordKey(account.id), account.password)
        invalidate()
    }

    fun delete(id: String) {
        write(all().filterNot { it.id == id })
        secrets.put(passwordKey(id), "")
        invalidate()
    }

    private fun write(accounts: List<RemoteAccount>) {
        prefs.edit().putString("accounts", AccountCodec.encode(accounts)).apply()
    }

    private fun passwordKey(id: String) = "account_${id}_pass"

    /**
     * Przeniesienie z formatu sprzed obsługi wielu kont. Hasła były już wtedy
     * w [SecretStore] pod kluczami `ftp_pass` i `smb_pass`, więc kopiujemy je
     * pod nowe klucze, a starych nie kasujemy — gdyby migracja poszła źle,
     * użytkownik nie traci danych logowania.
     */
    private fun migrateIfNeeded() {
        if (prefs.getBoolean("migrated", false)) return
        val migrated = mutableListOf<RemoteAccount>()

        val ftpHost = legacy.getString("ftp_host", "").orEmpty()
        if (ftpHost.isNotBlank()) {
            val secure = legacy.getBoolean("ftp_ftps", false)
            migrated += RemoteAccount(
                id = RemoteAccount.LEGACY_FTP_ID,
                type = RemoteType.FTP,
                label = if (secure) "FTPS" else "FTP",
                host = ftpHost,
                port = legacy.getInt("ftp_port", 21),
                username = legacy.getString("ftp_user", "").orEmpty(),
                password = secrets.get("ftp_pass"),
                rootPath = legacy.getString("ftp_root", "/").orEmpty(),
                secure = secure,
                enabled = legacy.getBoolean("FTP_enabled", false)
            )
        }

        val smbHost = legacy.getString("smb_host", "").orEmpty()
        if (smbHost.isNotBlank()) {
            migrated += RemoteAccount(
                id = RemoteAccount.LEGACY_SMB_ID,
                type = RemoteType.SMB,
                label = "SMB",
                host = smbHost,
                port = legacy.getInt("smb_port", 445),
                username = legacy.getString("smb_user", "").orEmpty(),
                password = secrets.get("smb_pass"),
                rootPath = legacy.getString("smb_root", "").orEmpty(),
                domain = legacy.getString("smb_domain", "").orEmpty(),
                share = legacy.getString("smb_share", "").orEmpty(),
                enabled = legacy.getBoolean("SMB_enabled", false)
            )
        }

        prefs.edit()
            .putString("accounts", AccountCodec.encode(migrated))
            .putBoolean("migrated", true)
            .apply()
        migrated.forEach { secrets.put(passwordKey(it.id), it.password) }
    }
}
