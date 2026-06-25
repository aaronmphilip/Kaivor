package com.kaivor.agent

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotchActivityHubTest {

    @After
    fun tearDown() {
        NotchActivityHub.clear()
    }

    @Test
    fun setListening_addsBackgroundActivity() {
        NotchActivityHub.setListening(true)
        val listen = NotchActivityHub.snapshot().firstOrNull { it.id == "listen" }
        assertEquals("Listening on Telegram", listen?.title)
        assertEquals(NotchActivityKind.BACKGROUND, listen?.kind)
    }

    @Test
    fun startPrimary_and_completePrimary() {
        NotchActivityHub.startPrimary("phone", "Open Swiggy")
        assertEquals("Open Swiggy", NotchActivityHub.primary()?.title)
        NotchActivityHub.completePrimary()
        assertNull(NotchActivityHub.primary())
    }

    @Test
    fun syncQueue_replacesQueuedEntries() {
        NotchActivityHub.syncQueue(listOf("Order pizza", "Check weather"))
        val queued = NotchActivityHub.snapshot().filter { it.kind == NotchActivityKind.QUEUED }
        assertEquals(2, queued.size)
        assertEquals("Order pizza", queued[0].title)
    }

    @Test
    fun setPrimaryPaused_updatesState() {
        NotchActivityHub.startPrimary("phone", "Run macro")
        NotchActivityHub.setPrimaryPaused(true)
        assertEquals(NotchActivityState.PAUSED, NotchActivityHub.primary()?.state)
    }

    @Test
    fun onChanged_firesAfterUpdates() {
        var changes = 0
        val listener: () -> Unit = { changes += 1 }
        NotchActivityHub.onChanged(listener)
        NotchActivityHub.startBackground("voice", "Voice note", "Transcribing")
        NotchActivityHub.endBackground("voice")
        assertTrue(changes >= 2)
        NotchActivityHub.offChanged(listener)
    }
}