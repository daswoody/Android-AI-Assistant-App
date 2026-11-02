package de.heimai.app.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolActivitiesTest {

    private fun row(id: Long, vararg a: ToolActivity) =
        UiMessage(id, "tool_activity", activities = a.toList())

    @Test
    fun `End-Frame erkennen`() {
        assertTrue(ToolActivities.isBackgroundEnd("j1", "done"))
        assertTrue(ToolActivities.isBackgroundEnd("j1", "error"))
        assertFalse(ToolActivities.isBackgroundEnd("j1", "background"))
        assertFalse(ToolActivities.isBackgroundEnd(null, "done"))
    }

    @Test
    fun `normaler Aufruf stempelt per Tool-Name`() {
        val start = ToolActivities.upsert(emptyList(), "wetter", "running")
        val end = ToolActivities.upsert(start, "wetter", "done")
        assertEquals(listOf(ToolActivity("wetter", "done")), end)
    }

    @Test
    fun `normaler Aufruf ueberschreibt den Hintergrund-Chip desselben Agenten nicht`() {
        val bg = listOf(ToolActivity("agent-routen", "background", "j1"))
        val withSync = ToolActivities.upsert(bg, "agent-routen", "running")
        assertEquals(2, withSync.size)
        val done = ToolActivities.upsert(withSync, "agent-routen", "done")
        assertEquals(ToolActivity("agent-routen", "background", "j1"), done[0])
        assertEquals(ToolActivity("agent-routen", "done"), done[1])
    }

    @Test
    fun `zwei Hintergrund-Jobs desselben Agenten bekommen zwei Chips`() {
        val a = ToolActivities.upsert(emptyList(), "agent-routen", "background", "j1")
        val b = ToolActivities.upsert(a, "agent-routen", "background", "j2")
        assertEquals(listOf("j1", "j2"), b.map { it.jobId })
    }

    @Test
    fun `Ende stempelt den Chip in seiner urspruenglichen Zeile`() {
        val messages = listOf(
            row(1, ToolActivity("agent-routen", "background", "j1")),
            UiMessage(2, "assistant", "Ich suche raus."),
            UiMessage(3, "user", "Danke"),
            row(4, ToolActivity("agent-routen", "background", "j2"), ToolActivity("wetter", "done")),
        )
        val stamped = ToolActivities.stampJob(messages, "j1", "done")!!
        assertEquals("done", stamped[0].activities.single().status)
        // andere Zeilen/Jobs unveraendert, keine neue Zeile
        assertEquals(messages.size, stamped.size)
        assertEquals(messages[3], stamped[3])
        assertEquals(messages[1], stamped[1])
    }

    @Test
    fun `Ende ohne passenden Chip legt nichts an`() {
        val messages = listOf(row(1, ToolActivity("wetter", "done")))
        assertNull(ToolActivities.stampJob(messages, "unbekannt", "done"))
    }
}
