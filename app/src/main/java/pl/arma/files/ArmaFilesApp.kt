package pl.arma.files

import android.app.Application
import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import pl.arma.files.index.FileIndexDb
import pl.arma.files.index.Indexer
import pl.arma.files.ops.OperationEngine

/**
 * Silnik operacji musi przeżyć zamknięcie ekranu, więc żyje w procesie
 * aplikacji, a nie w ViewModelu. Kopiowanie kilku gigabajtów na NAS nie
 * przerywa się dlatego, że użytkownik wyszedł z listy plików.
 */
class ArmaFilesApp : Application() {

    companion object {
        @Volatile
        var instance: ArmaFilesApp? = null
            private set
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Jedno repozytorium na proces. Wcześniej ViewModel tworzył własne obok
     * tego z silnika, przez co istniały dwie pule połączeń i dwa konteksty SMB
     * — kopiowanie łączyło się z serwerem osobno od przeglądania.
     */
    val repository: FileRepository by lazy { FileRepository(this) }

    val engine: OperationEngine by lazy { OperationEngine(repository, scope) }

    val trash: TrashStore by lazy { TrashStore(this) }

    /** Indeks plików i skaner, który go buduje — wspólne dla całego procesu. */
    val index: FileIndexDb by lazy { FileIndexDb(this) }

    val indexer: Indexer by lazy { Indexer(index, scope) }

    val cacheJanitor: CacheJanitor by lazy { CacheJanitor(this, scope) }
    val videoThumbnails: VideoThumbnails by lazy { VideoThumbnails(this, scope) }

    val indexAutomation: IndexAutomation by lazy { IndexAutomation(this, indexer, scope) }

    val offline: OfflineStore by lazy { OfflineStore(this, repository, scope).also { repository.offline = it } }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLanguage.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        AppLanguage.applyToProcess(this)
        // DWG otwiera przeglądarka arkuszy przez LibreDWG (moduł :dwg, GPL).
        pl.arma.dwg.LibreDwgConverter.install()
        cacheJanitor.applyDwgLimit()
        cacheJanitor.trimAsync()
        // Kopie offline doganiają serwer przy każdym starcie — tylko przez Wi-Fi.
        offline.syncAll(automatic = true)
        OfflineSyncWorker.schedule(this, offline.intervalMinutes, offline.wifiOnly)
        // Także po to, żeby ręcznie zbudowany indeks zapisał czas i zajętość.
        indexAutomation.checkOnStartup()
        indexAutomation.schedule()
    }
}

/**
 * Tekst z zasobów dla kodu bez kontekstu (błędy serwerów, opisy operacji).
 * W czystych testach JVM aplikacji nie ma — wtedy zostaje identyfikator.
 */
fun text(@StringRes id: Int, vararg args: Any): String =
    ArmaFilesApp.instance?.getString(id, *args) ?: "#$id"

/** Liczebnik z zasobów: „1 plik”, „3 pliki”, „5 plików”. */
fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): String =
    ArmaFilesApp.instance?.resources?.getQuantityString(id, count, *args) ?: "#$id $count"

val Context.armaApp: ArmaFilesApp
    get() = applicationContext as ArmaFilesApp
