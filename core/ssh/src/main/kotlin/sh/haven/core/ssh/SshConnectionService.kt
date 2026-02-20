package sh.haven.core.ssh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class SshConnectionService : Service() {

    @Inject
    lateinit var sessionManager: SshSessionManager

    companion object {
        const val CHANNEL_ID = "haven_ssh_connection"
        const val NOTIFICATION_ID = 1
        const val ACTION_DISCONNECT_ALL = "sh.haven.action.DISCONNECT_ALL"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT_ALL) {
            sessionManager.disconnectAll()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        sessionManager.disconnectAll()
    }

    private fun buildNotification(): Notification {
        val count = sessionManager.activeSessions.size
        val labels = sessionManager.activeSessions.joinToString(", ") { it.label }

        val disconnectIntent = Intent(this, SshConnectionService::class.java).apply {
            action = ACTION_DISCONNECT_ALL
        }
        val disconnectPending = PendingIntent.getService(
            this, 0, disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Haven — $count active session${if (count != 1) "s" else ""}")
            .setContentText(labels.ifEmpty { "Connecting..." })
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_delete,
                "Disconnect All",
                disconnectPending,
            )
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "SSH Connections",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Active SSH connection status"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
}
