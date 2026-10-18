package pl.arma.dwg

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process

/**
 * Konwersja DWG → binarny DXF w osobnym procesie (:dwg).
 *
 * Po każdej konwersji proces się kończy: LibreDWG nie zwalnia pamięci dużych
 * rysunków (gigabajt przy największych), a nowy proces startuje w ułamku sekundy.
 */
class DwgConversionService : Service() {

    private lateinit var worker: HandlerThread
    private lateinit var messenger: Messenger

    override fun onCreate() {
        super.onCreate()
        worker = HandlerThread("dwg").apply { start() }
        messenger = Messenger(object : Handler(worker.looper) {
            override fun handleMessage(msg: Message) {
                if (msg.what != CONVERT) return
                val data = msg.data
                val input = data.getString(INPUT) ?: return
                val output = data.getString(OUTPUT) ?: return
                val error = runCatching { DwgNative.toDxf(input, output, true) }
                    .getOrElse { it.message ?: it::class.java.simpleName }
                val reply = Message.obtain(null, DONE).apply {
                    this.data = Bundle().apply { putString(ERROR, error) }
                }
                runCatching { msg.replyTo?.send(reply) }
                Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, 300)
            }
        })
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        worker.quitSafely()
        super.onDestroy()
    }

    companion object {
        const val CONVERT = 1
        const val DONE = 2
        const val INPUT = "input"
        const val OUTPUT = "output"
        const val ERROR = "error"
    }
}
