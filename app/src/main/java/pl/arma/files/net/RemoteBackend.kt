package pl.arma.files.net

import java.io.InputStream
import pl.arma.files.R
import pl.arma.files.text
import java.io.OutputStream

/** Pozycja katalogu widziana przez backend, jeszcze bez URI aplikacji. */
data class RemoteEntry(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val modified: Long
)

/**
 * Jeden protokół sieciowy. Ścieżki są względne wobec konta — korzeń to `/`, a
 * doklejenie `rootPath` z konfiguracji należy do backendu, żeby reszta
 * aplikacji nie musiała wiedzieć, czy użytkownik zawęził widok do podkatalogu.
 */
interface RemoteBackend {
    fun list(account: RemoteAccount, path: String): List<RemoteEntry>
    fun info(account: RemoteAccount, path: String): RemoteEntry?
    fun createFolder(account: RemoteAccount, path: String)
    fun createFile(account: RemoteAccount, path: String)
    fun delete(account: RemoteAccount, path: String, isDirectory: Boolean): Boolean
    fun openInput(account: RemoteAccount, path: String): InputStream
    fun openOutput(account: RemoteAccount, path: String): OutputStream

    /** Zmiana nazwy lub przeniesienie po stronie serwera. False = serwer odmówił. */
    fun move(account: RemoteAccount, from: String, to: String): Boolean

    /** Zamyka połączenia trzymane w puli — wołane przy usunięciu konta. */
    fun disconnect(accountId: String) = Unit
}

/** Narzędzia do składania ścieżek wspólne dla wszystkich backendów. */
object RemotePaths {

    /** Zawsze `/a/b`, bez końcowego ukośnika (poza samym korzeniem). */
    fun normalize(path: String): String {
        val parts = path.replace('\\', '/').split('/').filter { it.isNotBlank() && it != "." }
        if (parts.any { it == ".." }) throw IllegalArgumentException(text(R.string.err_path_forbidden, path))
        return "/" + parts.joinToString("/")
    }

    /** Ścieżka na serwerze: korzeń z konfiguracji konta plus ścieżka względna. */
    fun absolute(rootPath: String, path: String): String {
        val base = normalize(rootPath)
        val relative = normalize(path)
        return when {
            base == "/" -> relative
            relative == "/" -> base
            else -> base + relative
        }
    }

    fun parentOf(path: String): String {
        val normalized = normalize(path)
        if (normalized == "/") return "/"
        return normalized.substringBeforeLast('/').ifBlank { "/" }
    }

    fun nameOf(path: String): String = normalize(path).substringAfterLast('/')

    fun child(parent: String, name: String): String {
        if (name.contains('/') || name.contains('\\') || name == ".." || name == ".") {
            throw IllegalArgumentException(text(R.string.err_name_forbidden, name))
        }
        val base = normalize(parent)
        return if (base == "/") "/$name" else "$base/$name"
    }
}
