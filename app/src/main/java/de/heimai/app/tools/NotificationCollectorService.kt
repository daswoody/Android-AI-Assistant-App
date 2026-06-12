package de.heimai.app.tools

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

data class NotificationInfo(
    val appLabel: String,
    val title: String,
    val text: String,
    /** Epoch-Millis (UTC) */
    val postedAt: Long,
    val key: String,
)

/**
 * In-Memory-Speicher der aktuell sichtbaren Benachrichtigungen.
 * Bewusst NICHT persistiert (Privacy): Daten verlassen das Gerät nur,
 * wenn das Tool read_notifications explizit aufgerufen und (je nach
 * Rechte-Einstellung) bestätigt wurde.
 */
object NotificationStore {
    @Volatile
    var listenerConnected: Boolean = false
        internal set

    private val items = LinkedHashMap<String, NotificationInfo>()

    @Synchronized
    internal fun put(info: NotificationInfo) {
        items[info.key] = info
    }

    @Synchronized
    internal fun remove(key: String) {
        items.remove(key)
    }

    @Synchronized
    internal fun replaceAll(list: List<NotificationInfo>) {
        items.clear()
        list.forEach { items[it.key] = it }
    }

    @Synchronized
    fun snapshot(): List<NotificationInfo> = items.values.sortedByDescending { it.postedAt }
}

/**
 * NotificationListenerService — Grundlage für das Tool read_notifications
 * ("Fass mir meine Benachrichtigungen zusammen"). Muss vom Nutzer in den
 * Android-Einstellungen (Benachrichtigungszugriff) freigegeben werden.
 */
class NotificationCollectorService : NotificationListenerService() {

    override fun onListenerConnected() {
        NotificationStore.listenerConnected = true
        NotificationStore.replaceAll(activeNotifications.orEmpty().mapNotNull { it.toInfo() })
    }

    override fun onListenerDisconnected() {
        NotificationStore.listenerConnected = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        sbn.toInfo()?.let { NotificationStore.put(it) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        NotificationStore.remove(sbn.key)
    }

    private fun StatusBarNotification.toInfo(): NotificationInfo? {
        if (isOngoing) return null // Dauer-Notifications (Player, FGS) sind selten relevant
        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return null
        val appLabel = runCatching {
            val pm = packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault(packageName)
        return NotificationInfo(appLabel, title, text, postTime, key)
    }
}
