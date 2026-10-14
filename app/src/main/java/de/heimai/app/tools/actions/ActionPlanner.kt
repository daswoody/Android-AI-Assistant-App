package de.heimai.app.tools.actions

import kotlinx.serialization.json.JsonObject

/**
 * Vorbereitung für ein On-Device-Action-Modell zur Steuerung von
 * Android-Funktionen über die einfachen Intent-Tools hinaus.
 *
 * Geplanter Pfad (siehe Spez. v1.5, Phase 2.x / offene Punkte):
 *  - Google Gemma 3 270M (bzw. Mobile-Actions-Finetunes davon) läuft
 *    on-device über die MediaPipe LLM Inference API (.task/.litertlm-Datei)
 *  - Der Planner übersetzt eine Nutzer-Äußerung in einen strukturierten
 *    Tool-Aufruf (Function Calling), der dann vom DeviceToolExecutor
 *    bzw. einem AccessibilityService ausgeführt wird
 *  - Vorteil: Geräte-Steuerung funktioniert auch ohne Server-Roundtrip
 *
 * Bis das Modell integriert ist, liefert [NoOpActionPlanner] null und der
 * Orchestrator entscheidet serverseitig über Tool-Aufrufe.
 */
interface ActionPlanner {
    data class PlannedAction(val tool: String, val arguments: JsonObject)

    /** @return geplante Aktion oder null, wenn lokal nichts erkannt wurde */
    suspend fun plan(utterance: String): PlannedAction?
}

class NoOpActionPlanner : ActionPlanner {
    override suspend fun plan(utterance: String): ActionPlanner.PlannedAction? = null
}
