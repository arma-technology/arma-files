package pl.arma.files

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runInterruptible
import pl.arma.files.archive.ArchiveRepository
import java.io.File

/**
 * Sygnały „w tym folderze coś się zmieniło” — żeby lista odświeżała się
 * sama, gdy plik doda inna aplikacja albo ktoś na komputerze.
 *
 * Pamięć telefonu zgłasza zmiany od razu (inotify), dostawcy SAF przez
 * ContentObserver, SMB przez powiadomienia serwera. FTP, SFTP i WebDAV
 * nie mają powiadomień, więc folder jest odpytywany co [POLL_MS].
 */
class DirectoryWatcher(private val context: Context, private val repo: FileRepository) {

    companion object {
        const val POLL_MS = 15_000L
    }

    fun changes(uri: Uri): Flow<Unit> = when {
        uri.scheme == "file" -> uri.path?.let { local(File(it)) } ?: emptyFlow()
        uri.scheme == "content" -> provider(uri)
        RemoteRepository.isRemoteScheme(uri.scheme) -> remote(uri)
        // Archiwum się nie zmienia, dopóki jest otwarte.
        ArchiveRepository.isArchive(uri) -> emptyFlow()
        else -> emptyFlow()
    }

    private fun local(dir: File): Flow<Unit> = callbackFlow {
        val mask = FileObserver.CREATE or FileObserver.DELETE or FileObserver.MOVED_FROM or
            FileObserver.MOVED_TO or FileObserver.CLOSE_WRITE or FileObserver.DELETE_SELF or FileObserver.MOVE_SELF
        // Konstruktor z File jest dopiero od Androida 10.
        @Suppress("DEPRECATION")
        val observer = object : FileObserver(dir.absolutePath, mask) {
            override fun onEvent(event: Int, path: String?) {
                trySend(Unit)
            }
        }
        observer.startWatching()
        awaitClose { observer.stopWatching() }
    }

    private fun provider(uri: Uri): Flow<Unit> = callbackFlow {
        val children = runCatching {
            DocumentsContract.buildChildDocumentsUriUsingTree(uri, DocumentsContract.getDocumentId(uri))
        }.getOrNull()
        if (children == null) {
            close()
            return@callbackFlow
        }
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        context.contentResolver.registerContentObserver(children, true, observer)
        awaitClose { context.contentResolver.unregisterContentObserver(observer) }
    }

    private fun remote(uri: Uri): Flow<Unit> = flow {
        val watch = runCatching { repo.watchRemote(uri) }.getOrNull()
        if (watch != null) {
            try {
                // Serwer, który od razu odrzuca obserwację, przestaje być
                // pytany — przechodzimy na odpytywanie.
                var quickFailures = 0
                while (quickFailures < 3) {
                    val started = System.currentTimeMillis()
                    val changed = try {
                        runInterruptible { watch.await() }
                        true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        false
                    }
                    if (changed) {
                        quickFailures = 0
                        emit(Unit)
                    } else if (System.currentTimeMillis() - started < 2_000) {
                        quickFailures++
                        delay(1_000)
                    }
                }
            } finally {
                watch.close()
            }
        }
        while (true) {
            delay(POLL_MS)
            emit(Unit)
        }
    }.flowOn(Dispatchers.IO)
}
