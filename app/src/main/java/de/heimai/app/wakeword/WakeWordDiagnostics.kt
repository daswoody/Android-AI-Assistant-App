package de.heimai.app.wakeword

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Live-Zustand der Wake-Word-Engine für die Einstellungs-UI.
 *
 * Hintergrund: Der Dienst läuft unsichtbar im Hintergrund — ob die Engine
 * wirklich lauscht (und warum nicht), war bisher nur per adb logcat zu sehen.
 * Die Engine schreibt hier ihren Zustand hinein, die AI-Einstellungen zeigen
 * ihn live an: Status, geladenes Modell, Mikrofonpegel und Erkennungs-Score.
 * Kein Ersatz für die Fehler-Notification, sondern die Dauer-Anzeige dazu.
 */
object WakeWordDiagnostics {

    enum class State { OFF, STARTING, RUNNING, ERROR }

    data class Diag(
        val state: State = State.OFF,
        /** Modellname (RUNNING), Fehlergrund (ERROR) oder Hinweis (OFF). */
        val detail: String = "",
        /** Aktueller Mikrofonpegel (RMS) — nur bei RUNNING gepflegt. */
        val rms: Int = 0,
        /** Höchster Klassifikator-Score der letzten ~3 s (0..1). */
        val score: Float = 0f,
        /** Energie-Gate gerade offen (ML-Pipeline aktiv)? */
        val gateActive: Boolean = false,
        /** Effektive (adaptive) Gate-Schwelle; 0 = Gate deaktiviert. */
        val gateLimit: Int = 0,
    )

    private val _state = MutableStateFlow(Diag())
    val state: StateFlow<Diag> = _state.asStateFlow()

    fun starting() = _state.update { Diag(State.STARTING) }

    fun running(model: String) = _state.update { Diag(State.RUNNING, detail = model) }

    fun level(rms: Int, score: Float, gateActive: Boolean, gateLimit: Int = 0) = _state.update {
        if (it.state == State.RUNNING) {
            it.copy(rms = rms, score = score, gateActive = gateActive, gateLimit = gateLimit)
        } else it
    }

    fun error(reason: String) = _state.update { Diag(State.ERROR, detail = reason) }

    /**
     * Engine sauber beendet. Ein ERROR bleibt absichtlich stehen, bis der
     * nächste Start ihn überschreibt — sonst würde der Grund des Todes in der
     * UI sofort wieder verschwinden (Service stoppt sich nach einem Fatal).
     */
    fun stopped(hint: String = "") = _state.update {
        if (it.state == State.ERROR) it else Diag(State.OFF, detail = hint)
    }
}
