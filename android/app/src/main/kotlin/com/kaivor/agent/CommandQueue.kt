package com.kaivor.agent

import java.util.concurrent.ConcurrentLinkedQueue

data class QueuedCommand(
    val msg: IncomingMessage,
    val trimmed: String,
)

/**
 * Phone-task queue — when automation is already running, new commands wait here
 * instead of killing the current task (Clicky-style multi-task visibility).
 */
object CommandQueue {
    private val queue = ConcurrentLinkedQueue<QueuedCommand>()

    fun enqueue(msg: IncomingMessage, trimmed: String) {
        queue.add(QueuedCommand(msg, trimmed))
        NotchActivityHub.syncQueue(queue.map { it.trimmed })
    }

    fun poll(): QueuedCommand? {
        val item = queue.poll()
        NotchActivityHub.syncQueue(queue.map { it.trimmed })
        return item
    }

    fun peekAll(): List<QueuedCommand> = queue.toList()

    fun size(): Int = queue.size

    fun clear() {
        queue.clear()
        NotchActivityHub.syncQueue(emptyList())
    }
}