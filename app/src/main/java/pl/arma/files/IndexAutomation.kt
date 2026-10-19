package pl.arma.files

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import pl.arma.files.index.Indexer
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Samoczynne odświeżanie indeksu plików: przy starcie aplikacji, co kilka
 * dni albo gdy zajętość pamięci zmieni się o zadaną liczbę megabajtów
 * (znak, że przybyło lub ubyło plików). Sprawdzenie warunków jest tanie —
 * kosztowny jest tylko sam skan, więc uruchamiamy go wyłącznie, gdy trzeba.
 */
class IndexAutomation(
    private val context: Context,
    private val indexer: Indexer,
    private val scope: CoroutineScope
) {
    private val prefs = context.getSharedPreferences("index_auto", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) { prefs.edit().putBoolean("enabled", value).apply(); schedule() }

    var onStartup: Boolean
        get() = prefs.getBoolean("startup", true)
        set(value) { prefs.edit().putBoolean("startup", value).apply() }

    /** Co ile dni; 0 = wyłączone. */
    var everyDays: Int
        get() = prefs.getInt("days", 7)
        set(value) { prefs.edit().putInt("days", value).apply(); schedule() }

    /** Próg zmiany zajętości w MB; 0 = wyłączone. */
    var onChangeMb: Int
        get() = prefs.getInt("change_mb", 500)
        set(value) { prefs.edit().putInt("change_mb", value).apply(); schedule() }

    val lastBuilt: Long get() = prefs.getLong("last_built", 0L)

    init {
        indexer.onFinished = {
            prefs.edit()
                .putLong("last_built", System.currentTimeMillis())
                .putLong("used_bytes", usedBytes())
                .apply()
        }
    }

    /** Po usunięciu indeksu: następne sprawdzenie zbuduje go od nowa. */
    fun forget() {
        prefs.edit().remove("last_built").remove("used_bytes").apply()
    }

    /** Zajęte miejsce na wszystkich nośnikach, które indeksujemy. */
    private fun usedBytes(): Long = roots().sumOf { root ->
        runCatching { StatFs(root.path).let { it.totalBytes - it.availableBytes } }.getOrDefault(0L)
    }

    /** Czy któryś z włączonych warunków każe odświeżyć indeks. */
    fun due(startup: Boolean): Boolean {
        if (!enabled || !hasStorageAccess()) return false
        if (startup && onStartup) return true
        val last = lastBuilt
        if (last == 0L) return true
        if (everyDays > 0 && System.currentTimeMillis() - last >= TimeUnit.DAYS.toMillis(everyDays.toLong())) return true
        if (onChangeMb > 0) {
            val before = prefs.getLong("used_bytes", 0L)
            if (before > 0 && kotlin.math.abs(usedBytes() - before) >= onChangeMb * 1_048_576L) return true
        }
        return false
    }

    /** Przy starcie aplikacji: sprawdza warunki w tle i ewentualnie buduje. */
    fun checkOnStartup() {
        scope.launch { if (due(startup = true)) indexer.start(roots().map(Uri::fromFile)) }
    }

    /** Z pracy w tle: buduje, jeśli trzeba, i czeka na koniec. */
    suspend fun runIfDue() {
        if (due(startup = false)) indexer.start(roots().map(Uri::fromFile))?.join()
    }

    /**
     * Sprawdzanie w tle raz na kilka godzin — dni i zmiana zajętości nie
     * zależą od tego, czy aplikacja jest otwarta.
     */
    fun schedule() {
        val work = runCatching { WorkManager.getInstance(context) }.getOrNull() ?: return
        if (!enabled || (everyDays == 0 && onChangeMb == 0)) {
            work.cancelUniqueWork(WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<IndexWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        work.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    private fun hasStorageAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else true

    /** Nośniki do indeksowania: pamięć wbudowana i podłączone karty/pendrive’y. */
    fun roots(): List<File> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val manager = context.getSystemService(StorageManager::class.java)
            return manager.storageVolumes.mapNotNull { it.directory?.takeIf(File::exists) }
        }
        @Suppress("DEPRECATION")
        return listOf(Environment.getExternalStorageDirectory())
    }

    private companion object {
        const val WORK = "index-auto"
    }
}

class IndexWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        applicationContext.armaApp.indexAutomation.runIfDue()
        return Result.success()
    }
}
