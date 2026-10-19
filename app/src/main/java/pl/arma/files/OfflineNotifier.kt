package pl.arma.files

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.text.DateFormat
import java.util.Date

/**
 * Powiadomienie „rozbieżność wersji pliku” z obiema wersjami i wyborem,
 * którą zachować. Wybór z powiadomienia trafia do [OfflineConflictReceiver];
 * te same rozbieżności widać też w aplikacji, w sekcji „Dostępne offline”.
 */
class OfflineNotifier(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "offline_conflicts"
        const val EXTRA_ENTRY = "entry"
        const val EXTRA_PATH = "path"
        const val EXTRA_CHOICE = "choice"
        const val ACTION_RESOLVE = "pl.arma.files.action.RESOLVE_OFFLINE_CONFLICT"

        fun describe(context: Context, size: Long, modified: Long): String =
            context.getString(
                R.string.offline_version,
                formatSize(size),
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(modified))
            )
    }

    private fun id(entry: OfflineStore.Entry, conflict: OfflineStore.Conflict) = (entry.id + "/" + conflict.path).hashCode()

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.offline_conflict_channel), NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    fun show(entry: OfflineStore.Entry, conflict: OfflineStore.Conflict) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        ensureChannel()
        val server = if (conflict.remoteDeleted) context.getString(R.string.offline_version_deleted)
        else describe(context, conflict.remoteSize, conflict.remoteModified)
        val phone = describe(context, conflict.localSize, conflict.localModified)
        val body = context.getString(R.string.offline_conflict_text, server, phone)
        val open = PendingIntent.getActivity(
            context, id(entry, conflict),
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(context.getString(R.string.offline_conflict_title, conflict.name))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(false)
        listOf(
            OfflineStore.Choice.REMOTE to R.string.offline_keep_remote,
            OfflineStore.Choice.LOCAL to R.string.offline_keep_local,
            OfflineStore.Choice.BOTH to R.string.offline_keep_both
        ).forEach { (choice, label) ->
            val intent = Intent(context, OfflineConflictReceiver::class.java)
                .setAction(ACTION_RESOLVE)
                .putExtra(EXTRA_ENTRY, entry.id)
                .putExtra(EXTRA_PATH, conflict.path)
                .putExtra(EXTRA_CHOICE, choice.name)
            val pending = PendingIntent.getBroadcast(
                context, id(entry, conflict) * 4 + choice.ordinal, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(0, context.getString(label), pending)
        }
        runCatching { NotificationManagerCompat.from(context).notify(id(entry, conflict), builder.build()) }
    }

    fun cancel(entry: OfflineStore.Entry, conflict: OfflineStore.Conflict) {
        NotificationManagerCompat.from(context).cancel(id(entry, conflict))
    }
}

/** Wybór wersji naciśnięty w powiadomieniu. */
class OfflineConflictReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != OfflineNotifier.ACTION_RESOLVE) return
        val entry = intent.getStringExtra(OfflineNotifier.EXTRA_ENTRY) ?: return
        val path = intent.getStringExtra(OfflineNotifier.EXTRA_PATH) ?: return
        val choice = runCatching { OfflineStore.Choice.valueOf(intent.getStringExtra(OfflineNotifier.EXTRA_CHOICE).orEmpty()) }.getOrNull() ?: return
        context.armaApp.offline.resolve(entry, path, choice)
    }
}
