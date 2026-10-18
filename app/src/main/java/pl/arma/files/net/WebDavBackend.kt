package pl.arma.files.net

import okhttp3.Credentials
import pl.arma.files.R
import pl.arma.files.text
import okhttp3.HttpUrl
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/**
 * WebDAV — czyli Nextcloud, ownCloud, serwery Apache z mod_dav i większość
 * dysków, które nie mają własnego SDK.
 */
class WebDavBackend : RemoteBackend {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Pełny adres zasobu w czystym ASCII. Polskie litery muszą być zakodowane
     * procentowo: OkHttp zrobi to sam w adresie żądania, ale nie w nagłówku
     * Destination, więc zmiana nazwy na „ąę” kończyła się wyjątkiem.
     */
    private fun url(account: RemoteAccount, path: String, directory: Boolean = false): String {
        if (account.host.isBlank()) throw IOException(text(R.string.dav_no_host))
        val builder = HttpUrl.Builder()
            .scheme(if (account.secure) "https" else "http")
            .host(account.host)
            .port(account.port)
        RemotePaths.absolute(account.rootPath, path)
            .split('/')
            .filter { it.isNotBlank() }
            .forEach { builder.addPathSegment(it) }
        if (directory) builder.addPathSegment("")
        return builder.build().toString()
    }

    private fun request(account: RemoteAccount, url: String): Request.Builder {
        val builder = Request.Builder().url(url)
        if (account.username.isNotBlank()) {
            builder.header("Authorization", Credentials.basic(account.username, account.password))
        }
        return builder
    }

    private fun <T> execute(request: Request, block: (Response) -> T): T =
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code != 207) {
                throw IOException(describe(response.code))
            }
            block(response)
        }

    private fun describe(code: Int): String = when (code) {
        401, 403 -> text(R.string.dav_auth)
        404 -> text(R.string.dav_missing)
        405 -> text(R.string.dav_rejected)
        507 -> text(R.string.dav_full)
        else -> text(R.string.dav_error, code)
    }

    private val propfindBody = """
        <?xml version="1.0" encoding="utf-8" ?>
        <D:propfind xmlns:D="DAV:">
          <D:prop>
            <D:displayname/>
            <D:getcontentlength/>
            <D:getlastmodified/>
            <D:getcontenttype/>
            <D:resourcetype/>
          </D:prop>
        </D:propfind>
    """.trimIndent()

    private fun propfind(account: RemoteAccount, path: String, depth: String): List<WebDavXml.Response> {
        val request = request(account, url(account, path, directory = true))
            .method("PROPFIND", propfindBody.toRequestBody("application/xml; charset=utf-8".toMediaType()))
            .header("Depth", depth)
            .build()
        return execute(request) { response ->
            WebDavXml.parse(response.body?.string().orEmpty())
        }
    }

    override fun list(account: RemoteAccount, path: String): List<RemoteEntry> {
        val self = url(account, path, directory = true)
        return propfind(account, path, depth = "1")
            .mapNotNull { entry ->
                val name = nameFromHref(entry) ?: return@mapNotNull null
                // Pierwsza pozycja odpowiedzi to sam katalog, o który pytamy.
                if (sameResource(entry.href, self)) return@mapNotNull null
                RemoteEntry(name, entry.isDirectory, entry.size, entry.modified)
            }
    }

    private fun nameFromHref(entry: WebDavXml.Response): String? {
        entry.displayName?.let { return it }
        val decoded = runCatching { URLDecoder.decode(entry.href, Charsets.UTF_8.name()) }
            .getOrDefault(entry.href)
        return decoded.trimEnd('/').substringAfterLast('/').takeIf { it.isNotBlank() }
    }

    /** Porównanie ścieżek href, bo serwer może zwrócić adres względny albo pełny. */
    private fun sameResource(href: String, url: String): Boolean {
        fun pathOf(value: String) = runCatching { URI(value).path }.getOrNull().orEmpty()
            .trimEnd('/')
            .let { runCatching { URLDecoder.decode(it, Charsets.UTF_8.name()) }.getOrDefault(it) }
        return pathOf(href) == pathOf(url)
    }

    override fun info(account: RemoteAccount, path: String): RemoteEntry? {
        val responses = runCatching { propfind(account, path, depth = "0") }.getOrNull() ?: return null
        val entry = responses.firstOrNull() ?: return null
        return RemoteEntry(
            name = RemotePaths.nameOf(path).ifBlank { "/" },
            isDirectory = entry.isDirectory,
            size = entry.size,
            modified = entry.modified
        )
    }

    override fun createFolder(account: RemoteAccount, path: String) {
        val request = request(account, url(account, path, directory = true))
            .method("MKCOL", null)
            .build()
        execute(request) { }
    }

    override fun createFile(account: RemoteAccount, path: String) {
        val request = request(account, url(account, path))
            .put(ByteArray(0).toRequestBody(null))
            .build()
        execute(request) { }
    }

    override fun delete(account: RemoteAccount, path: String, isDirectory: Boolean): Boolean {
        val request = request(account, url(account, path, directory = isDirectory))
            .delete()
            .build()
        return runCatching { execute(request) { true } }.getOrDefault(false)
    }

    override fun move(account: RemoteAccount, from: String, to: String): Boolean {
        val request = request(account, url(account, from))
            .method("MOVE", null)
            .header("Destination", url(account, to))
            .header("Overwrite", "F")
            .build()
        return runCatching { execute(request) { true } }.getOrDefault(false)
    }

    override fun openInput(account: RemoteAccount, path: String): InputStream {
        val call = client.newCall(request(account, url(account, path)).get().build())
        val response = call.execute()
        if (!response.isSuccessful) {
            response.close()
            throw IOException(describe(response.code))
        }
        val body = response.body ?: run {
            response.close()
            throw IOException(text(R.string.dav_empty))
        }
        return object : InputStream() {
            private val source = body.byteStream()
            override fun read(): Int = source.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = source.read(b, off, len)
            override fun close() {
                runCatching { source.close() }
                response.close()
            }
        }
    }

    /**
     * WebDAV nie ma dopisywania strumieniem — PUT wysyła całą zawartość naraz.
     * Zapis idzie do pliku tymczasowego (w pamięci film zabrałby całą stertę)
     * i wysyłany jest przy zamknięciu strumienia, ze znaną długością.
     */
    override fun openOutput(account: RemoteAccount, path: String): OutputStream {
        val target = url(account, path)
        val temp = File.createTempFile("webdav", ".part")
        return object : FilterOutputStream(temp.outputStream().buffered()) {
            private var closed = false
            override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
            override fun close() {
                if (closed) return
                closed = true
                try {
                    super.close()
                    execute(request(account, target).put(temp.asRequestBody(null)).build()) { }
                } finally {
                    temp.delete()
                }
            }
        }
    }
}
