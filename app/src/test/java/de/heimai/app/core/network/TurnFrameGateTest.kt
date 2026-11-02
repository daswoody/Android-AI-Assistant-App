package de.heimai.app.core.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnFrameGateTest {

    @Test
    fun `ohne interrupt kommt alles durch`() {
        val gate = TurnFrameGate()
        TurnFrameGate.TURN_FRAMES.forEach { assertTrue(it, gate.accept(it)) }
        assertTrue(gate.accept("done"))
        assertTrue(gate.accept("proactive"))
    }

    @Test
    fun `nach interrupt werden Turn-Frames bis zum done verworfen`() {
        val gate = TurnFrameGate()
        gate.onInterruptSent()
        TurnFrameGate.TURN_FRAMES.forEach { assertFalse(it, gate.accept(it)) }
        // Nicht-Turn-Frames laufen weiter
        assertTrue(gate.accept("done"))
        assertTrue(gate.accept("conversation"))
        assertTrue(gate.onDone())
        TurnFrameGate.TURN_FRAMES.forEach { assertTrue(it, gate.accept(it)) }
        assertFalse(gate.onDone())
    }

    @Test
    fun `Frames von Hintergrund-Agenten passieren auch nach interrupt`() {
        val gate = TurnFrameGate()
        gate.onInterruptSent()
        assertTrue(gate.accept("tool_activity", backgroundJob = true))
        // normale tool_activity (ohne job_id) gehoert zum abgebrochenen Turn
        assertFalse(gate.accept("tool_activity"))
    }

    @Test
    fun `proactive eroeffnet einen Turn und ist kein Turn-Frame`() {
        assertFalse("proactive" in TurnFrameGate.TURN_FRAMES)
        val gate = TurnFrameGate()
        gate.onInterruptSent()
        assertTrue(gate.accept("proactive"))
    }

    @Test
    fun `reset hebt das Verwerfen auf`() {
        val gate = TurnFrameGate()
        gate.onInterruptSent()
        gate.reset()
        assertTrue(gate.accept("assistant_text"))
        assertFalse(gate.onDone())
    }
}
