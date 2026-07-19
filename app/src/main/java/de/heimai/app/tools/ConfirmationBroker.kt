package de.heimai.app.tools

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Vermittelt Bestätigungs-Dialoge zwischen dem DeviceToolExecutor
 * (läuft ohne UI-Bezug) und der gerade sichtbaren Oberfläche
 * (Chat, Talk oder Assistant-Overlay), die [pending] beobachtet
 * und den Dialog anzeigt.
 */
object ConfirmationBroker {

    data class Request(
        val title: String,
        val description: String,
        internal val deferred: CompletableDeferred<Boolean>,
    )

    private val _pending = MutableStateFlow<Request?>(null)
    val pending: StateFlow<Request?> = _pending.asStateFlow()

    /** @return true = bestätigt; false = abgelehnt oder Timeout (60 s) */
    suspend fun confirm(title: String, description: String): Boolean {
        val request = Request(title, description, CompletableDeferred())
        _pending.value = request
        val result = withTimeoutOrNull(60_000) { request.deferred.await() } ?: false
        _pending.value = null
        return result
    }

    fun respond(request: Request, approved: Boolean) {
        request.deferred.complete(approved)
    }
}
