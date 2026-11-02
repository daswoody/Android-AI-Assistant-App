package de.heimai.app.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import de.heimai.app.HeimAiApp
import de.heimai.app.MainActivity
import de.heimai.app.R

/**
 * Hält den App-Prozess am Leben, solange der [de.heimai.app.core.session.SessionHub]
 * eine Sitzung für Hintergrund-Agenten offen hält (v1.29) — ohne Bildschirm
 * würde Android den Prozess sonst irgendwann beenden, die Verbindung bräche
 * ab und der Server verwürfe das Agenten-Ergebnis.
 *
 * Der Service hält selbst keine Sitzung; er zeigt nur die laufende
 * Benachrichtigung ("Agent arbeitet …") mit einem Abbrechen-Knopf.
 */
class AgentSessionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Innerhalb von 5 s nach startForegroundService Pflicht — auch beim Abbrechen.
        startInForeground()
        if (intent?.action == ACTION_CANCEL) {
            HeimAiApp.from(application).container.sessions.cancelBackground()
        }
        // Prozess-Neustart: Sitzungen sind weg, nichts wiederherzustellen.
        return START_NOT_STICKY
    }

    /** Android 15: dataSync-Services haben ein Tageslimit — dann sauber beenden. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "Foreground-Zeitlimit erreicht")
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        lastText = null
        super.onDestroy()
    }

    private fun startInForeground() {
        ensureChannel(this)
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(this),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
    }

    companion object {
        private const val TAG = "AgentSessionService"
        private const val CHANNEL_ID = "agent_session"
        private const val NOTIFICATION_ID = 20
        private const val ACTION_CANCEL = "de.heimai.app.action.CANCEL_AGENTS"

        @Volatile private var running = false
        /** Zuletzt angezeigter Text — Benachrichtigung nur bei Änderung neu posten. */
        private var lastText: String? = null

        /** Starten bzw. die Benachrichtigung aktualisieren (Main-Thread). */
        fun ensureRunning(context: Context) {
            val app = context.applicationContext
            if (!running) {
                running = true // gegen Mehrfachstart, bis onCreate läuft
                runCatching {
                    app.startForegroundService(Intent(app, AgentSessionService::class.java))
                }.onFailure {
                    // z. B. Start aus dem Hintergrund verweigert: Sitzungen leben trotzdem,
                    // solange Android den Prozess nicht beendet.
                    running = false
                    Log.w(TAG, "Service-Start abgelehnt: ${it.message}")
                }
                return
            }
            val text = HeimAiApp.from(app as android.app.Application).container.sessions.notificationText()
            if (text == lastText) return
            val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, buildNotification(app))
        }

        fun stop(context: Context) {
            if (!running) return
            running = false
            context.applicationContext.stopService(Intent(context.applicationContext, AgentSessionService::class.java))
        }

        private fun ensureChannel(context: Context) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.agent_session_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }

        private fun buildNotification(context: Context): Notification {
            val app = context.applicationContext
            val text = HeimAiApp.from(app as android.app.Application).container.sessions.notificationText()
            lastText = text
            val open = PendingIntent.getActivity(
                app, 0, Intent(app, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
            )
            val cancel = PendingIntent.getService(
                app, 1,
                Intent(app, AgentSessionService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE,
            )
            return NotificationCompat.Builder(app, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_app)
                .setContentTitle("Heim-AI")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .addAction(0, "Abbrechen", cancel)
                .build()
        }
    }
}
