package de.heimai.app.features.talk

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import de.heimai.app.HeimAiApp
import de.heimai.app.core.network.ConnectionState
import de.heimai.app.core.settings.AppSettings
import de.heimai.app.features.chat.ChatViewModel
import de.heimai.app.ui.components.MessageItem
import de.heimai.app.ui.components.ToolConfirmationDialog
import kotlin.math.roundToInt

/**
 * Realtime Talk: dauerhaft offenes Mikrofon, Antworten als Audio-Stream,
 * Karten und Transkript laufen parallel mit. Wiederverwendet das
 * ChatViewModel mit mode="talk" (gleiches Protokoll, andere Session-Art).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TalkScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val viewModel: ChatViewModel = viewModel(
        key = "talk",
        factory = ChatViewModel.factory(0L, mode = "talk"),
    )
    val container = HeimAiApp.from(context.applicationContext as Application).container
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val state by viewModel.session.state.collectAsState()
    val settings by container.settings.settings.collectAsState(initial = AppSettings())
    val micLevel by viewModel.session.micLevel.collectAsState()
    val listState = rememberLazyListState()

    var micGranted by remember {
        mutableStateOf(
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micGranted = granted
    }

    LaunchedEffect(Unit) {
        if (!micGranted) micLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
    // Realtime: dauerhaft zuhören, automatisch senden nach Sprechpause (talkSilenceMs)
    LaunchedEffect(state.connection, micGranted) {
        if (state.connection == ConnectionState.CONNECTED && micGranted && !state.listening) {
            viewModel.session.startListening(
                continuous = true,
                silenceMs = settings.talkSilenceMs,
                thresholdRms = settings.talkThreshold,
                halfDuplex = settings.talkHalfDuplex,
            )
        }
    }
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.size - 1)
    }
    // Regler wirken sofort auf die laufende Aufnahme (kein Neustart nötig)
    LaunchedEffect(settings.talkThreshold, settings.talkHalfDuplex) {
        viewModel.session.updateVad(settings.talkThreshold, settings.talkHalfDuplex)
    }

    ToolConfirmationDialog()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Realtime Talk") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.Bottom,
            ) {
                items(state.messages, key = { it.id }) { msg -> MessageItem(msg, container) }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                when {
                    state.connection == ConnectionState.ERROR -> state.error ?: "Verbindungsfehler"
                    state.connection != ConnectionState.CONNECTED -> "Verbinde…"
                    state.speaking -> "Antwort läuft — sprich zum Unterbrechen"
                    state.listening && state.partialTranscript.isNotBlank() -> state.partialTranscript
                    state.listening -> "Ich höre zu — sprich einfach los"
                    else -> "Mikrofon pausiert"
                },
                style = MaterialTheme.typography.bodyLarge,
                color = if (state.connection == ConnectionState.ERROR)
                    MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(12.dp))

            // Live-Pegelanzeige + Schwellen-Regler zum Kalibrieren des Sweet-Spots.
            // Ziel: die Marke (Schwelle) so setzen, dass DEINE Stimme den Balken
            // klar über die Marke treibt, das Echo der KI-Antwort aber darunter bleibt.
            Text("Mikrofon-Pegel", style = MaterialTheme.typography.labelMedium)
            MicLevelMeter(
                level = micLevel,
                threshold = settings.talkThreshold.toFloat(),
                active = micLevel > settings.talkThreshold.toFloat(),
            )
            Slider(
                value = settings.talkThreshold.toFloat(),
                onValueChange = { scope.launch { container.settings.setTalkThreshold(it.roundToInt()) } },
                valueRange = 50f..4000f,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Schwelle ${settings.talkThreshold} · höher = KI hört sich weniger selbst · " +
                    "niedriger = reagiert auf leisere Stimme",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Half-Duplex", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Mikro während der Antwort pausieren — kein Reinreden, aber kein Selbst-Mithören",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.talkHalfDuplex,
                    onCheckedChange = { scope.launch { container.settings.setTalkHalfDuplex(it) } },
                )
            }
            Text(
                "Auto-Senden nach ${settings.talkSilenceMs} ms Stille · alles auch in den AI-Einstellungen",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))
            FilledIconButton(
                onClick = {
                    if (state.listening) viewModel.session.stopListening()
                    else if (micGranted) viewModel.session.startListening(
                        continuous = true,
                        silenceMs = settings.talkSilenceMs,
                        thresholdRms = settings.talkThreshold,
                        halfDuplex = settings.talkHalfDuplex,
                    )
                    else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                },
                modifier = Modifier.size(84.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (state.listening) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Icon(
                    if (state.listening) Icons.Filled.Mic else Icons.Filled.MicOff,
                    contentDescription = "Mikrofon",
                    modifier = Modifier.size(36.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Horizontaler Pegelbalken (0..METER_MAX RMS) mit einer senkrechten Marke an der
 * Schwelle. Grün, sobald der Pegel die Schwelle überschreitet (= würde als Sprache
 * gewertet), sonst gedämpft.
 */
@Composable
private fun MicLevelMeter(level: Float, threshold: Float, active: Boolean) {
    val meterMax = 4000f
    val track = MaterialTheme.colorScheme.surfaceVariant
    val fill = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    val marker = MaterialTheme.colorScheme.error
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(20.dp)
            .padding(vertical = 4.dp),
    ) {
        val w = size.width
        val h = size.height
        drawRoundRect(color = track, size = Size(w, h))
        val lvl = (level / meterMax).coerceIn(0f, 1f)
        if (lvl > 0f) drawRoundRect(color = fill, size = Size(w * lvl, h))
        val thr = (threshold / meterMax).coerceIn(0f, 1f)
        val x = w * thr
        drawLine(color = marker, start = Offset(x, 0f), end = Offset(x, h), strokeWidth = 4f)
    }
}
