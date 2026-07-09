package de.heimai.app.features.chat

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import de.heimai.app.HeimAiApp
import de.heimai.app.core.network.ConnectionState
import de.heimai.app.ui.components.MessageItem
import de.heimai.app.ui.components.ToolConfirmationDialog

/**
 * Text-Chat mit dem Orchestrator. Lädt bei bestehender Konversation den
 * Verlauf aus Room und setzt das Gespräch über dieselbe WebSocket-Session
 * fort. Mikrofon-Button = Push-to-Talk (Mikrophase 2.2).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(conversationId: String?, onBack: () -> Unit) {
    val context = LocalContext.current
    val viewModel: ChatViewModel = viewModel(
        key = "chat-${conversationId ?: "new"}",
        factory = ChatViewModel.factory(conversationId, mode = "chat"),
    )
    val container = HeimAiApp.from(context.applicationContext as Application).container
    val state by viewModel.session.state.collectAsState()
    val history by viewModel.history.collectAsState()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    var micGranted by remember {
        mutableStateOf(
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micGranted = granted
        if (granted) viewModel.session.startListening()
    }

    val totalCount = history.size + state.messages.size
    LaunchedEffect(totalCount) {
        if (totalCount > 0) listState.animateScrollToItem(totalCount - 1)
    }

    ToolConfirmationDialog()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Chat")
                        Text(
                            when (state.connection) {
                                ConnectionState.CONNECTED -> "verbunden"
                                ConnectionState.CONNECTING -> "verbinde…"
                                ConnectionState.ERROR -> state.error ?: "Fehler"
                                ConnectionState.DISCONNECTED -> "getrennt"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (state.connection == ConnectionState.ERROR)
                                MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            ) {
                items(history, key = { "h${it.id}" }) { msg -> MessageItem(msg, container) }
                items(state.messages, key = { "s${it.id}" }) { msg -> MessageItem(msg, container) }
            }
            if (state.listening) {
                Text(
                    if (state.partialTranscript.isBlank()) "Ich höre zu…" else state.partialTranscript,
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("Nachricht…") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                IconButton(
                    enabled = input.isNotBlank() && state.connection == ConnectionState.CONNECTED,
                    onClick = {
                        viewModel.session.sendText(input)
                        input = ""
                    },
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Senden")
                }
                FilledIconButton(onClick = {
                    when {
                        state.listening -> viewModel.session.stopListening()
                        micGranted -> viewModel.session.startListening()
                        else -> micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }) {
                    Icon(
                        if (state.listening) Icons.Filled.Stop else Icons.Filled.Mic,
                        contentDescription = "Push-to-Talk",
                    )
                }
            }
        }
    }
}
