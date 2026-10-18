package pl.arma.files.net

import net.schmizz.sshj.SSHClient
import pl.arma.files.R
import pl.arma.files.text
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.transport.verification.OpenSSHKnownHosts
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.userauth.method.AuthKeyboardInteractive
import net.schmizz.sshj.userauth.method.AuthPassword
import net.schmizz.sshj.userauth.method.PasswordResponseProvider
import net.schmizz.sshj.userauth.password.PasswordUtils
import java.io.File
import java.security.PublicKey
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
class SftpBackend(
    /**
     * Plik z kluczami znanych serwerów (format OpenSSH). Null wyłącza
     * sprawdzanie — tylko dla testów.
     */
    private val knownHosts: File? = null
) : RemoteBackend {

    private class Session(val ssh: SSHClient, val sftp: SFTPClient)

    /** Katalog domowy konta, gdy w konfiguracji nie podano katalogu startowego. */
    private val homes = java.util.concurrent.ConcurrentHashMap<String, String>()

    private val pool = ConnectionPool(
        create = ::connect,
        validate = { it.ssh.isConnected && it.ssh.isAuthenticated },
        destroy = ::close
    )

    private fun connect(account: RemoteAccount): Session {
        if (account.host.isBlank()) throw IOException(text(R.string.sftp_no_host))
        installBouncyCastle
        val ssh = SSHClient()
        ssh.addHostKeyVerifier(knownHosts?.let(::TrustOnFirstUse) ?: PromiscuousVerifier())
        ssh.connectTimeout = 10_000
        ssh.timeout = 20_000
        ssh.connect(account.host, account.port)
        runCatching {
            // Część serwerów (m.in. z PAM) przyjmuje hasło tylko jako
            // „keyboard-interactive”, a zwykłe logowanie hasłem odrzuca.
            ssh.auth(
                account.username,
                AuthPassword(PasswordUtils.createOneOff(account.password.toCharArray())),
                AuthKeyboardInteractive(PasswordResponseProvider(PasswordUtils.createOneOff(account.password.toCharArray())))
            )
        }.onFailure {
            runCatching { ssh.disconnect() }
            throw IOException(text(R.string.sftp_auth))
        }
        // Serwer bez podsystemu SFTP nie odpowiada na jego otwarcie, a sshj
        // czeka na tę odpowiedź bez limitu — aplikacja wisiała na zawsze.
        val sftp = runCatching { withTimeout(20_000) { ssh.newSFTPClient() } }.getOrElse {
            runCatching { ssh.disconnect() }
            throw IOException(text(R.string.sftp_no_subsystem))
        }
        if (account.rootPath.isBlank()) {
            homes.computeIfAbsent(account.id) { runCatching { sftp.canonicalize(".") }.getOrDefault("/") }
        }
        return Session(ssh, sftp)
    }

    private fun <T> withTimeout(millis: Long, block: () -> T): T {
        val task = java.util.concurrent.FutureTask(block)
        Thread(task, "sftp-init").apply { isDaemon = true }.start()
        return task.get(millis, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    /**
     * Klucz serwera zapamiętany przy pierwszym połączeniu; zmieniony klucz
     * to możliwe podszycie się pod serwer, więc połączenie jest odrzucane.
     */
    private class TrustOnFirstUse(file: File) : OpenSSHKnownHosts(file.apply { parentFile?.mkdirs(); if (!exists()) createNewFile() }) {
        override fun hostKeyUnverifiableAction(hostname: String, key: PublicKey): Boolean {
            runCatching {
                val entry = HostEntry(null, hostname, KeyType.fromKey(key), key)
                entries().add(entry)
                write(entry)
            }
            return true
        }

        override fun hostKeyChangedAction(hostname: String, key: PublicKey): Boolean =
            throw IOException(text(R.string.sftp_key_changed, hostname))
    }

    private fun close(session: Session) {
        runCatching { session.sftp.close() }
        runCatching { session.ssh.disconnect() }
    }

    private fun path(account: RemoteAccount, path: String) =
        RemotePaths.absolute(account.rootPath.ifBlank { homes[account.id] ?: "/" }, path)

    /** Dowiązanie do katalogu ma typ SYMLINK; o tym, czym jest cel, mówi dopiero stat. */
    private fun entryOf(session: Session, directory: String, name: String, attributes: FileAttributes): RemoteEntry {
        val resolved = if (attributes.type == FileMode.Type.SYMLINK) {
            runCatching { session.sftp.stat("$directory/$name") }.getOrDefault(attributes)
        } else {
            attributes
        }
        val isDirectory = resolved.type == FileMode.Type.DIRECTORY
        return RemoteEntry(
            name = name,
            isDirectory = isDirectory,
            size = if (isDirectory) 0L else resolved.size,
            modified = resolved.mtime * 1000L
        )
    }

    override fun list(account: RemoteAccount, path: String): List<RemoteEntry> =
        pool.use(account) { session ->
            val directory = path(account, path)
            session.sftp.ls(directory)
                .filter { it.name != "." && it.name != ".." }
                .map { entryOf(session, directory.trimEnd('/'), it.name, it.attributes) }
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
            .getOrElse { close(session); throw IOException(text(R.string.sftp_open_failed)) }
        // Kilka żądań odczytu w locie naraz; pojedyncze czekało na każdą
        // odpowiedź i w sieci z opóźnieniem transfer spadał kilkukrotnie.
        val stream = handle.ReadAheadRemoteFileInputStream(16)
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
        }.getOrElse { close(session); throw IOException(text(R.string.sftp_write_failed)) }
        val stream = handle.RemoteFileOutputStream(0, 16)
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

    private companion object {
        /**
         * Android rejestruje pod nazwą „BC” własną, okrojoną kopię
         * BouncyCastle — bez X25519, którym serwery SSH wymieniają klucze.
         * sshj szuka dostawcy po tej nazwie, więc bez podmiany każde
         * połączenie kończyło się „no such algorithm: X25519 for provider BC”.
         */
        val installBouncyCastle: Unit by lazy {
            val provider = org.bouncycastle.jce.provider.BouncyCastleProvider()
            if (java.security.Security.getProvider("BC")?.javaClass != provider.javaClass) {
                // Na końcu listy: sshj prosi o „BC” z nazwy, a reszta aplikacji
                // (Keystore, TLS) dalej dostaje domyślnych dostawców Androida.
                java.security.Security.removeProvider("BC")
                java.security.Security.addProvider(provider)
            }
        }
    }
}
