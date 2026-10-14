# Heim-AI Android App

Begleit-App des Heim-AI-Projekts (Phase 2, Mikro-Phasen 2.1–2.4):
Sprach-Assistent für den eigenen, lokal gehosteten Voice-Orchestrator.

## Features

- **Server-Auswahl + Login** beim ersten Start (eigener Orchestrator, kein Cloud-Dienst)
- **Chat & Realtime Talk** gegen den Orchestrator (WebSocket, Audio-Streaming PCM16)
- **Wake Word** über Picovoice Porcupine (lokal, energiesparend, Foreground-Service)
- **System-Assistent:** App ist als "Digitaler Assistent" in Android auswählbar und
  öffnet ein Popup-Overlay über der laufenden App (wie der Google Assistant)
- **Karten-System:** Server-definierte Layout-Templates (JSON), zentral verteilbar
  über `GET /v1/cards/layouts` — neue Karten ohne App-Update (siehe `docs/CARDS.md`)
- **Geräte-Tools fürs LLM:** Apps öffnen, Navigation, Anruf, E-Mail, Kontakt anlegen,
  Websuche, Wecker, Benachrichtigungen auslesen (siehe `docs/PROTOCOL.md`)
- **Tiered Security:** sensible Tools mit Bestätigungsdialog oder "entsperrtes Gerät genügt"
- **On-Device-TTS-Fallback**, wenn der Server kein Audio liefert
- **Verlauf:** vergangene Gespräche (Chat/Talk/Assistant) lokal in Room

## Build

**Empfohlen — GitHub Actions:** Jeder Push baut die Debug-APK automatisch.
Actions-Tab → Lauf öffnen → Artifact **heimai-debug-apk** herunterladen.

**Lokal:** Android Studio (oder Android SDK + JDK 17) und dann:

```bash
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
adb install app/build/outputs/apk/debug/app-debug.apk
```

Die Debug-APK ist debug-signiert und per Sideload installierbar
(kein Play Store nötig).

## Einrichtung auf dem Gerät

1. App starten → **Server-URL** eingeben (z. B. `http://192.168.2.105:8200`)
2. **Login** (Vertrag: `POST /v1/auth/login`, siehe `docs/PROTOCOL.md`)
3. Einstellungen → AI-Einstellungen:
   - **Picovoice AccessKey** eintragen (kostenlos: https://console.picovoice.ai)
     und **Wake Word** aktivieren + Phrase wählen
   - **Als digitalen Assistenten festlegen** (System-Dialog)
   - **Über anderen Apps anzeigen** erlauben (damit das Wake Word das
     Popup direkt öffnen darf)
   - Optional: **Benachrichtigungszugriff** für das Zusammenfassungs-Tool

## Architektur (Kurzfassung)

```
app/src/main/java/de/heimai/app/
├── assistant/    VoiceInteractionService + Assistant-Popup (Overlay)
├── wakeword/     Porcupine-Engine + Foreground-Service + Boot-Receiver
├── core/
│   ├── network/  REST-Client + AssistantSession (WebSocket, Tool-Bridge)
│   ├── db/       Room (Gespräche, Nachrichten, Karten-Layout-Cache)
│   ├── model/    CardEnvelope, LayoutTemplate (plattformneutral)
│   └── settings/ DataStore (Server, Auth, Design, AI-Einstellungen)
├── cards/        Layout-Repository (Assets→Cache→Server) + Compose-Renderer
├── tools/        Geräte-Tools, Bestätigungs-Broker, NotificationListener
├── audio/        Mikrofon-Streaming, PCM-Player, TTS-Fallback
└── features/     Setup, Home, Chat, Talk, Settings (Jetpack Compose)
```

Der Orchestrator-Endpoint, gegen den die App spricht, ist in
`docs/PROTOCOL.md` spezifiziert (Server entsteht in Mikro-Phase 1.7+).

## Bekannte Plattform-Einschränkungen

- **Android 15+:** Microphone-Foreground-Services dürfen nicht direkt aus
  `BOOT_COMPLETED` starten — nach einem Reboot die App einmal öffnen.
- Das Wake Word benötigt ein dauerhaft offenes Mikrofon; Android zeigt den
  grünen Mikrofon-Indikator permanent an. Akku-Verbrauch ist gering
  (Porcupine), aber OEM-Batterie-Optimierungen (Xiaomi/Samsung etc.)
  können den Service beenden → App von der Akku-Optimierung ausnehmen.
- Während das Assistant-Popup zuhört, pausiert die Wake-Word-Erkennung
  (ein Mikrofon-Zugriff zur Zeit).
