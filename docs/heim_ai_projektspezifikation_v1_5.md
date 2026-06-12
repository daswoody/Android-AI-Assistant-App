# Heim-AI Projektspezifikation

> **Single Source of Truth** für das Heim-AI-Projekt. Dieses Dokument soll am Anfang jeder Claude-Session als Kontext mitgegeben werden, damit Claude den Projektstand und alle Entscheidungen kennt.

---

## 1. Projekt-Zielsetzung

Aufbau einer privaten, lokal gehosteten Heim-AI mit folgenden Kernfähigkeiten:
- Sprachinteraktion über Wake Word (lokale Spracherkennung)
- Hochwertige, deutsche TTS mit niedriger Latenz
- Multi-Tool-Orchestrierung (Kalender, Notizen, Dokumente, Smart Home, etc.)
- Sprecher-Identifikation für rollenbasierte Rechte (Admin / User / Gast)
- Komplexe Inhalte werden parallel zur Sprachantwort als Modal in begleitender App angezeigt
- Screenshot-Analyse für PC/Gaming-Unterstützung
- Discord Voice Bot (finale Ausbaustufe)

Charakter des Projekts: **Lernprojekt** – schrittweise Umsetzung mit erklärendem Vorgehen. *Ausnahme Phase 2: Hier zählt das Endergebnis (App wurde komplett generiert), die Code-Basis ist aber ausführlich kommentiert und dokumentiert.*

---

## 2. Hardware

| Komponente | Spezifikation |
|---|---|
| Heimserver | 12-Core CPU, NVIDIA RTX 2080 Ti **(11 GB VRAM)** |
| Hypervisor | Proxmox |
| AI-VM | Ubuntu 24.04.4 LTS mit aktivem GPU-Passthrough, **16 GB RAM** (Ziel langfristig: 128 GB) |
| Webserver (RZ) | 2 Cores, 4 GB RAM (Headscale, optional Exit-Node, später Endpoints) |
| Audio-Endpoints | Android-Smartphones (eigene App, Phase 2) / später: ESP32-basierte Raumstationen |
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
| `Android-AI-Assistant-App` | Android-App (Phase 2), Protokoll-Spezifikation, Karten-System | ✅ Code komplett, baut per GitHub Actions |

---

## 4. Architektur-Entscheidungen (final)

### 4.1 Voice-Pipeline-Layout

```
[Android-App / ESP32-Satellit]
   │ Wake Word lokal erkannt (Android: Porcupine)
   ▼
[Audio-Stream via WebSocket /v1/assistant/stream — JSON + Base64-PCM16/16k]
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

Filler werden **vor** der eigentlichen Antwort über Piper (Sub-Sekunden-Latenz) ausgespielt, während im Hintergrund die LLM-Antwort generiert wird. *Für die App ist das transparent: Filler und Hauptantwort kommen als ein `audio_chunk`-Stream.*

### 4.4 Sicherheits-/Rechtekonzept (Tiered Security)

| Tier | Voraussetzung | Erlaubte Aktionen |
|---|---|---|
| **Tier 1 – Gast** | Keine Stimm-Erkennung | Web-Suche, allgemeine Konversation, Wetter, News |
| **Tier 2 – User (Frau)** | Stimme erkannt | + Kalender, Notes, Smart Home, Musik, RAG-Lesen |
| **Tier 3 – Admin (du)** | Stimme erkannt + Gerätekontext | + Systemeinstellungen, kritische Steuerung, RAG-Schreiben |

Ergänzung: **Kritische Aktionen** (z. B. Türschloss, Heizung extrem) erfordern explizite App-Bestätigung, unabhängig vom Tier.

**Umsetzung in der Android-App (NEU in v1.5):** Geräte-Tools tragen ein `sensitive`-Flag. Sensible Tools (z. B. Benachrichtigungen auslesen) erfordern eine Bestätigung im UI-Dialog — außer der Nutzer aktiviert in den Rechte-Einstellungen "entsperrtes Gerät genügt" (dann prüft die App nur, dass der Keyguard nicht aktiv ist). Der Login liefert das Tier des Users mit; die serverseitige Tier-Durchsetzung kommt in Phase 4/5.

**Defense in depth bei Knowledge-Collections:** Die Trennung in `PrivateKnowledge` / `GeneralKnowledge` / `WebKnowledge` ist nicht nur logisch, sondern auch sicherheitsrelevant. Der Voice-Orchestrator routet Tier-1-Anfragen API-seitig gar nicht erst gegen `PrivateKnowledge` – zusätzlich zu eventuellen Filtern.

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

**Sonderfall Geräte-Tools (NEU in v1.5):** Die Tools der Android-App (App öffnen, Navigation, Benachrichtigungen, …) sind **session-gebunden** — sie existieren nur, solange das Gerät verbunden ist, und der Aufruf muss zum richtigen Gerät zurückgeroutet werden. Sie laufen daher NICHT über die LiteLLM-MCP-Registry, sondern werden vom Orchestrator pro WebSocket-Verbindung dynamisch als Tools beim LLM registriert (Manifest kommt im `hello`, siehe 4.13). Das ist eine bewusste, dokumentierte Ausnahme vom MCP-First-Prinzip.

### 4.7 Custom-Image-Pipeline & lokale Docker-Registry

(unverändert — Registry `registry:2` auf Port 5000, Build-Konvention `~/docker-builds/<image-name>/`, Tag `localhost:5000/<name>:latest`)

### 4.8 Embedding-Pipeline für RAG

(unverändert — `intfloat/multilingual-e5-base`, 768 Dim, CPU-Sidecar `t2v-transformers`, Healthcheck via Python statt wget)

### 4.9 RAG-Verhalten und Tuning-Pfad

(unverändert — e5-Präfixe `query:`/`passage:`, relative Filterung statt absoluter Schwellwerte, Hybrid Search evaluieren)

### 4.10 Zeitzonen-Konvention

(unverändert — UTC-First, ISO 8601 mit `Z`; Konvertierung nach `Europe/Berlin` nur an Code-/User-Grenzen)

### 4.11 App-Tech-Stack (NEU in v1.5, Mikro-Phase 2.1 abgeschlossen)

**Entscheidung Android: Kotlin nativ + Jetpack Compose** (statt Flutter/React Native).

Begründung — die Kern-Anforderungen der App sind tief im Android-System verankert, Cross-Platform-Frameworks würden für jeden Punkt Plugin-/Plattformkanal-Krücken brauchen:
- **System-Assistent:** `VoiceInteractionService` (App als "Digitaler Assistent" auswählbar) gibt es nur nativ
- **Wake Word im Hintergrund:** Microphone-Foreground-Service mit korrekten Service-Typen und Battery-Verhalten
- **Overlay-Popup** über anderen Apps (Assistant-Modus)
- **NotificationListenerService**, Intent-basierte Geräte-Tools, später AccessibilityService

**Windows (Phase 2.5): separater nativer Client** (Empfehlung: .NET/WinUI oder Tauri — Entscheidung bei 2.5). Die "zentrale Karten-Definition" wird NICHT über ein gemeinsames UI-Framework gelöst, sondern über das **plattformneutrale Karten-Format** (4.12): beide Apps interpretieren dasselbe JSON.

**Wake-Word-Engine: Picovoice Porcupine** (entschieden, war offener Punkt). Kostenlos für Personal Use, sehr geringer CPU-/Akku-Verbrauch, stabile Android-Integration (Maven Central). Benötigt einen AccessKey (console.picovoice.ai), der in den App-Einstellungen hinterlegt wird. Die App kapselt die Engine hinter einem `WakeWordEngine`-Interface, damit später openWakeWord/microWakeWord (ESP32-Parität, eigene Phrase) nachgerüstet werden kann. **Konsequenz:** Eigene Wake-Word-Phrasen erfordern bei Porcupine ein über die Picovoice-Konsole trainiertes `.ppn`-Modell.

### 4.12 Karten-System & zentrale Karten-Verwaltung (NEU in v1.5)

Karten ("Skills") sind zweischichtig:
- **CardEnvelope** `{type, version, title?, data}` — wird vom Orchestrator zur Laufzeit parallel zur Sprachantwort gepusht
- **LayoutTemplate** `{card_type, layout_version, root}` — deklaratives, **plattformneutrales** Layout-JSON (column/row/text/icon/image/list/button/… mit `{{data.*}}`-Bindings), das Android mit Compose und Windows später mit eigenem Renderer interpretiert

**Card-Layout-Server:** Endpoint `GET /v1/cards/layouts?since_version=N` des Voice-Orchestrators (noch zu bauen, Vertrag fixiert in `docs/PROTOCOL.md` + `docs/CARDS.md` im App-Repo). Globale Versionsnummer, App pollt beim Start, cacht in Room, Fallback auf mitgelieferte Asset-Templates. **Neue Karten-Layouts erreichen die Clients damit ohne App-Update.** Unbekannte Kartentypen rendern über ein `generic`-Template.

Mitgelieferte Templates: `generic`, `weather`, `list`, `calendar`, `navigation`, `notifications_summary`, `media`.

### 4.13 App ↔ Orchestrator-Protokoll (NEU in v1.5)

Verbindlicher Vertrag in `docs/PROTOCOL.md` (App-Repo). Kurzfassung:
- REST: `GET /v1/health`, `POST /v1/auth/login` (→ Bearer-Token + Tier), `GET /v1/voices` (Stimmauswahl), `GET /v1/cards/layouts`
- WebSocket `/v1/assistant/stream`: JSON-Frames; Audio als Base64-PCM16 (Client 16 kHz → Whisper; Server mit `sample_rate`-Angabe, typisch 24 kHz XTTS)
- Nachrichten: `hello` (mit Geräte-Tool-Manifest), `text_input`, `audio_chunk`/`audio_end`, `interrupt` ↔ `transcript`, `assistant_text` (Streaming), `audio_chunk`/`audio_end`, `card`, `tool_call`/`tool_result`, `done`, `error`
- **Geräte-Tool-Bridge:** App meldet Tools im `hello` an; LLM ruft sie über `tool_call` auf, App führt lokal aus und antwortet mit `tool_result`
- **TTS-Fallback-Regel:** Kommt bis `done` kein Server-Audio, liest die App den Antwort-Text per Android-TTS vor

---

## 5. Phasen-Roadmap mit Mikro-Phasen

> Jede Mikro-Phase liefert ein eigenständig testbares Ergebnis. Reihenfolge ist verbindlich (Abhängigkeiten). **Ausnahme (v1.5): Phase 2 (App-Seite) wurde vorgezogen — die App ist fertig und definiert den Vertrag, den der Orchestrator (1.7+) implementiert.**

### **PHASE 1 – Infrastruktur & Basis-AI** (Foundation)

#### ✅ Mikro-Phase 1.1 – 1.5b: ERLEDIGT (Details siehe v1.4)

#### ⏭️ Mikro-Phase 1.5c: Retention-Workflow in n8n – NÄCHSTER SCHRITT (Server-Seite)
(unverändert, siehe v1.4)

#### Mikro-Phase 1.6: Web-Search-Integration (SearXNG + Firecrawl)
(unverändert)

#### Mikro-Phase 1.7: Voice-Orchestrator-Skelett (Python)
- Python-Projekt aufsetzen (FastAPI + LangGraph)
- Minimaler Flow: Text rein → LLM-Call → Text raus
- Anbindung an LiteLLM (LLM und MCP-Tools)
- RAG-Anbindung an Weaviate mit `query:`/`passage:`-Präfix-Konvention (siehe 4.9)
- **NEU:** REST-Endpoints aus `docs/PROTOCOL.md` implementieren (`/v1/health`, `/v1/auth/login`, `/v1/voices`, `/v1/cards/layouts`) — damit ist die Android-App ab 1.7 nutzbar (Text-Chat)
- **Erfolg:** Du hast einen eigenen API-Service, der LLM-Antworten liefert — testbar direkt aus der Android-App.

#### Mikro-Phase 1.8 – 1.13: STT, TTS (Piper/XTTS), Voice-Loop, Tool-Calling, eigene MCP-Server
(unverändert; zusätzlich in 1.11: WebSocket `/v1/assistant/stream` mit Audio-Streaming gemäß Protokoll — danach funktionieren Push-to-Talk, Realtime Talk und Wake-Word-Assist der App End-to-End)

#### Mikro-Phase 1.14: Test-Frontend (Open WebUI / Matrix)
**Stark relativiert:** Die Android-App übernimmt diese Rolle, sobald 1.7 steht. Optional überspringen.

---

### **PHASE 2 – Eigene Apps (Android + Windows)**

#### ✅ Mikro-Phase 2.1: Tech-Stack-Entscheidung – ERLEDIGT (v1.5)
- Android: **Kotlin + Jetpack Compose** (Begründung: 4.11)
- Windows: separater nativer Client, Entscheidung bei 2.5; Karten-Parität über plattformneutrales Layout-JSON (4.12)

#### ✅ Mikro-Phase 2.2: Android-App MVP – ERLEDIGT (App-Seite, v1.5)
- Server-Auswahl → Login → Startscreen (Einstellungen / Neuer Chat / Realtime Talk / vergangene Gespräche)
- Push-to-Talk im Chat, Dauer-Mikrofon im Realtime Talk, Audio-Antwort-Playback (Streaming)
- Einstellungen: Account, Design/Farben (5 Farbwelten + Hell/Dunkel), AI (Stimmauswahl vom Server, Wake Word, Rechte-Modus, TTS-Fallback, Stimmerkennungs-Training als vorbereiteter Platzhalter für Phase 5)
- Verlauf lokal in Room; On-Device-TTS-Fallback
- ⏳ **End-to-End-Test offen**, bis Orchestrator 1.7/1.11 die Gegenseite liefert

#### ✅ Mikro-Phase 2.3: Wake-Word-Integration Android – ERLEDIGT (v1.5)
- Porcupine hinter `WakeWordEngine`-Abstraktion, Microphone-Foreground-Service, Boot-Receiver
- Wake Word → Assistant-Popup (bevorzugt via VoiceInteraction-Session, sonst Overlay, sonst Notification)
- Built-in-Keywords wählbar (Computer, Jarvis, …); eigene Phrase = später trainiertes `.ppn`

#### ✅ Mikro-Phase 2.4: Modal-Anzeige (Komplexe Inhalte) – ERLEDIGT (App-Seite, v1.5)
- App als System-Assistent registrierbar (`VoiceInteractionService`), Assistant-Modus als transluzentes Popup-Overlay über der laufenden App
- Karten erscheinen als schwebende Popup-Karten im Overlay sowie inline in Chat/Talk
- Karten-Push via WebSocket (`card`-Nachricht), Rendering über zentrale Layout-Templates (4.12)
- Geräte-Tool-Bridge: open_app, navigate_to, dial_number, compose_email, create_contact, web_search, set_alarm, read_notifications (Zusammenfassung formuliert das LLM)
- Vorbereitet: `ActionPlanner`-Interface für On-Device-Action-Modell (z. B. Gemma-3-270M-Finetune via MediaPipe LLM Inference) für lokale Gerätesteuerung ohne Server-Roundtrip

#### Mikro-Phase 2.5: Windows-App MVP
- Analog zu Android, aber Desktop; System-Tray-Integration
- Konsumiert dasselbe Protokoll (`docs/PROTOCOL.md`) und dieselben Karten-Layouts (4.12)
- **Erfolg:** Wake Word und Modal auch am PC.

---

### **PHASE 3 – Screenshot-Analyse**
(unverändert. Kompatibilität: Das WebSocket-Protokoll wird bei Bedarf um eine `image_input`-Nachricht erweitert — Envelope-Design lässt das zu, App-seitig dann Kamera/Share-Target ergänzen.)

### **PHASE 4 – Basis-User- & Rechte-System**
(unverändert. Die App nutzt bereits Token-Login mit `device_name` und gespeichertem Tier — 4.1/4.2 füllen die Server-Seite, der App-Vertrag bleibt stabil.)

### **PHASE 5 – Stimmerkennung & komplexes Rechte-Management**
(unverändert. Die App hat den Einstiegspunkt "Stimmprofil trainieren" in den AI-Einstellungen bereits als Platzhalter; Trainings-Upload wird als REST-Endpoint ergänzt.)

### **PHASE 6 – Erweiterungen**
(unverändert)

---

## 6. Offene Punkte / Recherche-Aufgaben

- [ ] **Finale Modellwahl** (Gemma 4 E4B vs. Qwen 3 vs. Llama 3.1) – Benchmark im Live-Setup nach Mikro-Phase 1.11
- [ ] TTS: Evaluation Kokoro, StyleTTS2, Orpheus TTS
- [x] ~~Wake-Word-Engine~~ → **Porcupine** für Android (4.11); openWakeWord/microWakeWord bleibt Kandidat für ESP32
- [ ] Eigene Wake-Word-Phrase definieren → bei Porcupine: Custom-`.ppn` über Picovoice-Konsole trainieren
- [ ] Konkrete Voice-Samples für XTTS-v2 erstellen (deutsch)
- [x] ~~App-Tech-Stack finalisieren~~ → Kotlin + Compose (4.11)
- [ ] **Lokales DNS mit Zertifikat** – für `wss://` aus der App heraus relevant, sobald HTTPS erzwungen wird (Cleartext-HTTP im LAN funktioniert, ist aber nur Übergangslösung)
- [ ] **Upgrade auf `multilingual-e5-large`** sobald die VM mehr RAM hat
- [ ] **Slim-Image für Embedding-Sidecar** evaluieren
- [ ] **e5-Präfixe im Voice-Orchestrator implementieren** (4.9, Phase 1.7+)
- [ ] **Hybrid Search evaluieren**
- [ ] **BM25-Stopwords auf Deutsch konfigurieren**
- [ ] **NEU: Release-Signing der APK** – aktuell Debug-Signatur (für Sideload ok); für saubere Updates eigenen Keystore anlegen und im CI signieren
- [ ] **NEU: On-Device-Action-Modell** (Gemma-3-270M-Finetune / "Mobile Actions") via MediaPipe LLM Inference in den `ActionPlanner` integrieren — prüfen, sobald ein offizielles Modell-Artefakt verfügbar ist
- [ ] **NEU: Binär-Frames statt Base64** für Audio im WebSocket evaluieren (~33 % Overhead; im LAN unkritisch, daher v1 bewusst simpel)

---

## 7. Bekannte Probleme

(Einträge aus v1.4 unverändert: LiteLLM reset_budget_job, Open WebUI leere Tool-Ergebnisse, LiteLLM MCP `_experimental`, Coolify Image-Cleanup, Traefik Port 80, kein wget im Inference-Image, e5-Hochbias)

### Android: Mic-FGS-Restriktionen ab Android 15 (NEU in v1.5)
**Symptom:** Wake-Word-Service startet nach Reboot nicht automatisch.
**Ursache:** Microphone-Foreground-Services dürfen ab Android 15 nicht mehr aus `BOOT_COMPLETED` heraus starten.
**Workaround:** App nach Reboot einmal öffnen. Zusätzlich OEM-Battery-Optimierung für die App deaktivieren (sonst killt z. B. MIUI/One UI den Service).

### Android: Ein Mikrofon-Konsument zur Zeit (NEU in v1.5)
**Symptom:** Wake-Word-Erkennung und Assistant-Aufnahme können nicht gleichzeitig laufen.
**Status:** Gelöst per Design — das Assistant-Popup pausiert die Wake-Word-Engine bei Öffnen und reaktiviert sie beim Schließen.

---

## 8. Wichtige Constraints & Reminder

(alle aus v1.4 unverändert, zusätzlich:)
- **Protokoll-Vertrag ist fixiert** – Änderungen an `docs/PROTOCOL.md` müssen App UND Orchestrator berücksichtigen (App ist bereits gebaut!)
- **Karten-Layouts sind plattformneutral** – kein Android-spezifisches Feature in Templates einbauen, Windows rendert dasselbe JSON
- **Geräte-Tools sind die dokumentierte Ausnahme vom MCP-First-Prinzip** (session-gebunden, siehe 4.6)

---

## 9. Kommunikationsregeln für Claude

(unverändert)

---

**Version:** 1.5
**Stand:** 2026-06-12
**Changelog:**
- v1.5 (2026-06-12): **Phase 2 (Android, Mikro-Phasen 2.1–2.4) app-seitig komplett umgesetzt** (Repo `Android-AI-Assistant-App`). Neue Architektur-Sektionen: 4.11 App-Tech-Stack (Kotlin+Compose, Porcupine), 4.12 Karten-System & Card-Layout-Server (zentrale Verwaltung, Layouts ohne App-Update), 4.13 App↔Orchestrator-Protokoll inkl. Geräte-Tool-Bridge. Mikro-Phase 1.7/1.11 um die Protokoll-Implementierung erweitert, 1.14 relativiert. Tiered-Security-Umsetzung in der App dokumentiert (4.4). Zwei neue bekannte Probleme (Android-15-FGS, Mikrofon-Exklusivität). Offene Punkte aktualisiert (Wake-Word-Engine & App-Stack entschieden; neu: Release-Signing, On-Device-Action-Modell, Binär-Frames).
- v1.4 (2026-06-04): Mikro-Phase 1.5b abgeschlossen, 1.5c eingefügt, Sektionen 4.9/4.10 neu.
- v1.3 (2026-05-29): 1.5a/b aufgeteilt, Sektionen 4.7/4.8 neu.
- v1.2 (2026-05-11): 1.4 abgeschlossen, LiteLLM als MCP-Gateway.
- v1.1 (2026-04-29): Headscale als "noch nicht installiert" markiert.

**Nächster Schritt:** Mikro-Phase 1.5c (Retention-Workflow in n8n) — danach 1.7 inkl. Protokoll-Endpoints, damit die App End-to-End nutzbar wird.
