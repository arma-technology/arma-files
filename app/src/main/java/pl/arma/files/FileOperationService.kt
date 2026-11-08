package pl.arma.files

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import pl.arma.files.ops.Operation
import pl.arma.files.ops.OperationStatus

/**
 * Utrzymuje proces przy życiu na czas trwania kolejki i pokazuje postęp w
 * powiadomieniu. Sam nie wykonuje pracy — ta dzieje się w silniku żyjącym w
 * [ArmaFilesApp]; serwis tylko obserwuje jego stan i gasi się, gdy kolejka
 * opustoszeje.
 */
class FileOperationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            intent.getStringExtra(EXTRA_OPERATION_ID)?.let { armaApp.engine.cancel(it) }
        }

        startForegroundWith(notification(null))
        // Obserwacja zawsze od nowa: przepływ od razu poda bieżący stan kolejki.
        // Stary obserwator mógł już uznać kolejkę za pustą i zatrzymać usługę —
        // kolejne uruchomienie (np. dwa zadania z szuflady naraz) zostawiało wtedy
        // na zawsze powiadomienie „Przygotowywanie…”, bo nic go już nie gasiło.
        watcher?.cancel()
        watcher = scope.launch { watchQueue() }
        return START_NOT_STICKY
    }

    private suspend fun watchQueue() {
        armaApp.engine.operations.collectLatest { operations ->
            val active = operations.firstOrNull { !it.isFinished }
            if (active == null) {
                ServiceCompat.stopForeground(this@FileOperationService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                notificationManager().cancel(NOTIFICATION_ID)
                stopSelf()
                return@collectLatest
            }
            notificationManager().notify(NOTIFICATION_ID, notification(active).build())
        }
    }

    private fun startForegroundWith(builder: NotificationCompat.Builder) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, builder.build(), type)
    }

    private fun notification(operation: Operation?): NotificationCompat.Builder {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (operation == null) {
            return builder.setContentTitle("Przygotowywanie…").setProgress(0, 0, true)
        }

        val progress = operation.progress
        val paused = operation.status == OperationStatus.PAUSED
        val speed = if (paused || progress.bytesPerSecond <= 0L) {
            null
        } else {
            "${formatSize(progress.bytesPerSecond)}/s"
        }
        val remaining = if (paused) null else formatRemaining(progress.secondsRemaining)

        val details = listOfNotNull(
            progress.currentName.takeIf { it.isNotBlank() },
            speed,
            remaining?.let { getString(R.string.ops_remaining, it) }
        ).joinToString(" · ")

        builder.setContentTitle(if (paused) getString(R.string.ops_paused_suffix, operation.label) else operation.label)
            .setContentText(details)
            .setSubText("${progress.filesDone}/${progress.filesTotal}")

        if (progress.bytesTotal > 0L) {
            builder.setProgress(1000, (progress.fraction * 1000).toInt(), false)
        } else {
            builder.setProgress(0, 0, true)
        }

        val cancel = PendingIntent.getService(
            this,
            1,
            Intent(this, FileOperationService::class.java)
                .setAction(ACTION_CANCEL)
                .putExtra(EXTRA_OPERATION_ID, operation.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        builder.addAction(0, getString(R.string.common_cancel), cancel)

        return builder
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        notificationManager().createNotificationChannel(channel)
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override fun onDestroy() {
        watcher?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "file_operations"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_CANCEL = "pl.arma.files.CANCEL_OPERATION"
        private const val EXTRA_OPERATION_ID = "operation_id"

        /**
         * Wołane z ekranu w chwili dodania zadania — Android 12+ pozwala wtedy
         * wystartować usługę pierwszoplanową, bo aplikacja jest widoczna.
         */
        fun start(context: Context) {
            val intent = Intent(context, FileOperationService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }
    }
}
