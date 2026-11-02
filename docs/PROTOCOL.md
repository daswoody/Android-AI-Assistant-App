# Heim-AI App ↔ Voice-Orchestrator Protokoll (v1)

> **Status:** Dieses Dokument ist der verbindliche Vertrag zwischen der
> Android-App (Phase 2) und dem Voice-Orchestrator (Mikro-Phase 1.7+).
> Der Server existiert noch nicht — die App ist gegen genau diese
> Schnittstelle gebaut, der Orchestrator implementiert sie nach.
> Die Windows-App (Phase 2.5) nutzt denselben Vertrag.

Basis-URL = vom Nutzer beim App-Start eingegebene Server-URL,
z. B. `http://192.168.2.105:8200` oder `https://orchestrator.ai.lab`.

Alle Timestamps: UTC, ISO 8601 mit `Z` (Spez. 4.10).

---

## 1. REST-Endpoints

### `GET /v1/health`
Erreichbarkeits-Check (Server-Auswahl-Screen). Keine Auth.

```json
{ "status": "ok", "name": "heim-ai-orchestrator", "version": "0.1.0" }
```

### `POST /v1/auth/login`
Geräte-Login (Phase 4 ersetzt das Schema ggf. durch echte User-DB —
der Vertrag bleibt gleich).

Request:
```json
{ "username": "...", "password": "...", "device_name": "Pixel 8" }
```
Response `200`:
```json
{ "token": "<bearer-token>", "user": { "name": "Daniel", "tier": 3 } }
```
Fehler: `401` mit `{ "error": "..." }`.

Alle weiteren Aufrufe tragen `Authorization: Bearer <token>`.

### `GET /v1/voices`
Verfügbare TTS-Stimmen für die Stimmauswahl in den AI-Einstellungen.
```json
{ "voices": [ { "id": "xtts-anna", "name": "Anna (XTTS-v2)" } ] }
```

> **Entfallen:** `GET /v1/config` (lieferte früher den Picovoice-AccessKey).
> Das Wake Word nutzt jetzt **openWakeWord** (frei, kein Lizenz-Key) — der
> Endpoint wird nicht mehr benötigt.

### Zentrale Chat-Historie (`/v1/conversations`)
**Der Server besitzt die Gespräche, Clients sind Ansichten** — Browser-Frontend,
Android und Windows sehen dieselbe Historie. Die App speichert Verläufe nicht
mehr lokal; jeder Turn wird serverseitig im Stream-Router persistiert.

- `GET /v1/conversations` → `{ "conversations": [ { "id", "title", "device_name", "message_count", "created_at", "updated_at" } ] }`
- `GET /v1/conversations/{id}` → zusätzlich `"messages": [ { "id", "role", "content", "cards": [CardEnvelope…], "tools": [ { "tool", "status", "job_id?" } ], "has_image", "created_at" } ]`
  - `tools`: Chips der Tool-/Agenten-Aktivität dieser Antwort; die App zeigt sie als Chip-Zeile vor der Antwort. Hintergrund-Agenten (v1.29) tragen `job_id`; der Server stempelt `background` nach, sobald der Agent fertig ist (`done`/`error`, `error` auch bei Verbindungsabbruch).
  - `agent_results` (v1.29): Server-Kontext, wird von der App ignoriert.
  - Eine proaktive Meldung (v1.29) steht als Assistant-Message **ohne** User-Message davor.
- `DELETE /v1/conversations/{id}` → `{ "ok": true }`

Sichtbarkeit: strikt nur eigene Gespräche (Username aus dem Token); fremde
IDs liefern 404. Fortsetzen: die App schickt die `conversation_id` im
WebSocket-`hello` mit (siehe 2.1); der Server bestätigt bzw. vergibt die Id
mit einem eigenen Frame (siehe 2.2).

### `GET /v1/cards/layouts?since_version=N`
**Card-Layout-Server** (zentrale Karten-Verwaltung, siehe Spez. 4.12).
Liefert alle Layout-Templates, wenn sich seit `N` etwas geändert hat,
sonst `{ "version": N, "layouts": [] }`.

```json
{
  "version": 7,
  "layouts": [
    {
      "card_type": "weather",
      "layout_version": 3,
      "format": "json",
      "root": { "component": "column", "children": [ ... ] }
    },
    {
      "card_type": "fancy",
      "layout_version": 7,
      "format": "html",
      "html": "<div><h3>{{data.headline}}</h3>...</div>",
      "root": { "component": "column", "children": [ ... ] }
    }
  ]
}
```
**ADDITIV (v1.12):** pro Template zwei optionale Felder:
- `format`: `"json"` (Default, wie bisher → `root` mit Compose rendern) oder `"html"`.
- `html`: HTML-Fragment für `format=="html"`; die App löst `{{data.*}}`/`{{title}}`-Bindings
  clientseitig auf (identische Syntax wie JSON-Layouts, Werte werden HTML-escaped) und rendert
  es in einer isolierten WebView. `root` bleibt als Fallback-Baum enthalten.
- Felder fehlen / Cache-Einträge ohne sie → gelten als `"json"`.

Das Template-Format ist in `docs/CARDS.md` definiert. Die App cacht
Layouts lokal (Room, inkl. `format`/`html`) und fällt offline auf mitgelieferte Assets zurück.

---

## 2. WebSocket `GET /v1/assistant/stream`

Header: `Authorization: Bearer <token>`. Alle Frames sind JSON-Text.
Audio ist Base64-kodiertes rohes PCM16 (mono). Client sendet 16 kHz
(Whisper-Eingang), Server antwortet mit eigener Rate (`sample_rate`-Feld,
typisch 24000 bei XTTS-v2).

### 2.1 Client → Server

| type | Felder | Bedeutung |
|---|---|---|
| `hello` | `mode` (chat\|talk\|assist), `voice_id`, `conversation_id?`, `device{platform,name,app_version}`, `capabilities{audio_in,audio_out,cards}`, `tools[]` | Erste Nachricht nach Connect. `tools` = Manifest der Geräte-Tools (siehe 2.3); `conversation_id` setzt ein bestehendes Gespräch der zentralen Historie fort |
| `text_input` | `text` | Texteingabe statt Sprache |
| `audio_chunk` | `data` (b64 PCM16/16k) | ~100-ms-Mikrofon-Chunk |
| `audio_end` | – | Äußerung beendet. Push-to-Talk: beim Loslassen. **Realtime Talk:** die App erkennt Sprechpausen client-seitig (VAD) und sendet `audio_end` automatisch nach einer einstellbaren Stille (Default 900 ms); danach bleibt der Socket offen für die nächste Äußerung |
| `interrupt` | – | Barge-in: laufende Antwort abbrechen (im Realtime Talk automatisch, wenn der Nutzer während der Antwort spricht — auch solange die Antwort noch entsteht und noch kein Audio kam). Der Server beantwortet **jedes** `interrupt` mit `done`. **Seit Orchestrator v1.26.1:** Mit LLM-Streaming kommen Text und Audio laufend; was zwischen `interrupt` und diesem `done` eintrifft (`assistant_text`, `audio_chunk`, `audio_end`, `card`, `tool_call`, `tool_activity`), gehört zum abgebrochenen Turn und wird verworfen (`TurnFrameGate`). **Ausnahme (v1.29):** `tool_activity` mit `job_id` (Hintergrund-Agent) wird nie verworfen. Nach einem Abbruch kein TTS-Fallback. Auch eine proaktive Meldung (siehe `proactive`) bricht die App per `interrupt` ab, ebenso einen laufenden Turn vor einem neuen `text_input` |
| `tool_result` | `call_id`, `ok` (bool), `result` (JSON) | Ergebnis eines Geräte-Tool-Aufrufs |

### 2.2 Server → Client

| type | Felder | Bedeutung |
|---|---|---|
| `session` | `session_id` | Optional, nach hello |
| `conversation` | `conversation_id` | Zentrale Historie: Id des (neu angelegten oder fortgesetzten) Gesprächs — damit findet die App es später über `GET /v1/conversations/{id}` wieder |
| `transcript` | `text`, `final` (bool) | STT-Zwischenstand / final |
| `assistant_text` | `text`, `final` (bool) | Antwort-Text; Deltas mit `final:false`, Abschluss `final:true` (bei `final:true` darf `text` der Volltext sein, sonst leer). **Seit Orchestrator v1.26** standardmäßig gestreamt (LLM-Streaming, im Admin-Panel abschaltbar): Deltas kommen, während das LLM schreibt, und `audio_chunk`s des ersten Satzes oft schon vor dem `final`. Endet ein Turn ohne `final` (Barge-in), bleibt der bisherige Text stehen; der nächste Turn bekommt eine eigene Nachricht |
| `audio_chunk` | `data` (b64 PCM16), `sample_rate` | TTS-Audio-Stream (Filler über Piper zuerst, dann XTTS — für die App transparent) |
| `audio_end` | – | TTS-Stream zu Ende |
| `card` | `card{type,version,title?,data}` | Karten-Push parallel zur Sprachantwort. **ADDITIV (v1.12):** `type:"html"` → `data.html` ist ein fertiges HTML-Fragment (in isolierter WebView gerendert), optional `data.height` (px, auf 800 gekappt); übrige `data`-Keys sind Rohdaten. Unbekannte Typen → weiterhin `generic`-Fallback |
| `tool_activity` | `tool`, `status` (running\|background\|done\|error), `job_id?` | **ADDITIV (v1.12), rein informativ:** Tool-/Agent-Aktivität als Chip-Zeile über der Antwort (running legt Chip an, done/error stempelt ihn; mehrere Frames eines Turns in EINER Zeile). Präfix `agent-` → 🤖 „Agent: …". Kein Effekt auf den Turn-Ablauf. **Hintergrund-Agenten (v1.29):** `background` + `job_id` kommt im startenden Turn (Chip pulsiert „· arbeitet im Hintergrund …"); `done`/`error` mit derselben `job_id` kommt, sobald der Agent fertig ist — oft zwischen zwei Turns, manchmal mitten in einem anderen Turn oder nach einem `interrupt`. Dieses End-Frame gehört zu **keinem** Turn: Es stempelt den Chip mit dieser `job_id`, egal in welcher Zeile er steht (kein Treffer → ignorieren), und wird vom `TurnFrameGate` nie verworfen. Höchstens 3 Hintergrund-Agenten pro Verbindung; darüber und für `code-card` laufen Agenten wie bisher im Turn (`running` → `done`, ohne `job_id`) |
| `proactive` | `reason` (`agent_result`) | **ADDITIV (v1.29):** Kündigt einen Turn an, den der Nutzer nicht angefragt hat (Hintergrund-Agent fertig, Nutzer sagt eine Weile nichts — Admin-Panel „Von selbst melden nach", Default 20 s, 0 = nie). Danach ein normaler Turn: `assistant_text`, ggf. `card`/`tool_activity`, `audio_chunk`s + `audio_end` (nur wenn die letzte Nutzereingabe gesprochen war), `done`. Die App behandelt ihn als laufenden Turn (eigene Sprechblase, Barge-in per `interrupt`, kein TTS-Fallback). Steht nicht in den Turn-Frames des Gates. Kommt eine Eingabe (`text_input`, `audio_chunk`, `audio_end`, `image_input`) ohne vorheriges `interrupt`, bricht der Server die Meldung ab und schickt **sofort ein `done`** vor der Antwort; das Ergebnis fließt in diese Antwort ein |
| `tool_call` | `call_id`, `name`, `arguments` (JSON) | LLM will ein Geräte-Tool ausführen |
| `done` | – | Turn abgeschlossen. **TTS-Fallback-Regel:** Die App liest den Antworttext nur dann per On-Device-TTS vor, wenn die Anfrage per **Audio** (Mikrofon) kam UND der Server in diesem Turn **kein** `audio_chunk` geliefert hat. Bei Texteingaben wird nie vorgelesen (der Server antwortet dort bewusst ohne Audio) |
| `error` | `message` | Fehler |

**Lebensdauer der Hintergrund-Agenten (v1.29):** Agenten leben so lange wie
die WebSocket-Verbindung — schließt sie, bricht der Server sie ab, und das
Ergebnis ist verloren. Die App hält ihre Sitzungen deshalb im `SessionHub`
statt pro Bildschirm: Wird Chat, Talk oder das Assist-Overlay geschlossen,
während ein Agent arbeitet, bleibt die Verbindung offen (Foreground-Service
`AgentSessionService` mit „Abbrechen"-Knopf), bis das Ergebnis zugestellt
ist, und danach noch 60 s für eine Rückfrage im selben Gespräch. Meldet sich
die Assistenz von selbst, während kein Bildschirm dieser Sitzung sichtbar
ist, öffnet die App das Assist-Overlay auf ihr (ohne „Über anderen Apps
anzeigen": Benachrichtigung zum Antippen). Ein erneutes Öffnen desselben
Chats bzw. ein neues Wake Word übernimmt die noch offene Sitzung.

### 2.3 Geräte-Tool-Bridge

Die App meldet im `hello` ihre Tools an (Name, Beschreibung, Parameter,
`sensitive`-Flag). Der Orchestrator registriert sie pro Verbindung als
session-gebundene Tools beim LLM (MCP-Pattern: das Gerät ist ein
temporärer Tool-Server). Aktuelle Tools der Android-App:

`open_app`, `navigate_to`, `dial_number`, `compose_email`,
`create_contact`, `web_search`, `set_alarm`, `read_notifications`

`read_notifications` liefert die Rohdaten der aktuellen Benachrichtigungen
— die Zusammenfassung formuliert das LLM und schickt sie idealerweise
zusätzlich als Karte `notifications_summary`.

Sensible Tools bestätigt der Nutzer auf dem Gerät (Tiered Security,
Spez. 4.4); bei Ablehnung kommt `tool_result` mit `ok:false`.

### 2.4 Beispiel-Flow (Wake Word → Antwort mit Karte)

```
C→S  hello {mode:"assist", tools:[...]}
C→S  audio_chunk … audio_chunk
C→S  audio_end
S→C  transcript {"text":"wie wird das wetter morgen","final":true}
S→C  audio_chunk (Filler: "Ich schaue eben nach")
S→C  tool_call {"call_id":"1","name":"…"} (serverseitige Tools laufen intern)
S→C  assistant_text {"text":"Morgen wird es …","final":false} …
S→C  card {"card":{"type":"weather","data":{…}}}
S→C  audio_chunk … audio_end
S→C  done
```
