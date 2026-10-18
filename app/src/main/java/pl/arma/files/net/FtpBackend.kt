package pl.arma.files.net

import org.apache.commons.net.ftp.FTP
import pl.arma.files.R
import pl.arma.files.text
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Duration

class FtpBackend(private val pins: CertificatePins = CertificatePins(null)) : RemoteBackend {

    /**
     * Katalog, w którym serwer stawia użytkownika po zalogowaniu. Bez
     * chroota to np. /home/jan, a „/” byłby katalogiem głównym całego
     * serwera — z cudzymi plikami i bez prawa zapisu.
     */
    private val homes = java.util.concurrent.ConcurrentHashMap<String, String>()

    private val pool = ConnectionPool(
        create = ::connect,
        validate = { it.isConnected && runCatching { it.sendNoOp() }.getOrDefault(false) },
        destroy = ::close
    )

    private fun connect(account: RemoteAccount): FTPClient {
        if (account.host.isBlank()) throw IOException(text(R.string.ftp_no_host))
        val client: FTPClient = if (account.secure) {
            // Nazwę serwera sprawdza CertificatePins — przy zapamiętanym
            // certyfikacie NAS-u z adresem IP zwykła weryfikacja by go odrzuciła.
            FTPSClient(false).apply {
                trustManager = pins.trustManager(account.host, account.port)
                setEndpointCheckingEnabled(false)
            }
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
            throw IOException(text(R.string.ftp_auth))
        }
        if (client is FTPSClient) {
            client.execPBSZ(0)
            client.execPROT("P")
        }
        client.enterLocalPassiveMode()
        client.setFileType(FTP.BINARY_FILE_TYPE)
        if (account.rootPath.isBlank()) {
            homes.computeIfAbsent(account.id) { client.printWorkingDirectory()?.takeIf { it.startsWith("/") } ?: "/" }
        }
        return client
    }

    private fun replyError(client: FTPClient): IOException {
        val reply = client.replyString?.trim().orEmpty()
        return IOException(
            when {
                // vsftpd i FileZilla Server domyślnie wymagają wznowienia sesji
                // TLS na kanale danych, czego commons-net nie potrafi.
                client is FTPSClient && (reply.contains("reuse", true) || client.replyCode == 522 || client.replyCode == 425) ->
                    text(R.string.ftps_reuse)
                reply.startsWith("550") -> text(R.string.ftp_missing)
                else -> text(R.string.ftp_reply, reply)
            }
        )
    }

    private fun close(client: FTPClient) {
        runCatching { if (client.isConnected) client.logout() }
        runCatching { if (client.isConnected) client.disconnect() }
    }

    private fun path(account: RemoteAccount, path: String) =
        RemotePaths.absolute(account.rootPath.ifBlank { homes[account.id] ?: "/" }, path)

    override fun list(account: RemoteAccount, path: String): List<RemoteEntry> =
        pool.use(account) { client ->
            val directory = path(account, path)
            val files = client.listFiles(directory)
            // Nieudany listing commons-net zwraca jako pustą tablicę; bez tego
            // odmowa serwera wyglądała jak pusty katalog.
            if (!FTPReply.isPositiveCompletion(client.replyCode)) throw replyError(client)
            files
                .filter { it.name != "." && it.name != ".." }
                .map {
                    // Dowiązanie może wskazywać katalog; sprawdzamy, czy da się do niego wejść.
                    val isDirectory = it.isDirectory ||
                        (it.isSymbolicLink && client.changeWorkingDirectory("${directory.trimEnd('/')}/${it.name}"))
                    RemoteEntry(
                        name = it.name,
                        isDirectory = isDirectory,
                        size = if (isDirectory) 0L else it.size,
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
                throw IOException(text(R.string.ftp_mkdir))
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
            val error = replyError(client)
            close(client)
            throw error
        }
        return object : FilterInputStream(stream) {
            override fun close() {
                var failure: Throwable? = null
                runCatching { super.close() }.onFailure { failure = it }
                runCatching {
                    if (!client.completePendingCommand()) throw IOException(text(R.string.ftp_transfer_incomplete))
                }.onFailure { if (failure == null) failure = it }
                close(client)
                failure?.let { throw it }
            }
        }
    }

    override fun openOutput(account: RemoteAccount, path: String): OutputStream {
        val client = connect(account)
        val stream = client.storeFileStream(path(account, path)) ?: run {
            val error = replyError(client)
            close(client)
            throw error
        }
        return object : FilterOutputStream(stream) {
            // FilterOutputStream domyślnie pisze tablicę bajt po bajcie.
            override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)

            override fun close() {
                var failure: Throwable? = null
                runCatching { super.close() }.onFailure { failure = it }
                runCatching {
                    if (!client.completePendingCommand()) throw IOException(text(R.string.ftp_write_incomplete))
                }.onFailure { if (failure == null) failure = it }
                close(client)
                failure?.let { throw it }
            }
        }
    }

    override fun disconnect(accountId: String) = pool.closeAccount(accountId)
}
