package pl.arma.files.net

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet

/**
 * SFTP — czyli transfer plików po SSH. W odróżnieniu od FTPS nie ma tu
 * osobnego kanału danych ani trybu pasywnego, więc działa przez NAT bez
 * dodatkowej konfiguracji; dlatego to on, a nie FTP, jest sensownym
 * domyślnym wyborem przy serwerach linuksowych.
 */
class SftpBackend : RemoteBackend {

    private class Session(val ssh: SSHClient, val sftp: SFTPClient)

    private val pool = ConnectionPool(
        create = ::connect,
        validate = { it.ssh.isConnected && it.ssh.isAuthenticated },
        destroy = ::close
    )

    private fun connect(account: RemoteAccount): Session {
        if (account.host.isBlank()) throw IOException("SFTP: brak adresu serwera.")
        val ssh = SSHClient()
        // Zaufanie do klucza hosta przy pierwszym połączeniu; docelowo trzeba to
        // zastąpić zapamiętanym odciskiem klucza i ostrzeżeniem przy zmianie.
        ssh.addHostKeyVerifier(PromiscuousVerifier())
        ssh.connectTimeout = 10_000
        ssh.timeout = 20_000
        ssh.connect(account.host, account.port)
        runCatching { ssh.authPassword(account.username, account.password) }
            .onFailure {
                runCatching { ssh.disconnect() }
                throw IOException("SFTP: błędny login lub hasło.")
            }
        return Session(ssh, ssh.newSFTPClient())
    }

    private fun close(session: Session) {
        runCatching { session.sftp.close() }
        runCatching { session.ssh.disconnect() }
    }

    private fun path(account: RemoteAccount, path: String) = RemotePaths.absolute(account.rootPath, path)

    override fun list(account: RemoteAccount, path: String): List<RemoteEntry> =
        pool.use(account) { session ->
            session.sftp.ls(path(account, path))
                .filter { it.name != "." && it.name != ".." }
                .map { entry ->
                    val directory = entry.attributes.type == FileMode.Type.DIRECTORY
                    RemoteEntry(
                        name = entry.name,
                        isDirectory = directory,
                        size = if (directory) 0L else entry.attributes.size,
                        modified = entry.attributes.mtime * 1000L
                    )
                }
        }

    override fun info(account: RemoteAccount, path: String): RemoteEntry? =
        pool.use(account) { session ->
            val target = path(account, path)
            val attributes = runCatching { session.sftp.stat(target) }.getOrNull() ?: return@use null
            val directory = attributes.type == FileMode.Type.DIRECTORY
            RemoteEntry(
                name = RemotePaths.nameOf(path).ifBlank { "/" },
                isDirectory = directory,
                size = if (directory) 0L else attributes.size,
                modified = attributes.mtime * 1000L
            )
        }

    override fun createFolder(account: RemoteAccount, path: String) {
        pool.use(account) { it.sftp.mkdir(path(account, path)) }
    }

    override fun createFile(account: RemoteAccount, path: String) {
        pool.use(account) { session ->
            session.sftp.open(
                path(account, path),
                EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)
            ).close()
        }
    }

    override fun delete(account: RemoteAccount, path: String, isDirectory: Boolean): Boolean =
        pool.use(account) { session ->
            val target = path(account, path)
            runCatching {
                if (isDirectory) session.sftp.rmdir(target) else session.sftp.rm(target)
            }.isSuccess
        }

    override fun move(account: RemoteAccount, from: String, to: String): Boolean =
        pool.use(account) { session ->
            runCatching { session.sftp.rename(path(account, from), path(account, to)) }.isSuccess
        }

    /**
     * Strumień trzyma własną sesję, bo żyje dłużej niż pojedyncze wywołanie i
     * zwrócenie połączenia do puli w trakcie transferu popsułoby kolejną operację.
     */
    override fun openInput(account: RemoteAccount, path: String): InputStream {
        val session = connect(account)
        val handle = runCatching { session.sftp.open(path(account, path)) }
            .getOrElse { close(session); throw IOException("SFTP: nie można otworzyć pliku.") }
        val stream = handle.RemoteFileInputStream()
        return object : InputStream() {
            override fun read(): Int = stream.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = stream.read(b, off, len)
            override fun close() {
                runCatching { stream.close() }
                runCatching { handle.close() }
                close(session)
            }
        }
    }

    override fun openOutput(account: RemoteAccount, path: String): OutputStream {
        val session = connect(account)
        val handle = runCatching {
            session.sftp.open(
                path(account, path),
                EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)
            )
        }.getOrElse { close(session); throw IOException("SFTP: nie można zapisać pliku.") }
        val stream = handle.RemoteFileOutputStream()
        return object : OutputStream() {
            override fun write(b: Int) = stream.write(b)
            override fun write(b: ByteArray, off: Int, len: Int) = stream.write(b, off, len)
            override fun close() {
                runCatching { stream.close() }
                runCatching { handle.close() }
                close(session)
            }
        }
    }

    override fun disconnect(accountId: String) = pool.closeAccount(accountId)
}
