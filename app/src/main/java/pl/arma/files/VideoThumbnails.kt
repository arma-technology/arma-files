package pl.arma.files

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * Miniatury filmów z pamięci telefonu. Film z galerii Androida ma gotową
 * miniaturę systemową; inne (np. z ukrytych folderów) trzeba otworzyć
 * i zdekodować jedną klatkę — to kosztuje, więc wynik trafia też na dysk.
 * Filmy z serwerów i archiwów zostają przy ikonie: dla kafelka trzeba by
 * ściągać megabajty.
 */
class VideoThumbnails(private val context: Context, private val scope: CoroutineScope) {

    /**
     * Dwa dekodowania naraz: telefon ma kilka sprzętowych dekoderów wideo,
     * a zajęcie wszystkich zablokowałoby odtwarzacz.
     */
    private val gate = Semaphore(2)
    private val directory get() = File(context.cacheDir, DIRECTORY).apply { mkdirs() }
    private var prefetch: Job? = null

    /**
     * Tylko pliki leżące w telefonie. content:// to nie zawsze telefon:
     * folder Dropboxa czy Dysku Google podpięty przez SAF też daje content://,
     * a dostawca na prośbę o klatkę ściąga cały film — przy wejściu do folderu
     * byłyby to setki megabajtów. Serwery i archiwa mają własne schematy.
     */
    fun supports(item: FileItem): Boolean =
        !item.isDirectory && item.mimeType.startsWith("video/") &&
            (item.uri.scheme == "file" || (item.uri.scheme == "content" && item.uri.authority in LOCAL_PROVIDERS))

    /** Z pamięci, z dysku albo zdekodowana; null, gdy film się nie da odczytać. */
    suspend fun load(item: FileItem): Bitmap? {
        val key = ThumbnailCache.key(item.uri.toString(), item.lastModified, item.size)
        ThumbnailCache.get(key)?.let { return it }
        val bitmap = withContext(Dispatchers.IO) {
            val stored = File(directory, digest(key) + ".jpg")
            if (stored.length() > 0) {
                BitmapFactory.decodeFile(stored.path)?.let {
                    stored.setLastModified(System.currentTimeMillis())
                    return@withContext it
                }
            }
            gate.withPermit {
                coroutineContext.ensureActive()
                val fromGallery = galleryThumbnail(item)
                val decoded = fromGallery ?: runCatching { frame(item) }.getOrNull() ?: return@withPermit null
                // Systemowa miniatura już leży w pamięci Androida, nie dublujemy jej.
                if (fromGallery == null) runCatching {
                    val part = File(stored.path + ".part")
                    part.outputStream().use { decoded.compress(Bitmap.CompressFormat.JPEG, 82, it) }
                    part.renameTo(stored)
                }
                decoded
            }
        } ?: return null
        ThumbnailCache.put(key, bitmap)
        return bitmap
    }

    /**
     * Po wejściu do folderu: miniatury wszystkich filmów w kolejności
     * wyświetlania, po jednej, żeby kafelki na ekranie miały wolny dekoder.
     * Nowy folder przerywa poprzedni.
     */
    fun prefetch(items: List<FileItem>) {
        prefetch?.cancel()
        val videos = items.filter(::supports).take(PREFETCH_LIMIT)
        if (videos.isEmpty()) return
        prefetch = scope.launch(Dispatchers.IO) {
            for (item in videos) {
                ensureActive()
                runCatching { load(item) }
            }
        }
    }

    /** Miniatura z galerii (MediaStore), jeśli Android już ją zna. */
    private fun galleryThumbnail(item: FileItem): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val target = when (item.uri.scheme) {
            "file" -> {
                val path = item.uri.path ?: return null
                val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                runCatching {
                    context.contentResolver.query(
                        collection, arrayOf(MediaStore.MediaColumns._ID),
                        "${MediaStore.MediaColumns.DATA} = ?", arrayOf(path), null
                    )?.use { if (it.moveToFirst()) ContentUris.withAppendedId(collection, it.getLong(0)) else null }
                }.getOrNull()
            }
            else -> item.uri.takeIf { it.authority == MediaStore.AUTHORITY }
        } ?: return null
        return runCatching { context.contentResolver.loadThumbnail(target, Size(SIDE, SIDE), null) }.getOrNull()
    }

    /**
     * Klatka z około 10% filmu (najwyżej 3 s): pierwsza bywa czarna.
     * Najbliższa klatka kluczowa — dokładna wymagałaby dekodowania kolejnych.
     */
    private fun frame(item: FileItem): Bitmap? {
        val retriever = MediaMetadataRetriever()
        try {
            if (item.uri.scheme == "file") retriever.setDataSource(item.uri.path)
            else retriever.setDataSource(context, item.uri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val atUs = (durationMs / 10).coerceAtMost(3_000L) * 1000L
            val option = MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(atUs, option, SIDE, SIDE)
            } else {
                retriever.getFrameAtTime(atUs, option)?.let(::scaled)
            }
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun scaled(bitmap: Bitmap): Bitmap {
        val ratio = SIDE.toFloat() / maxOf(bitmap.width, bitmap.height)
        if (ratio >= 1f) return bitmap
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt().coerceAtLeast(1), (bitmap.height * ratio).toInt().coerceAtLeast(1), true)
            .also { if (it !== bitmap) bitmap.recycle() }
    }

    private fun digest(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        const val DIRECTORY = "video_thumbs"
        /** Dostawcy SAF, których pliki są w pamięci telefonu albo na karcie. */
        private val LOCAL_PROVIDERS = setOf(
            "com.android.externalstorage.documents",
            "com.android.providers.downloads.documents",
            "com.android.providers.media.documents",
            MediaStore.AUTHORITY
        )
        private const val SIDE = 512
        /** Folder z tysiącami filmów: dalsze miniatury powstaną przy przewijaniu. */
        private const val PREFETCH_LIMIT = 300
    }
}
