package de.heimai.app.wakeword

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.sqrt

/**
 * On-Device-Wake-Word-Erkennung mit **openWakeWord** (frei, keine Lizenz/kein
 * AccessKey nötig — Ersatz für Picovoice Porcupine).
 *
 * Pipeline (alles TensorFlow Lite, CPU, 1 Thread):
 *   16-kHz-Audio → melspectrogram.tflite → embedding_model.tflite → <wakeword>.tflite → Wahrscheinlichkeit
 *
 * **Energiesparen (Wunsch des Nutzers):** Ein billiges RMS-Energie-Gate läuft immer;
 * die teure ML-Pipeline wird NUR ausgeführt, wenn der Mikrofonpegel eine Schwelle
 * übersteigt (plus kurze Nachlaufzeit). Bei Stille schläft die ML komplett — dann
 * kostet nur das offene Mikrofon + RMS Strom. Ein kleiner Pre-Roll-Puffer stellt
 * sicher, dass der (oft leise) Wortanfang trotzdem mitverarbeitet wird.
 *
 * Hinweis: Die exakten Erkennungs-Parameter (Normalisierung, Schrittweiten) folgen
 * der openWakeWord-Referenz; Tensor-Formen werden zur Laufzeit aus den Modellen
 * gelesen, damit Modell-Versionen robust unterstützt werden. Feintuning der
 * Schwelle geschieht auf dem Gerät.
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

    private var melModel: Interpreter? = null
    private var embModel: Interpreter? = null
    private var wwModel: Interpreter? = null

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
        runCatching { melModel?.close() }
        runCatching { embModel?.close() }
        runCatching { wwModel?.close() }
        melModel = null; embModel = null; wwModel = null
    }

    // ---- Modell-Laden ----

    private fun interpreter(buffer: ByteBuffer): Interpreter =
        Interpreter(buffer, Interpreter.Options().apply { numThreads = 1 })

    private fun loadAsset(name: String): ByteBuffer {
        context.assets.openFd("openwakeword/$name").use { fd ->
            FileInputStream(fd.fileDescriptor).channel.use { ch ->
                return ch.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
        }
    }

    private fun loadFile(path: String): ByteBuffer {
        FileInputStream(File(path)).channel.use { ch ->
            return ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size())
        }
    }

    private fun setup() {
        melModel = interpreter(loadAsset("melspectrogram.tflite"))
        embModel = interpreter(loadAsset("embedding_model.tflite"))
        wwModel = if (customModelPath.isNotBlank()) interpreter(loadFile(customModelPath))
        else interpreter(loadAsset(wakeWordModel))
        // melspectrogram erwartet festes Fenster [1, CHUNK]
        melModel!!.resizeInput(0, intArrayOf(1, CHUNK))
        melModel!!.allocateTensors()
    }

    // ---- Verarbeitungs-Schleife ----

    @SuppressLint("MissingPermission")
    private fun loop() {
        Log.i(TAG, "Engine-Thread gestartet (Modell=$wakeWordModel, custom=${customModelPath.isNotBlank()})")
        try {
            setup()
        } catch (e: Exception) {
            Log.e(TAG, "openWakeWord-Modelle konnten nicht geladen werden", e)
            running = false
            onFatal("Wake-Word-Modell '$wakeWordModel' konnte nicht geladen werden: ${e.message}")
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
            onFatal("Mikrofon nicht verfügbar (von anderer App/Session belegt?)")
            return
        }

        // Ausgabe-Formen der Modelle zur Laufzeit
        val melOutShape = melModel!!.getOutputTensor(0).shape() // z. B. [1,1,5,32]
        val melBins = melOutShape.last()
        val framesPerChunk = melOutShape[melOutShape.size - 2]
        val melOutFloats = melOutShape.fold(1) { a, b -> a * b }
        val embOutFloats = embModel!!.getOutputTensor(0).shape().fold(1) { a, b -> a * b } // ~96
        Log.i(
            TAG,
            "Modelle geladen: mel=${melOutShape.joinToString("x")} " +
                "emb=$embOutFloats gate=$energyGate gateRms=$gateRms threshold=$threshold"
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
                    Log.d(TAG, "maxScore=%.2f rms=%.0f gateAktiv=%b".format(maxScore, rms, wasActive))
                    maxScore = 0f
                    lastScoreLogMs = now
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
                            newMelFrames += pushMel(chunk, chunk.size, melModel!!, melOutFloats, framesPerChunk, melBins, melFrames)
                        }
                        preRoll.clear()
                    }
                }

                newMelFrames += pushMel(audio, read, melModel!!, melOutFloats, framesPerChunk, melBins, melFrames)
                while (melFrames.size > MEL_KEEP) melFrames.removeFirst()

                // --- Embeddings berechnen (alle EMB_STEP Mel-Frames, sobald 76 vorhanden) ---
                while (newMelFrames >= EMB_STEP && melFrames.size >= EMB_WINDOW) {
                    newMelFrames -= EMB_STEP
                    embeddings.addLast(runEmbedding(melFrames, melBins, embModel!!, embOutFloats))
                    while (embeddings.size > EMB_KEEP) embeddings.removeFirst()
                }

                // --- Wake-Word-Klassifikator ---
                if (embeddings.size >= WW_WINDOW && now >= cooldownUntil) {
                    val prob = runWakeWord(embeddings, embOutFloats, wwModel!!)
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
            onFatal("Wake-Word-Erkennung abgestürzt: ${e.message}")
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }

    /** Melspec über einen Audio-Chunk, hängt die Frames an [out] an. @return Anzahl neuer Frames. */
    private fun pushMel(
        audio: ShortArray, len: Int, mel: Interpreter, melOutFloats: Int,
        framesPerChunk: Int, melBins: Int, out: ArrayDeque<FloatArray>,
    ): Int {
        val input = floatBuffer(CHUNK)
        for (i in 0 until CHUNK) input.putFloat(if (i < len) audio[i].toFloat() else 0f)
        input.rewind()
        val output = byteFloatBuffer(melOutFloats)
        mel.run(input, output)
        output.rewind()
        // openWakeWord-Normalisierung
        for (f in 0 until framesPerChunk) {
            val frame = FloatArray(melBins)
            for (b in 0 until melBins) frame[b] = output.getFloat() / 10f + 2f
            out.addLast(frame)
        }
        return framesPerChunk
    }

    /** Embedding über die letzten EMB_WINDOW Mel-Frames. */
    private fun runEmbedding(
        melFrames: ArrayDeque<FloatArray>, melBins: Int, emb: Interpreter, embOutFloats: Int,
    ): FloatArray {
        val input = byteFloatBuffer(EMB_WINDOW * melBins)
        val start = melFrames.size - EMB_WINDOW
        var idx = 0
        for (frame in melFrames) {
            if (idx++ < start) continue
            for (b in 0 until melBins) input.putFloat(frame[b])
        }
        input.rewind()
        val output = byteFloatBuffer(embOutFloats)
        emb.run(input, output)
        output.rewind()
        return FloatArray(embOutFloats) { output.getFloat() }
    }

    /** Klassifikator über die letzten WW_WINDOW Embeddings → Wahrscheinlichkeit. */
    private fun runWakeWord(
        embeddings: ArrayDeque<FloatArray>, embOutFloats: Int, ww: Interpreter,
    ): Float {
        val input = byteFloatBuffer(WW_WINDOW * embOutFloats)
        val start = embeddings.size - WW_WINDOW
        var idx = 0
        for (e in embeddings) {
            if (idx++ < start) continue
            for (v in e) input.putFloat(v)
        }
        input.rewind()
        val output = byteFloatBuffer(1)
        ww.run(input, output)
        output.rewind()
        return output.getFloat()
    }

    private fun rmsOf(a: ShortArray, len: Int): Double {
        var sum = 0.0
        for (i in 0 until len) sum += a[i].toDouble() * a[i].toDouble()
        return if (len == 0) 0.0 else sqrt(sum / len)
    }

    private fun floatBuffer(floats: Int): ByteBuffer =
        ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder())

    private fun byteFloatBuffer(floats: Int): ByteBuffer =
        ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder())

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
            "Alexa" to "alexa_v0.1.tflite",
            "Hey Jarvis" to "hey_jarvis_v0.1.tflite",
            "Hey Mycroft" to "hey_mycroft_v0.1.tflite",
        )
        const val DEFAULT_MODEL = "hey_jarvis_v0.1.tflite"
        const val CUSTOM = "CUSTOM"
    }
}
