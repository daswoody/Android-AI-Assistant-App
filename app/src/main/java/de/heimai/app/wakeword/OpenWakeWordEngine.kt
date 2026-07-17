package de.heimai.app.wakeword

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.sqrt

/**
 * On-Device-Wake-Word-Erkennung mit **openWakeWord** (frei, keine Lizenz/kein
 * AccessKey nötig — Ersatz für Picovoice Porcupine).
 *
 * Pipeline (alles ONNX Runtime, CPU, 1 Thread):
 *   16-kHz-Audio → melspectrogram.onnx → embedding_model.onnx → <wakeword>.onnx → Wahrscheinlichkeit
 *
 * **Warum ONNX Runtime statt TensorFlow Lite:** Die openWakeWord-Modelle tragen
 * dynamische Eingabe-Dimensionen. TFLite-Java allokiert die Tensoren aber schon
 * im Interpreter-Konstruktor — vor jedem möglichen resizeInput() — und stirbt
 * daran ("BytesRequired number of elements overflowed", CONV_2D failed to
 * prepare; openWakeWord#223, tensorflow#57131). ONNX Runtime ist ohnehin das
 * primäre Inferenz-Framework von openWakeWord und kommt mit dynamischen Shapes
 * nativ klar: die konkrete Form wird pro run() vom Eingabe-Tensor bestimmt.
 *
 * **Energiesparen (Wunsch des Nutzers):** Ein billiges RMS-Energie-Gate läuft immer;
 * die teure ML-Pipeline wird NUR ausgeführt, wenn der Mikrofonpegel eine Schwelle
 * übersteigt (plus kurze Nachlaufzeit). Bei Stille schläft die ML komplett — dann
 * kostet nur das offene Mikrofon + RMS Strom. Ein kleiner Pre-Roll-Puffer stellt
 * sicher, dass der (oft leise) Wortanfang trotzdem mitverarbeitet wird.
 *
 * Hinweis: Die exakten Erkennungs-Parameter (Normalisierung, Schrittweiten) folgen
 * der openWakeWord-Referenz; Ausgabe-Formen werden zur Laufzeit per Probelauf aus
 * den Modellen gelesen, damit Modell-Versionen robust unterstützt werden.
 */
class OpenWakeWordEngine(
    private val context: Context,
    /** Asset-Dateiname (in assets/openwakeword) ODER absoluter Pfad bei eigenem Modell. */
    private val wakeWordModel: String,
    private val customModelPath: String,
    /** Auslöseschwelle 0..1. */
    private val threshold: Float,
    private val energyGate: Boolean,
    /** RMS-Schwelle des Energie-Gates. */
    private val gateRms: Double,
    private val onDetected: () -> Unit,
    /**
     * Engine ist endgültig gestorben (Modell fehlt, Mikrofon belegt, Loop-Absturz).
     * WICHTIG für die Diagnose: Ohne diesen Kanal bleibt die Foreground-Notification
     * stehen, obwohl nichts mehr lauscht — genau das Symptom "läuft angeblich,
     * aber keine Logs".
     */
    private val onFatal: (String) -> Unit = {},
) : WakeWordEngine {

    private var thread: Thread? = null
    @Volatile private var running = false

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var melSession: OrtSession? = null
    private var embSession: OrtSession? = null
    private var wwSession: OrtSession? = null

    override fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "openWakeWord").apply { priority = Thread.NORM_PRIORITY - 1; start() }
    }

    override fun stop() {
        running = false
        thread?.let { runCatching { it.join(500) } }
        thread = null
    }

    override fun release() {
        stop()
        runCatching { melSession?.close() }
        runCatching { embSession?.close() }
        runCatching { wwSession?.close() }
        melSession = null; embSession = null; wwSession = null
    }

    // ---- Modell-Laden ----

    private fun sessionOptions(): OrtSession.SessionOptions =
        OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(1)
            setInterOpNumThreads(1)
        }

    private fun loadAsset(name: String): ByteArray =
        context.assets.open("openwakeword/$name").use { it.readBytes() }

    /**
     * Alle drei Modelle laden. [stage] benennt im Fehlerfall das Modell,
     * das tatsächlich gescheitert ist.
     */
    private fun setup() {
        var stage = "melspectrogram"
        try {
            melSession = env.createSession(loadAsset("melspectrogram.onnx"), sessionOptions())
            stage = "embedding"
            embSession = env.createSession(loadAsset("embedding_model.onnx"), sessionOptions())
            stage = if (customModelPath.isNotBlank()) "custom" else wakeWordModel
            wwSession =
                if (customModelPath.isNotBlank()) env.createSession(File(customModelPath).readBytes(), sessionOptions())
                else env.createSession(loadAsset(wakeWordModel), sessionOptions())
        } catch (e: Exception) {
            throw RuntimeException("[$stage] ${e.message}", e)
        }
    }

    // ---- Verarbeitungs-Schleife ----

    @SuppressLint("MissingPermission")
    private fun loop() {
        Log.i(TAG, "Engine-Thread gestartet (Modell=$wakeWordModel, custom=${customModelPath.isNotBlank()})")
        WakeWordDiagnostics.starting()
        try {
            setup()
        } catch (e: Exception) {
            Log.e(TAG, "openWakeWord-Modelle konnten nicht geladen werden", e)
            running = false
            WakeWordDiagnostics.error("Modell '$wakeWordModel': ${e.message}")
            onFatal("Wake-Word-Modell '$wakeWordModel' konnte nicht geladen werden: ${e.message}")
            return
        }

        // Ausgabe-Formen per Probelauf mit Stille ermitteln (Modelle sind dynamisch)
        val melBins: Int
        val framesPerChunk: Int
        val embOutFloats: Int
        try {
            val probe = runMel(ShortArray(CHUNK), CHUNK)
            melBins = probe.first().size
            framesPerChunk = probe.size
            val zeroMels = ArrayDeque<FloatArray>().apply { repeat(EMB_WINDOW) { addLast(FloatArray(melBins)) } }
            embOutFloats = runEmbedding(zeroMels, melBins).size
        } catch (e: Exception) {
            Log.e(TAG, "Modell-Probelauf fehlgeschlagen", e)
            running = false
            WakeWordDiagnostics.error("Probelauf: ${e.message}")
            onFatal("Wake-Word-Modelle nicht lauffähig: ${e.message}")
            return
        }

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, CHUNK * 4),
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord nicht initialisierbar (Mikrofon belegt?)")
            record.release()
            running = false
            WakeWordDiagnostics.error("Mikrofon nicht verfügbar")
            onFatal("Mikrofon nicht verfügbar (von anderer App/Session belegt?)")
            return
        }

        Log.i(
            TAG,
            "Modelle geladen: mel=${framesPerChunk}x$melBins emb=$embOutFloats " +
                "gate=$energyGate gateRms=$gateRms threshold=$threshold"
        )
        WakeWordDiagnostics.running(
            if (customModelPath.isNotBlank()) "Eigenes Modell" else wakeWordModel
        )

        val melFrames = ArrayDeque<FloatArray>()  // je melBins Werte
        val embeddings = ArrayDeque<FloatArray>() // je embOutFloats Werte
        var newMelFrames = 0
        var cooldownUntil = 0L

        // Puffer mit Nullen vorbefüllen (Referenzverhalten von openWakeWord):
        // Ohne Priming braucht die Pipeline erst 76 Mel-Frames + 16 Embeddings
        // (~3 s Dauer-Audio), bevor ÜBERHAUPT klassifiziert wird — nach jedem
        // Gate-Reset wäre das Wake Word damit faktisch taub. Mit Priming läuft
        // die erste Klassifikation schon nach dem ersten frischen Embedding.
        fun primeBuffers() {
            melFrames.clear(); embeddings.clear(); newMelFrames = 0
            repeat(EMB_WINDOW) { melFrames.addLast(FloatArray(melBins)) }
            repeat(WW_WINDOW - 1) { embeddings.addLast(FloatArray(embOutFloats)) }
        }
        primeBuffers()

        val preRoll = ArrayDeque<ShortArray>()    // Roh-Audio-Vorlauf für den Wortanfang
        var lastVoiceMs = 0L
        var wasActive = false
        var maxScore = 0f
        var lastScoreLogMs = 0L
        var lastDiagMs = 0L

        val audio = ShortArray(CHUNK)
        try {
            record.startRecording()
            while (running) {
                var read = 0
                while (read < CHUNK && running) {
                    val r = record.read(audio, read, CHUNK - read)
                    if (r <= 0) break
                    read += r
                }
                if (read < CHUNK) continue

                val now = System.currentTimeMillis()
                val rms = rmsOf(audio, read)

                // Debug-Hilfe (adb logcat -s OpenWakeWord): alle ~3 s höchster Score
                // seit dem letzten Log + aktueller Pegel + Gate-Zustand. maxScore=0.00
                // bei Sprache heißt: Klassifikator lief nicht (Gate öffnet nicht? rms
                // mit gateRms vergleichen) oder Modell erkennt nichts.
                if (now - lastScoreLogMs > 3_000) {
                    Log.i(TAG, "maxScore=%.2f rms=%.0f gateAktiv=%b".format(maxScore, rms, wasActive))
                    maxScore = 0f
                    lastScoreLogMs = now
                }
                // Live-Anzeige für die Einstellungs-UI (~5×/s reicht)
                if (now - lastDiagMs > 200) {
                    WakeWordDiagnostics.level(rms.toInt(), maxScore, wasActive || !energyGate)
                    lastDiagMs = now
                }

                // --- Energie-Gate ---
                if (energyGate) {
                    if (rms > gateRms) lastVoiceMs = now
                    val active = (now - lastVoiceMs) < GATE_HANGOVER_MS
                    if (!active) {
                        // ML schläft. Nur Pre-Roll pflegen.
                        preRoll.addLast(audio.copyOf(read))
                        while (preRoll.size > PREROLL_CHUNKS) preRoll.removeFirst()
                        wasActive = false
                        continue
                    }
                    if (!wasActive) {
                        // Übergang Stille → aktiv: Puffer frisch primen (Nullen),
                        // dann den Audio-Vorlauf durch die Melspec schieben — so ist
                        // der leise Wortanfang enthalten UND sofort klassifizierbar.
                        wasActive = true
                        primeBuffers()
                        for (chunk in preRoll) {
                            val frames = runMel(chunk, chunk.size)
                            frames.forEach { melFrames.addLast(it) }
                            newMelFrames += frames.size
                        }
                        preRoll.clear()
                    }
                }

                val frames = runMel(audio, read)
                frames.forEach { melFrames.addLast(it) }
                newMelFrames += frames.size
                while (melFrames.size > MEL_KEEP) melFrames.removeFirst()

                // --- Embeddings berechnen (alle EMB_STEP Mel-Frames, sobald 76 vorhanden) ---
                while (newMelFrames >= EMB_STEP && melFrames.size >= EMB_WINDOW) {
                    newMelFrames -= EMB_STEP
                    embeddings.addLast(runEmbedding(melFrames, melBins))
                    while (embeddings.size > EMB_KEEP) embeddings.removeFirst()
                }

                // --- Wake-Word-Klassifikator ---
                if (embeddings.size >= WW_WINDOW && now >= cooldownUntil) {
                    val prob = runWakeWord(embeddings, embOutFloats)
                    if (prob > maxScore) maxScore = prob
                    if (prob >= threshold) {
                        Log.i(TAG, "Wake Word erkannt (score=%.2f)".format(prob))
                        cooldownUntil = now + COOLDOWN_MS
                        primeBuffers()
                        runCatching { onDetected() }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Wake-Word-Schleife abgebrochen", e)
            WakeWordDiagnostics.error("Abgestürzt: ${e.message}")
            onFatal("Wake-Word-Erkennung abgestürzt: ${e.message}")
        } finally {
            runCatching { record.stop() }
            record.release()
            // ERROR bleibt stehen (siehe WakeWordDiagnostics.stopped)
            WakeWordDiagnostics.stopped()
        }
    }

    /**
     * Melspec über einen Audio-Chunk. @return neue Mel-Frames (je Mel-Bins Werte),
     * bereits openWakeWord-normalisiert (/10 + 2).
     */
    private fun runMel(audio: ShortArray, len: Int): List<FloatArray> {
        val floats = FloatArray(CHUNK) { i -> if (i < len) audio[i].toFloat() else 0f }
        OnnxTensor.createTensor(env, FloatBuffer.wrap(floats), longArrayOf(1, CHUNK.toLong())).use { input ->
            melSession!!.run(mapOf(melSession!!.inputNames.first() to input)).use { result ->
                val out = result.get(0) as OnnxTensor
                val bins = out.info.shape.last().toInt() // [1,1,frames,bins]
                val buf = out.floatBuffer
                val frameCount = buf.remaining() / bins
                return List(frameCount) { FloatArray(bins) { buf.get() / 10f + 2f } }
            }
        }
    }

    /** Embedding über die letzten EMB_WINDOW Mel-Frames. */
    private fun runEmbedding(melFrames: ArrayDeque<FloatArray>, melBins: Int): FloatArray {
        val floats = FloatArray(EMB_WINDOW * melBins)
        val start = melFrames.size - EMB_WINDOW
        var idx = 0
        var offset = 0
        for (frame in melFrames) {
            if (idx++ < start) continue
            frame.copyInto(floats, offset)
            offset += melBins
        }
        val shape = longArrayOf(1, EMB_WINDOW.toLong(), melBins.toLong(), 1)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(floats), shape).use { input ->
            embSession!!.run(mapOf(embSession!!.inputNames.first() to input)).use { result ->
                val buf = (result.get(0) as OnnxTensor).floatBuffer
                return FloatArray(buf.remaining()) { buf.get() }
            }
        }
    }

    /** Klassifikator über die letzten WW_WINDOW Embeddings → Wahrscheinlichkeit. */
    private fun runWakeWord(embeddings: ArrayDeque<FloatArray>, embDim: Int): Float {
        val floats = FloatArray(WW_WINDOW * embDim)
        val start = embeddings.size - WW_WINDOW
        var idx = 0
        var offset = 0
        for (e in embeddings) {
            if (idx++ < start) continue
            e.copyInto(floats, offset)
            offset += embDim
        }
        val shape = longArrayOf(1, WW_WINDOW.toLong(), embDim.toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(floats), shape).use { input ->
            wwSession!!.run(mapOf(wwSession!!.inputNames.first() to input)).use { result ->
                val buf = (result.get(0) as OnnxTensor).floatBuffer
                return buf.get(0)
            }
        }
    }

    private fun rmsOf(a: ShortArray, len: Int): Double {
        var sum = 0.0
        for (i in 0 until len) sum += a[i].toDouble() * a[i].toDouble()
        return if (len == 0) 0.0 else sqrt(sum / len)
    }

    companion object {
        private const val TAG = "OpenWakeWord"
        const val SAMPLE_RATE = 16_000
        const val CHUNK = 1280            // 80 ms Audio pro Melspec-Aufruf
        const val EMB_WINDOW = 76         // Mel-Frames pro Embedding-Fenster
        const val EMB_STEP = 8            // neues Embedding alle 8 Mel-Frames
        const val WW_WINDOW = 16          // Embeddings pro Klassifikator-Fenster
        const val MEL_KEEP = 120          // Mel-Frames vorhalten
        const val EMB_KEEP = 24           // Embeddings vorhalten
        const val COOLDOWN_MS = 2_000L    // Nach Auslösung kurz aussetzen
        const val GATE_HANGOVER_MS = 1_500L // Nach letztem Pegel-Peak noch aktiv bleiben
        const val PREROLL_CHUNKS = 8      // ~640 ms Vorlauf für den Wortanfang

        /** Mitgelieferte Wake-Word-Modelle (Anzeigename → Asset-Datei). */
        val BUILT_IN = linkedMapOf(
            "Alexa" to "alexa_v0.1.onnx",
            "Hey Jarvis" to "hey_jarvis_v0.1.onnx",
            "Hey Mycroft" to "hey_mycroft_v0.1.onnx",
        )
        const val DEFAULT_MODEL = "hey_jarvis_v0.1.onnx"
        const val CUSTOM = "CUSTOM"
    }
}
