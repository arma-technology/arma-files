package pl.arma.files

import android.net.Uri
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import jcifs.smb.SmbFileInputStream
import jcifs.smb.SmbFileOutputStream
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPSClient
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URLEncoder
import java.time.Duration
import java.util.Properties

class RemoteRepository(private val prefs: NetworkPrefs) {
    companion object {
        const val FTP_SCHEME = "armaftp"
        const val SMB_SCHEME = "armasmb"

        fun ftpRoot(): Uri = Uri.Builder().scheme(FTP_SCHEME).authority("ftp").path("/").build()
        fun smbRoot(): Uri = Uri.Builder().scheme(SMB_SCHEME).authority("smb").path("/").build()
    }

    fun isRemote(uri: Uri): Boolean = uri.scheme == FTP_SCHEME || uri.scheme == SMB_SCHEME

    fun listChildren(parent: Uri): List<FileItem> = when (parent.scheme) {
        FTP_SCHEME -> ftpList(parent)
        SMB_SCHEME -> smbList(parent)
        else -> error("Nieznane źródło sieciowe")
    }

    fun createFolder(parent: Uri, name: String): Uri = when (parent.scheme) {
        FTP_SCHEME -> ftpCreateFolder(parent, name)
        SMB_SCHEME -> smbCreateFolder(parent, name)
        else -> error("Nieznane źródło sieciowe")
    }

    fun createFile(parent: Uri, name: String): Uri = when (parent.scheme) {
        FTP_SCHEME -> ftpCreateFile(parent, name)
        SMB_SCHEME -> smbCreateFile(parent, name)
        else -> error("Nieznane źródło sieciowe")
    }

    fun rename(item: FileItem, newName: String): Uri = when (item.uri.scheme) {
        FTP_SCHEME -> ftpRename(item.uri, newName)
        SMB_SCHEME -> smbRename(item.uri, newName)
        else -> error("Nieznane źródło sieciowe")
    }

    /**
     * Przenosi element po stronie serwera, bez ściągania go na telefon. Możliwe
     * tylko wtedy, gdy źródło i cel leżą na tym samym serwerze — przy jednym
     * globalnym koncie na protokół sprowadza się to do zgodności schematu URI.
     * Zwraca null, gdy serwer odmówił; wtedy wołający kopiuje i kasuje źródło.
     */
    fun move(item: FileItem, targetParent: Uri, newName: String): Uri? {
        val scheme = item.uri.scheme ?: return null
        if (scheme != targetParent.scheme) return null
        val target = childUri(targetParent, newName)
        return when (scheme) {
            FTP_SCHEME -> {
                val client = ftpClient()
                try {
                    if (client.rename(ftpPath(item.uri), ftpPath(target))) target else null
                } finally { closeFtp(client) }
            }
            SMB_SCHEME -> SmbFile(smbUrl(item.uri).trimEnd('/'), smbContext()).use { source ->
                SmbFile(smbUrl(target).trimEnd('/'), smbContext()).use { destination ->
                    runCatching { source.renameTo(destination) }.map { target }.getOrNull()
                }
            }
            else -> null
        }
    }

    fun delete(item: FileItem): Boolean = when (item.uri.scheme) {
        FTP_SCHEME -> ftpDelete(item.uri, item.isDirectory)
        SMB_SCHEME -> smbDelete(item.uri)
        else -> false
    }

    fun openInput(uri: Uri): InputStream = when (uri.scheme) {
        FTP_SCHEME -> ftpInput(uri)
        SMB_SCHEME -> smbInput(uri)
        else -> error("Nieznane źródło sieciowe")
    }

    fun openOutput(uri: Uri): OutputStream = when (uri.scheme) {
        FTP_SCHEME -> ftpOutput(uri)
        SMB_SCHEME -> smbOutput(uri)
        else -> error("Nieznane źródło sieciowe")
    }

    fun itemInfo(uri: Uri): FileItem? = when (uri.scheme) {
        FTP_SCHEME -> ftpInfo(uri)
        SMB_SCHEME -> smbInfo(uri)
        else -> null
    }

    private fun ftpClient(): FTPClient {
        val config = prefs.ftp()
        if (config.host.isBlank()) throw IOException("FTP: brak adresu serwera.")
        val client: FTPClient = if (config.ftps) {
            FTPSClient(false).apply { setEndpointCheckingEnabled(true) }
        } else FTPClient()
        client.setConnectTimeout(10_000)
        client.setDefaultTimeout(15_000)
        client.setDataTimeout(Duration.ofSeconds(60))
        client.setAutodetectUTF8(true)
        client.controlEncoding = "UTF-8"
        client.connect(config.host, config.port)
        if (!client.login(config.username, config.password)) {
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

    private fun ftpPath(uri: Uri): String {
        val cfg = prefs.ftp()
        val base = normalizeFtpPath(cfg.rootPath)
        val relative = uri.path.orEmpty().trim('/')
        return when {
            relative.isBlank() -> base
            base == "/" -> "/$relative"
            else -> "$base/$relative"
        }
    }

    private fun normalizeFtpPath(path: String): String {
        val p = "/" + path.trim().trim('/')
        return if (p == "//") "/" else p
    }

    private fun childUri(parent: Uri, name: String): Uri {
        val rel = parent.path.orEmpty().trim('/')
        val child = if (rel.isBlank()) name else "$rel/$name"
        return Uri.Builder().scheme(parent.scheme).authority(parent.authority).path("/$child").build()
    }

    private fun parentUri(uri: Uri): Uri {
        val parts = uri.path.orEmpty().trim('/').split('/').filter { it.isNotBlank() }
        return Uri.Builder().scheme(uri.scheme).authority(uri.authority).path("/" + parts.dropLast(1).joinToString("/")).build()
    }

    private fun ftpList(parent: Uri): List<FileItem> {
        val client = ftpClient()
        return try {
            client.listFiles(ftpPath(parent))
                .asSequence()
                .filter { it.name != "." && it.name != ".." }
                .map {
                    val isDir = it.isDirectory
                    FileItem(
                        uri = childUri(parent, it.name),
                        name = it.name,
                        mimeType = if (isDir) DIRECTORY_MIME else guessMime(it.name),
                        size = if (isDir) 0 else it.size,
                        lastModified = it.timestamp?.timeInMillis ?: 0L,
                        isDirectory = isDir
                    )
                }.toList()
        } finally {
            closeFtp(client)
        }
    }

    private fun ftpInfo(uri: Uri): FileItem? {
        if (uri.path.orEmpty().trim('/').isBlank()) return FileItem(uri, "FTP", DIRECTORY_MIME, 0, 0, true)
        val parent = parentUri(uri)
        val name = uri.lastPathSegment ?: return null
        return ftpList(parent).firstOrNull { it.name == name }
    }

    private fun ftpCreateFolder(parent: Uri, name: String): Uri {
        val client = ftpClient()
        val uri = childUri(parent, name)
        try {
            if (!client.makeDirectory(ftpPath(uri))) throw IOException("FTP: nie udało się utworzyć folderu.")
            return uri
        } finally { closeFtp(client) }
    }

    private fun ftpCreateFile(parent: Uri, name: String): Uri {
        val uri = childUri(parent, name)
        ftpOutput(uri).use { }
        return uri
    }

    private fun ftpRename(uri: Uri, newName: String): Uri {
        val client = ftpClient()
        val target = childUri(parentUri(uri), newName)
        try {
            if (!client.rename(ftpPath(uri), ftpPath(target))) throw IOException("FTP: zmiana nazwy nie powiodła się.")
            return target
        } finally { closeFtp(client) }
    }

    private fun ftpDelete(uri: Uri, directory: Boolean): Boolean {
        val client = ftpClient()
        return try {
            if (directory) {
                ftpDeleteDirectory(client, ftpPath(uri))
                client.removeDirectory(ftpPath(uri))
            } else client.deleteFile(ftpPath(uri))
        } finally { closeFtp(client) }
    }

    private fun ftpDeleteDirectory(client: FTPClient, path: String) {
        client.listFiles(path).filter { it.name != "." && it.name != ".." }.forEach { child ->
            val childPath = path.trimEnd('/') + "/" + child.name
            if (child.isDirectory) {
                ftpDeleteDirectory(client, childPath)
                if (!client.removeDirectory(childPath)) throw IOException("FTP: nie udało się usunąć $childPath")
            } else if (!client.deleteFile(childPath)) throw IOException("FTP: nie udało się usunąć $childPath")
        }
    }

    private fun ftpInput(uri: Uri): InputStream {
        val client = ftpClient()
        val stream = client.retrieveFileStream(ftpPath(uri)) ?: run {
            closeFtp(client)
            throw IOException("FTP: nie można otworzyć pliku.")
        }
        return object : FilterInputStream(stream) {
            override fun close() {
                var failure: Throwable? = null
                try { super.close() } catch (t: Throwable) { failure = t }
                try { if (!client.completePendingCommand()) throw IOException("FTP: transfer nie został zakończony.") } catch (t: Throwable) { if (failure == null) failure = t }
                closeFtp(client)
                failure?.let { throw it }
            }
        }
    }

    private fun ftpOutput(uri: Uri): OutputStream {
        val client = ftpClient()
        val stream = client.storeFileStream(ftpPath(uri)) ?: run {
            closeFtp(client)
            throw IOException("FTP: nie można zapisać pliku.")
        }
        return object : FilterOutputStream(stream) {
            override fun close() {
                var failure: Throwable? = null
                try { super.close() } catch (t: Throwable) { failure = t }
                try { if (!client.completePendingCommand()) throw IOException("FTP: zapis nie został zakończony.") } catch (t: Throwable) { if (failure == null) failure = t }
                closeFtp(client)
                failure?.let { throw it }
            }
        }
    }

    private fun closeFtp(client: FTPClient) {
        runCatching { if (client.isConnected) client.logout() }
        runCatching { if (client.isConnected) client.disconnect() }
    }

    private fun smbContext(): CIFSContext {
        val cfg = prefs.smb()
        val properties = Properties().apply {
            setProperty("jcifs.smb.client.minVersion", "SMB202")
            setProperty("jcifs.smb.client.maxVersion", "SMB311")
            setProperty("jcifs.smb.client.enableSMB2", "true")
            setProperty("jcifs.smb.client.disableSMB1", "true")
        }
        val base = BaseContext(PropertyConfiguration(properties))
        return base.withCredentials(NtlmPasswordAuthenticator(cfg.domain, cfg.username, cfg.password))
    }

    private fun smbUrl(uri: Uri): String {
        val cfg = prefs.smb()
        if (cfg.host.isBlank() || cfg.share.isBlank()) throw IOException("SMB: podaj serwer i nazwę udziału.")
        val host = if (cfg.port == 445) cfg.host else "${cfg.host}:${cfg.port}"
        val baseParts = cfg.rootPath.trim('/').split('/').filter { it.isNotBlank() }
        val relParts = uri.path.orEmpty().trim('/').split('/').filter { it.isNotBlank() }
        val all = baseParts + relParts
        val suffix = if (all.isEmpty()) "" else all.joinToString("/") { encodeSegment(it) } + "/"
        return "smb://$host/${encodeSegment(cfg.share)}/$suffix"
    }

    private fun encodeSegment(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private fun smbList(parent: Uri): List<FileItem> {
        SmbFile(smbUrl(parent), smbContext()).use { dir ->
            if (!dir.exists()) throw IOException("SMB: udział lub katalog nie istnieje.")
            return dir.listFiles().map { child ->
                child.use {
                    val name = child.name.trimEnd('/')
                    val isDir = child.isDirectory
                    FileItem(
                        uri = childUri(parent, name),
                        name = name,
                        mimeType = if (isDir) DIRECTORY_MIME else guessMime(name),
                        size = if (isDir) 0 else child.length(),
                        lastModified = child.lastModified(),
                        isDirectory = isDir
                    )
                }
            }
        }
    }

    private fun smbInfo(uri: Uri): FileItem? {
        if (uri.path.orEmpty().trim('/').isBlank()) return FileItem(uri, "SMB", DIRECTORY_MIME, 0, 0, true)
        SmbFile(smbUrl(uri).trimEnd('/'), smbContext()).use { file ->
            if (!file.exists()) return null
            val dir = file.isDirectory
            val name = uri.lastPathSegment ?: file.name.trimEnd('/')
            return FileItem(uri, name, if (dir) DIRECTORY_MIME else guessMime(name), if (dir) 0 else file.length(), file.lastModified(), dir)
        }
    }

    private fun smbCreateFolder(parent: Uri, name: String): Uri {
        val uri = childUri(parent, name)
        SmbFile(smbUrl(uri), smbContext()).use { it.mkdir() }
        return uri
    }

    private fun smbCreateFile(parent: Uri, name: String): Uri {
        val uri = childUri(parent, name)
        SmbFile(smbUrl(uri).trimEnd('/'), smbContext()).use { it.createNewFile() }
        return uri
    }

    private fun smbRename(uri: Uri, newName: String): Uri {
        val target = childUri(parentUri(uri), newName)
        SmbFile(smbUrl(uri).trimEnd('/'), smbContext()).use { src ->
            SmbFile(smbUrl(target).trimEnd('/'), smbContext()).use { dst -> src.renameTo(dst) }
        }
        return target
    }

    private fun smbDelete(uri: Uri): Boolean {
        SmbFile(smbUrl(uri).trimEnd('/'), smbContext()).use { file ->
            if (!file.exists()) return false
            smbDeleteRecursively(file)
            return true
        }
    }

    private fun smbDeleteRecursively(file: SmbFile) {
        if (file.isDirectory) {
            file.listFiles().forEach { child ->
                child.use { smbDeleteRecursively(it) }
            }
        }
        file.delete()
    }

    private fun smbInput(uri: Uri): InputStream {
        val file = SmbFile(smbUrl(uri).trimEnd('/'), smbContext())
        val input = SmbFileInputStream(file)
        return object : FilterInputStream(input) {
            override fun close() { try { super.close() } finally { file.close() } }
        }
    }

    private fun smbOutput(uri: Uri): OutputStream {
        val file = SmbFile(smbUrl(uri).trimEnd('/'), smbContext())
        val output = SmbFileOutputStream(file, false)
        return object : FilterOutputStream(output) {
            override fun close() { try { super.close() } finally { file.close() } }
        }
    }

}
