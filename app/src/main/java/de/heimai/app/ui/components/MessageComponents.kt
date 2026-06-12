package de.heimai.app.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import de.heimai.app.cards.HeimCard
import de.heimai.app.core.AppContainer
import de.heimai.app.core.model.CardAction
import de.heimai.app.core.model.CardEnvelope
import de.heimai.app.core.network.UiMessage
import de.heimai.app.tools.ConfirmationBroker
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/** Eine Chat-Nachricht: Sprechblase (user/assistant) oder gerenderte Karte. */
@Composable
fun MessageItem(message: UiMessage, container: AppContainer) {
    when {
        message.card != null -> Box(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            CardItem(message.card, container)
        }
        message.role == "user" -> Bubble(message.text, fromUser = true)
        else -> Bubble(message.text, fromUser = false)
    }
}

@Composable
private fun Bubble(text: String, fromUser: Boolean) {
    if (text.isBlank()) return
    Box(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        contentAlignment = if (fromUser) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Surface(
            color = if (fromUser) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Text(text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
        }
    }
}

/** Lädt das passende Layout-Template (Server-Cache/Assets) und rendert die Karte. */
@Composable
fun CardItem(card: CardEnvelope, container: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val template by produceState<de.heimai.app.core.model.LayoutTemplate?>(null, card.type) {
        value = container.cardLayouts.templateFor(card.type)
    }
    HeimCard(card, template) { action -> handleCardAction(action, context, container, scope) }
}

private fun handleCardAction(
    action: CardAction,
    context: android.content.Context,
    container: AppContainer,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    when (action.type) {
        "open_url" -> action.url?.let {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        "device_tool" -> action.tool?.let { tool ->
            scope.launch {
                container.toolExecutor.execute(tool, action.arguments ?: JsonObject(emptyMap()))
            }
        }
    }
}

/**
 * Zeigt anstehende Tool-Bestätigungen (Tiered Security) als Dialog an.
 * In jeder Oberfläche einbinden, in der Tool-Calls laufen können.
 */
@Composable
fun ToolConfirmationDialog() {
    val pending by ConfirmationBroker.pending.collectAsState()
    val request = pending ?: return
    AlertDialog(
        onDismissRequest = { ConfirmationBroker.respond(request, false) },
        title = { Text(request.title) },
        text = { Text(request.description) },
        confirmButton = {
            TextButton(onClick = { ConfirmationBroker.respond(request, true) }) { Text("Erlauben") }
        },
        dismissButton = {
            TextButton(onClick = { ConfirmationBroker.respond(request, false) }) { Text("Ablehnen") }
        },
    )
}
