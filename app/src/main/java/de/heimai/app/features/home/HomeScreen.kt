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
import androidx.compose.material.icons.filled.Assistant
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import de.heimai.app.HeimAiApp
import de.heimai.app.core.settings.AppSettings
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Startscreen: neue Unterhaltung / Realtime Talk starten,
 * vergangene Gespräche (inkl. Assistant-Sessions) ansehen, Einstellungen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNewChat: () -> Unit,
    onOpenChat: (Long) -> Unit,
    onTalk: () -> Unit,
    onSettings: () -> Unit,
) {
    val container = HeimAiApp.from(LocalContext.current.applicationContext as android.app.Application).container
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsState(initial = AppSettings())
    val conversations by container.database.conversationDao().observeAll().collectAsState(initial = emptyList())
    val dateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Heim-AI") },
                actions = {
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
                Card(
                    onClick = onNewChat,
                    modifier = Modifier.weight(1f),
                ) {
                    Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.Chat, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(8.dp))
                        Text("Neuer Chat", style = MaterialTheme.typography.titleSmall)
                    }
                }
                Card(
                    onClick = onTalk,
                    modifier = Modifier.weight(1f),
                ) {
                    Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.GraphicEq, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(8.dp))
                        Text("Realtime Talk", style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            Text("Vergangene Gespräche", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            if (conversations.isEmpty()) {
                Text(
                    "Noch keine Gespräche. Starte einen Chat oder sag das Wake Word.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(conversations, key = { it.id }) { conversation ->
                    ListItem(
                        headlineContent = { Text(conversation.title) },
                        supportingContent = { Text(dateFormat.format(Date(conversation.updatedAt))) },
                        leadingContent = {
                            Icon(
                                when (conversation.source) {
                                    "talk" -> Icons.Filled.GraphicEq
                                    "assistant" -> Icons.Filled.Assistant
                                    else -> Icons.Filled.Chat
                                },
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        trailingContent = {
                            IconButton(onClick = {
                                scope.launch {
                                    container.database.messageDao().deleteForConversation(conversation.id)
                                    container.database.conversationDao().delete(conversation.id)
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
