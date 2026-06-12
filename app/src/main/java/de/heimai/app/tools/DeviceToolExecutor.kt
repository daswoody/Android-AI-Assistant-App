package de.heimai.app.tools

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract
import de.heimai.app.core.settings.SettingsRepository
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class ToolResult(val ok: Boolean, val payload: JsonElement)

/**
 * Führt die vom Orchestrator-LLM angeforderten Geräte-Tools lokal aus.
 *
 * Rechte-Logik (Tiered Security, vgl. Spez. 4.4): sensible Tools werden nur
 * ohne Rückfrage ausgeführt, wenn der entspannte Modus aktiv ist UND das
 * Gerät entsperrt ist. Sonst läuft die Anfrage über den ConfirmationBroker
 * (Dialog in der sichtbaren UI).
 *
 * Komplexere UI-Automatisierung (App-übergreifende Aktionen per On-Device-
 * Action-Modell, z. B. Gemma-3-270M-Finetunes wie MobileActions) ist über
 * [de.heimai.app.tools.actions.ActionPlanner] vorbereitet.
 */
class DeviceToolExecutor(
    private val context: Context,
    private val settings: SettingsRepository,
) {

    suspend fun execute(name: String, args: JsonObject): ToolResult {
        val def = DeviceToolRegistry.byName(name)
            ?: return error("Unbekanntes Tool: $name")

        if (def.sensitive && !sensitiveAllowed()) {
            val approved = ConfirmationBroker.confirm(
                title = "Zugriff erlauben?",
                description = "Die Heim-AI möchte ausführen: ${def.description}",
            )
            if (!approved) return error("Vom Nutzer abgelehnt")
        }

        return try {
            when (name) {
                "open_app" -> openApp(args.str("app"))
                "navigate_to" -> navigateTo(args.str("destination"), args.str("mode") ?: "drive")
                "dial_number" -> dialNumber(args.str("number"))
                "compose_email" -> composeEmail(args.str("to"), args.str("subject"), args.str("body"))
                "create_contact" -> createContact(args.str("name"), args.str("phone"), args.str("email"))
                "web_search" -> webSearch(args.str("query"))
                "set_alarm" -> setAlarm(args.int("hour"), args.int("minute"), args.str("label"))
                "read_notifications" -> readNotifications()
                else -> error("Tool nicht implementiert: $name")
            }
        } catch (e: Exception) {
            error("Ausführung fehlgeschlagen: ${e.message}")
        }
    }

    private suspend fun sensitiveAllowed(): Boolean {
        if (!settings.current().relaxedSecurity) return false
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return !keyguard.isDeviceLocked
    }

    // ---- Tool-Implementierungen ----

    private fun openApp(app: String?): ToolResult {
        if (app.isNullOrBlank()) return error("Parameter 'app' fehlt")
        val pm = context.packageManager
        // 1) Direkt als Paketname versuchen
        var launch = pm.getLaunchIntentForPackage(app)
        var label = app
        // 2) Sonst Label-Suche über alle Launcher-Apps
        if (launch == null) {
            val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val match = pm.queryIntentActivities(main, 0).firstOrNull {
                it.loadLabel(pm).toString().contains(app, ignoreCase = true)
            } ?: return error("Keine App namens '$app' gefunden")
            label = match.loadLabel(pm).toString()
            launch = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
                ?: return error("App '$label' ist nicht startbar")
        }
        start(launch)
        return ok("App geöffnet: $label")
    }

    private fun navigateTo(destination: String?, mode: String): ToolResult {
        if (destination.isNullOrBlank()) return error("Parameter 'destination' fehlt")
        val modeFlag = when (mode) { "walk" -> "w"; "bike" -> "b"; else -> "d" }
        val nav = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("google.navigation:q=${Uri.encode(destination)}&mode=$modeFlag")
        )
        val fallback = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(destination)}"))
        startWithFallback(nav, fallback)
        return ok("Navigation gestartet nach: $destination")
    }

    private fun dialNumber(number: String?): ToolResult {
        if (number.isNullOrBlank()) return error("Parameter 'number' fehlt")
        start(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number)}")))
        return ok("Telefon-App geöffnet mit Nummer $number")
    }

    private fun composeEmail(to: String?, subject: String?, body: String?): ToolResult {
        if (to.isNullOrBlank()) return error("Parameter 'to' fehlt")
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
            putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
            subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
            body?.let { putExtra(Intent.EXTRA_TEXT, it) }
        }
        start(intent)
        return ok("Mail-Entwurf an $to geöffnet")
    }

    private fun createContact(name: String?, phone: String?, email: String?): ToolResult {
        if (name.isNullOrBlank()) return error("Parameter 'name' fehlt")
        val intent = Intent(ContactsContract.Intents.Insert.ACTION).apply {
            type = ContactsContract.RawContacts.CONTENT_TYPE
            putExtra(ContactsContract.Intents.Insert.NAME, name)
            phone?.let { putExtra(ContactsContract.Intents.Insert.PHONE, it) }
            email?.let { putExtra(ContactsContract.Intents.Insert.EMAIL, it) }
        }
        start(intent)
        return ok("Kontakt-Formular für '$name' geöffnet (Nutzer speichert selbst)")
    }

    private fun webSearch(query: String?): ToolResult {
        if (query.isNullOrBlank()) return error("Parameter 'query' fehlt")
        start(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}")))
        return ok("Websuche geöffnet: $query")
    }

    private fun setAlarm(hour: Int?, minute: Int?, label: String?): ToolResult {
        if (hour == null || minute == null) return error("Parameter 'hour'/'minute' fehlen")
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        }
        start(intent)
        return ok("Wecker-Dialog geöffnet für %02d:%02d".format(hour, minute))
    }

    private fun readNotifications(): ToolResult {
        if (!NotificationStore.listenerConnected) {
            return error(
                "Benachrichtigungszugriff ist nicht aktiviert. " +
                    "In den Android-Einstellungen unter 'Benachrichtigungszugriff' für Heim-AI freigeben."
            )
        }
        val items = NotificationStore.snapshot()
        return ToolResult(true, buildJsonArray {
            items.forEach { n ->
                add(buildJsonObject {
                    put("app", n.appLabel)
                    put("title", n.title)
                    put("text", n.text)
                    put("posted_at", n.postedAt)
                })
            }
        })
    }

    // ---- Helpers ----

    private fun start(intent: Intent) {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun startWithFallback(primary: Intent, fallback: Intent) {
        try {
            start(primary)
        } catch (e: Exception) {
            start(fallback)
        }
    }

    private fun ok(message: String) = ToolResult(true, JsonPrimitive(message))
    private fun error(message: String) = ToolResult(false, JsonPrimitive(message))

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.content?.toIntOrNull()
}
