package pl.arma.files

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import pl.arma.files.ops.OperationEngine

/**
 * Silnik operacji musi przeżyć zamknięcie ekranu, więc żyje w procesie
 * aplikacji, a nie w ViewModelu. Kopiowanie kilku gigabajtów na NAS nie
 * przerywa się dlatego, że użytkownik wyszedł z listy plików.
 */
class ArmaFilesApp : Application() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val engine: OperationEngine by lazy { OperationEngine(FileRepository(this), scope) }

    val trash: TrashStore by lazy { TrashStore(this) }
}

val Context.armaApp: ArmaFilesApp
    get() = applicationContext as ArmaFilesApp
