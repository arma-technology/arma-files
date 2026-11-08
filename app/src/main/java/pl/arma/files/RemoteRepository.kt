package pl.arma.files

import android.net.Uri
import pl.arma.files.net.AccountStore
import pl.arma.files.net.CertificatePins
import pl.arma.files.net.FtpBackend
import pl.arma.files.net.RemoteAccount
import pl.arma.files.net.RemoteBackend
import pl.arma.files.net.RemoteEntry
import pl.arma.files.net.RemotePaths
import pl.arma.files.net.RemoteType
import pl.arma.files.net.SftpBackend
import pl.arma.files.net.SmbBackend
import pl.arma.files.net.WebDavBackend
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Wspólne wejście do zasobów sieciowych. Sam nie zna protokołów — rozpoznaje
 * konto po autorytecie URI i przekazuje pracę odpowiedniemu backendowi.
 */
class RemoteRepository(
    private val lookup: (String?) -> RemoteAccount?,
    /** Zapamiętane klucze serwerów SSH; null tylko w testach. */
    private val knownHosts: java.io.File? = null
) {

    constructor(accounts: AccountStore, knownHosts: java.io.File) : this({ id -> accounts.get(id) }, knownHosts)

    companion object {
        /** Schemat dla nowych kont; protokół wynika z konta, nie z adresu. */
        const val NET_SCHEME = "armanet"

        /** Schematy sprzed obsługi wielu kont — nadal spotykane w Ulubionych. */
        const val FTP_SCHEME = "armaftp"
        const val SMB_SCHEME = "armasmb"

        fun rootOf(account: RemoteAccount): Uri = Uri.Builder()
            .scheme(schemeFor(account))
            .authority(account.id)
            .path("/")
            .build()

        /**
         * Konta z migracji zachowują stare schematy, żeby zapisane wcześniej
         * URI nadal wskazywały ten sam zasób.
         */
        fun schemeFor(account: RemoteAccount): String = when (account.id) {
            RemoteAccount.LEGACY_FTP_ID -> FTP_SCHEME
            RemoteAccount.LEGACY_SMB_ID -> SMB_SCHEME
            else -> NET_SCHEME
        }

        fun isRemoteScheme(scheme: String?): Boolean =
            scheme == NET_SCHEME || scheme == FTP_SCHEME || scheme == SMB_SCHEME
    }

    private val backends = mutableMapOf<RemoteType, RemoteBackend>()

    private fun backend(type: RemoteType): RemoteBackend = synchronized(backends) {
        backends.getOrPut(type) {
            when (type) {
                RemoteType.FTP -> FtpBackend(CertificatePins(knownHosts?.resolveSibling("known_certificates")))
                RemoteType.SFTP -> SftpBackend(knownHosts)
                RemoteType.SMB -> SmbBackend()
                RemoteType.WEBDAV -> WebDavBackend()
            }
        }
    }

    fun isRemote(uri: Uri): Boolean = isRemoteScheme(uri.scheme)

    private fun accountFor(uri: Uri): RemoteAccount =
        lookup(uri.authority)
            ?: throw IOException(text(R.string.err_account_missing))

    private fun pathOf(uri: Uri): String = RemotePaths.normalize(uri.path.orEmpty())

    private fun uriFor(uri: Uri, path: String): Uri = Uri.Builder()
        .scheme(uri.scheme)
        .authority(uri.authority)
        .path(path)
        .build()

    private fun toItem(parent: Uri, path: String, entry: RemoteEntry) = FileItem(
        uri = uriFor(parent, path),
        name = entry.name,
        mimeType = if (entry.isDirectory) DIRECTORY_MIME else guessMime(entry.name),
        size = entry.size,
        lastModified = entry.modified,
        isDirectory = entry.isDirectory
    )

    private fun <T> withAccount(uri: Uri, block: (RemoteBackend, RemoteAccount, String) -> T): T {
        val account = accountFor(uri)
        return try {
            block(backend(account.type), account, pathOf(uri))
        } catch (e: Exception) {
            // Zmieniony klucz albo certyfikat wymaga decyzji użytkownika —
            // niezależnie od tego, która operacja go wykryła.
            pl.arma.files.net.ServerIdentityAlerts.reportIfPresent(e)
            // „The system cannot find the path specified” z jCIFS po ludzku i po polsku.
            if (e is jcifs.smb.SmbException && e.ntStatus in SMB_NOT_FOUND) {
                throw java.io.FileNotFoundException(text(R.string.offline_missing)).apply { initCause(e) }
            }
            throw e
        }
    }

    fun listChildren(parent: Uri): List<FileItem> = withAccount(parent) { backend, account, path ->
        backend.list(account, path).map { entry ->
            toItem(parent, RemotePaths.child(path, entry.name), entry)
        }
    }

    fun itemInfo(uri: Uri): FileItem? = withAccount(uri) { backend, account, path ->
        if (path == "/") {
            FileItem(uri, account.label, DIRECTORY_MIME, 0, 0, true)
        } else {
            backend.info(account, path)?.let { toItem(uri, path, it) }
        }
    }

    fun createFolder(parent: Uri, name: String): Uri = withAccount(parent) { backend, account, path ->
        val child = RemotePaths.child(path, name)
        backend.createFolder(account, child)
        uriFor(parent, child)
    }

    fun createFile(parent: Uri, name: String): Uri = withAccount(parent) { backend, account, path ->
        val child = RemotePaths.child(path, name)
        backend.createFile(account, child)
        uriFor(parent, child)
    }

    fun rename(item: FileItem, newName: String): Uri = withAccount(item.uri) { backend, account, path ->
        val target = RemotePaths.child(RemotePaths.parentOf(path), newName)
        if (!backend.move(account, path, target)) {
            throw IOException(text(R.string.err_rename_failed))
        }
        uriFor(item.uri, target)
    }

    /**
     * Przeniesienie po stronie serwera — możliwe tylko w obrębie jednego konta.
     * Różne konta oznaczają różne serwery, więc pozostaje kopiowanie.
     */
    fun move(item: FileItem, targetParent: Uri, newName: String): Uri? {
        if (item.uri.authority != targetParent.authority) return null
        return withAccount(item.uri) { backend, account, path ->
            val target = RemotePaths.child(pathOf(targetParent), newName)
            if (backend.move(account, path, target)) uriFor(item.uri, target) else null
        }
    }

    fun delete(item: FileItem): Boolean = withAccount(item.uri) { backend, account, path ->
        backend.delete(account, path, item.isDirectory)
    }

    fun openInput(uri: Uri): InputStream = withAccount(uri) { backend, account, path ->
        backend.openInput(account, path)
    }

    fun openOutput(uri: Uri): OutputStream = withAccount(uri) { backend, account, path ->
        backend.openOutput(account, path)
    }

    fun watch(uri: Uri): pl.arma.files.net.RemoteWatch? = withAccount(uri) { backend, account, path ->
        backend.watch(account, path)
    }

    /** Zamyka połączenia konta — po zmianie hasła albo usunięciu połączenia. */
    fun disconnect(account: RemoteAccount) {
        runCatching { backend(account.type).disconnect(account.id) }
    }
}

/** NT_STATUS_OBJECT_NAME_NOT_FOUND i NT_STATUS_OBJECT_PATH_NOT_FOUND. */
private val SMB_NOT_FOUND = setOf(0xC0000034.toInt(), 0xC000003A.toInt())
