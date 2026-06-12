package de.heimai.app.features.talk

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import de.heimai.app.features.chat.ChatViewModel
import de.heimai.app.ui.components.MessageItem
import de.heimai.app.ui.components.ToolConfirmationDialog

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
    val state by viewModel.session.state.collectAsState()
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
    // Dauer-Zuhören, sobald verbunden und Berechtigung da
    LaunchedEffect(state.connection, micGranted) {
        if (state.connection == ConnectionState.CONNECTED && micGranted && !state.listening) {
            viewModel.session.startListening()
        }
    }
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.size - 1)
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
                    state.listening && state.partialTranscript.isNotBlank() -> state.partialTranscript
                    state.listening -> "Ich höre zu — sprich einfach los"
                    else -> "Mikrofon pausiert"
                },
                style = MaterialTheme.typography.bodyLarge,
                color = if (state.connection == ConnectionState.ERROR)
                    MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(16.dp))
            FilledIconButton(
                onClick = {
                    if (state.listening) viewModel.session.stopListening()
                    else if (micGranted) viewModel.session.startListening()
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
