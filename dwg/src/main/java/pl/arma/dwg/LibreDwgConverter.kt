package pl.arma.dwg

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import pl.arma.viewer.SheetDocuments
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Zamiana DWG na DXF przez LibreDWG w osobnym procesie, z pamięcią podręczną:
 * ponowne otwarcie tego samego rysunku nie konwertuje go od nowa.
 */
object LibreDwgConverter : SheetDocuments.DwgConverter {

    /** Limit wyników w pamięci podręcznej; gospodarz może go zmienić. */
    @Volatile
    var cacheLimitBytes: Long = 1_500_000_000L

    /** Rejestruje konwerter w przeglądarce. */
    fun install() {
        SheetDocuments.dwgConverter = this
    }

    override suspend fun toDxf(context: Context, dwg: File): File = withContext(Dispatchers.IO) {
        val cache = File(context.cacheDir, "dwg_dxf").apply { mkdirs() }
        val key = fingerprint(dwg)
        val target = File(cache, "$key.dxf")
        if (target.isFile && target.length() > 0) return@withContext target.also { it.setLastModified(System.currentTimeMillis()) }
        val partial = File(cache, "$key.part")
        partial.delete()
        // Największe rysunki konwertują się na telefonie około minuty.
        val error = withTimeout(5 * 60_000L) { convert(context, dwg, partial) }
        if (error != null) {
            partial.delete()
            throw IllegalStateException(error)
        }
        check(partial.length() > 0) { context.getString(R.string.dwg_no_result) }
        partial.renameTo(target)
        trim(cache, keep = target)
        target
    }

    /** Odcisk pliku: rozmiar, czas zmiany i pierwsze 64 KB treści. */
    private fun fingerprint(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update("${file.length()}:${file.lastModified()}".toByteArray())
        file.inputStream().use { input ->
            val buffer = ByteArray(65_536)
            val n = input.read(buffer)
            if (n > 0) digest.update(buffer, 0, n)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }.take(24)
    }

    /** Pamięć podręczna do [cacheLimitBytes] — najdawniej używane wyniki wylatują pierwsze. */
    private fun trim(dir: File, keep: File) {
        val files = dir.listFiles { f -> f.extension == "dxf" || f.extension == "dxb" }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= cacheLimitBytes) break
            if (f == keep) continue
            total -= f.length()
            f.delete()
        }
    }

    private suspend fun convert(context: Context, input: File, output: File): String? =
        suspendCancellableCoroutine { continuation ->
            val app = context.applicationContext
            lateinit var connection: ServiceConnection
            val replies = Messenger(object : Handler(Looper.getMainLooper()) {
                override fun handleMessage(msg: Message) {
                    if (msg.what != DwgConversionService.DONE) return
                    runCatching { app.unbindService(connection) }
                    if (continuation.isActive) continuation.resume(msg.data.getString(DwgConversionService.ERROR))
                }
            })
            connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    val request = Message.obtain(null, DwgConversionService.CONVERT).apply {
                        data = Bundle().apply {
                            putString(DwgConversionService.INPUT, input.absolutePath)
                            putString(DwgConversionService.OUTPUT, output.absolutePath)
                        }
                        replyTo = replies
                    }
                    runCatching { Messenger(service).send(request) }.onFailure { fail(continuation, it) }
                }

                // Proces padł przed odpowiedzią: błąd biblioteki albo brak pamięci.
                override fun onServiceDisconnected(name: ComponentName?) {
                    if (continuation.isActive) {
                        runCatching { app.unbindService(this) }
                        continuation.resume(app.getString(R.string.dwg_crashed))
                    }
                }
            }
            val bound = app.bindService(Intent(app, DwgConversionService::class.java), connection, Context.BIND_AUTO_CREATE)
            if (!bound) continuation.resume(app.getString(R.string.dwg_start_failed))
            continuation.invokeOnCancellation { runCatching { app.unbindService(connection) } }
        }

    private fun fail(continuation: CancellableContinuation<String?>, error: Throwable) {
        if (continuation.isActive) continuation.resumeWithException(error)
    }
}
