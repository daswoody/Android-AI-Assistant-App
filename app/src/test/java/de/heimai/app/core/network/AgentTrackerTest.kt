package de.heimai.app.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTrackerTest {

    @Test
    fun `Ergebnis zwischen zwei Turns kommt mit dem naechsten Turn`() {
        val t = AgentTracker()
        t.onTurnStart()              // "Wie komme ich nach Berlin?"
        t.onJobStarted("j1")
        assertEquals(1, t.runningCount)
        t.onAssistantFinal()         // "Ich suche das raus."
        t.onJobFinished("j1")        // zwischen den Turns
        assertEquals(0, t.runningCount)
        assertTrue(t.resultPending)
        t.onTurnStart()              // proaktiv oder naechste Eingabe
        t.onAssistantFinal()
        assertFalse(t.resultPending)
    }

    @Test
    fun `Ergebnis mitten im Turn steckt noch nicht in dessen Antwort`() {
        val t = AgentTracker()
        t.onTurnStart()
        t.onJobStarted("j1")
        t.onAssistantFinal()
        t.onTurnStart()              // "Danke"
        t.onJobFinished("j1")        // waehrend die Antwort auf "Danke" entsteht
        t.onAssistantFinal()         // Antwort auf "Danke" - ohne Ergebnis
        assertTrue(t.resultPending)
        t.onTurnStart()
        t.onAssistantFinal()
        assertFalse(t.resultPending)
    }

    @Test
    fun `spaeteres Agenten-Ende haelt die Zustellung offen`() {
        val t = AgentTracker()
        t.onTurnStart()
        t.onJobStarted("a")
        t.onJobStarted("b")
        t.onJobFinished("a")         // zwischen Turns (seq 1)
        t.onTurnStart()              // seq 2 bringt a
        t.onJobFinished("b")         // mitten in seq 2
        t.onAssistantFinal()
        assertTrue(t.resultPending)  // b steht noch aus
        t.onTurnStart()
        t.onAssistantFinal()
        assertFalse(t.resultPending)
    }

    @Test
    fun `Verbindungsabbruch verwirft alles`() {
        val t = AgentTracker()
        t.onJobStarted("a")
        t.onJobFinished("a")
        t.onJobStarted("b")
        t.reset()
        assertEquals(0, t.runningCount)
        assertFalse(t.resultPending)
    }
}
