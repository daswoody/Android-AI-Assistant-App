package de.heimai.app.wakeword

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import de.heimai.app.HeimAiApp
import de.heimai.app.MainActivity
import de.heimai.app.R
import de.heimai.app.assistant.AssistantOverlayActivity
import de.heimai.app.assistant.HeimVoiceInteractionService

/**
 * Dauerhaft laufender Microphone-Foreground-Service für die
 * Wake-Word-Erkennung. Energie-Profil: Porcupine dekodiert auf dem
 * DSP-freundlichen Pfad (~16 kHz Mono, winziges Modell) — der dominante
 * Verbraucher ist das offene Mikrofon selbst, nicht die Erkennung.
 *
 * Bei Erkennung wird das Assistant-Popup geöffnet:
 *  1. bevorzugt über die VoiceInteraction-Session (wenn Heim-AI als
 *     System-Assistent gesetzt ist),
 *  2. sonst direkt als Overlay-Activity ("Über anderen Apps anzeigen"
 *     muss erteilt sein),
 *  3. sonst als High-Priority-Notification.
 */
class WakeWordService : Service() {

    private var engine: WakeWordEngine? = null
    /** true, solange das Assistant-Overlay das Mikrofon nutzt (Erkennung pausiert). */
    private var paused = false
    /** Signatur der aktuell gebauten Engine (Key|Keyword|ppn|pv) — verhindert unnötigen Neuaufbau. */
    private var builtSignature: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        val settings = HeimAiApp.from(application).container.settings.currentBlocking()
        val accessKey = settings.effectiveWakeWordKey
        val useCustom = settings.wakeWordKeyword == PorcupineEngine.CUSTOM &&
            settings.customWakeWordPath.isNotBlank()
        val configReady = if (settings.wakeWordKeyword == PorcupineEngine.CUSTOM) useCustom else true
        if (!settings.wakeWordEnabled || accessKey.isBlank() || !configReady || !hasMicPermission()) {
            stopSelf()
            return START_NOT_STICKY
        }
        val signature = listOf(
            accessKey,
            settings.wakeWordKeyword,
            if (useCustom) settings.customWakeWordPath else "",
            if (useCustom) settings.customWakeWordModelPath else "",
        ).joinToString("|")

        // Unveränderte Konfiguration + bereits gebaute Engine: NICHTS tun.
        // Ein beiläufiger Neustart (z. B. MainActivity.onCreate bei Rotation) darf
        // eine vom Overlay gewollte Pause nicht aufheben (sonst Mikrofon-Konflikt).
        if (engine != null && signature == builtSignature) {
            return START_STICKY
        }

        // Konfiguration neu/geändert: Engine mit frischen Einstellungen aufbauen.
        engine?.release()
        engine = null
        try {
            engine = PorcupineEngine(
                context = this,
                accessKey = accessKey,
                keyword = settings.wakeWordKeyword,
                customKeywordPath = if (useCustom) settings.customWakeWordPath else "",
                customModelPath = if (useCustom) settings.customWakeWordModelPath else "",
                onDetected = ::onWakeWord,
            )
            builtSignature = signature
            // Nur starten, wenn nicht gerade vom Overlay pausiert (Mikrofon frei halten).
            if (!paused) engine?.start()
        } catch (e: Exception) {
            builtSignature = null
            Log.e(TAG, "Wake-Word-Engine konnte nicht starten", e)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        engine?.release()
        engine = null
        instance = null
        super.onDestroy()
    }

    /** Erkennung pausieren, solange das Assistant-Popup selbst das Mikrofon braucht. */
    fun pauseDetection() {
        paused = true
        engine?.stop()
    }

    fun resumeDetection() {
        paused = false
        runCatching { engine?.start() }
    }

    private fun onWakeWord() {
        val voiceService = HeimVoiceInteractionService.instance
        when {
            voiceService != null -> runCatching { voiceService.showSession(null, 0) }
                .onFailure { launchOverlayDirectly() }
            Settings.canDrawOverlays(this) -> launchOverlayDirectly()
            else -> postOpenNotification()
        }
    }

    private fun launchOverlayDirectly() {
        runCatching {
            startActivity(
                Intent(this, AssistantOverlayActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { postOpenNotification() }
    }

    private fun postOpenNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val open = PendingIntent.getActivity(
            this, 1,
            Intent(this, AssistantOverlayActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle("Wake Word erkannt")
            .setContentText("Tippen, um die Heim-AI zu öffnen")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        manager.notify(2, notification)
    }

    private fun startInForeground() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.wakeword_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle(getString(R.string.wakeword_notification_title))
            .setContentText(getString(R.string.wakeword_notification_text))
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .build()
        ServiceCompat.startForeground(
            this, 1, notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0,
        )
    }

    private fun hasMicPermission(): Boolean =
        checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "WakeWordService"
        private const val CHANNEL_ID = "wakeword"

        @Volatile
        var instance: WakeWordService? = null
            private set

        fun start(context: Context) {
            runCatching {
                context.startForegroundService(Intent(context, WakeWordService::class.java))
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WakeWordService::class.java))
        }
    }
}
