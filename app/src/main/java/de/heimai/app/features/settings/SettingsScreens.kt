package de.heimai.app.features.settings

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import de.heimai.app.HeimAiApp
import de.heimai.app.core.AppContainer
import de.heimai.app.core.network.Voice
import de.heimai.app.core.settings.AppSettings
import de.heimai.app.ui.theme.THEMES
import de.heimai.app.wakeword.OpenWakeWordEngine
import de.heimai.app.wakeword.WakeWordDiagnostics
import de.heimai.app.wakeword.WakeWordImport
import de.heimai.app.wakeword.WakeWordService
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
private fun container(): AppContainer =
    HeimAiApp.from(LocalContext.current.applicationContext as Application).container

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScaffold(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
        ) {
            content()
        }
    }
}

/** Einstellungs-Übersicht. */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onAccount: () -> Unit,
    onAppearance: () -> Unit,
    onAi: () -> Unit,
) {
    SettingsScaffold("Einstellungen", onBack) {
        ListItem(
            headlineContent = { Text("Account") },
            supportingContent = { Text("Server, Anmeldung, Abmelden") },
            leadingContent = { Icon(Icons.Filled.AccountCircle, null, tint = MaterialTheme.colorScheme.primary) },
            modifier = Modifier.clickable(onClick = onAccount),
        )
        HorizontalDivider()
        ListItem(
            headlineContent = { Text("Design & Farben") },
            supportingContent = { Text("Farbwelt, Hell/Dunkel") },
            leadingContent = { Icon(Icons.Filled.Palette, null, tint = MaterialTheme.colorScheme.primary) },
            modifier = Modifier.clickable(onClick = onAppearance),
        )
        HorizontalDivider()
        ListItem(
            headlineContent = { Text("AI-Einstellungen") },
            supportingContent = { Text("Stimme, Wake Word, Rechte, Stimmerkennung") },
            leadingContent = { Icon(Icons.Filled.Psychology, null, tint = MaterialTheme.colorScheme.primary) },
            modifier = Modifier.clickable(onClick = onAi),
        )
    }
}

/** Account: Server-Info, Tier, Logout. */
@Composable
fun AccountSettingsScreen(onBack: () -> Unit, onLoggedOut: () -> Unit) {
    val container = container()
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsState(initial = AppSettings())

    SettingsScaffold("Account", onBack) {
        ListItem(
            headlineContent = { Text("Server") },
            supportingContent = { Text(settings.serverUrl.ifBlank { "—" }) },
        )
        ListItem(
            headlineContent = { Text("Angemeldet als") },
            supportingContent = { Text("${settings.userName.ifBlank { "—" }} (Tier ${settings.userTier})") },
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {
                scope.launch {
                    container.settings.logout()
                    onLoggedOut()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Abmelden") }
    }
}

/** Design: Farbwelt + Dark Mode. */
@Composable
fun AppearanceSettingsScreen(onBack: () -> Unit) {
    val container = container()
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsState(initial = AppSettings())

    SettingsScaffold("Design & Farben", onBack) {
        Text("Farbwelt", style = MaterialTheme.typography.titleSmall)
        THEMES.forEach { theme ->
            Row(
                Modifier.fillMaxWidth().clickable { scope.launch { container.settings.setTheme(theme.name) } }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = settings.themeName == theme.name,
                    onClick = { scope.launch { container.settings.setTheme(theme.name) } },
                )
                Surface(
                    color = theme.seed,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.size(24.dp),
                ) {}
                Spacer(Modifier.size(8.dp))
                Text(theme.name)
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("Erscheinungsbild", style = MaterialTheme.typography.titleSmall)
        listOf("system" to "System folgen", "light" to "Hell", "dark" to "Dunkel").forEach { (key, label) ->
            Row(
                Modifier.fillMaxWidth().clickable { scope.launch { container.settings.setDarkMode(key) } }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = settings.darkMode == key,
                    onClick = { scope.launch { container.settings.setDarkMode(key) } },
                )
                Text(label)
            }
        }
    }
}

/** AI: Stimme, Wake Word, Rechte, TTS-Fallback, Systemfreigaben, Training (Phase 5). */
@Composable
fun AiSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = container()
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsState(initial = AppSettings())

    var voices by remember { mutableStateOf<List<Voice>>(emptyList()) }
    var voicesError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        runCatching { container.api.voices() }
            .onSuccess { voices = it.voices }
            .onFailure { voicesError = "Stimmen nicht abrufbar (Server offline?)" }
    }

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            scope.launch { container.settings.setWakeWordEnabled(true) }
            WakeWordService.start(context)
        }
    }

    // Modell wechseln: Setting schreiben, DANN (falls aktiv) Dienst mit neuer
    // Konfiguration neu starten — der Service baut die Engine bei jedem Start frisch auf.
    fun selectKeyword(kw: String) {
        scope.launch {
            container.settings.setWakeWordKeyword(kw)
            if (settings.wakeWordEnabled) WakeWordService.start(context)
        }
    }

    // Import eines eigenen openWakeWord-Modells (.tflite)
    val modelLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val path = WakeWordImport.copyToStorage(context, uri, "custom_wakeword.tflite")
            if (path != null) scope.launch {
                container.settings.setCustomWakeWordPath(path)
                container.settings.setWakeWordKeyword(OpenWakeWordEngine.CUSTOM)
                if (settings.wakeWordEnabled) WakeWordService.start(context)
            }
        }
    }

    SettingsScaffold("AI-Einstellungen", onBack) {

        // --- Stimmauswahl ---
        Text("Stimme", style = MaterialTheme.typography.titleSmall)
        if (voices.isEmpty()) {
            Text(
                voicesError ?: "Lade Stimmen…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        voices.forEach { voice ->
            Row(
                Modifier.fillMaxWidth().clickable { scope.launch { container.settings.setVoiceId(voice.id) } }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = settings.voiceId == voice.id,
                    onClick = { scope.launch { container.settings.setVoiceId(voice.id) } },
                )
                Text(voice.name)
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        // --- Realtime Talk ---
        Text("Realtime Talk", style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Echo-Unterdrückung (Freisprechen)")
                Text(
                    "Talk läuft wie ein Freisprech-Telefonat, damit die geräteeigene " +
                        "Anruf-Echo-Unterdrückung greift. Empfohlen an. Ohne Kopfhörer/AEC " +
                        "sonst Half-Duplex nutzen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = settings.talkAec,
                onCheckedChange = { scope.launch { container.settings.setTalkAec(it) } },
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Automatisch senden nach dieser Sprechpause: ${settings.talkSilenceMs} ms",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "Kürzer = reaktionsschneller, schneidet aber bei Denkpausen eher ab. " +
                "Länger = mehr Zeit zwischen Sätzen.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = settings.talkSilenceMs.toFloat(),
            onValueChange = { scope.launch { container.settings.setTalkSilenceMs(it.roundToInt()) } },
            valueRange = 300f..3000f,
            steps = 26, // 100-ms-Raster zwischen 300 und 3000
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Mikrofon-Schwelle: ${settings.talkThreshold}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "Höher = die KI reagiert nur auf lautere/klarere Stimme und hört sich im " +
                "Freisprechbetrieb weniger selbst. Am besten mit der Live-Pegelanzeige im " +
                "Realtime-Talk-Screen einstellen.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = settings.talkThreshold.toFloat(),
            onValueChange = { scope.launch { container.settings.setTalkThreshold(it.roundToInt()) } },
            valueRange = 50f..4000f,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Half-Duplex (Mikro pausiert während Antwort)")
                Text(
                    "Verhindert Selbst-Mithören zuverlässig — dafür kein Reinreden/Unterbrechen " +
                        "während die KI spricht. Für Geräte ohne gute Echo-Unterdrückung empfohlen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = settings.talkHalfDuplex,
                onCheckedChange = { scope.launch { container.settings.setTalkHalfDuplex(it) } },
            )
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        // --- Wake Word (openWakeWord, frei, kein Lizenz-Key) ---
        Text("Wake Word", style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Wake-Word-Erkennung")
                Text(
                    "Lokal & offline mit openWakeWord (TensorFlow Lite). Energiesparend: die " +
                        "Erkennung läuft nur, wenn Geräusch anliegt.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = settings.wakeWordEnabled,
                enabled = settings.wakeWordReady,
                onCheckedChange = { enable ->
                    if (enable) {
                        val granted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED
                        if (granted) {
                            scope.launch { container.settings.setWakeWordEnabled(true) }
                            WakeWordService.start(context)
                        } else {
                            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    } else {
                        scope.launch { container.settings.setWakeWordEnabled(false) }
                        WakeWordService.stop(context)
                    }
                },
            )
        }
        Spacer(Modifier.height(8.dp))

        // Live-Status der Engine: macht ohne adb sichtbar, ob wirklich gelauscht
        // wird — inkl. Pegel (öffnet das Gate?) und Score (reagiert das Modell?).
        val diag by WakeWordDiagnostics.state.collectAsState()
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(10.dp).background(
                            when (diag.state) {
                                WakeWordDiagnostics.State.RUNNING -> Color(0xFF4CAF50)
                                WakeWordDiagnostics.State.STARTING -> Color(0xFFFFC107)
                                WakeWordDiagnostics.State.ERROR -> MaterialTheme.colorScheme.error
                                WakeWordDiagnostics.State.OFF -> MaterialTheme.colorScheme.outline
                            },
                            CircleShape,
                        )
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        when (diag.state) {
                            WakeWordDiagnostics.State.RUNNING -> "Lauscht — ${diag.detail}"
                            WakeWordDiagnostics.State.STARTING -> "Startet …"
                            WakeWordDiagnostics.State.ERROR -> "Fehler"
                            WakeWordDiagnostics.State.OFF ->
                                if (diag.detail.isBlank()) "Aus" else "Aus — ${diag.detail}"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (diag.state == WakeWordDiagnostics.State.ERROR) {
                    Text(
                        diag.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (diag.state == WakeWordDiagnostics.State.RUNNING) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Pegel ${diag.rms}" + when {
                            !settings.wakeWordEnergyGate -> ""
                            diag.gateActive -> " · Gate offen"
                            else -> " · Gate zu (öffnet ab ${settings.wakeWordGateRms})"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LinearProgressIndicator(
                        progress = { (diag.rms / 4000f).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Score %.2f · löst aus ab %.2f".format(diag.score, settings.wakeWordThreshold / 100f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LinearProgressIndicator(
                        progress = { diag.score.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        Text("Wake-Word-Modell", style = MaterialTheme.typography.labelMedium)
        OpenWakeWordEngine.BUILT_IN.forEach { (label, asset) ->
            Row(
                Modifier.fillMaxWidth().clickable { selectKeyword(asset) }.padding(vertical = 0.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = settings.wakeWordKeyword == asset,
                    onClick = { selectKeyword(asset) },
                )
                Text(label)
            }
        }
        // Eigenes openWakeWord-Modell (.tflite)
        Row(
            Modifier.fillMaxWidth().clickable {
                if (settings.customWakeWordPath.isNotBlank()) selectKeyword(OpenWakeWordEngine.CUSTOM)
                else modelLauncher.launch(arrayOf("*/*"))
            }.padding(vertical = 0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = settings.wakeWordKeyword == OpenWakeWordEngine.CUSTOM,
                enabled = settings.customWakeWordPath.isNotBlank(),
                onClick = { selectKeyword(OpenWakeWordEngine.CUSTOM) },
            )
            Text(
                if (settings.customWakeWordPath.isNotBlank()) "Eigenes Modell (importiert)"
                else "Eigenes Modell …"
            )
        }
        Text(
            "Eigene Wake Words trainierst du kostenlos mit openWakeWord (auch deutsch) und " +
                "importierst die .tflite-Datei hier.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = { modelLauncher.launch(arrayOf("*/*")) },
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        ) { Text(if (settings.customWakeWordPath.isBlank()) "Eigenes .tflite wählen" else ".tflite ersetzen") }

        Spacer(Modifier.height(8.dp))
        Text("Empfindlichkeit: ${settings.wakeWordThreshold}%", style = MaterialTheme.typography.bodyMedium)
        Text(
            "Höher = weniger Fehlauslöser, aber du musst evtl. deutlicher sprechen.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = settings.wakeWordThreshold.toFloat(),
            onValueChange = { scope.launch { container.settings.setWakeWordThreshold(it.roundToInt()) } },
            // Engine erst beim Loslassen neu aufbauen (nicht bei jedem Slider-Tick)
            onValueChangeFinished = { if (settings.wakeWordEnabled) WakeWordService.start(context) },
            valueRange = 5f..95f,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Energiesparen (Pegel-Gate)")
                Text(
                    "ML-Erkennung nur bei Geräusch aktivieren. Aus = etwas empfindlicher, " +
                        "aber höherer Verbrauch.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = settings.wakeWordEnergyGate,
                onCheckedChange = {
                    scope.launch {
                        container.settings.setWakeWordEnergyGate(it)
                        if (settings.wakeWordEnabled) WakeWordService.start(context)
                    }
                },
            )
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        // --- Rechte ---
        Text("Rechte & Sicherheit", style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Entsperrtes Gerät genügt")
                Text(
                    "Sensible Aktionen (z. B. Benachrichtigungen vorlesen) ohne separate " +
                        "Bestätigung ausführen, solange das Gerät entsperrt ist",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = settings.relaxedSecurity,
                onCheckedChange = { scope.launch { container.settings.setRelaxedSecurity(it) } },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("On-Device-TTS-Fallback")
                Text(
                    "Antwort lokal vorlesen, wenn der Server kein Audio liefert",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = settings.ttsFallbackEnabled,
                onCheckedChange = { scope.launch { container.settings.setTtsFallback(it) } },
            )
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        // --- Systemfreigaben ---
        Text("Systemfreigaben", style = MaterialTheme.typography.titleSmall)
        OutlinedButton(
            onClick = {
                runCatching { context.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }
            },
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        ) { Text("Als digitalen Assistenten festlegen") }
        OutlinedButton(
            onClick = {
                runCatching {
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
            },
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        ) { Text("Benachrichtigungszugriff erlauben") }
        OutlinedButton(
            onClick = {
                runCatching {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}"),
                        )
                    )
                }
            },
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        ) { Text("Über anderen Apps anzeigen erlauben") }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        // --- Stimmerkennung (vorbereitet, Phase 5) ---
        Text("Stimmerkennung", style = MaterialTheme.typography.titleSmall)
        Text(
            "Training der Sprecher-Erkennung (Tier-Zuordnung per Stimme) wird in " +
                "Phase 5 freigeschaltet. Die Trainings-Samples werden dann hier aufgenommen.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text("Stimmprofil trainieren (bald verfügbar)")
        }
    }
}
