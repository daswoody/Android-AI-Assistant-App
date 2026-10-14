package de.heimai.app.core.network

/**
 * Barge-in mit LLM-Streaming (Orchestrator v1.26.1): Der Server schickt Text
 * und Audio, während das LLM noch schreibt. Was nach einem `interrupt` noch
 * eintrifft, gehört zum abgebrochenen Turn — ohne dieses Tor spielte der
 * Player es wieder ab und der Chat hängte die Deltas an.
 *
 * Der Server beantwortet JEDES `interrupt` mit einem `done`; bis dahin werden
 * alle Turn-Frames verworfen. Frames des nächsten Turns (z. B. `transcript`)
 * kommen erst danach, die Reihenfolge garantiert der Server.
 */
class TurnFrameGate {
    @Volatile
    var discarding: Boolean = false
        private set

    /** Nach dem Senden eines `interrupt`. */
    fun onInterruptSent() {
        discarding = true
    }

    /** false = Frame gehört zum abgebrochenen Turn und wird verworfen. */
    fun accept(type: String?): Boolean = !(discarding && type in TURN_FRAMES)

    /** Beim `done`: true, wenn es das done eines abgebrochenen Turns war. */
    fun onDone(): Boolean {
        val interrupted = discarding
        discarding = false
        return interrupted
    }

    /** Verbindung neu/weg: es kommt kein done mehr für das alte interrupt. */
    fun reset() {
        discarding = false
    }

    private companion object {
        val TURN_FRAMES = setOf(
            "assistant_text", "audio_chunk", "audio_end", "card", "tool_call", "tool_activity",
        )
    }
}
