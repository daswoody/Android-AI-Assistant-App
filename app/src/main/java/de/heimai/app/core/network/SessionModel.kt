package de.heimai.app.core.network

import de.heimai.app.core.model.CardEnvelope

// Reine Datenmodelle und Logik der Assistenz-Sitzung — bewusst ohne
// Android-Abhängigkeiten, damit sie in JVM-Unit-Tests laufen.

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

/**
 * Tool-/Agent-Aktivität eines Turns (informativ, v1.12).
 * status: running | background | done | error.
 * jobId nur bei Hintergrund-Agenten (v1.29): Ihr Ende kommt irgendwann
 * später und stempelt genau diesen Chip, egal in welcher Zeile er steht.
 */
data class ToolActivity(val tool: String, val status: String, val jobId: String? = null)

data class UiMessage(
    val id: Long,
    /** user | assistant | card | info | tool_activity */
    val role: String,
    val text: String = "",
    val card: CardEnvelope? = null,
    val activities: List<ToolActivity> = emptyList(),
    val final: Boolean = true,
)

data class SessionState(
    val connection: ConnectionState = ConnectionState.DISCONNECTED,
    val listening: Boolean = false,
    val speaking: Boolean = false,
    val partialTranscript: String = "",
    val messages: List<UiMessage> = emptyList(),
    val error: String? = null,
    /** true zwischen Anfrage (bzw. proaktiver Meldung) und done — auch ohne Audio. */
    val turnActive: Boolean = false,
    /** Hintergrund-Agenten, die gerade arbeiten (v1.29). */
    val backgroundAgents: Int = 0,
    /** Ein Agent ist fertig, sein Ergebnis steckt aber noch in keiner Antwort. */
    val agentResultPending: Boolean = false,
) {
    /** Die Verbindung muss offen bleiben, sonst bricht der Server Agenten ab bzw. das Ergebnis geht verloren. */
    val agentWorkPending: Boolean get() = backgroundAgents > 0 || agentResultPending
}

/** Chip-Logik für tool_activity-Frames (rein funktional, testbar). */
object ToolActivities {
    const val RUNNING = "running"
    const val BACKGROUND = "background"

    /** true = End-Frame eines Hintergrund-Agenten (gehört zu keinem Turn). */
    fun isBackgroundEnd(jobId: String?, status: String): Boolean =
        jobId != null && status != BACKGROUND

    /**
     * Chip einer Zeile anlegen oder aktualisieren. Mit [jobId] zählt nur der
     * Chip mit derselben jobId; ohne jobId nur Chips OHNE jobId mit gleichem
     * Tool-Namen — ein normaler Aufruf desselben Agenten darf den
     * Hintergrund-Chip nicht überschreiben.
     */
    fun upsert(
        activities: List<ToolActivity>,
        tool: String,
        status: String,
        jobId: String? = null,
    ): List<ToolActivity> {
        val matches: (ToolActivity) -> Boolean =
            if (jobId != null) { a -> a.jobId == jobId } else { a -> a.jobId == null && a.tool == tool }
        return if (activities.any(matches)) {
            activities.map { if (matches(it)) it.copy(status = status) else it }
        } else {
            activities + ToolActivity(tool, status, jobId)
        }
    }

    /**
     * Ende eines Hintergrund-Agenten: Chip mit dieser jobId stempeln, egal in
     * welcher Zeile er steht. null = kein Chip gefunden (z. B. Start-Frame nie
     * gesehen) — dann bewusst keine neue Zeile anlegen.
     */
    fun stampJob(messages: List<UiMessage>, jobId: String, status: String): List<UiMessage>? {
        if (messages.none { msg -> msg.activities.any { it.jobId == jobId } }) return null
        return messages.map { msg ->
            if (msg.activities.none { it.jobId == jobId }) msg
            else msg.copy(activities = msg.activities.map { if (it.jobId == jobId) it.copy(status = status) else it })
        }
    }
}

/**
 * Buchhaltung der Hintergrund-Agenten einer Verbindung: Welche laufen noch,
 * und wartet ein fertiges Ergebnis noch auf Zustellung?
 *
 * Zugestellt ist ein Ergebnis mit der ersten finalen Antwort eines Turns, der
 * NACH dem Agenten-Ende begonnen hat. Ein Turn, der beim Agenten-Ende schon
 * lief, enthält es noch nicht — der Server liefert es mit dem nächsten Turn
 * (Antwort auf eine Eingabe oder proaktive Meldung).
 */
class AgentTracker {
    private val running = mutableSetOf<String>()
    private var turnSeq = 0L
    /** turnSeq beim letzten Agenten-Ende, dessen Ergebnis noch aussteht (null = nichts offen). */
    private var pendingSince: Long? = null

    @get:Synchronized
    val runningCount: Int get() = running.size

    @get:Synchronized
    val resultPending: Boolean get() = pendingSince != null

    /** Neuer Turn: text_input, audio_end oder proaktive Meldung. */
    @Synchronized
    fun onTurnStart() {
        turnSeq++
    }

    @Synchronized
    fun onJobStarted(jobId: String) {
        running += jobId
    }

    @Synchronized
    fun onJobFinished(jobId: String) {
        running -= jobId
        pendingSince = turnSeq
    }

    @Synchronized
    fun onAssistantFinal() {
        val since = pendingSince ?: return
        if (turnSeq > since) pendingSince = null
    }

    /** Verbindung weg: Der Server hat alle Agenten abgebrochen. */
    @Synchronized
    fun reset() {
        running.clear()
        pendingSince = null
    }
}
