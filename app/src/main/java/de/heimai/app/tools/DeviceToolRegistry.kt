package de.heimai.app.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Deklariert die Geräte-Tools, die diese App dem Orchestrator anbietet.
 * Das Manifest wird beim WebSocket-Hello mitgeschickt; der Server stellt
 * die Tools dem LLM (per MCP-Bridge) zur Verfügung und ruft sie über
 * tool_call-Nachrichten zurück auf dem Gerät auf.
 *
 * `sensitive: true` = Ausführung erfordert Nutzer-Bestätigung,
 * sofern in den Rechte-Einstellungen nicht der entspannte Modus
 * (entsperrtes Gerät genügt) aktiviert ist.
 */
object DeviceToolRegistry {

    data class ToolDef(
        val name: String,
        val description: String,
        val sensitive: Boolean,
        val parameters: Map<String, String>, // name -> Beschreibung
    )

    val tools: List<ToolDef> = listOf(
        ToolDef(
            "open_app",
            "Öffnet eine App auf dem Gerät anhand ihres Namens oder Paketnamens.",
            sensitive = false,
            parameters = mapOf("app" to "App-Name (z. B. 'Spotify') oder Paketname"),
        ),
        ToolDef(
            "navigate_to",
            "Startet die Navigation zu einem Ziel in der Karten-App.",
            sensitive = false,
            parameters = mapOf(
                "destination" to "Adresse oder Ortsname",
                "mode" to "drive | walk | bike (optional, Default drive)",
            ),
        ),
        ToolDef(
            "dial_number",
            "Öffnet die Telefon-App mit vorgewählter Nummer (Nutzer startet den Anruf selbst).",
            sensitive = false,
            parameters = mapOf("number" to "Telefonnummer"),
        ),
        ToolDef(
            "compose_email",
            "Öffnet die Mail-App mit vorausgefüllter E-Mail.",
            sensitive = false,
            parameters = mapOf(
                "to" to "Empfänger-Adresse",
                "subject" to "Betreff (optional)",
                "body" to "Nachrichtentext (optional)",
            ),
        ),
        ToolDef(
            "create_contact",
            "Öffnet die Kontakte-App mit vorausgefülltem neuen Kontakt.",
            sensitive = false,
            parameters = mapOf(
                "name" to "Vollständiger Name",
                "phone" to "Telefonnummer (optional)",
                "email" to "E-Mail-Adresse (optional)",
            ),
        ),
        ToolDef(
            "web_search",
            "Öffnet eine Websuche auf dem Gerät.",
            sensitive = false,
            parameters = mapOf("query" to "Suchanfrage"),
        ),
        ToolDef(
            "set_alarm",
            "Stellt einen Wecker in der Uhr-App.",
            sensitive = false,
            parameters = mapOf(
                "hour" to "Stunde 0-23",
                "minute" to "Minute 0-59",
                "label" to "Bezeichnung (optional)",
            ),
        ),
        ToolDef(
            "read_notifications",
            "Liest die aktuellen Benachrichtigungen des Geräts aus (für Zusammenfassungen). " +
                "Liefert strukturierte Rohdaten; die Zusammenfassung formuliert das LLM.",
            sensitive = true,
            parameters = emptyMap(),
        ),
    )

    fun manifest(): JsonArray = buildJsonArray {
        tools.forEach { tool ->
            add(buildJsonObject {
                put("name", tool.name)
                put("description", tool.description)
                put("sensitive", tool.sensitive)
                putJsonObject("parameters") {
                    tool.parameters.forEach { (name, desc) -> put(name, desc) }
                }
            })
        }
    }

    fun byName(name: String): ToolDef? = tools.firstOrNull { it.name == name }
}
