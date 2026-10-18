package pl.arma.files

import android.app.Application
import android.content.Context
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

    override fun onCreate() {
        super.onCreate()
        // DWG otwiera przeglądarka arkuszy przez LibreDWG (moduł :dwg, GPL).
        pl.arma.dwg.LibreDwgConverter.install()
    }
}

val Context.armaApp: ArmaFilesApp
    get() = applicationContext as ArmaFilesApp
