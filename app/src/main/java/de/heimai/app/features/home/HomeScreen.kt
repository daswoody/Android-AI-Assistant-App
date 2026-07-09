package de.heimai.app.features.home

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import de.heimai.app.HeimAiApp
import de.heimai.app.core.network.RemoteConversation
import de.heimai.app.core.settings.AppSettings
import kotlinx.coroutines.launch

/**
 * Startscreen. Die Gesprächsliste kommt vom Orchestrator (zentrale Historie):
 * dieselben Gespräche erscheinen im Browser-Frontend, hier und später in der
 * Windows-App — egal, auf welcher Plattform sie geführt wurden.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNewChat: () -> Unit,
    onOpenChat: (String) -> Unit,
    onTalk: () -> Unit,
    onSettings: () -> Unit,
) {
    val container = HeimAiApp.from(LocalContext.current.applicationContext as android.app.Application).container
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsState(initial = AppSettings())

    var conversations by remember { mutableStateOf<List<RemoteConversation>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun refresh() {
        loading = true
        try {
            conversations = container.api.conversations().conversations
            error = null
        } catch (e: Exception) {
            error = "Verlauf nicht abrufbar (Server offline?)"
        }
        loading = false
    }

    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Heim-AI") },
                actions = {
                    IconButton(onClick = { scope.launch { refresh() } }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Aktualisieren")
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Einstellungen")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = {},
                    label = { Text(if (settings.userName.isBlank()) "Nicht angemeldet" else settings.userName) },
                )
                AssistChip(
                    onClick = {},
                    label = { Text(if (settings.wakeWordEnabled) "Wake Word aktiv" else "Wake Word aus") },
                    leadingIcon = { Icon(Icons.Filled.Mic, contentDescription = null, Modifier.size(16.dp)) },
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Card(onClick = onNewChat, modifier = Modifier.weight(1f)) {
                    Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.Chat, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(8.dp))
                        Text("Neuer Chat", style = MaterialTheme.typography.titleSmall)
                    }
                }
                Card(onClick = onTalk, modifier = Modifier.weight(1f)) {
                    Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.GraphicEq, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(8.dp))
                        Text("Realtime Talk", style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            Text("Vergangene Gespräche", style = MaterialTheme.typography.titleMedium)
            Text(
                "Zentral gespeichert — auf allen Geräten dieselbe Historie",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            when {
                loading -> Text(
                    "Lade Gespräche…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error != null -> Text(
                    error!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                conversations.isEmpty() -> Text(
                    "Noch keine Gespräche. Starte einen Chat oder sag das Wake Word.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(conversations, key = { it.id }) { conversation ->
                    ListItem(
                        headlineContent = { Text(conversation.title.ifBlank { "Unterhaltung" }) },
                        supportingContent = {
                            Text(
                                listOfNotNull(
                                    conversation.device_name.takeIf { it.isNotBlank() },
                                    "${conversation.message_count} Nachrichten",
                                    conversation.updated_at.take(16).replace("T", " "),
                                ).joinToString(" · ")
                            )
                        },
                        leadingContent = {
                            Icon(
                                Icons.Filled.Chat,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        trailingContent = {
                            IconButton(onClick = {
                                scope.launch {
                                    runCatching { container.api.deleteConversation(conversation.id) }
                                    refresh()
                                }
                            }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Löschen")
                            }
                        },
                        modifier = Modifier.clickable { onOpenChat(conversation.id) },
                    )
                }
            }
        }
    }
}
