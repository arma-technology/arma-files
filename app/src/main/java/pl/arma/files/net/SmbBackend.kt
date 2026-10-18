package pl.arma.files.net

import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import jcifs.smb.SmbFileInputStream
import jcifs.smb.SmbFileOutputStream
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class SmbBackend : RemoteBackend {

    // jCIFS trzyma własną pulę połączeń wewnątrz kontekstu, więc wystarczy nie
    // budować kontekstu na nowo przy każdej operacji.
    private val contexts = HashMap<String, CIFSContext>()

    private fun context(account: RemoteAccount): CIFSContext = synchronized(contexts) {
        contexts.getOrPut(account.id) {
            val properties = java.util.Properties().apply {
                setProperty("jcifs.smb.client.minVersion", "SMB202")
                setProperty("jcifs.smb.client.maxVersion", "SMB311")
                setProperty("jcifs.smb.client.enableSMB2", "true")
                setProperty("jcifs.smb.client.disableSMB1", "true")
                setProperty("jcifs.smb.client.responseTimeout", "20000")
                setProperty("jcifs.smb.client.connTimeout", "10000")
            }
            BaseContext(PropertyConfiguration(properties))
                .withCredentials(NtlmPasswordAuthenticator(account.domain, account.username, account.password))
        }
    }

    /**
     * Katalog udziału jako adres, a dalsze segmenty jako nazwy dzieci. jCIFS
     * nie dekoduje procentów w adresie, więc zakodowane „ż” albo spacja
     * wskazywały nieistniejący plik; dziecko przyjmuje nazwę dosłownie.
     */
    private fun file(account: RemoteAccount, path: String, directory: Boolean = false): SmbFile {
        if (account.host.isBlank() || account.share.isBlank()) {
            throw IOException("SMB: podaj serwer i nazwę udziału.")
        }
        val host = if (account.port == 445) account.host else "${account.host}:${account.port}"
        val segments = RemotePaths.absolute(account.rootPath, path).split('/').filter { it.isNotBlank() }
        var current = SmbFile("smb://$host/${account.share.trim('/')}/", context(account))
        segments.forEachIndexed { index, name ->
            val last = index == segments.lastIndex
            current = SmbFile(current, if (!last || directory) "$name/" else name)
        }
        return current
    }

    override fun list(account: RemoteAccount, path: String): List<RemoteEntry> =
        file(account, path, directory = true).use { dir ->
            if (!dir.exists()) throw IOException("SMB: udział lub katalog nie istnieje.")
            dir.listFiles().map { child ->
                child.use {
                    RemoteEntry(
                        name = it.name.trimEnd('/'),
                        isDirectory = it.isDirectory,
                        size = if (it.isDirectory) 0L else it.length(),
                        modified = it.lastModified()
                    )
                }
            }
        }

    override fun info(account: RemoteAccount, path: String): RemoteEntry? {
        if (RemotePaths.normalize(path) == "/") return RemoteEntry("/", true, 0, 0)
        return file(account, path).use { target ->
            if (!target.exists()) return null
            val directory = target.isDirectory
            RemoteEntry(
                name = RemotePaths.nameOf(path),
                isDirectory = directory,
                size = if (directory) 0L else target.length(),
                modified = target.lastModified()
            )
        }
    }

    override fun createFolder(account: RemoteAccount, path: String) {
        file(account, path, directory = true).use { it.mkdir() }
    }

    override fun createFile(account: RemoteAccount, path: String) {
        file(account, path).use { it.createNewFile() }
    }

    override fun delete(account: RemoteAccount, path: String, isDirectory: Boolean): Boolean =
        file(account, path, directory = isDirectory).use { target ->
            if (!target.exists()) return false
            target.delete()
            true
        }

    override fun move(account: RemoteAccount, from: String, to: String): Boolean =
        runCatching {
            file(account, from).use { source ->
                file(account, to).use { target -> source.renameTo(target) }
            }
            true
        }.getOrDefault(false)

    override fun openInput(account: RemoteAccount, path: String): InputStream {
        val target = file(account, path)
        return object : FilterInputStream(SmbFileInputStream(target)) {
            override fun close() { try { super.close() } finally { target.close() } }
        }
    }

    override fun openOutput(account: RemoteAccount, path: String): OutputStream {
        val target = file(account, path)
        return object : FilterOutputStream(SmbFileOutputStream(target, false)) {
            // FilterOutputStream pisze tablicę bajt po bajcie, a jCIFS wysyła
            // każdy bajt osobnym żądaniem — 2 MB szły ponad minutę.
            override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
            override fun close() { try { super.close() } finally { target.close() } }
        }
    }

    override fun disconnect(accountId: String) {
        synchronized(contexts) { contexts.remove(accountId) }
    }
}
