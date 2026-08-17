package pl.arma.files

import android.net.Uri
import pl.arma.files.net.AccountStore
import pl.arma.files.net.DriveBackend
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
class RemoteRepository(private val lookup: (String?) -> RemoteAccount?) {

    constructor(accounts: AccountStore) : this({ id -> accounts.get(id) })

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
                RemoteType.FTP -> FtpBackend()
                RemoteType.SFTP -> SftpBackend()
                RemoteType.SMB -> SmbBackend()
                RemoteType.WEBDAV -> WebDavBackend()
                RemoteType.GDRIVE -> DriveBackend(pl.arma.files.BuildConfig.DRIVE_CLIENT_ID)
            }
        }
    }

    fun isRemote(uri: Uri): Boolean = isRemoteScheme(uri.scheme)

    private fun accountFor(uri: Uri): RemoteAccount =
        lookup(uri.authority)
            ?: throw IOException("Połączenie nie istnieje lub zostało usunięte.")

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
        return block(backend(account.type), account, pathOf(uri))
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
            throw IOException("Zmiana nazwy nie powiodła się.")
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

    /** Zamyka połączenia konta — po zmianie hasła albo usunięciu połączenia. */
    fun disconnect(account: RemoteAccount) {
        runCatching { backend(account.type).disconnect(account.id) }
    }
}
