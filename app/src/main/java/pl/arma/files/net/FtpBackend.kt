package pl.arma.files.net

import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPSClient
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Duration

class FtpBackend : RemoteBackend {

    private val pool = ConnectionPool(
        create = ::connect,
        validate = { it.isConnected && runCatching { it.sendNoOp() }.getOrDefault(false) },
        destroy = ::close
    )

    private fun connect(account: RemoteAccount): FTPClient {
        if (account.host.isBlank()) throw IOException("FTP: brak adresu serwera.")
        val client: FTPClient = if (account.secure) {
            FTPSClient(false).apply { setEndpointCheckingEnabled(true) }
        } else {
            FTPClient()
        }
        client.setConnectTimeout(10_000)
        client.setDefaultTimeout(15_000)
        client.setDataTimeout(Duration.ofSeconds(60))
        client.setAutodetectUTF8(true)
        client.controlEncoding = "UTF-8"
        client.connect(account.host, account.port)
        if (!client.login(account.username, account.password)) {
            client.disconnect()
            throw IOException("FTP: błędny login lub hasło.")
        }
        if (client is FTPSClient) {
            client.execPBSZ(0)
            client.execPROT("P")
        }
        client.enterLocalPassiveMode()
        client.setFileType(FTP.BINARY_FILE_TYPE)
        return client
    }

    private fun close(client: FTPClient) {
        runCatching { if (client.isConnected) client.logout() }
        runCatching { if (client.isConnected) client.disconnect() }
    }

    private fun path(account: RemoteAccount, path: String) = RemotePaths.absolute(account.rootPath, path)

    override fun list(account: RemoteAccount, path: String): List<RemoteEntry> =
        pool.use(account) { client ->
            client.listFiles(path(account, path))
                .filter { it.name != "." && it.name != ".." }
                .map {
                    RemoteEntry(
                        name = it.name,
                        isDirectory = it.isDirectory,
                        size = if (it.isDirectory) 0L else it.size,
                        modified = it.timestamp?.timeInMillis ?: 0L
                    )
                }
        }

    override fun info(account: RemoteAccount, path: String): RemoteEntry? {
        if (RemotePaths.normalize(path) == "/") return RemoteEntry("/", true, 0, 0)
        val name = RemotePaths.nameOf(path)
        return list(account, RemotePaths.parentOf(path)).firstOrNull { it.name == name }
    }

    override fun createFolder(account: RemoteAccount, path: String) {
        pool.use(account) { client ->
            if (!client.makeDirectory(path(account, path))) {
                throw IOException("FTP: nie udało się utworzyć folderu.")
            }
        }
    }

    override fun createFile(account: RemoteAccount, path: String) {
        openOutput(account, path).use { }
    }

    override fun delete(account: RemoteAccount, path: String, isDirectory: Boolean): Boolean =
        pool.use(account) { client ->
            val target = path(account, path)
            if (isDirectory) client.removeDirectory(target) else client.deleteFile(target)
        }

    override fun move(account: RemoteAccount, from: String, to: String): Boolean =
        pool.use(account) { client -> client.rename(path(account, from), path(account, to)) }

    /**
     * Transfer trzyma połączenie na wyłączność aż do zamknięcia strumienia, bo
     * FTP prowadzi go w ramach jednej sesji sterującej. Dlatego klient jest tu
     * pożyczany ręcznie, poza `use`, i zwracany dopiero w `close`.
     */
    override fun openInput(account: RemoteAccount, path: String): InputStream {
        val client = connect(account)
        val stream = client.retrieveFileStream(path(account, path)) ?: run {
            close(client)
            throw IOException("FTP: nie można otworzyć pliku.")
        }
        return object : FilterInputStream(stream) {
            override fun close() {
                var failure: Throwable? = null
                runCatching { super.close() }.onFailure { failure = it }
                runCatching {
                    if (!client.completePendingCommand()) throw IOException("FTP: transfer nie został zakończony.")
                }.onFailure { if (failure == null) failure = it }
                close(client)
                failure?.let { throw it }
            }
        }
    }

    override fun openOutput(account: RemoteAccount, path: String): OutputStream {
        val client = connect(account)
        val stream = client.storeFileStream(path(account, path)) ?: run {
            close(client)
            throw IOException("FTP: nie można zapisać pliku.")
        }
        return object : FilterOutputStream(stream) {
            override fun close() {
                var failure: Throwable? = null
                runCatching { super.close() }.onFailure { failure = it }
                runCatching {
                    if (!client.completePendingCommand()) throw IOException("FTP: zapis nie został zakończony.")
                }.onFailure { if (failure == null) failure = it }
                close(client)
                failure?.let { throw it }
            }
        }
    }

    override fun disconnect(accountId: String) = pool.closeAccount(accountId)
}
