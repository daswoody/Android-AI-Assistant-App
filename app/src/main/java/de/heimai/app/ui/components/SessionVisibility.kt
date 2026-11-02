package de.heimai.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import de.heimai.app.core.session.SessionHub

/**
 * Meldet dem [SessionHub], solange dieser Bildschirm sichtbar ist (STARTED).
 * Meldet sich die Assistenz von selbst, während kein Bildschirm der Sitzung
 * sichtbar ist, öffnet der Hub das Overlay.
 */
@Composable
fun SessionVisibility(hub: SessionHub, sessionKey: Long) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, sessionKey) {
        var visible = false
        fun set(value: Boolean) {
            if (visible == value) return
            visible = value
            hub.setVisible(sessionKey, value)
        }
        val observer = LifecycleEventObserver { _, _ ->
            set(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            set(false)
        }
    }
}
