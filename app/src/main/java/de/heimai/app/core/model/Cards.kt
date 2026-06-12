package de.heimai.app.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Eine Karte, wie sie der Orchestrator pusht. `type` referenziert ein
 * LayoutTemplate (Skill-Prinzip), `data` ist der frei strukturierte Inhalt,
 * den das Template per {{data.*}}-Bindings rendert.
 */
@Serializable
data class CardEnvelope(
    val type: String,
    val version: Int = 1,
    val title: String? = null,
    val data: JsonObject,
)

/**
 * Server-definiertes Karten-Layout (zentral für Android UND Windows).
 * Wird vom Card-Layout-Server (GET /v1/cards/layouts) aktualisiert und
 * lokal gecacht; Fallback sind die mitgelieferten Templates in assets/cards.
 */
@Serializable
data class LayoutTemplate(
    @SerialName("card_type") val cardType: String,
    @SerialName("layout_version") val layoutVersion: Int = 1,
    val root: LayoutNode,
)

/**
 * Deklarativer Layout-Knoten. Bewusst plattformneutral gehalten:
 * Android interpretiert ihn mit Compose, Windows (Phase 2.5) mit
 * seinem eigenen Renderer gegen dasselbe JSON.
 *
 * Komponenten: column, row, text, image, icon, divider, spacer,
 * badge, progress, button, list.
 * String-Properties unterstützen {{pfad}}-Bindings gegen die Card-Daten.
 */
@Serializable
data class LayoutNode(
    val component: String,
    val text: String? = null,
    /** display | title | body | label */
    val style: String? = null,
    /** primary | secondary | onSurface | muted | #RRGGBB */
    val color: String? = null,
    val url: String? = null,
    /** Material-Icon-Name für component=icon (Teilmenge, siehe CardRenderer) */
    val icon: String? = null,
    val size: Int? = null,
    val padding: Int? = null,
    /** start | center | end (horizontale Ausrichtung in column / Gravity in row) */
    val align: String? = null,
    /** Gewichtung innerhalb einer row */
    val weight: Float? = null,
    /** Fortschritt 0..1 für component=progress */
    val value: Float? = null,
    /** Daten-Pfad für component=list, z. B. "items" */
    @SerialName("items_path") val itemsPath: String? = null,
    /** Template, das pro Listen-Eintrag gerendert wird (Bindings relativ zum Eintrag) */
    @SerialName("item_template") val itemTemplate: LayoutNode? = null,
    val action: CardAction? = null,
    val children: List<LayoutNode> = emptyList(),
)

/** Aktion hinter component=button (oder klickbarer Karte). */
@Serializable
data class CardAction(
    /** open_url | device_tool */
    val type: String,
    val url: String? = null,
    val tool: String? = null,
    val arguments: JsonObject? = null,
    val label: String? = null,
)
