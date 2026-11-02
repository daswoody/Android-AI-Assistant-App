package de.heimai.app.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.heimai.app.HeimAiApp
import de.heimai.app.MainActivity
import de.heimai.app.core.network.AssistantSession
import de.heimai.app.core.network.ConnectionState
import de.heimai.app.core.session.SessionHub
import de.heimai.app.ui.components.MessageItem
import de.heimai.app.ui.components.ToolConfirmationDialog
import de.heimai.app.ui.theme.HeimAiTheme
import de.heimai.app.wakeword.WakeWordService

/**
 * Das Assistant-Popup: transluzente Activity über der aktuellen App
 * (wie das Google-Assistant-Overlay). Unten ein kompaktes Panel mit
 * Transkript, Mikrofon und Texteingabe; Karten erscheinen darüber als
 * schwebende Popup-Karten. Tipp außerhalb schließt das Popup.
 *
 * Die Sitzung kommt aus dem [SessionHub]: Schließt das Popup, während ein
 * Hintergrund-Agent arbeitet, bleibt die Verbindung offen; meldet sich die
 * Assistenz dann von selbst, öffnet der Hub das Popup mit
 * [EXTRA_SESSION_KEY] wieder auf genau dieser Sitzung. Ein neues Wake Word
 * übernimmt eine noch offene Assist-Sitzung (gleiches Gespräch).
 */
class AssistantOverlayActivity : ComponentActivity() {

    private val hub: SessionHub get() = HeimAiApp.from(application).container.sessions
    /** Aktuelle Sitzung; Compose baut bei einem Wechsel (onNewIntent) neu auf. */
    private val leaseState = mutableStateOf<SessionHub.Lease?>(null)
    private val session: AssistantSession get() = leaseState.value!!.session
    private var visibleNow = false
    private var micGranted = false

    companion object {
        /** Inaktivität, nach der sich das Popup selbst schließt (Wake Word übernimmt wieder). */
        private const val IDLE_CLOSE_MS = 8_000L
        /** Schlüssel einer Sitzung im [SessionHub], auf der das Popup öffnen soll (proaktive Meldung). */
        const val EXTRA_SESSION_KEY = "session_key"
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            micGranted = granted
            if (granted) startRealtimeTalk()
        }

    /**
     * Wake-Word-Flow = Realtime Talk: kontinuierlich zuhören, Sprechpausen per
     * VAD erkennen und automatisch senden (gleiche Einstellungen wie der
     * Talk-Screen) — kein manuelles Stopp/Start pro Äußerung.
     */
    private fun startRealtimeTalk(interruptCurrent: Boolean = true) {
        val s = HeimAiApp.from(application).container.settings.currentBlocking()
        session.startListening(
            continuous = true,
            silenceMs = s.talkSilenceMs,
            thresholdRms = s.talkThreshold,
            halfDuplex = s.talkHalfDuplex,
            aec = s.talkAec,
            interruptCurrent = interruptCurrent,
        )
    }

    /** Sitzung aus dem Intent (proaktive Meldung) oder Assist-Sitzung aus dem Hub. */
    private fun acquireFor(intent: Intent?): SessionHub.Lease {
        val key = intent?.getLongExtra(EXTRA_SESSION_KEY, -1L) ?: -1L
        return (if (key > 0) hub.acquireByKey(key) else null)
            ?: hub.acquire(mode = "assist", conversationId = null)
    }

    /** Popup gibt die Sitzung ab: Mikrofon zu, laufende Antwort beenden — Verbindung bleibt beim Hub. */
    private fun leaveSession(lease: SessionHub.Lease) {
        lease.session.stopListening()
        lease.session.interruptPlayback()
        if (visibleNow) hub.setVisible(lease.key, false)
        hub.release(lease.key)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = HeimAiApp.from(application).container
        // Historie ist zentral: Der Orchestrator persistiert jeden Turn selbst,
        // die Assist-Session braucht keinen lokalen Recorder mehr.
        leaseState.value = acquireFor(intent)

        micGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!micGranted) permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)

        setContent {
            val settings by container.settings.settings.collectAsState(
                initial = de.heimai.app.core.settings.AppSettings()
            )
            HeimAiTheme(settings.themeName, settings.darkMode) {
                // Sitzungswechsel (onNewIntent): alle Effekte neu starten.
                key(leaseState.value?.key) { OverlayContent() }
            }
        }
    }

    /** Popup ist schon offen (singleInstance) und soll auf eine andere Sitzung wechseln. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val key = intent.getLongExtra(EXTRA_SESSION_KEY, -1L)
        val current = leaseState.value ?: return
        if (key <= 0 || key == current.key) return
        val next = hub.acquireByKey(key) ?: return
        leaveSession(current)
        leaseState.value = next
        if (visibleNow) hub.setVisible(next.key, true)
    }

    override fun onStart() {
        super.onStart()
        WakeWordService.instance?.pauseDetection()
        leaseState.value?.let { hub.setVisible(it.key, true) }
        visibleNow = true
    }

    override fun onStop() {
        WakeWordService.instance?.resumeDetection()
        leaseState.value?.let { hub.setVisible(it.key, false) }
        visibleNow = false
        super.onStop()
        finish()
    }

    override fun onDestroy() {
        // Nicht trennen: Arbeitet ein Hintergrund-Agent, hält der Hub die
        // Verbindung offen, bis das Ergebnis da ist (plus kurzer Leerlauf).
        // leaseState bleibt gesetzt: Compose darf beim Abbau nicht auf null stoßen.
        leaseState.value?.let { leaveSession(it) }
        super.onDestroy()
    }

    @androidx.compose.runtime.Composable
    private fun OverlayContent() {
        val container = HeimAiApp.from(application).container
        val state by session.state.collectAsState()
        var input by remember { mutableStateOf("") }
        val listState = rememberLazyListState()

        // Mikrofon automatisch öffnen, sobald verbunden (Wake-Word-Flow):
        // direkt im Realtime-Talk-Modus (VAD, Auto-Send nach Sprechpause).
        // Eine laufende Antwort (proaktive Meldung, wegen der das Popup aufging)
        // dabei NICHT abbrechen — Barge-in per Stimme geht trotzdem.
        LaunchedEffect(state.connection) {
            if (state.connection == ConnectionState.CONNECTED && micGranted && !state.listening) {
                startRealtimeTalk(interruptCurrent = false)
            }
        }
        LaunchedEffect(state.messages.size) {
            if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.size - 1)
        }

        // Auto-Standby: Nach [IDLE_CLOSE_MS] ohne Aktivität (keine Sprache, keine
        // Antwort/Wiedergabe, keine offene Bestätigung, kein Tipp-Entwurf) schließt
        // sich das Popup selbst — danach übernimmt wieder das Wake Word. Deckt auch
        // "Aktion fertig ausgeführt" ab: Karte gezeigt + Antwort zu Ende → Timer läuft.
        val overlaySettings by HeimAiApp.from(application).container.settings.settings.collectAsState(
            initial = de.heimai.app.core.settings.AppSettings()
        )
        LaunchedEffect(overlaySettings.talkThreshold) {
            var lastActivity = System.currentTimeMillis()
            var lastMessageCount = session.state.value.messages.size
            while (true) {
                kotlinx.coroutines.delay(500)
                val s = session.state.value
                val busy = s.speaking ||
                    s.turnActive ||
                    s.partialTranscript.isNotBlank() ||
                    s.connection == ConnectionState.CONNECTING ||
                    session.micLevel.value > overlaySettings.talkThreshold ||
                    de.heimai.app.tools.ConfirmationBroker.pending.value != null ||
                    input.isNotBlank()
                if (busy || s.messages.size != lastMessageCount) {
                    lastMessageCount = s.messages.size
                    lastActivity = System.currentTimeMillis()
                }
                if (System.currentTimeMillis() - lastActivity > IDLE_CLOSE_MS) {
                    finish()
                    break
                }
            }
        }

        ToolConfirmationDialog()

        Box(Modifier.fillMaxSize()) {
            // Tipp außerhalb des Panels schließt das Popup
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable { finish() }
            )
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(12.dp),
            ) {
                // Schwebende Karten + Verlauf dieses Assists
                LazyColumn(
                    state = listState,
                    modifier = Modifier.heightIn(max = 420.dp).fillMaxWidth(),
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    items(state.messages, key = { it.id }) { msg ->
                        MessageItem(msg, container)
                    }
                }

                Spacer(Modifier.size(8.dp))

                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    tonalElevation = 6.dp,
                    shadowElevation = 8.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            when (state.connection) {
                                ConnectionState.CONNECTING -> {
                                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Text("  Verbinde mit Heim-AI…", style = MaterialTheme.typography.labelMedium)
                                }
                                ConnectionState.ERROR -> Text(
                                    state.error ?: "Verbindungsfehler",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                else -> Text(
                                    when {
                                        state.listening -> if (state.partialTranscript.isBlank())
                                            "Ich höre zu…" else state.partialTranscript
                                        state.speaking -> "…"
                                        state.backgroundAgents > 0 -> "Heim-AI · Agent arbeitet …"
                                        else -> "Heim-AI"
                                    },
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = {
                                startActivity(Intent(this@AssistantOverlayActivity, MainActivity::class.java))
                                finish()
                            }) {
                                Icon(Icons.Filled.OpenInNew, contentDescription = "App öffnen")
                            }
                            IconButton(onClick = { finish() }) {
                                Icon(Icons.Filled.Close, contentDescription = "Schließen")
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = input,
                                onValueChange = { input = it },
                                placeholder = { Text("Oder tippen…") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                            )
                            IconButton(
                                enabled = input.isNotBlank(),
                                onClick = {
                                    session.sendText(input)
                                    input = ""
                                },
                            ) {
                                Icon(Icons.Filled.Send, contentDescription = "Senden")
                            }
                            FilledIconButton(onClick = {
                                if (state.listening) session.stopListening()
                                else if (micGranted) startRealtimeTalk()
                                else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }) {
                                Icon(
                                    if (state.listening) Icons.Filled.Stop else Icons.Filled.Mic,
                                    contentDescription = "Mikrofon",
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
