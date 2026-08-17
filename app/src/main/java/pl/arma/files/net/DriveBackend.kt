package pl.arma.files.net

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Google Drive przez oficjalne API v3.
 *
 * W odróżnieniu od pozostałych backendów Drive nie zna ścieżek, więc każda
 * operacja zaczyna się od zamiany ścieżki na identyfikator — patrz
 * [DrivePathResolver].
 */
class DriveBackend(
    private val clientId: String,
    private val tokens: DriveTokens = DriveTokens()
) : RemoteBackend {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val resolvers = HashMap<String, DrivePathResolver>()

    private fun resolver(account: RemoteAccount): DrivePathResolver = synchronized(resolvers) {
        resolvers.getOrPut(account.id) {
            DrivePathResolver { parentId -> children(account, parentId) }
        }
    }

    /** Token odświeżania trzymamy w polu hasła, czyli w szyfrowanym SecretStore. */
    private fun authorize(account: RemoteAccount, builder: Request.Builder): Request.Builder {
        val token = tokens.accessToken(account.id, clientId, account.password)
        return builder.header("Authorization", "Bearer $token")
    }

    private fun <T> call(account: RemoteAccount, request: Request, block: (Response) -> T): T =
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val text = response.body?.string().orEmpty()
                throw IOException(
                    DriveJson.parseError(text) ?: "Google Drive: błąd ${response.code}."
                )
            }
            block(response)
        }

    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name())

    /** Pobiera zawartość katalogu, przechodząc przez wszystkie strony wyników. */
    private fun children(account: RemoteAccount, parentId: String): List<DriveFile> {
        val all = mutableListOf<DriveFile>()
        var pageToken: String? = null
        do {
            val query = encode("'$parentId' in parents and trashed = false")
            val fields = encode("nextPageToken,files(id,name,mimeType,size,modifiedTime)")
            val page = pageToken?.let { "&pageToken=${encode(it)}" }.orEmpty()
            val request = authorize(
                account,
                Request.Builder().url(
                    "$FILES_ENDPOINT?q=$query&fields=$fields&pageSize=1000" +
                        "&supportsAllDrives=true&includeItemsFromAllDrives=true$page"
                )
            ).build()
            val (files, next) = call(account, request) {
                DriveJson.parseFileList(it.body?.string().orEmpty())
            }
            all += files
            pageToken = next
        } while (pageToken != null)
        return all
    }

    private fun idOf(account: RemoteAccount, path: String): String =
        resolver(account).resolve(RemotePaths.absolute(account.rootPath, path))
            ?: throw IOException("Google Drive: nie znaleziono ${RemotePaths.nameOf(path)}.")

    private fun fullPath(account: RemoteAccount, path: String) =
        RemotePaths.absolute(account.rootPath, path)

    override fun list(account: RemoteAccount, path: String): List<RemoteEntry> =
        children(account, idOf(account, path)).map { file ->
            RemoteEntry(
                name = file.name,
                isDirectory = file.isFolder,
                // Dokument Google nie ma pobieralnej treści ani rozmiaru;
                // pokazujemy go, ale użytkownik zobaczy zero bajtów.
                size = file.size,
                modified = file.modified
            )
        }

    override fun info(account: RemoteAccount, path: String): RemoteEntry? {
        val full = fullPath(account, path)
        if (full == "/") return RemoteEntry("/", true, 0, 0)
        val id = resolver(account).resolve(full) ?: return null
        val fields = encode("id,name,mimeType,size,modifiedTime")
        val request = authorize(
            account,
            Request.Builder().url("$FILES_ENDPOINT/$id?fields=$fields&supportsAllDrives=true")
        ).build()
        val file = call(account, request) { DriveJson.parseFile(it.body?.string().orEmpty()) } ?: return null
        return RemoteEntry(file.name, file.isFolder, file.size, file.modified)
    }

    override fun createFolder(account: RemoteAccount, path: String) {
        val full = fullPath(account, path)
        val parentId = resolver(account).resolve(RemotePaths.parentOf(full))
            ?: throw IOException("Google Drive: brak katalogu nadrzędnego.")
        val payload = JSONObject()
            .put("name", RemotePaths.nameOf(full))
            .put("mimeType", DriveFile.FOLDER_MIME)
            .put("parents", JSONArray().put(parentId))
        val request = authorize(
            account,
            Request.Builder()
                .url("$FILES_ENDPOINT?supportsAllDrives=true")
                .post(payload.toString().toRequestBody(JSON_MEDIA))
        ).build()
        val created = call(account, request) { DriveJson.parseFile(it.body?.string().orEmpty()) }
        created?.let { resolver(account).remember(full, it.id) }
    }

    override fun createFile(account: RemoteAccount, path: String) {
        upload(account, path, ByteArray(0))
    }

    override fun delete(account: RemoteAccount, path: String, isDirectory: Boolean): Boolean {
        val full = fullPath(account, path)
        val id = resolver(account).resolve(full) ?: return false
        val request = authorize(
            account,
            Request.Builder().url("$FILES_ENDPOINT/$id?supportsAllDrives=true").delete()
        ).build()
        return runCatching {
            call(account, request) { true }
            resolver(account).invalidate(full)
            true
        }.getOrDefault(false)
    }

    override fun move(account: RemoteAccount, from: String, to: String): Boolean {
        val fullFrom = fullPath(account, from)
        val fullTo = fullPath(account, to)
        val id = resolver(account).resolve(fullFrom) ?: return false
        val oldParent = resolver(account).resolve(RemotePaths.parentOf(fullFrom)) ?: return false
        val newParent = resolver(account).resolve(RemotePaths.parentOf(fullTo)) ?: return false

        val payload = JSONObject().put("name", RemotePaths.nameOf(fullTo))
        val parents = if (oldParent != newParent) {
            "&addParents=${encode(newParent)}&removeParents=${encode(oldParent)}"
        } else {
            ""
        }
        val request = authorize(
            account,
            Request.Builder()
                .url("$FILES_ENDPOINT/$id?supportsAllDrives=true$parents")
                .patch(payload.toString().toRequestBody(JSON_MEDIA))
        ).build()
        return runCatching {
            call(account, request) { true }
            resolver(account).invalidate(fullFrom)
            resolver(account).remember(fullTo, id)
            true
        }.getOrDefault(false)
    }

    override fun openInput(account: RemoteAccount, path: String): InputStream {
        val id = idOf(account, path)
        val request = authorize(
            account,
            Request.Builder().url("$FILES_ENDPOINT/$id?alt=media&supportsAllDrives=true")
        ).build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            val text = response.body?.string().orEmpty()
            response.close()
            throw IOException(
                DriveJson.parseError(text)
                    ?: "Google Drive: nie można pobrać pliku (${response.code})."
            )
        }
        val body = response.body ?: run {
            response.close()
            throw IOException("Google Drive: pusta odpowiedź.")
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
     * Drive nie przyjmuje strumienia przyrostowo — zawartość idzie w jednym
     * żądaniu, więc zapis buforujemy i wysyłamy przy zamknięciu strumienia.
     */
    override fun openOutput(account: RemoteAccount, path: String): OutputStream =
        object : ByteArrayOutputStream() {
            override fun close() {
                super.close()
                upload(account, path, toByteArray())
            }
        }

    private fun upload(account: RemoteAccount, path: String, content: ByteArray) {
        val full = fullPath(account, path)
        val name = RemotePaths.nameOf(full)
        val existing = resolver(account).resolve(full)

        val media = content.toRequestBody("application/octet-stream".toMediaType())
        val request = if (existing != null) {
            // Podmiana treści istniejącego pliku — zwykły upload multipart
            // utworzyłby drugi plik o tej samej nazwie, co Drive dopuszcza.
            authorize(
                account,
                Request.Builder()
                    .url("$UPLOAD_ENDPOINT/$existing?uploadType=media&supportsAllDrives=true")
                    .patch(media)
            ).build()
        } else {
            val parentId = resolver(account).resolve(RemotePaths.parentOf(full))
                ?: throw IOException("Google Drive: brak katalogu nadrzędnego.")
            val metadata = JSONObject()
                .put("name", name)
                .put("parents", JSONArray().put(parentId))
            val body = MultipartBody.Builder()
                .setType("multipart/related".toMediaType())
                .addPart(metadata.toString().toRequestBody(JSON_MEDIA))
                .addPart(media)
                .build()
            authorize(
                account,
                Request.Builder()
                    .url("$UPLOAD_ENDPOINT?uploadType=multipart&supportsAllDrives=true")
                    .post(body)
            ).build()
        }

        val uploaded = call(account, request) { DriveJson.parseFile(it.body?.string().orEmpty()) }
        uploaded?.let { resolver(account).remember(full, it.id) }
    }

    override fun disconnect(accountId: String) {
        tokens.forget(accountId)
        synchronized(resolvers) { resolvers.remove(accountId) }
    }

    private companion object {
        const val FILES_ENDPOINT = "https://www.googleapis.com/drive/v3/files"
        const val UPLOAD_ENDPOINT = "https://www.googleapis.com/upload/drive/v3/files"
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
