package de.heimai.app.core.network

import android.media.MediaRecorder
import android.util.Base64
import de.heimai.app.audio.AudioPlayer
import de.heimai.app.audio.AudioSessionController
import de.heimai.app.audio.AudioStreamer
import de.heimai.app.audio.TtsFallback
import de.heimai.app.core.model.CardEnvelope
import de.heimai.app.core.settings.SettingsRepository
import de.heimai.app.tools.DeviceToolExecutor
import de.heimai.app.tools.DeviceToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

data class UiMessage(
    val id: Long,
    /** user | assistant | card | info */
    val role: String,
    val text: String = "",
    val card: CardEnvelope? = null,
    val final: Boolean = true,
)

data class SessionState(
    val connection: ConnectionState = ConnectionState.DISCONNECTED,
    val listening: Boolean = false,
    val speaking: Boolean = false,
    val partialTranscript: String = "",
    val messages: List<UiMessage> = emptyList(),
    val error: String? = null,
)

/**
 * Eine Assistenz-Sitzung gegen den Voice-Orchestrator:
 * WebSocket {server}/v1/assistant/stream (Protokoll: docs/PROTOCOL.md).
 *
 * Übernimmt Audio-Streaming (PCM16/16k rein, PCM16 raus), Transkript- und
 * Text-Streaming, Card-Push und die Tool-Calling-Bridge: Der Server-LLM
 * kann die in [DeviceToolRegistry] deklarierten Geräte-Tools aufrufen,
 * die Ausführung passiert lokal in [DeviceToolExecutor].
 */
class AssistantSession(
    private val scope: CoroutineScope,
    private val client: OkHttpClient,
    private val json: Json,
    private val settings: SettingsRepository,
    private val toolExecutor: DeviceToolExecutor,
    private val tts: TtsFallback,
    private val audioSession: AudioSessionController,
    /** chat | talk | assist — rein informativ für den Server */
    private val mode: String,
    /** Server-Konversation fortsetzen (zentrale Historie); null = neues Gespräch */
    initialConversationId: String? = null,
    private val listener: Listener? = null,
) {
    interface Listener {
        fun onUserText(text: String) {}
        fun onAssistantText(text: String) {}
        fun onCard(card: CardEnvelope) {}
        /** Server hat dieser Sitzung eine Konversation zugeordnet (zentrale Historie). */
        fun onConversation(id: String) {}
    }

    /** Vom Server vergebene/fortgesetzte Konversations-Id (zentrale Historie). */
    @Volatile
    var conversationId: String? = initialConversationId
        private set

    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    /** Aktueller Mikrofon-Pegel (RMS) im Realtime Talk — für die Pegelanzeige/Kalibrierung. */
    private val _micLevel = MutableStateFlow(0f)
    val micLevel: StateFlow<Float> = _micLevel.asStateFlow()

    private val nextId = AtomicLong(1)
    private var webSocket: WebSocket? = null
    private var micJob: Job? = null
    private val streamer = AudioStreamer()
    private val player = AudioPlayer()

    /** true, sobald der Server für den aktuellen Turn Audio geliefert hat */
    @Volatile private var serverAudioThisTurn = false
    /** true, wenn die aktuelle Anfrage per Mikrofon (Audio) rausging, nicht per Text */
    @Volatile private var userAudioThisTurn = false
    /** true, solange eine Sprech-Äußerung läuft (für sauberes audio_end) */
    @Volatile private var utteranceActive = false
    /** Zeitpunkt des letzten abgespielten Server-Audio-Chunks (Echo-Ausklang-Schutz) */
    @Volatile private var lastServerAudioAtMs = 0L
    /** Live einstellbare VAD-Parameter (Regler in der Talk-Oberfläche greifen sofort). */
    @Volatile private var vadThreshold = 400.0
    @Volatile private var vadHalfDuplex = false
    /** true, solange der Kommunikations-Audiomodus (Geräte-AEC) für diese Sitzung aktiv ist. */
    private var commActive = false
    private var currentAssistantText = StringBuilder()
    private var currentAssistantMsgId: Long? = null

    fun connect() {
        if (webSocket != null) return
        _state.value = _state.value.copy(connection = ConnectionState.CONNECTING, error = null)
        scope.launch {
            val s = settings.current()
            if (s.serverUrl.isBlank()) {
                fail("Kein Server konfiguriert")
                return@launch
            }
            val wsUrl = s.serverUrl
                .replaceFirst("http://", "ws://")
                .replaceFirst("https://", "wss://") + "/v1/assistant/stream"
            val request = Request.Builder()
                .url(wsUrl)
                .header("Authorization", "Bearer ${s.authToken}")
                .build()
            webSocket = client.newWebSocket(request, SocketListener())
        }
    }

    fun disconnect() {
        stopListening()
        player.release()
        webSocket?.close(1000, "bye")
        webSocket = null
        _state.value = _state.value.copy(connection = ConnectionState.DISCONNECTED, listening = false)
    }

    fun sendText(text: String) {
        if (text.isBlank()) return
        addMessage(UiMessage(nextId.getAndIncrement(), "user", text))
        listener?.onUserText(text)
        // Texteingabe: KEIN TTS-Fallback (Server antwortet hier bewusst ohne Audio)
        userAudioThisTurn = false
        serverAudioThisTurn = false
        send(buildJsonObject {
            put("type", "text_input")
            put("text", text)
        })
    }

    /**
     * Mikrofon streamen.
     * @param continuous   false = Push-to-Talk (streamt bis stopListening()).
     *                     true  = Realtime Talk: erkennt Sprechpausen selbst und
     *                             sendet nach [silenceMs] Stille automatisch audio_end.
     * @param silenceMs    Stille-Dauer bis zum automatischen Senden.
     * @param thresholdRms Lautstärke-Schwelle (RMS), ab der Sprache zählt (kalibrierbar).
     * @param halfDuplex   true = Mikrofon während der eigenen Antwort ignorieren
     *                     (kein Barge-in, dafür garantiert kein Selbst-Mithören).
     * @param aec          true = Kommunikations-Audiomodus + Voice-Call-Aufnahme/-Wiedergabe,
     *                     damit die geräteeigene Echo-Unterdrückung greift (Full-Duplex).
     */
    fun startListening(
        continuous: Boolean = false,
        silenceMs: Int = 900,
        thresholdRms: Int = 400,
        halfDuplex: Boolean = false,
        aec: Boolean = false,
    ) {
        if (micJob != null) return
        vadThreshold = thresholdRms.toDouble()
        vadHalfDuplex = halfDuplex
        // Kommunikationsmodus + Voice-Call-Routing aktivieren, BEVOR das Mikrofon öffnet,
        // damit die Geräte-AEC das Wiedergabesignal als Referenz kennt (nur Realtime Talk).
        val useAec = continuous && aec
        if (useAec) {
            audioSession.enterCommunication()
            commActive = true
        }
        player.communication = useAec
        interruptPlayback()
        _state.value = _state.value.copy(listening = true, partialTranscript = "")
        // Turn-Flags werden bewusst NICHT hier gesetzt, sondern erst beim
        // tatsächlichen Senden (sendAudioChunk/sendAudioEnd) — sonst würde ein
        // Barge-in die Buchhaltung des noch offenen vorigen Turns überschreiben.
        //
        // Mit AEC: VOICE_COMMUNICATION (Voice-Call-Pfad, Geräte-Echo-Unterdrückung greift).
        // Ohne AEC: VOICE_RECOGNITION (rohere, lautere Pegel für die VAD).
        val source = if (useAec) MediaRecorder.AudioSource.VOICE_COMMUNICATION
        else MediaRecorder.AudioSource.VOICE_RECOGNITION
        micJob = scope.launch {
            val silenceLimit = (silenceMs / CHUNK_MS).coerceAtLeast(1)
            var inUtterance = false
            var silenceChunks = 0
            var bargeChunks = 0
            val preRoll = ArrayDeque<ByteArray>()
            try {
                // Im Kommunikationsmodus macht die Plattform AEC/NS — keine zweite Software-AEC
                // darauflegen (sonst Audio-HAL-Instabilität/Absturz).
                streamer.stream(source, enableEffects = !useAec).collect { chunk ->
                    if (!continuous) {
                        sendAudioChunk(chunk)
                        return@collect
                    }
                    val rms = rmsOf(chunk)
                    _micLevel.value = rms.toFloat()
                    val threshold = vadThreshold
                    val isVoice = rms > threshold
                    // Echo-Schutzfenster: solange die eigene Antwort läuft ODER der lokale
                    // Audio-Puffer noch abspielt (Server-audio_end kommt bis ~1 s bevor der
                    // Lautsprecher wirklich still ist!) — plus Nachhall-Tail danach.
                    val now = System.currentTimeMillis()
                    val playbackActive = _state.value.speaking || player.isDraining()
                    if (playbackActive) lastServerAudioAtMs = now
                    val guarding = playbackActive ||
                        (now - lastServerAudioAtMs) < PLAYBACK_TAIL_MS

                    // Half-Duplex: während der eigenen Antwort Mikrofon komplett ignorieren
                    // (kein Barge-in, dafür garantiert kein Selbst-Mithören).
                    if (vadHalfDuplex && guarding) {
                        bargeChunks = 0
                        if (inUtterance) { inUtterance = false; silenceChunks = 0; preRoll.clear() }
                        return@collect
                    }

                    if (!inUtterance) {
                        preRoll.addLast(chunk)
                        if (preRoll.size > PREROLL_CHUNKS) preRoll.removeFirst()
                        // Unterbrechung während laufender Antwort muss deutlich lauter sein (Echo-Schutz)
                        val startsUtterance = if (guarding) rms > threshold * BARGE_LOUD_FACTOR else isVoice
                        if (startsUtterance) {
                            if (guarding) {
                                bargeChunks++
                                if (bargeChunks < BARGE_MIN_CHUNKS) return@collect
                                interruptPlayback()
                            }
                            bargeChunks = 0
                            inUtterance = true
                            silenceChunks = 0
                            while (preRoll.isNotEmpty()) sendAudioChunk(preRoll.removeFirst())
                        } else {
                            bargeChunks = 0
                        }
                    } else {
                        sendAudioChunk(chunk)
                        if (isVoice) {
                            silenceChunks = 0
                        } else {
                            silenceChunks++
                            if (silenceChunks >= silenceLimit) {
                                sendAudioEnd()
                                inUtterance = false
                                silenceChunks = 0
                                preRoll.clear()
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e // Normales Ende via stopListening() — kein Fehler
            } catch (e: Exception) {
                fail("Mikrofon-Fehler: ${e.message}")
            } finally {
                _micLevel.value = 0f
            }
        }
    }

    /** VAD-Parameter live ändern (Regler in der Talk-Oberfläche), ohne die Aufnahme neu zu starten. */
    fun updateVad(thresholdRms: Int, halfDuplex: Boolean) {
        vadThreshold = thresholdRms.toDouble()
        vadHalfDuplex = halfDuplex
    }

    fun stopListening() {
        micJob?.cancel()
        micJob = null
        // Nur abschließen, wenn wirklich eine Äußerung offen war
        if (_state.value.listening && utteranceActive) {
            sendAudioEnd()
        }
        if (commActive) {
            audioSession.exitCommunication()
            player.communication = false
            commActive = false
        }
        _state.value = _state.value.copy(listening = false)
    }

    /** Barge-in: laufende Sprachausgabe abbrechen. */
    fun interruptPlayback() {
        player.stop()
        tts.stop()
        if (_state.value.speaking) {
            send(buildJsonObject { put("type", "interrupt") })
        }
        _state.value = _state.value.copy(speaking = false)
    }

    // ---- intern ----

    private fun sendHello() {
        scope.launch {
            val s = settings.current()
            send(buildJsonObject {
                put("type", "hello")
                put("mode", mode)
                put("voice_id", s.voiceId)
                // Zentrale Historie: bestehendes Gespräch serverseitig fortsetzen
                conversationId?.let { put("conversation_id", it) }
                put("device", buildJsonObject {
                    put("platform", "android")
                    put("name", android.os.Build.MODEL)
                    put("app_version", "0.1.0")
                })
                put("capabilities", buildJsonObject {
                    put("audio_in", "pcm16_16000")
                    put("audio_out", "pcm16")
                    put("cards", true)
                })
                put("tools", DeviceToolRegistry.manifest())
            })
        }
    }

    private fun handleMessage(text: String) {
        runCatching { dispatchMessage(text) }
            .onFailure { android.util.Log.w("AssistantSession", "Nachricht verworfen: ${it.message}") }
    }

    private fun dispatchMessage(text: String) {
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        when (obj["type"]?.jsonPrimitive?.content) {
            "transcript" -> {
                val t = obj["text"]?.jsonPrimitive?.content.orEmpty()
                val final = obj["final"]?.jsonPrimitive?.boolean ?: false
                if (final) {
                    _state.value = _state.value.copy(partialTranscript = "")
                    if (t.isNotBlank()) {
                        addMessage(UiMessage(nextId.getAndIncrement(), "user", t))
                        listener?.onUserText(t)
                    }
                } else {
                    _state.value = _state.value.copy(partialTranscript = t)
                }
            }
            "assistant_text" -> {
                val t = obj["text"]?.jsonPrimitive?.content.orEmpty()
                val final = obj["final"]?.jsonPrimitive?.boolean ?: false
                if (final) {
                    val full = if (t.isNotBlank()) t else currentAssistantText.toString()
                    upsertAssistantMessage(full, final = true)
                    listener?.onAssistantText(full)
                    currentAssistantText = StringBuilder()
                    currentAssistantMsgId = null
                } else {
                    currentAssistantText.append(t)
                    upsertAssistantMessage(currentAssistantText.toString(), final = false)
                }
            }
            "audio_chunk" -> {
                serverAudioThisTurn = true
                lastServerAudioAtMs = System.currentTimeMillis()
                _state.value = _state.value.copy(speaking = true)
                val data = obj["data"]?.jsonPrimitive?.content ?: return
                val rate = obj["sample_rate"]?.jsonPrimitive?.int ?: 24_000
                player.play(Base64.decode(data, Base64.NO_WRAP), rate)
            }
            "audio_end" -> _state.value = _state.value.copy(speaking = false)
            "conversation" -> {
                val id = obj["conversation_id"]?.jsonPrimitive?.content ?: return
                conversationId = id
                listener?.onConversation(id)
            }
            "card" -> {
                val cardObj = obj["card"] ?: return
                runCatching {
                    json.decodeFromJsonElement(CardEnvelope.serializer(), cardObj)
                }.getOrNull()?.let { card ->
                    addMessage(UiMessage(nextId.getAndIncrement(), "card", card = card))
                    listener?.onCard(card)
                }
            }
            "tool_call" -> {
                val callId = obj["call_id"]?.jsonPrimitive?.content ?: return
                val name = obj["name"]?.jsonPrimitive?.content ?: return
                val args = obj["arguments"] as? JsonObject ?: JsonObject(emptyMap())
                scope.launch {
                    val result = toolExecutor.execute(name, args)
                    send(buildJsonObject {
                        put("type", "tool_result")
                        put("call_id", callId)
                        put("ok", result.ok)
                        put("result", result.payload)
                    })
                }
            }
            "done" -> {
                _state.value = _state.value.copy(speaking = false)
                maybeTtsFallback()
            }
            "error" -> fail(obj["message"]?.jsonPrimitive?.content ?: "Unbekannter Server-Fehler")
        }
    }

    /**
     * TTS-Fallback: NUR wenn die Anfrage per Mikrofon rausging UND der Server
     * kein eigenes Audio geliefert hat. Bei Texteingaben antwortet der Server
     * bewusst ohne Audio — dann wird NICHT vorgelesen.
     */
    private fun maybeTtsFallback() {
        if (!userAudioThisTurn) return
        if (serverAudioThisTurn) return
        val lastAssistant = _state.value.messages.lastOrNull { it.role == "assistant" } ?: return
        scope.launch {
            if (settings.current().ttsFallbackEnabled) tts.speak(lastAssistant.text)
        }
    }

    private fun sendAudioChunk(chunk: ByteArray) {
        // Ab dem ersten tatsächlich gesendeten Chunk gilt die Äußerung als aktiv
        // (verhindert leeres audio_end bei sofortigem Stopp im Push-to-Talk).
        utteranceActive = true
        userAudioThisTurn = true
        send(buildJsonObject {
            put("type", "audio_chunk")
            put("data", Base64.encodeToString(chunk, Base64.NO_WRAP))
        })
    }

    /**
     * Schließt die aktuelle Äußerung ab. serverAudioThisTurn wird hier (Grenze zur
     * neuen Server-Antwort) zurückgesetzt — NICHT beim Äußerungsbeginn, damit ein
     * Barge-in die Audio-Buchhaltung des noch offenen vorigen Turns nicht verfälscht.
     */
    private fun sendAudioEnd() {
        utteranceActive = false
        userAudioThisTurn = true
        serverAudioThisTurn = false
        send(buildJsonObject { put("type", "audio_end") })
    }

    /** RMS-Lautstärke eines PCM16-mono-Chunks (Little-Endian) für die Sprechpausen-Erkennung. */
    private fun rmsOf(chunk: ByteArray): Double {
        var sum = 0.0
        var count = 0
        var i = 0
        while (i + 1 < chunk.size) {
            val sample = (chunk[i].toInt() and 0xff) or (chunk[i + 1].toInt() shl 8)
            sum += sample.toDouble() * sample.toDouble()
            count++
            i += 2
        }
        return if (count == 0) 0.0 else sqrt(sum / count)
    }

    private fun upsertAssistantMessage(text: String, final: Boolean) {
        val id = currentAssistantMsgId
        if (id == null) {
            val newId = nextId.getAndIncrement()
            currentAssistantMsgId = newId
            addMessage(UiMessage(newId, "assistant", text, final = final))
        } else {
            _state.value = _state.value.copy(
                messages = _state.value.messages.map {
                    if (it.id == id) it.copy(text = text, final = final) else it
                }
            )
        }
    }

    private fun addMessage(message: UiMessage) {
        _state.value = _state.value.copy(messages = _state.value.messages + message)
    }

    private fun send(payload: JsonObject) {
        webSocket?.send(payload.toString())
    }

    private fun fail(message: String) {
        _state.value = _state.value.copy(connection = ConnectionState.ERROR, error = message)
    }

    private inner class SocketListener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            _state.value = _state.value.copy(connection = ConnectionState.CONNECTED, error = null)
            sendHello()
        }

        override fun onMessage(webSocket: WebSocket, text: String) = handleMessage(text)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            this@AssistantSession.webSocket = null
            fail("Verbindung fehlgeschlagen: ${t.message}")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            this@AssistantSession.webSocket = null
            _state.value = _state.value.copy(connection = ConnectionState.DISCONNECTED)
        }
    }

    private companion object {
        const val CHUNK_MS = 100                 // Dauer eines Audio-Chunks (AudioStreamer)
        const val BARGE_LOUD_FACTOR = 2.5        // Unterbrechung während Wiedergabe muss deutlich lauter sein (Echo-Schutz)
        const val PREROLL_CHUNKS = 3             // ~300 ms Vorlauf vor Sprechbeginn mitsenden
        const val BARGE_MIN_CHUNKS = 3           // ~300 ms Stimme nötig, um laufende Antwort zu unterbrechen
        const val PLAYBACK_TAIL_MS = 800L        // Nachhall-Fenster NACH tatsächlichem Wiedergabe-Ende (Raumecho)
    }
}
