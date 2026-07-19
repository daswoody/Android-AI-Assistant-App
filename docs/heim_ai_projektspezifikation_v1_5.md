# Heim-AI Projektspezifikation

> **Single Source of Truth** für das Heim-AI-Projekt. Dieses Dokument soll am Anfang jeder Claude-Session als Kontext mitgegeben werden, damit Claude den Projektstand und alle Entscheidungen kennt.

---

## 1. Projekt-Zielsetzung

Aufbau einer privaten, lokal gehosteten Heim-AI mit folgenden Kernfähigkeiten:
- Sprachinteraktion über Wake Word (lokale Spracherkennung)
- Hochwertige, deutsche TTS mit niedriger Latenz
- Multi-Tool-Orchestrierung (Kalender, Notizen, Dokumente, Smart Home, etc.)
- Sprecher-Identifikation für rollenbasierte Rechte (Admin / User / Gast)
- Komplexe Inhalte werden parallel zur Sprachantwort als Modal/Karten in begleitender App angezeigt
- Screenshot-Analyse für PC/Gaming-Unterstützung
- Discord Voice Bot (finale Ausbaustufe)

Charakter des Projekts: **Lernprojekt** – schrittweise Umsetzung mit erklärendem Vorgehen. *Ausnahme Phase 2 (beschlossen in v1.5): Hier zählte das Endergebnis – die Android-App wurde komplett generiert statt schrittweise erarbeitet. Die Codebasis ist dafür ausführlich kommentiert und dokumentiert.*

---

## 2. Hardware

| Komponente | Spezifikation |
|---|---|
| Heimserver | 12-Core CPU, NVIDIA RTX 2080 Ti **(11 GB VRAM)** |
| Hypervisor | Proxmox |
| AI-VM | Ubuntu 24.04.4 LTS mit aktivem GPU-Passthrough, **16 GB RAM** (Ziel langfristig: 128 GB) |
| Webserver (RZ) | 2 Cores, 4 GB RAM (Headscale, optional Exit-Node, später Endpoints) |
| Audio-Endpoints | Android-Smartphones mit eigener App (Phase 2 ✅) / später: ESP32-basierte Raumstationen |
| Audio-Output | Sony STR-DN1060 AV-Receiver (Spotify Connect-fähig) |

**VRAM-Budget ist der kritische Constraint** – muss bei jeder Komponentenwahl berücksichtigt werden. RAM ist aktuell ebenfalls eng und Teil jeder Entscheidung.

---

## 3. Bestehender Software-Stack

### Auf der AI-VM (192.168.2.105)
| Tool | Zweck im Projekt | Status |
|---|---|---|
| LM Studio | LLM-Hosting mit GPU | ✅ läuft, erreichbar via `lmstudio.ai.lab` und IP:1234 |
| LiteLLM | LLM-Gateway + **MCP-Gateway** | ✅ läuft (Container `litellm`, ai-lab Netzwerk) |
| Open WebUI | Chat-UI für Tests + komplexe Inhaltsanzeige (initial) | ✅ läuft (v0.9.5) |
| n8n | Tool-Integrationen, Routinen, Webhooks, **Retention-Cleanup** | ✅ läuft |
| Langflow | Optional: visuelle LLM-Flow-Prototypen | ✅ läuft |
| Weaviate | Vektor-DB für RAG / Erinnerungsspeicher | ✅ läuft (v1.26.4), 4 Collections angelegt, mit lokalem Embedding-Sidecar |
| t2v-transformers | Embedding-Sidecar für Weaviate (intfloat/multilingual-e5-base, CPU) | ✅ läuft |
| mcp-time | MCP-Tool-Server (Time/Timezone) | ✅ läuft (theo01/mcp-time, `mcp-time:8080`) |
| Postgres | DB für LiteLLM Storage | ✅ läuft |
| local-registry | Lokale Docker-Registry für Custom-Images (Port 5000) | ✅ läuft (`--restart=always`) |

### Auf Container 100/101/102 etc.
| Container | Service |
|---|---|
| 100 | Home Assistant (VM, Thermostate) |
| 101 | pihole (DHCP, lokaler DNS – `*.ai.lab` → AI-VM) |
| 102 | n8n |
| 103 | nginx proxy manager |
| 104 | Languagetool |

### Auf dem Webserver (RZ)
| Tool | Status |
|---|---|
| SearXNG | ✅ läuft (Meta-Suche) |
| Affine | ✅ läuft |
| Authentik | ✅ läuft |
| Umami | ✅ läuft |
| n8n | ✅ läuft (zweite Instanz für externe Hooks) |
| Headscale | ⏳ noch nicht installiert – geplant |

### Eigene Codebasen (NEU in v1.5)
| Repo | Inhalt | Status |
|---|---|---|
| `Android-AI-Assistant-App` (GitHub: daswoody) | Android-App (Phase 2: Kotlin + Compose), Protokoll-Vertrag (`docs/PROTOCOL.md`), Karten-Format (`docs/CARDS.md`), CI-Workflow für APK-Build | ✅ Code komplett, CI grün, Debug-APK als Actions-Artifact (`heimai-debug-apk`) |

---

## 4. Architektur-Entscheidungen (final)

### 4.1 Voice-Pipeline-Layout

```
[Android-App / ESP32-Satellit]
   │ Wake Word lokal erkannt (Android: Porcupine)
   ▼
[Audio-Stream via WebSocket /v1/assistant/stream — JSON-Frames, Base64-PCM16/16k]
   ▼
[STT-Service] ─── Whisper (Medium oder Large-v3, je nach VRAM-Plan)
   ▼
[Voice-Orchestrator] (eigener Python-Service mit LangGraph)
   │ ├─ Routing-Entscheidung
   │ ├─ Filler-Audio ausspielen ("Lass mich nachdenken...")
   │ ├─ Tool-Calls (über MCP-Gateway in LiteLLM)
   │ ├─ Geräte-Tool-Calls (zurück an die App, siehe 4.13)
   │ ├─ RAG-Abfrage (Weaviate)
   │ └─ LLM-Anfrage (über LiteLLM)
   ▼
[TTS-Service] ─── Piper (Filler) + XTTS-v2 (Hauptantwort)
   ▼
[Audio zurück an Endpoint] + parallel: Card-Push an App (siehe 4.12)
```

### 4.2 Modell-Strategie (VRAM-Budget)

**Aktuelles Modell für Tests:** Gemma 4 E4B (Google, multimodal, mit Thinking-Mode und nativem Function-Calling). Finale Modellwahl wird später basierend auf Speicherbedarf der anderen Komponenten getroffen.

**Default-Konfiguration** (alle Komponenten gleichzeitig aktiv, Zielwert):
- LLM: ~5 GB (Modell TBD)
- STT: Whisper Medium (~1.5 GB)
- TTS: XTTS-v2 (~3 GB)
- Buffer / Speaker-Embeddings (~1.5 GB)
- **Summe: ~11 GB** (knapp, aber machbar)

**Embedding für RAG:** läuft bewusst **außerhalb der GPU** auf CPU (siehe 4.8), um das VRAM-Budget nicht zu belasten.

### 4.3 Filler-Phrasen-Strategie

| Trigger | Phrasen-Typ |
|---|---|
| Web-Search / RAG-Abfrage | "Lass mich kurz nachdenken", "Hm…", "Gib mir einen Augenblick" |
| Tool-Call (Kalender, Notes, etc.) | "Ich schaue eben nach", "Lass mich kurz nachschauen" |

Filler werden **vor** der eigentlichen Antwort über Piper (Sub-Sekunden-Latenz) ausgespielt, während im Hintergrund die LLM-Antwort generiert wird. *Für die App ist das transparent: Filler und Hauptantwort kommen als ein zusammenhängender `audio_chunk`-Stream.*

### 4.4 Sicherheits-/Rechtekonzept (Tiered Security)

| Tier | Voraussetzung | Erlaubte Aktionen |
|---|---|---|
| **Tier 1 – Gast** | Keine Stimm-Erkennung | Web-Suche, allgemeine Konversation, Wetter, News |
| **Tier 2 – User (Frau)** | Stimme erkannt | + Kalender, Notes, Smart Home, Musik, RAG-Lesen |
| **Tier 3 – Admin (du)** | Stimme erkannt + Gerätekontext | + Systemeinstellungen, kritische Steuerung, RAG-Schreiben |

Ergänzung: **Kritische Aktionen** (z. B. Türschloss, Heizung extrem) erfordern explizite App-Bestätigung, unabhängig vom Tier.

**Umsetzung in der Android-App (NEU in v1.5):** Geräte-Tools tragen ein `sensitive`-Flag. Sensible Tools (z. B. Benachrichtigungen auslesen) erfordern eine Bestätigung per Dialog in der App — außer der Nutzer aktiviert in den Rechte-Einstellungen "entsperrtes Gerät genügt" (dann prüft die App nur, dass der Keyguard nicht aktiv ist). Der Login liefert das Tier des Users mit (`user.tier`); die serverseitige Tier-Durchsetzung pro Tool kommt in Phase 4/5. Der App-Bestätigungsdialog ist gleichzeitig der vorgesehene Mechanismus für die "kritischen Aktionen" oben.

**Defense in depth bei Knowledge-Collections:** Die Trennung in `PrivateKnowledge` / `GeneralKnowledge` / `WebKnowledge` ist nicht nur logisch, sondern auch sicherheitsrelevant. Der Voice-Orchestrator routet Tier-1-Anfragen API-seitig gar nicht erst gegen `PrivateKnowledge` – zusätzlich zu eventuellen Filtern. Das verhindert, dass ein Code-Bug bei der Filter-Konstruktion private Daten leakt.

### 4.5 Web-Search / Crawling

- **SearXNG** auf dem Webserver für Meta-Suche
- **Firecrawl lokal** auf dem Heimserver (volle Performance)
- **Headscale Exit-Node** auf dem Webserver für Firecrawl-Routing (saubere IP, Split-Tunneling – nicht aller Heim-Traffic)

### 4.6 MCP-Architektur

**LiteLLM ist die zentrale MCP-Quelle.** Alle MCP-Server werden in LiteLLM registriert. Clients (heute Open WebUI, später Voice-Orchestrator) sprechen mit LiteLLM's MCP-Gateway-Endpoint `/mcp/`, der die Tools aller registrierten Server aggregiert.

**Vorteile:**
- Ein einziger MCP-Endpoint für alle Clients
- Permission-Management über LiteLLM Virtual Keys
- Beim Hinzufügen neuer MCP-Server müssen Clients nicht angepasst werden
- Saubere Trennung: MCP-Server kennen nur Tools, LLM kennt nur Sprache, LiteLLM verbindet beides

**Hostname-Konvention im ai-lab-Netzwerk:** Coolify vergibt UUIDs an Container-Namen, der **kurze Compose-Service-Name funktioniert aber als DNS-Alias** (z. B. `mcp-time`, `litellm`, `weaviate`). MCP-Server werden im Compose mit kurzen, sprechenden Namen versehen.

**Tool-Naming:** LiteLLM prefixt Tools automatisch mit dem Server-Namen (z. B. `Time-current_time`, `Time-relative_time`), damit Tool-Namen über mehrere Server eindeutig bleiben.

**Sonderfall Geräte-Tools (NEU in v1.5):** Die Tools der Android-App (App öffnen, Navigation, Benachrichtigungen, …) sind **session-gebunden** — sie existieren nur, solange das jeweilige Gerät verbunden ist, und ein Aufruf muss zum richtigen Gerät zurückgeroutet werden. Sie laufen daher NICHT über die LiteLLM-MCP-Registry, sondern werden vom Orchestrator pro WebSocket-Verbindung dynamisch als Tools beim LLM registriert (Manifest kommt in der `hello`-Nachricht, siehe 4.13). Das ist eine bewusste, dokumentierte Ausnahme vom MCP-First-Prinzip.

### 4.7 Custom-Image-Pipeline & lokale Docker-Registry

Für eigene Docker-Images (z. B. Embedding-Sidecar, später eigene MCP-Server) nutzen wir eine **lokale Docker-Registry auf der AI-VM** statt Images nur lokal getagged zu lassen.

**Begründung:**
- Coolify räumt periodisch "unbenutzte" Images auf. Lokal gebaute Images ohne Container-Referenz können dabei verschwinden
- `pull_policy: never` im Compose ist fragil und nicht konsistent von allen Coolify-Versionen unterstützt
- Eine lokale Registry ist die saubere, offizielle Lösung für dieses Pattern

**Registry-Setup:**
- Container `local-registry` mit Image `registry:2`
- Lauscht auf Port 5000 der AI-VM
- Persistenter Storage unter `/opt/docker-registry`
- `--restart=always` – läuft nach VM-Neustart automatisch wieder

**Build-Konvention:**
- Custom-Image-Quellen liegen unter `~/docker-builds/<image-name>/` auf der AI-VM
- Jedes Verzeichnis enthält ein `Dockerfile`
- Workflow: `docker build` → `docker tag <name>:local localhost:5000/<name>:latest` → `docker push localhost:5000/<name>:latest`
- Im Compose referenziert als `image: 'localhost:5000/<name>:latest'`

**Erstes Beispiel:** `t2v-e5-base` (siehe 4.8).

### 4.8 Embedding-Pipeline für RAG

**Modell:** `intfloat/multilingual-e5-base`
- 768 Dimensionen
- Max. 512 Token Input (514 Position-Embeddings inkl. 2 Spezial-Tokens)
- XLM-RoBERTa-Architektur, mehrsprachig (Deutsch sehr gut)
- Bewusst **base** statt **large** wegen RAM-Budget der VM (16 GB)

**Deployment-Pattern: Sidecar-Container**
- Embedding läuft in eigenem Container (`t2v-transformers`), separat von Weaviate
- Weaviate ruft den Sidecar über `TRANSFORMERS_INFERENCE_API=http://t2v-transformers:8080` auf
- Vorteil: Modell kann unabhängig von Weaviate neu gestartet / ausgetauscht werden
- Sidecar bewusst **nicht** im ai-lab-Netzwerk: nur Weaviate spricht mit ihm

**CPU statt GPU:**
- Bewusste Entscheidung wegen VRAM-Knappheit
- e5-base auf CPU: ~80-150 ms pro Embedding – für RAG (nicht-Echtzeit-Pfad) absolut ausreichend
- ENV: `ENABLE_CUDA=0`

**Image-Build:**
- Basis-Image: `semitechnologies/transformers-inference:custom` (von Weaviate)
- Dockerfile zwei Zeilen: `FROM ...:custom` + `RUN MODEL_NAME=intfloat/multilingual-e5-base ./download.py`
- Image-Größe: ~10 GB on-disk (großer Anteil ist CUDA-Runtime im Basis-Image, ungenutzt)
- Build-Pfad: `~/docker-builds/t2v-e5-base/`
- Registry-Tag: `localhost:5000/t2v-e5-base:latest`

**Healthcheck-Hinweis:** Das Inference-Image enthält **kein `wget`**. Healthchecks müssen über Python erfolgen:
```yaml
test:
  - CMD
  - python3
  - '-c'
  - 'import urllib.request,sys; sys.exit(0 if urllib.request.urlopen("http://localhost:8080/.well-known/ready").status==204 else 1)'
```

**Modellwechsel-Strategie:** Embeddings sind modellgebunden. Bei einem späteren Wechsel auf z. B. `multilingual-e5-large` (sobald die VM mehr RAM hat) müssen Collections gedroppt und neu indexiert werden. Da die Datenmengen im Heim-AI-Kontext überschaubar bleiben, ist das kein blockierendes Problem.

### 4.9 RAG-Verhalten und Tuning-Pfad

Aus den Such-Tests in 1.5b haben wir empirisch gelernt, wie sich das e5-base-Modell auf unseren deutschen Test-Daten verhält. Das definiert den Tuning-Pfad für den späteren Voice-Orchestrator.

**Empirische Beobachtungen:**
- Semantische Suche funktioniert: Synonyme werden erkannt ("sieden" findet "kochen"), anders formulierte Anfragen finden inhaltlich passende Treffer (z. B. "Norditalien" findet "Garda-See")
- **Hochbias bei Certainty-Werten:** e5-base liefert für nahezu jede Anfrage Werte im engen Band 0,85–0,95. Auch komplett unzusammenhängende Anfragen ("Wie programmiere ich in Rust?" gegen Wasser-/Kochen-Wissen) erreichen 0,90+
- → **Absolute Schwellwerte sind unbrauchbar.** Nur die relative Reihenfolge der Treffer ist verlässlich
- Trennschärfe bei sehr kurzen Anfragen ist begrenzt – richtige Treffer landen manchmal auf Platz 2 oder 3 statt 1

**Tuning-Pfad (Implementierung in Voice-Orchestrator, Phase 1.7+):**

1. **e5-Präfixe nutzen** – das Modell erwartet `query: <Frage>` bei Suchen und `passage: <Inhalt>` beim Schreiben. Verbessert Retrieval messbar. Voice-Orchestrator legt Inhalte mit `passage:`-Prefix in Weaviate ab und sucht mit `query:`-Prefix
2. **Relative Filterung** statt absoluter Schwellwerte. Konkret: Top-K Ergebnisse holen, dann nur die behalten, deren Certainty mindestens X% **über dem Durchschnitt der gesamten Top-K** liegt (z. B. >5%). Damit wird die Hochbias-Eigenschaft umschifft
3. **Hybrid Search evaluieren** – Weaviate unterstützt nativ `hybrid`-Queries (Vektor + BM25-Keyword). Für Anfragen mit konkreten Begriffen wie Eigennamen, Fachwörtern oder Produktnamen kann das die Trennschärfe deutlich verbessern. Im Voice-Orchestrator pro Query-Typ entscheiden

**Bewusste Nicht-Aktion jetzt:** Wir migrieren die Test-Daten in 1.5b **nicht** auf Präfixe. Sie sind explizite Test-Inhalte und werden später ohnehin verworfen. Die Präfix-Konvention startet ab Voice-Orchestrator.

### 4.10 Zeitzonen-Konvention

Alle Timestamps in Weaviate werden in **UTC** gespeichert (ISO 8601 mit `Z`-Suffix, z. B. `2026-05-15T08:30:00Z`).

**Begründung:**
- Sommerzeit-/Winterzeit-Eindeutigkeit (`02:30 Berlin` existiert am Umstellungstag zweimal, UTC nie)
- Sortierung und Vergleichbarkeit unabhängig von Zeitzonen-Wechseln
- Cleanup-Logik (n8n) arbeitet zuverlässig mit `now() - X` ohne Spezialfälle

**Konvertierung in `Europe/Berlin`** erfolgt an der Grenze zwischen Code und User: Voice-Orchestrator beim Anzeigen, n8n-Workflows beim Lesen für User-Output. Beim **Schreiben** wandelt der Voice-Orchestrator User-Eingaben aus Berlin-Zeit in UTC um, bevor sie in Weaviate landen. *Die Android-App speichert lokale Verlaufs-Timestamps als Epoch-Millis und zeigt sie in Gerätezeit an — konsistent mit dieser Regel.*

### 4.11 App-Tech-Stack (NEU in v1.5 — Mikro-Phase 2.1 abgeschlossen)

**Entscheidung Android: Kotlin nativ + Jetpack Compose** (statt Flutter/React Native).

Begründung — die Kern-Anforderungen der App sind tief im Android-System verankert, Cross-Platform-Frameworks würden für jeden Punkt Plugin-/Plattformkanal-Krücken brauchen:
- **System-Assistent:** `VoiceInteractionService` (App als "Digitaler Assistent" auswählbar) gibt es nur nativ
- **Wake Word im Hintergrund:** Microphone-Foreground-Service mit korrektem Service-Typ und Battery-Verhalten
- **Overlay-Popup** über anderen Apps (Assistant-Modus)
- **NotificationListenerService**, Intent-basierte Geräte-Tools, später ggf. AccessibilityService

**Windows (Phase 2.5): separater nativer Client** (Empfehlung: .NET/WinUI oder Tauri — finale Entscheidung bei 2.5). Die "zentrale Karten-Definition" wird NICHT über ein gemeinsames UI-Framework gelöst, sondern über das **plattformneutrale Karten-Format** (4.12): beide Apps interpretieren dasselbe JSON.

**Wake-Word-Engine: openWakeWord** (Umstieg von Picovoice Porcupine, weil Picovoice keine Lizenz bereitstellt):
- **Frei & lokal, KEIN Lizenz-Key** (das war der Blocker bei Porcupine). Läuft komplett on-device über **ONNX Runtime** (`com.microsoft.onnxruntime:onnxruntime-android`, Maven Central). *Hinweis:* Der erste Wurf nutzte TensorFlow Lite — dessen Java-API allokiert die Tensoren aber schon im Interpreter-Konstruktor und scheitert damit an den dynamischen Eingabe-Shapes der openWakeWord-Modelle ("BytesRequired overflowed", openWakeWord#223); ONNX ist zudem das primäre Format von openWakeWord.
- Pipeline: 16-kHz-Audio → `melspectrogram.onnx` → `embedding_model.onnx` → `<wakeword>.onnx` → Wahrscheinlichkeit. Ausgabe-Formen werden zur Laufzeit per Probelauf aus den Modellen gelesen (robust ggü. Modell-Versionen).
- **Energiesparen (Pflicht-Anforderung):** RMS-**Energie-Gate** mit **adaptiver Schwelle** — das Grundrauschen wird laufend nachgeführt (schnell abwärts, träge aufwärts), das Gate öffnet ab Grundrauschen × 2 (Minimum: eingestellte RMS-Schwelle). Damit schläft die ML-Pipeline auch bei Dauerbeschallung (TV, Lüfter) oder AGC-angehobenem Rauschen wieder ein; nur ein Pegel-Sprung (Sprache) weckt sie (plus kurze Nachlaufzeit). Ein Pre-Roll-Puffer (~640 ms) stellt sicher, dass der leise Wortanfang trotzdem verarbeitet wird. Bei Stille kostet nur das offene Mikrofon + RMS Strom. In den Einstellungen abschaltbar; die Status-Karte warnt bei deaktiviertem Gate vor dem Dauerverbrauch.
- **Modelle**: mitgeliefert werden Alexa / Hey Jarvis / Hey Mycroft (openWakeWord-Releases). Sie werden bewusst NICHT eingecheckt, sondern im **CI-Build** in `assets/openwakeword/` geladen (Binärdateien raus aus Git). **Eigene Wake Words** (auch deutsch) trainiert man kostenlos mit openWakeWord und importiert die `.onnx`-Datei in der App.
- Einstellbar: Modellwahl, **Empfindlichkeit** (Threshold-Slider), Energie-Gate an/aus. Eine **Live-Status-Karte** in den AI-Einstellungen zeigt Engine-Zustand (lauscht/aus/Fehler mit Grund), Mikrofonpegel inkl. Gate-Zustand und Erkennungs-Score — Diagnose ohne adb.
- Weiterhin hinter dem `WakeWordEngine`-Interface (`OpenWakeWordEngine`), damit später microWakeWord (ESP32-Parität) nachgerüstet werden kann.
- **Offen:** On-Device-Feintuning der Erkennungsschwelle (in der Build-Umgebung nicht testbar).

**App-interne Architektur-Eckpunkte:** Single-Module Kotlin/Compose-Projekt, manueller DI-Container (bewusst kein Hilt — weniger Build-Magie), Room für Verlauf + Layout-Cache, DataStore für Settings, OkHttp für REST + WebSocket, kotlinx.serialization. APK-Build über GitHub Actions (Debug-Signatur, Sideload-fähig).

### 4.12 Karten-System & zentrale Karten-Verwaltung (NEU in v1.5)

Karten ("Skills") sind zweischichtig getrennt:

- **CardEnvelope** `{type, version, title?, data}` — wird vom Orchestrator zur Laufzeit parallel zur Sprachantwort über die WebSocket-Verbindung gepusht. `data` ist frei strukturiert.
- **LayoutTemplate** `{card_type, layout_version, root}` — deklaratives, **plattformneutrales** Layout-JSON. Komponenten: `column`, `row`, `text`, `image`, `icon`, `divider`, `spacer`, `badge`, `progress`, `button`, `list`; Daten-Anbindung über `{{data.*}}`-Bindings; Aktionen `open_url` und `device_tool`. Android interpretiert es mit Compose, Windows (Phase 2.5) mit eigenem Renderer — dasselbe JSON.

**Card-Layout-Server (zentrale Verwaltung):** Endpoint `GET /v1/cards/layouts?since_version=N` des Voice-Orchestrators (noch zu bauen, Vertrag fixiert in `docs/PROTOCOL.md` + `docs/CARDS.md` im App-Repo). Globale Versionsnummer zählt bei jeder Layout-Änderung hoch; die App pollt beim Start, cacht Templates in Room (höhere `layout_version` gewinnt) und fällt offline auf mitgelieferte Asset-Templates zurück.

**Konsequenzen:**
- **Neue Karten-Layouts erreichen die Clients ohne App-Update** ("Karte deployen = Skill deployen")
- Unbekannte Kartentypen rendern über ein `generic`-Template (`data.headline` + `data.body`) — der Server kann neue Typen einführen, bevor das Layout verteilt ist
- Mitgelieferte Templates: `generic`, `weather`, `list`, `calendar`, `navigation`, `notifications_summary`, `media`

### 4.13 App ↔ Orchestrator-Protokoll (NEU in v1.5)

Verbindlicher Vertrag in `docs/PROTOCOL.md` (App-Repo). **Die App ist fertig gebaut — der Orchestrator implementiert diese Schnittstelle nach.** Kurzfassung:

**REST:**
- `GET /v1/health` — Erreichbarkeits-Check (Server-Auswahl-Screen)
- `POST /v1/auth/login` `{username, password, device_name}` → `{token, user:{name, tier}}` (Bearer-Token für alles Weitere)
- `GET /v1/voices` — Stimmen für die Stimmauswahl
- `GET /v1/cards/layouts?since_version=N` — Karten-Layouts (4.12)
- *(entfallen: `GET /v1/config` — wurde nur für den Picovoice-Key gebraucht; openWakeWord braucht keinen Key, siehe 4.11)*

**WebSocket `/v1/assistant/stream`** (JSON-Frames; Audio als Base64-PCM16 — Client sendet 16 kHz für Whisper, Server antwortet mit `sample_rate`-Angabe, typisch 24 kHz XTTS):
- Client → Server: `hello` (mit Mode chat|talk|assist, voice_id und **Geräte-Tool-Manifest**), `text_input`, `audio_chunk`/`audio_end`, `interrupt` (Barge-in), `tool_result`
- Server → Client: `transcript` (partial/final), `assistant_text` (Streaming-Deltas + final), `audio_chunk`/`audio_end`, `card`, `tool_call`, `done`, `error`
- **Realtime Talk (mode=talk):** die App erkennt Sprechpausen client-seitig (VAD) und sendet `audio_end` automatisch nach einstellbarer Stille (Default 900 ms, in den AI-Einstellungen); Barge-in erfolgt automatisch, wenn der Nutzer während der Antwort spricht. Serverseitig kein Sonderfall — der Server sieht nur `audio_chunk`/`audio_end`/`interrupt`.

**Geräte-Tool-Bridge:** App meldet im `hello` ihre Tools an (`open_app`, `navigate_to`, `dial_number`, `compose_email`, `create_contact`, `web_search`, `set_alarm`, `read_notifications`); das LLM ruft sie über `tool_call` auf, die App führt lokal aus und antwortet mit `tool_result`. `read_notifications` liefert Rohdaten — die Zusammenfassung formuliert das LLM (idealerweise zusätzlich als `notifications_summary`-Karte).

**TTS-Fallback-Regel:** Die App liest den Antworttext nur dann per Android-On-Device-TTS vor, wenn die Anfrage per **Audio** (Mikrofon) kam UND der Server in diesem Turn **kein** Audio geliefert hat. Bei Texteingaben wird nie vorgelesen (der Server antwortet dort bewusst ohne Audio). In den Einstellungen abschaltbar.

---

## 5. Phasen-Roadmap mit Mikro-Phasen

> Jede Mikro-Phase liefert ein eigenständig testbares Ergebnis. Reihenfolge ist verbindlich (Abhängigkeiten). **Ausnahme (v1.5): Phase 2 (App-Seite) wurde vorgezogen — die App ist fertig und definiert den Vertrag, den der Orchestrator (1.7+) implementiert.**

### **PHASE 1 – Infrastruktur & Basis-AI** (Foundation)

#### ✅ Mikro-Phase 1.1: Baseline-Check & Dokumentation – ERLEDIGT
- IST-Stand aller installierten Tools dokumentiert (siehe Abschnitt 3)
- Netzwerk-Topologie skizziert (Baseline-Datei vorhanden)

#### ✅ Mikro-Phase 1.2: LM Studio + LiteLLM-Integration validieren – ERLEDIGT
- LM Studio im Server-Modus mit Gemma 4 E4B
- LiteLLM erreichbar via `http://litellm:4000` (Container) bzw. `http://192.168.2.105:4000` und `http://litellm.ai.lab`
- Testabfrage via curl funktioniert

#### ✅ Mikro-Phase 1.3: Open WebUI an LiteLLM koppeln – ERLEDIGT
- Open WebUI v0.9.5 verbindet sich mit LiteLLM-Backend, Chat funktioniert

#### ✅ Mikro-Phase 1.4: Erstes MCP-Tool integrieren – ERLEDIGT (v1.2)
- `mcp-time` deployed, LiteLLM als MCP-Gateway konfiguriert, 5 Time-Tools aggregiert
- Tool-Calling End-to-End validiert (curl, `/v1/responses` und `/v1/chat/completions`)
- **Erfolgskriterium erfüllt: "Das LLM kann ein externes Tool aufrufen."**

#### ✅ Mikro-Phase 1.5a: Weaviate-Infrastruktur mit lokalem Embedding – ERLEDIGT (v1.3)
- Lokale Registry, Custom-Image `t2v-e5-base`, Weaviate nur mit `text2vec-transformers`, Cross-Container-Erreichbarkeit validiert
- **Erfolgskriterium erfüllt: "Weaviate ist mit lokaler Embedding-Pipeline produktionsbereit."**

#### ✅ Mikro-Phase 1.5b: Schemas anlegen und semantische Suche validieren – ERLEDIGT (v1.4)
- Vier Collections (`Conversations`, `PrivateKnowledge`, `GeneralKnowledge`, `WebKnowledge`), Test-Daten, `nearText`-Suche validiert (6 Testfälle)
- Beobachtetes Verhalten und Tuning-Pfad in 4.9 dokumentiert
- **Erfolgskriterium erfüllt: "Du kannst per API in Weaviate semantisch nach deutschen Inhalten suchen."**

#### ⏭️ Mikro-Phase 1.5c: Retention-Workflow in n8n – NÄCHSTER SCHRITT (Server-Seite)
- n8n-Workflow, täglich (Schedule-Trigger, z. B. 03:00 UTC)
- Cleanup-Regeln pro Collection als konfigurierbare n8n-Variablen:
  - `Conversations`: Tier 1 → 30 Tage, Tier 2/3 → 12 Monate, `importance: high` → unbegrenzt
  - `WebKnowledge`: domain-spezifische TTL, Default 60 Tage
  - `PrivateKnowledge`, `GeneralKnowledge`: keine automatische Löschung
- Vor dem Löschen: Anzahl der zu löschenden Objekte loggen
- **Erfolg:** Workflow läuft, löscht abgelaufene Einträge, hinterlässt Logs. Regel-Änderung wirkt sofort retrospektiv.

#### Mikro-Phase 1.6: Web-Search-Integration (SearXNG + Firecrawl)
- SearXNG-API testen, Firecrawl lokal aufsetzen, Headscale-Routing, MCP-Wrapper in LiteLLM registrieren
- **Erfolg:** Das LLM kann das Web durchsuchen und Seiten crawlen.

#### Mikro-Phase 1.7: Voice-Orchestrator-Skelett (Python)
- Python-Projekt aufsetzen (FastAPI + LangGraph)
- Minimaler Flow: Text rein → LLM-Call → Text raus
- Anbindung an LiteLLM (LLM und MCP-Tools)
- RAG-Anbindung an Weaviate mit `query:`/`passage:`-Präfix-Konvention (4.9)
- **NEU in v1.5:** REST-Endpoints aus `docs/PROTOCOL.md` implementieren (`/v1/health`, `/v1/auth/login`, `/v1/voices`, `/v1/cards/layouts`) sowie den WebSocket `/v1/assistant/stream` zunächst im Text-Modus (`text_input` → `assistant_text` → `done`)
- **Erfolg:** Eigener API-Service liefert LLM-Antworten — **direkt testbar aus der Android-App** (Text-Chat inkl. TTS-Fallback-Vorlesen).

#### Mikro-Phase 1.8: STT-Service (Whisper)
- faster-whisper als eigenständigen Service, API: Audio rein → Text raus, Latenz messen
- **Erfolg:** Audiodatei hochladen → Text zurück.

#### Mikro-Phase 1.9: TTS-Service Piper (Filler-Engine)
- Piper-Container mit deutschem Voice-Modell, API: Text rein → Audio raus
- **Erfolg:** Text-zu-Sprache (schnell, mittlere Qualität).

#### Mikro-Phase 1.10: TTS-Service XTTS-v2 (Hauptstimme)
- XTTS-v2 mit deutschem Voice-Sample, API mit Streaming-Output
- **Erfolg:** Hochwertige deutsche Sprachausgabe per API.

#### Mikro-Phase 1.11: End-to-End-Voice-Loop
- Orchestrator verbindet STT + LLM + TTS, Filler-Logik (Piper parallel zu LLM-Call)
- **NEU in v1.5:** Audio-Pfad des WebSocket-Protokolls komplettieren (`audio_chunk` rein/raus, `transcript`-Streaming) — danach funktionieren Push-to-Talk, Realtime Talk und der Wake-Word-Assistant-Modus der App End-to-End
- **Erfolg:** Vollständige Voice-Pipeline — vom Handy aus sprechen, Antwort hören.

#### Mikro-Phase 1.12: Erstes Tool-Calling im Orchestrator
- LangGraph-Flow um Tool-Routing erweitern, 2-3 Beispiel-Tools (Wetter, Web-Search, Zeit)
- **NEU in v1.5:** zusätzlich die Geräte-Tool-Bridge (4.13): `hello`-Manifest als session-gebundene LLM-Tools registrieren, `tool_call`/`tool_result` über den WebSocket routen; erste Karten pushen (`card`-Nachricht gegen die mitgelieferten Templates)
- **Erfolg:** Sprachfrage → AI ruft Tool (Server ODER Gerät) → spricht Antwort, Karte erscheint.

#### Mikro-Phase 1.13: Erste echte Tool-Anbindungen (MCP-Server selbst schreiben)
- Eigene MCP-Server mit FastMCP für: Nextcloud Kalender (CalDAV), Affine Notes (API), Paperless (REST-API)
- Affine sauber neu aufsetzen; eigene Images über lokale Registry (4.7)
- **Erfolg:** "Was steht morgen im Kalender?" funktioniert per Sprache — mit `calendar`-Karte in der App.

#### Mikro-Phase 1.14: Test-Frontend (Open WebUI / Matrix)
**Stark relativiert in v1.5:** Die Android-App übernimmt diese Rolle, sobald 1.7 steht. Nur noch umsetzen, falls ein zweites Test-Frontend gebraucht wird — sonst überspringen.

---

### **PHASE 2 – Eigene Apps (Android + Windows)**

#### ✅ Mikro-Phase 2.1: Tech-Stack-Entscheidung – ERLEDIGT (v1.5)
- Android: **Kotlin + Jetpack Compose** (Begründung: 4.11)
- Windows: separater nativer Client, finale Wahl bei 2.5; Karten-Parität über plattformneutrales Layout-JSON (4.12)
- Wake Word: **Porcupine** (4.11)

#### ✅ Mikro-Phase 2.2: Android-App MVP – ERLEDIGT, App-Seite (v1.5)
- Server-Auswahl (Health-Check) → Login → Startscreen: Neuer Chat / Realtime Talk / vergangene Gespräche / Einstellungen
- Einstellungen: Account (Server, User, Tier, Logout), Design/Farben (5 Farbwelten, System/Hell/Dunkel), AI (Stimmauswahl vom Server, Wake Word inkl. AccessKey + Phrase, Rechte-Modus, TTS-Fallback, Systemfreigaben, Stimmerkennungs-Training als Platzhalter für Phase 5)
- Chat mit Push-to-Talk; Realtime Talk mit Dauer-Mikrofon; Audio-Antwort als Stream; Verlauf lokal in Room
- ⏳ **End-to-End-Test offen**, bis der Orchestrator (1.7/1.11) die Gegenseite liefert

#### ✅ Mikro-Phase 2.3: Wake-Word-Integration Android – ERLEDIGT (v1.5)
- Porcupine hinter `WakeWordEngine`-Abstraktion, Microphone-Foreground-Service, Boot-Receiver
- Wake Word → Assistant-Popup: bevorzugt via VoiceInteraction-Session, sonst Overlay-Activity ("Über anderen Apps anzeigen"), sonst High-Priority-Notification
- Erkennung pausiert automatisch, während das Popup selbst das Mikrofon nutzt

#### ✅ Mikro-Phase 2.4: Modal-Anzeige (Komplexe Inhalte) – ERLEDIGT, App-Seite (v1.5)
- App als System-Assistent registrierbar (`VoiceInteractionService`); Assistant-Modus als transluzentes Popup-Overlay über der laufenden App (wie Google Assistant), Karten als schwebende Popup-Karten, zusätzlich inline in Chat/Talk
- Karten-Push via WebSocket (`card`), Rendering über zentrale Layout-Templates (4.12)
- Geräte-Tool-Bridge implementiert (8 Tools, siehe 4.13) inkl. Tiered-Security-Bestätigung (4.4)
- Vorbereitet: `ActionPlanner`-Interface für ein On-Device-Action-Modell (z. B. Gemma-3-270M-Finetune via MediaPipe LLM Inference) für lokale Gerätesteuerung ohne Server-Roundtrip — Integration als offener Punkt
- ⏳ **End-to-End-Test offen** (wie 2.2)

#### Mikro-Phase 2.5: Windows-App MVP
- Analog zu Android, aber Desktop; System-Tray-Integration
- Konsumiert dasselbe Protokoll (`docs/PROTOCOL.md`) und dieselben Karten-Layouts (4.12)
- **Erfolg:** Wake Word und Modal auch am PC.

---

### **PHASE 3 – Screenshot-Analyse**

#### Mikro-Phase 3.1: VLM-Integration
- LLaVA oder ähnliches Vision-Modell evaluieren
- Modell-Swapping-Logik im Orchestrator (LLM zwischenzeitlich entladen)
- **Erfolg:** Bild-Input → Beschreibung als Text.

#### Mikro-Phase 3.2: Windows-Screenshot-Capture
- Auf Befehl Screenshot via Windows-API, Upload an Orchestrator
- **Erfolg:** "Was siehst du?" → korrekte Antwort über aktuellen Screen.

#### Mikro-Phase 3.3: Game-Specific Workflow
- Screenshot → VLM-Analyse → Web-Search nach Lösung → Antwort
- **Erfolg:** Konkrete Spielfrage wird beantwortet.

*Kompatibilität mit Phase 2 (v1.5): Das WebSocket-Protokoll wird um eine `image_input`-Nachricht erweitert (Envelope-Design lässt das zu); Android ergänzt dann Kamera/Share-Target.*

---

### **PHASE 4 – Basis-User- & Rechte-System**

#### Mikro-Phase 4.1: User-Datenbank
- Postgres oder SQLite für User-Profile; Schema: User, Geräte, Tier-Level, Permissions

#### Mikro-Phase 4.2: Geräte-basierte Auth
- App-Login mit Token, Gerät → User-Mapping
- *Hinweis (v1.5): Die App nutzt bereits `POST /v1/auth/login` mit `device_name` und speichert Token + Tier — 4.1/4.2 füllen die Server-Seite, der App-Vertrag bleibt stabil.*

#### Mikro-Phase 4.3: Permission-Middleware im Orchestrator
- Vor jedem Tool-Call: Tier prüfen; LiteLLM Virtual Keys pro Tier nutzen
- **Erfolg:** Gäste können keine privaten Daten abfragen.

---

### **PHASE 5 – Stimmerkennung & komplexes Rechte-Management**

#### Mikro-Phase 5.1: Speaker Embeddings
- pyannote.audio oder SpeechBrain, Trainings-Samples aufnehmen
- *Hinweis (v1.5): Einstiegspunkt "Stimmprofil trainieren" existiert bereits in den App-AI-Einstellungen (deaktiviert); Trainings-Upload wird als REST-Endpoint ergänzt.*
- **Erfolg:** Stimmen werden zu Embeddings vektorisiert.

#### Mikro-Phase 5.2: Speaker-ID im Voice-Flow
- STT + Speaker-ID parallel, Tier-Zuweisung dynamisch
- **Erfolg:** AI erkennt, wer spricht.

#### Mikro-Phase 5.3: Kombi-Auth für kritische Aktionen
- Stimme + App-Bestätigung (der Bestätigungs-Mechanismus der App aus 4.4 wird hierfür wiederverwendet)
- **Erfolg:** Sicherheitskritische Aktionen sind doppelt abgesichert.

---

### **PHASE 6 – Erweiterungen (in offener Reihenfolge)**

- Matrix Synapse Anbindung
- Plane Anbindung
- Discord Voice Bot (finale Ausbaustufe)
- News/Wetter-Routinen
- Netzwerk-/Server-Monitoring (read-only)
- Spotify-Steuerung über Sony AVR
- MOVA Home (zurückgestellt)

---

## 6. Offene Punkte / Recherche-Aufgaben

- [ ] **Finale Modellwahl** (Gemma 4 E4B vs. Qwen 3 vs. Llama 3.1) – Benchmark im Live-Setup nach Mikro-Phase 1.11
- [ ] TTS: Evaluation Kokoro, StyleTTS2, Orpheus TTS
- [x] ~~Wake-Word-Engine: Porcupine vs. openWakeWord~~ → **Porcupine** für Android (4.11); openWakeWord/microWakeWord bleibt Kandidat für ESP32-Satelliten
- [ ] Eigene Wake-Word-Phrase definieren → bei Porcupine: Custom-`.ppn` über die Picovoice-Konsole trainieren und in der App hinterlegen
- [ ] Konkrete Voice-Samples für XTTS-v2 erstellen (deutsch)
- [x] ~~App-Tech-Stack finalisieren~~ → Kotlin + Jetpack Compose (4.11); Windows-Stack bei 2.5
- [ ] **Lokales DNS mit Zertifikat** – für die App relevant, sobald `wss://`/HTTPS erzwungen wird; Cleartext-HTTP im LAN funktioniert, ist aber Übergangslösung
- [ ] **Upgrade auf `multilingual-e5-large`** sobald die VM mehr RAM hat (Ziel: 128 GB). Reindexing erforderlich.
- [ ] **Slim-Image für Embedding-Sidecar** evaluieren (~5-6 GB Plattenplatz)
- [ ] **e5-Präfixe (`query:`/`passage:`) im Voice-Orchestrator implementieren** (4.9, Phase 1.7+)
- [ ] **Hybrid Search (Vektor + BM25) evaluieren** – sobald Voice-Orchestrator steht
- [ ] **BM25-Stopwords auf Deutsch konfigurieren** – betrifft nur Hybrid Search
- [ ] **NEU: Orchestrator-Mock für App-Tests** – Mini-FastAPI mit `/v1/health` + `/v1/auth/login` + Echo-WebSocket (~100 Zeilen), um die App vor 1.7 auf dem Gerät durchspielen zu können
- [ ] **NEU: Release-Signing der APK** – aktuell Debug-Signatur (für Sideload ok); für saubere Update-Pfade eigenen Keystore anlegen und im CI signieren
- [ ] **NEU: On-Device-Action-Modell** in den `ActionPlanner` integrieren – Pfad: Gemma-3-270M-Finetune ("Mobile Actions") via MediaPipe LLM Inference; offizielles Modell-Artefakt war zum Zeitpunkt v1.5 nicht verifizierbar → prüfen
- [ ] **NEU: Binär-WebSocket-Frames statt Base64** für Audio evaluieren (~33 % Overhead; im LAN unkritisch, daher v1 bewusst simpel und debugbar)

---

## 7. Bekannte Probleme

### LiteLLM `reset_budget_job.py` Fehler
**Symptom:** LiteLLM-Logs zeigen kontinuierlich Prisma-Fehler (`MissingRequiredValueError: where.budget_limits.not`).
**Auswirkung:** Kosmetisch. **Status:** Beobachten, bei nächstem Update prüfen. **Workaround:** Keiner nötig.

### Open WebUI v0.9.5 zeigt MCP-Tool-Ergebnisse als leer an
**Symptom:** Tool-Call korrekt, Ergebnis kommt in der UI als leerer String an.
**Auswirkung:** Mittel — Architektur funktioniert (curl-validiert), nur das Test-Frontend zeigt es nicht.
**Status:** Nicht weiter debuggt; Open WebUI ist Übergangslösung, der Orchestrator implementiert die Tool-Schleife selbst.

### LiteLLM MCP-Gateway ist `_experimental`
**Hinweis:** Liegt unter `proxy/_experimental/mcp_server/`; APIs können sich zwischen Versionen ändern.

### Coolify räumt unbenutzte Custom-Images auf
**Status:** Gelöst durch lokale Docker-Registry (4.7).

### Traefik-Routing bei Coolify: Port 80, nicht Port der Anwendung
**Status:** Verstanden. Externe `*.ai.lab`-Aufrufe IMMER ohne expliziten Port.

### Inference-Image enthält kein `wget`
**Status:** Gelöst. Healthchecks über Python (`urllib.request`).

### e5-base hat Hochbias bei Certainty-Werten
**Status:** Verstanden, dokumentiert in 4.9. Nur relative Reihenfolge nutzen, keine absoluten Schwellwerte.

### Android: Mic-FGS-Restriktionen ab Android 15 (NEU in v1.5)
**Symptom:** Wake-Word-Service startet nach einem Reboot nicht automatisch.
**Ursache:** Microphone-Foreground-Services dürfen ab Android 15 nicht mehr aus `BOOT_COMPLETED` heraus starten.
**Workaround:** App nach Reboot einmal öffnen. Zusätzlich die App von der OEM-Akku-Optimierung ausnehmen (Samsung/Xiaomi etc. beenden den Service sonst).

### Android: Ein Mikrofon-Konsument zur Zeit (NEU in v1.5)
**Symptom:** Wake-Word-Erkennung und Assistant-Aufnahme können nicht gleichzeitig laufen.
**Status:** Gelöst per Design — das Assistant-Popup pausiert die Wake-Word-Engine beim Öffnen und reaktiviert sie beim Schließen.

### Android: Dauerhafter Mikrofon-Indikator (NEU in v1.5)
**Symptom:** Bei aktivem Wake Word zeigt Android permanent den grünen Mikrofon-Indikator und eine Foreground-Notification.
**Status:** Erwartetes Plattform-Verhalten (Privacy-Feature), kein Bug. Akku-Last durch Porcupine selbst ist gering; dominanter Faktor ist das offene Mikrofon.

### Android: Freisprech-Echo im Realtime Talk (NEU in v1.5)
**Symptom:** Bei Lautsprecher-Wiedergabe hört das Mikrofon die eigene Antwort mit; auf manchen Geräten ist das Echo lauter als die eigene Stimme → reine Schwellenwerte reichen nicht.

**Primäre Lösung (v1.5): Kommunikations-Audiomodus.** Der Realtime Talk läuft wie ein Freisprech-Telefonat:
- `AudioManager.MODE_IN_COMMUNICATION` + Routing auf den Lautsprecher über `setCommunicationDevice` (bzw. `setSpeakerphoneOn` < API 31),
- Aufnahme über `VOICE_COMMUNICATION`, Wiedergabe über `USAGE_VOICE_COMMUNICATION` (Voice-Call-Stream),
- dadurch greift die **geräteeigene, anrufqualitäts-Echo-Unterdrückung** (auf AOSP/vielen Geräten die WebRTC-AEC) — dieselbe, die Telefonate im Freisprechmodus nutzen. Sie kennt das Wiedergabesignal als Referenz und rechnet es aus dem Mic-Signal heraus (Full-Duplex, Barge-in möglich).
- Umschaltbar (Setting `talkAec`, Default an), plus `AcousticEchoCanceler`/`NoiseSuppressor` best-effort auf der Aufnahmesession.

**Fallbacks/Ergänzungen:**
- Kalibrierbare **Lautstärke-Schwelle** (RMS) mit **Live-Pegelanzeige** zum Feintuning.
- **Half-Duplex** (Mikro pausiert während der Antwort) — garantiert kein Selbst-Mithören, kein Barge-in.

**Tradeoffs des Kommunikationsmodus:** Wiedergabe hängt an der In-Call-Lautstärke (separate Lautstärkeregelung); der Modus wird beim Verlassen des Talks zurückgesetzt. Reicht die Geräte-AEC auf einem spezifischen Gerät nicht, ist der nächste Schritt eine **gebündelte WebRTC-AEC3-Software-Bibliothek** (native `.so` via JitPack/NDK). **Offener Punkt:** bleibt als „schwerere" Fallback-Option dokumentiert; erfordert Test auf echtem Gerät.

---

## 8. Wichtige Constraints & Reminder

- **VRAM ist der Bottleneck** – jede neue Komponente am Modell-Profil prüfen
- **RAM ist aktuell ebenfalls knapp** (16 GB) – Ziel langfristig 128 GB
- **Lernprojekt** – jeder Schritt mit Erklärung des "Warum", nicht nur "Wie" (Ausnahme Phase 2, siehe Abschnitt 1)
- **Eigenständig testbar** – jede Mikro-Phase hat ein konkretes Erfolgskriterium
- **Faktenbasis** – keine spekulativen Aussagen, im Zweifel Recherche/Quelle
- **Security First** – speziell bei Tier-2/3-Aktionen
- **Privacy** – alles soweit möglich lokal, Cloud nur wo unvermeidbar. Embeddings laufen lokal, NICHT über externe APIs. *App: Benachrichtigungsdaten bleiben in-memory auf dem Gerät und verlassen es nur nach explizitem (ggf. bestätigtem) Tool-Aufruf.*
- **MCP-First** – alle Tools als MCP-Server in LiteLLM; **dokumentierte Ausnahme:** session-gebundene Geräte-Tools der Apps (4.6/4.13)
- **Custom-Image-First** – eigene Images über die lokale Registry
- **UTC-First für Timestamps** (4.10)
- **Retention-Regeln zentral in n8n**, nicht im Datenschema
- **NEU: Protokoll-Vertrag ist fixiert** – Änderungen an `docs/PROTOCOL.md` müssen App UND Orchestrator berücksichtigen (die App ist bereits gebaut!)
- **NEU: Karten-Layouts sind plattformneutral** – keine Android-spezifischen Features in Templates; Windows rendert dasselbe JSON

---

## 9. Kommunikationsregeln für Claude

- Bei jeder Mikro-Phase **Schritt-für-Schritt-Anleitung** geben, als wäre es das erste Mal
- **Code-Beispiele** vollständig und kommentiert
- Bei Unklarheiten **nachfragen**, nicht raten
- Proaktive Vorschläge bei besseren Alternativen
- Dokument am Ende jeder Phase **aktualisieren** (Versionierung)

---

**Version:** 1.5
**Stand:** 2026-06-12
**Changelog:**
- v1.5 (2026-06-12): **Phase 2 (Android, Mikro-Phasen 2.1–2.4) app-seitig komplett umgesetzt** — Repo `Android-AI-Assistant-App`, CI-Build grün, Debug-APK als Actions-Artifact. Neue Architektur-Sektionen: 4.11 App-Tech-Stack (Kotlin+Compose statt Flutter, Porcupine als Wake-Word-Engine), 4.12 Karten-System & Card-Layout-Server (zentrale Verwaltung, Layout-Updates ohne App-Update, plattformneutral für Windows), 4.13 App↔Orchestrator-Protokoll inkl. Geräte-Tool-Bridge und TTS-Fallback-Regel. Mikro-Phasen 1.7/1.11/1.12 um Protokoll-Implementierung erweitert, 1.14 relativiert. Tiered-Security-Umsetzung in der App dokumentiert (4.4); Geräte-Tools als dokumentierte MCP-Ausnahme (4.6). Drei neue bekannte Probleme (Android-15-FGS, Mikrofon-Exklusivität, Mikrofon-Indikator). Offene Punkte: Wake-Word-Engine und App-Stack entschieden; neu: Orchestrator-Mock, Release-Signing, On-Device-Action-Modell, Binär-Frames.
- v1.4 (2026-06-04): Mikro-Phase 1.5b abgeschlossen (4 Collections, Test-Daten, semantische Suche validiert). Neue Mikro-Phase 1.5c (Retention-Workflow). Neue Sektionen 4.9 (RAG-Tuning) und 4.10 (UTC-First). e5-Hochbias dokumentiert.
- v1.3 (2026-05-29): 1.5 in 1.5a/1.5b aufgeteilt. Neue Sektionen 4.7 (Registry) und 4.8 (Embedding-Pipeline).
- v1.2 (2026-05-11): Mikro-Phase 1.4 abgeschlossen. LiteLLM als zentrales MCP-Gateway. mcp-time deployed.
- v1.1 (2026-04-29): Headscale als "noch nicht installiert" markiert.

**Nächster Schritt:** Mikro-Phase 1.5c (Retention-Workflow in n8n). Danach 1.7 inkl. der Protokoll-Endpoints aus `docs/PROTOCOL.md` — ab dann ist die Android-App End-to-End nutzbar. Optional vorher: Orchestrator-Mock (siehe offene Punkte) für sofortige App-Tests.
