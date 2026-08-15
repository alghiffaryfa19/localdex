package com.localdex

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.localdex.scrcpy.ScrcpySession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the DeX session. Its notification is the guaranteed
 * way to stop everything even if the viewer UI is gone.
 */
class DexService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                ScrcpySession.current?.stop()
                shutDown()
                return START_NOT_STICKY
            }
            else -> {
                createNotificationChannel()
                startForeground(NOTIFICATION_ID, buildNotification())

                // The session is created in [Companion.start] (synchronously, in the
                // caller) so the viewer can never race an async service start.
                val session = ScrcpySession.current
                if (session == null) {
                    shutDown()
                } else {
                    serviceScope.launch {
                        session.state.collectLatest { state ->
                            when (state) {
                                is ScrcpySession.State.Stopped -> shutDown()
                                is ScrcpySession.State.Running -> {
                                    getSystemService(NotificationManager::class.java)
                                        .notify(NOTIFICATION_ID, buildNotification(state.displayId))
                                }
                                else -> {}
                            }
                        }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun shutDown() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        // The service dying must never leave a session (and the overlay display) behind.
        ScrcpySession.current?.stop()
        serviceScope.cancel()
    }

    private fun buildNotification(displayId: Int = -1): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, DexService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this,
            2,
            Intent(this, ViewerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle(
                if (displayId >= 0) "LocalDex is running (display $displayId)"
                else "LocalDex is running"
            )
            .setContentText("Tap to open the viewer")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "DeX session",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shown while a DeX session is running"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "dex_session"
        private const val NOTIFICATION_ID = 4001

        const val ACTION_STOP = "com.localdex.STOP_DEX"

        fun start(context: Context) {
            if (ScrcpySession.current == null) {
                val session = ScrcpySession(
                    context.applicationContext,
                    Prefs.getDisplaySpec(context),
                )
                ScrcpySession.current = session
                session.start()
            }
            ContextCompat.startForegroundService(context, Intent(context, DexService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, DexService::class.java).setAction(ACTION_STOP))
        }
    }
}
