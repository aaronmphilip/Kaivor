package com.kaivor.agent

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandQueueTest {

    @After
    fun tearDown() {
        CommandQueue.clear()
        NotchActivityHub.clear()
    }

    @Test
    fun enqueue_and_poll_fifo() {
        val msg1 = IncomingMessage(1, 42, "user", "open chrome")
        val msg2 = IncomingMessage(2, 42, "user", "open settings")
        CommandQueue.enqueue(msg1, "open chrome")
        CommandQueue.enqueue(msg2, "open settings")
        assertEquals(2, CommandQueue.size())

        val first = CommandQueue.poll()
        assertEquals("open chrome", first?.trimmed)
        assertEquals(1, CommandQueue.size())

        val second = CommandQueue.poll()
        assertEquals("open settings", second?.trimmed)
        assertEquals(0, CommandQueue.size())
        assertNull(CommandQueue.poll())
    }

    @Test
    fun enqueue_syncsHubQueue() {
        CommandQueue.enqueue(IncomingMessage(1, 1, null, "task a"), "task a")
        CommandQueue.enqueue(IncomingMessage(2, 1, null, "task b"), "task b")
        val queued = NotchActivityHub.snapshot().filter { it.kind == NotchActivityKind.QUEUED }
        assertEquals(2, queued.size)
    }

    @Test
    fun clear_emptiesQueueAndHub() {
        CommandQueue.enqueue(IncomingMessage(1, 1, null, "task"), "task")
        CommandQueue.clear()
        assertEquals(0, CommandQueue.size())
        assertTrue(NotchActivityHub.snapshot().none { it.kind == NotchActivityKind.QUEUED })
    }
}