# Karten-System ("Karten wie Skills")

Karten sind das visuelle Gegenstück zur Sprachantwort. Sie sind in zwei
Schichten getrennt, damit **neue Karten ohne App-Update** möglich sind und
Android & Windows **dasselbe zentrale Format** rendern:

1. **CardEnvelope** (Laufzeit-Daten, vom Orchestrator pro Antwort gepusht)
2. **LayoutTemplate** (Darstellung, zentral verwaltet vom Card-Layout-Server)

## CardEnvelope

```json
{ "type": "weather", "version": 1, "title": "Wetter Berlin", "data": { ... } }
```

`type` referenziert das Template. `data` ist frei strukturiert — das
Template entscheidet, was gerendert wird. Unbekannte Typen fallen auf das
`generic`-Template zurück (erwartet `data.headline` + `data.body`), d. h.
der Server kann neue Kartentypen einführen, bevor das Layout verteilt ist.

## LayoutTemplate

```json
{
  "card_type": "weather",
  "layout_version": 3,
  "root": {
    "component": "column",
    "children": [
      { "component": "text", "text": "{{data.temperature}}°", "style": "display", "color": "primary" }
    ]
  }
}
```

### Komponenten

| component | Properties |
|---|---|
| `column` / `row` | `children[]`, `align` (start/center/end/row: space_between), `padding` |
| `text` | `text` (mit Bindings), `style` (display/title/body/label), `color` |
| `image` | `url`, `size` |
| `icon` | `icon` (Name, s. u.), `size`, `color` |
| `divider` / `spacer` | `size` |
| `badge` | `text` |
| `progress` | `value` (0..1) |
| `button` | `action` (s. u.) |
| `list` | `items_path` (z. B. `data.items`), `item_template` (Bindings relativ zum Eintrag) |

In `row`-Kindern: `weight` (Platzgewichtung).
Farben: `primary`, `secondary`, `onSurface`, `muted`, `#RRGGBB`.
Icons: `home, alarm, calendar, check, cloud, mail, light, location, music,
navigation, notification, person, phone, star, thermostat, timer, rain, sun`.

### Bindings

`{{pfad}}` wird gegen `{"type","title","data"}` der Envelope aufgelöst
(`{{data.forecast.0.min}}` für Array-Zugriff). In `item_template` relativ
zum Listeneintrag (`{{day}}`).

### Actions

```json
{ "type": "open_url", "url": "{{data.link}}" }
{ "type": "device_tool", "tool": "navigate_to", "arguments": { "destination": "..." }, "label": "Navigation starten" }
```

## Verteilung (zentrale Verwaltung)

- **Quelle:** Card-Layout-Server = Endpoint `GET /v1/cards/layouts` des
  Orchestrators (siehe PROTOCOL.md). Globale `version` zählt bei jeder
  Layout-Änderung hoch; Clients pollen mit `since_version` beim App-Start.
- **Cache:** App speichert Templates in Room; höhere `layout_version`
  gewinnt.
- **Fallback:** Mitgelieferte Templates unter `app/src/main/assets/cards/`
  (gleiche Dateien kann der Server initial als Layout-Bestand übernehmen).

**Neue Karte hinzufügen = Skill deployen:**
1. Template-JSON auf dem Server ablegen (Version hochzählen)
2. Orchestrator pusht `card`-Nachrichten mit dem neuen `type`
3. Apps ziehen das Layout beim nächsten Start — kein App-Update nötig

Mitgelieferte Templates: `generic`, `weather`, `list`, `calendar`,
`navigation`, `notifications_summary`, `media`.
