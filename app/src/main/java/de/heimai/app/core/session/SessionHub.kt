package de.heimai.app.core.session

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import de.heimai.app.R
import de.heimai.app.assistant.AgentSessionService
import de.heimai.app.assistant.AssistantOverlayActivity
import de.heimai.app.core.AppContainer
import de.heimai.app.core.network.AssistantSession
import de.heimai.app.core.network.ConnectionState
import de.heimai.app.core.network.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Hält die Assistenz-Sitzungen app-weit statt pro Bildschirm (v1.29).
 *
 * Hintergrund-Agenten leben serverseitig nur so lange wie die WebSocket-
 * Verbindung. Schließt der Nutzer Chat, Talk oder das Assist-Overlay, während
 * ein Agent arbeitet, bleibt die Sitzung deshalb hier offen — abgesichert
 * durch [AgentSessionService] —, bis das Ergebnis zugestellt ist, plus
 * [LINGER_MS] Leerlauf für eine Rückfrage im selben Gespräch.
 *
 * Meldet sich die Assistenz von selbst (`proactive`) und kein Bildschirm der
 * Sitzung ist sichtbar, öffnet der Hub das Overlay auf dieser Sitzung.
 *
 * Alle Methoden laufen auf dem Main-Thread.
 */
class SessionHub(private val app: Application, private val container: AppContainer) {

    /** Eine Sitzung + wer sie gerade nutzt. */
    private inner class Entry(val key: Long, val mode: String) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var session: AssistantSession
        /** Bildschirme (ViewModel/Activity), die die Sitzung halten */
        var holders = 0
        /** davon gerade sichtbar (zwischen onStart und onStop) */
        var visible = 0
        /** Die Sitzung hatte Hintergrund-Agenten → nach der Zustellung noch [LINGER_MS] offen halten */
        var hadAgents = false
        /** Ab hier darf eine unbenutzte Sitzung ohne offene Agenten-Arbeit schließen (0 = nicht gesetzt) */
        var lingerUntil = 0L
        var lingerJob: Job? = null
    }

    /** Ergebnis von [acquire]: isNew = frisch verbunden (Verlauf muss geladen werden). */
    data class Lease(val key: Long, val session: AssistantSession, val isNew: Boolean)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val entries = mutableListOf<Entry>()
    private var nextKey = 1L

    /**
     * Sitzung für einen Bildschirm holen. Wiederverwendet wird eine noch
     * offene Sitzung desselben Gesprächs ([conversationId]); ohne Gespräch
     * bei Assist und Talk die zuletzt verlassene Sitzung dieses Modus (die
     * Rückfrage per Wake Word landet so im selben Gespräch). Chat ohne
     * Gespräch beginnt immer neu.
     */
    fun acquire(mode: String, conversationId: String?): Lease {
        val reuse = when {
            conversationId != null -> entries.lastOrNull {
                it.session.conversationId == conversationId && it.alive()
            }
            mode == "assist" || mode == "talk" -> entries.lastOrNull {
                it.mode == mode && it.holders == 0 && it.alive()
            }
            else -> null
        }
        if (reuse != null) {
            reuse.holders++
            reuse.lingerUntil = 0L
            reuse.lingerJob?.cancel()
            Log.i(TAG, "Sitzung ${reuse.key} (${reuse.mode}) wiederverwendet")
            return Lease(reuse.key, reuse.session, isNew = false)
        }
        val entry = create(mode, conversationId)
        return Lease(entry.key, entry.session, isNew = true)
    }

    /** Sitzung mit diesem Schlüssel holen (Overlay für eine proaktive Meldung); null = schon geschlossen. */
    fun acquireByKey(key: Long): Lease? {
        val entry = entries.firstOrNull { it.key == key && it.alive() } ?: return null
        entry.holders++
        entry.lingerUntil = 0L
        entry.lingerJob?.cancel()
        return Lease(entry.key, entry.session, isNew = false)
    }

    /** Bildschirm gibt die Sitzung ab (onCleared/onDestroy). */
    fun release(key: Long) {
        val entry = entries.firstOrNull { it.key == key } ?: return
        entry.holders = (entry.holders - 1).coerceAtLeast(0)
        evaluate(entry)
    }

    /** Sichtbarkeit eines Bildschirms (onStart/onStop) — entscheidet, ob proaktiv das Overlay aufgeht. */
    fun setVisible(key: Long, visible: Boolean) {
        val entry = entries.firstOrNull { it.key == key } ?: return
        entry.visible = (entry.visible + if (visible) 1 else -1).coerceAtLeast(0)
    }

    /** "Abbrechen" in der Benachrichtigung: unbenutzte Sitzungen schließen (bricht ihre Agenten ab). */
    fun cancelBackground() {
        entries.filter { it.holders == 0 }.forEach { dispose(it) }
        updateService()
    }

    /** Text für die laufende Service-Benachrichtigung. */
    fun notificationText(): String {
        val states = entries.map { it.session.state.value }
        val running = states.sumOf { it.backgroundAgents }
        return when {
            running == 1 -> "Ein Agent arbeitet im Hintergrund …"
            running > 1 -> "$running Agenten arbeiten im Hintergrund …"
            states.any { it.agentResultPending } -> "Ergebnis liegt bereit und wird gleich gemeldet …"
            else -> "Gespräch bleibt kurz für Rückfragen offen"
        }
    }

    // ---- intern ----

    /** Neue Sitzung, die schon dem anfragenden Bildschirm gehört (holders = 1). */
    private fun create(mode: String, conversationId: String?): Entry {
        val entry = Entry(nextKey++, mode)
        // Halter VOR der Zustandsbeobachtung setzen: Der Collector läuft auf
        // Main.immediate synchron los und sähe sonst eine unbenutzte Sitzung im
        // Anfangszustand DISCONNECTED — und würde sie sofort wieder schließen
        // (samt Scope, in dem connect() den Verbindungsaufbau startet).
        entry.holders = 1
        entry.session = AssistantSession(
            scope = entry.scope,
            client = container.okHttp,
            json = container.json,
            settings = container.settings,
            toolExecutor = container.toolExecutor,
            tts = container.tts,
            audioSession = container.audioSession,
            mode = mode,
            initialConversationId = conversationId,
            listener = object : AssistantSession.Listener {
                override fun onProactive() {
                    scope.launch { openForProactive(entry) }
                }
            },
        )
        entries += entry
        entry.session.connect()
        entry.scope.launch {
            entry.session.state.collect { s -> onStateChanged(entry, s) }
        }
        Log.i(TAG, "Sitzung ${entry.key} ($mode) neu")
        return entry
    }

    private fun Entry.alive(): Boolean {
        val c = session.state.value.connection
        return entries.contains(this) && (c == ConnectionState.CONNECTED || c == ConnectionState.CONNECTING)
    }

    private fun onStateChanged(entry: Entry, s: SessionState) {
        if (s.backgroundAgents > 0) entry.hadAgents = true
        if (entry.holders == 0) {
            // Unbenutzt und Verbindung weg: Agenten sind ohnehin abgebrochen.
            if (s.connection == ConnectionState.ERROR || s.connection == ConnectionState.DISCONNECTED) {
                dispose(entry)
                updateService()
                return
            }
            evaluate(entry)
        } else {
            updateService()
        }
    }

    /**
     * Darf die Sitzung schließen? Nur unbenutzt; offen bleibt sie, solange
     * Agenten-Arbeit aussteht, und — hatte sie Agenten — noch [LINGER_MS]
     * danach (Rückfrage per Wake Word im selben Gespräch).
     */
    private fun evaluate(entry: Entry) {
        if (!entries.contains(entry)) return
        if (entry.holders > 0) {
            updateService()
            return
        }
        val s = entry.session.state.value
        when {
            !entry.alive() -> dispose(entry)
            s.agentWorkPending -> {
                entry.lingerUntil = 0L
                entry.lingerJob?.cancel()
            }
            !entry.hadAgents -> dispose(entry)
            entry.lingerUntil == 0L -> {
                entry.lingerUntil = System.currentTimeMillis() + LINGER_MS
                entry.lingerJob?.cancel()
                entry.lingerJob = scope.launch {
                    delay(LINGER_MS)
                    evaluate(entry)
                }
            }
            System.currentTimeMillis() >= entry.lingerUntil -> dispose(entry)
        }
        updateService()
    }

    private fun dispose(entry: Entry) {
        if (!entries.remove(entry)) return
        Log.i(TAG, "Sitzung ${entry.key} (${entry.mode}) geschlossen")
        entry.lingerJob?.cancel()
        entry.session.disconnect()
        entry.scope.cancel()
    }

    /**
     * Service läuft, solange irgendwo Agenten-Arbeit aussteht oder eine
     * unbenutzte Sitzung offen ist. Er startet beim Agenten-Start — da ist
     * ein Bildschirm sichtbar, Android erlaubt den Foreground-Start.
     */
    private fun updateService() {
        val needed = entries.any { it.holders == 0 || it.session.state.value.agentWorkPending }
        if (needed) AgentSessionService.ensureRunning(app) else AgentSessionService.stop(app)
    }

    /** Proaktive Meldung: Ist kein Bildschirm der Sitzung sichtbar, Overlay darauf öffnen. */
    private fun openForProactive(entry: Entry) {
        if (!entries.contains(entry) || entry.visible > 0) return
        val intent = Intent(app, AssistantOverlayActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(AssistantOverlayActivity.EXTRA_SESSION_KEY, entry.key)
        // Aus dem Hintergrund darf nur starten, wer "Über anderen Apps anzeigen" hat
        // (wie beim Wake Word) — sonst eine Benachrichtigung zum Antippen.
        val started = Settings.canDrawOverlays(app) && runCatching { app.startActivity(intent) }.isSuccess
        if (!started) postResultNotification(intent, entry.key)
    }

    private fun postResultNotification(intent: Intent, key: Long) {
        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                RESULT_CHANNEL_ID,
                app.getString(R.string.agent_result_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            )
        )
        val open = PendingIntent.getActivity(
            app, key.toInt(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(app, RESULT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle("Heim-AI hat ein Ergebnis")
            .setContentText("Tippen, um die Antwort zu sehen")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        manager.notify(RESULT_NOTIFICATION_ID, notification)
    }

    companion object {
        private const val TAG = "SessionHub"
        /** Leerlauf nach der Zustellung, bevor eine unbenutzte Sitzung schließt. */
        const val LINGER_MS = 60_000L
        private const val RESULT_CHANNEL_ID = "agent_result"
        private const val RESULT_NOTIFICATION_ID = 21
    }
}
