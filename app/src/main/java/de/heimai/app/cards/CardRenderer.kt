package de.heimai.app.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import de.heimai.app.core.model.CardAction
import de.heimai.app.core.model.CardEnvelope
import de.heimai.app.core.model.LayoutNode
import de.heimai.app.core.model.LayoutTemplate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Interpretiert ein server-definiertes LayoutTemplate (siehe Cards.kt)
 * gegen die Daten einer CardEnvelope und rendert es mit Compose.
 * {{pfad}}-Bindings werden gegen {"type","title","data"} aufgelöst;
 * innerhalb von list/item_template relativ zum Listeneintrag.
 */
@Composable
fun HeimCard(
    card: CardEnvelope,
    template: LayoutTemplate?,
    onAction: (CardAction) -> Unit = {},
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            card.title?.let {
                Text(it, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
            }
            val root = template?.root
            if (root == null) {
                Text(card.data.toString(), style = MaterialTheme.typography.bodySmall)
            } else {
                RenderNode(root, cardContext(card), onAction)
            }
        }
    }
}

private fun cardContext(card: CardEnvelope): JsonObject = buildJsonObject {
    put("type", JsonPrimitive(card.type))
    put("title", JsonPrimitive(card.title ?: ""))
    put("data", card.data)
}

@Composable
private fun RenderNode(node: LayoutNode, context: JsonElement, onAction: (CardAction) -> Unit) {
    val pad = node.padding?.dp ?: 0.dp
    when (node.component) {
        "column" -> Column(
            modifier = Modifier.fillMaxWidth().padding(pad),
            horizontalAlignment = when (node.align) {
                "center" -> Alignment.CenterHorizontally
                "end" -> Alignment.End
                else -> Alignment.Start
            },
        ) {
            node.children.forEach { RenderNode(it, context, onAction) }
        }
        "row" -> Row(
            modifier = Modifier.fillMaxWidth().padding(pad),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = when (node.align) {
                "center" -> Arrangement.Center
                "end" -> Arrangement.End
                "space_between" -> Arrangement.SpaceBetween
                else -> Arrangement.Start
            },
        ) {
            node.children.forEach { child ->
                val weightMod = child.weight?.let { Modifier.weight(it) } ?: Modifier
                Column(weightMod.padding(end = 4.dp)) { RenderNode(child, context, onAction) }
            }
        }
        "text" -> Text(
            text = bind(node.text, context),
            style = when (node.style) {
                "display" -> MaterialTheme.typography.displaySmall
                "title" -> MaterialTheme.typography.titleMedium
                "label" -> MaterialTheme.typography.labelMedium
                else -> MaterialTheme.typography.bodyMedium
            },
            color = nodeColor(node.color),
            modifier = Modifier.padding(pad),
        )
        "image" -> AsyncImage(
            model = bind(node.url, context),
            contentDescription = null,
            modifier = Modifier.size((node.size ?: 96).dp).padding(pad),
        )
        "icon" -> Icon(
            imageVector = iconFor(bind(node.icon, context)),
            contentDescription = null,
            tint = nodeColor(node.color),
            modifier = Modifier.size((node.size ?: 24).dp).padding(pad),
        )
        "divider" -> HorizontalDivider(Modifier.padding(vertical = 6.dp))
        "spacer" -> Spacer(Modifier.height((node.size ?: 8).dp).width((node.size ?: 8).dp))
        "badge" -> AssistChip(onClick = {}, label = { Text(bind(node.text, context)) })
        "progress" -> LinearProgressIndicator(
            progress = { node.value ?: 0f },
            modifier = Modifier.fillMaxWidth().padding(pad),
        )
        "button" -> node.action?.let { action ->
            Button(onClick = { onAction(resolveAction(action, context)) }, modifier = Modifier.padding(pad)) {
                Text(bind(action.label ?: node.text, context))
            }
        }
        "list" -> {
            val items = (lookup(context, node.itemsPath ?: "data.items") as? JsonArray) ?: return
            val itemTemplate = node.itemTemplate ?: return
            Column(Modifier.fillMaxWidth()) {
                items.forEach { item -> RenderNode(itemTemplate, item, onAction) }
            }
        }
    }
}

@Composable
private fun nodeColor(name: String?): Color = when {
    name == null -> Color.Unspecified
    name == "primary" -> MaterialTheme.colorScheme.primary
    name == "secondary" -> MaterialTheme.colorScheme.secondary
    name == "onSurface" -> MaterialTheme.colorScheme.onSurface
    name == "muted" -> MaterialTheme.colorScheme.onSurfaceVariant
    name.startsWith("#") -> runCatching {
        Color(android.graphics.Color.parseColor(name))
    }.getOrDefault(Color.Unspecified)
    else -> Color.Unspecified
}

private val BINDING = Regex("\\{\\{([^}]+)}}")

private fun bind(raw: String?, context: JsonElement): String {
    if (raw == null) return ""
    return BINDING.replace(raw) { match ->
        val value = lookup(context, match.groupValues[1].trim())
        (value as? JsonPrimitive)?.content ?: value?.toString() ?: ""
    }
}

private fun lookup(context: JsonElement, path: String): JsonElement? {
    var current: JsonElement? = context
    for (segment in path.split('.')) {
        current = when (current) {
            is JsonObject -> current[segment]
            is JsonArray -> segment.toIntOrNull()?.let { current.getOrNull(it) }
            else -> null
        } ?: return null
    }
    return current
}

private fun resolveAction(action: CardAction, context: JsonElement): CardAction =
    action.copy(url = action.url?.let { bind(it, context) })

private fun iconFor(name: String): ImageVector = when (name) {
    "home" -> Icons.Filled.Home
    "alarm" -> Icons.Filled.Alarm
    "calendar" -> Icons.Filled.CalendarMonth
    "check" -> Icons.Filled.Check
    "cloud" -> Icons.Filled.Cloud
    "mail" -> Icons.Filled.Email
    "light" -> Icons.Filled.Lightbulb
    "location" -> Icons.Filled.LocationOn
    "music" -> Icons.Filled.MusicNote
    "navigation" -> Icons.Filled.Navigation
    "notification" -> Icons.Filled.Notifications
    "person" -> Icons.Filled.Person
    "phone" -> Icons.Filled.Phone
    "star" -> Icons.Filled.Star
    "thermostat" -> Icons.Filled.Thermostat
    "timer" -> Icons.Filled.Timer
    "rain" -> Icons.Filled.WaterDrop
    "sun" -> Icons.Filled.WbSunny
    else -> Icons.Filled.Info
}
