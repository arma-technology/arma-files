package pl.arma.files

import android.graphics.Bitmap
import android.util.LruCache
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Miniatury: pamięć podręczna i ograniczenie równoległości.
 *
 * Bez tego każdy widoczny kafelek dekodował obraz od nowa przy każdym powrocie
 * na listę, a przy siatce zdjęć kilkanaście dekodowań ruszało naraz. Dla
 * zasobów sieciowych oznaczało to też tyle równoczesnych pobrań, ile kafelków.
 */
object ThumbnailCache {

    /** Ósma część pamięci aplikacji — reszta jest potrzebna na podgląd i PDF. */
    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8).toInt().coerceAtLeast(4 * 1024 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /**
     * Trzy naraz. Więcej nie przyspiesza dekodowania (i tak ogranicza je
     * procesor), a przy FTP i SMB rozjeżdża pulę połączeń.
     */
    private val gate = Semaphore(3)

    fun key(uri: String, modified: Long, size: Long): String = "$uri|$modified|$size"

    fun get(key: String): Bitmap? = cache.get(key)?.takeIf { !it.isRecycled }

    fun put(key: String, bitmap: Bitmap) {
        cache.put(key, bitmap)
    }

    /** Wykonuje dekodowanie z zachowaniem limitu równoległości. */
    suspend fun <T> throttled(block: suspend () -> T): T = gate.withPermit { block() }

    fun clear() = cache.evictAll()
}
