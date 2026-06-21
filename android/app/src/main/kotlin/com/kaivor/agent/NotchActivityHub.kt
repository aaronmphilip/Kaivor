package com.kaivor.agent

import java.util.concurrent.CopyOnWriteArrayList

enum class NotchActivityKind { PRIMARY, QUEUED, BACKGROUND }
enum class NotchActivityState { RUNNING, WAITING, PAUSED, IDLE }

data class NotchActivity(
    val id: String,
    val title: String,
    val subtitle: String = "",
    val kind: NotchActivityKind,
    val state: NotchActivityState,
)

/**
 * Clicky-style activity registry — every live stream of work Kaivor is doing
 * (phone automation, queued commands, Telegram listen, relay, voice, research).
 */
object NotchActivityHub {

    private val activities = LinkedHashMap<String, NotchActivity>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun onChanged(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun offChanged(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun emitChange() {
        listeners.forEach { runCatching { it() } }
    }

    fun snapshot(): List<NotchActivity> = activities.values.toList()

    fun primary(): NotchActivity? =
        activities.values.firstOrNull { it.kind == NotchActivityKind.PRIMARY }

    fun setListening(enabled: Boolean) {
        if (enabled) {
            upsert(
                "listen",
                "Listening on Telegram",
                "Ready for commands",
                NotchActivityKind.BACKGROUND,
                NotchActivityState.IDLE,
            )
        } else {
            activities.remove("listen")
            emitChange()
        }
    }

    fun setRelay(enabled: Boolean) {
        if (enabled) {
            upsert(
                "relay",
                "Notification relay",
                "Forwarding app alerts",
                NotchActivityKind.BACKGROUND,
                NotchActivityState.RUNNING,
            )
        } else {
            activities.remove("relay")
            emitChange()
        }
    }

    fun startPrimary(id: String, title: String) {
        upsert(id, title, "Starting...", NotchActivityKind.PRIMARY, NotchActivityState.RUNNING)
    }

    fun updatePrimary(title: String, subtitle: String, state: NotchActivityState = NotchActivityState.RUNNING) {
        val current = activities.entries.firstOrNull { it.value.kind == NotchActivityKind.PRIMARY }
        if (current != null) {
            activities[current.key] = current.value.copy(
                title = title.take(64),
                subtitle = subtitle.take(96),
                state = state,
            )
            emitChange()
        }
    }

    fun completePrimary() {
        activities.entries.removeAll { it.value.kind == NotchActivityKind.PRIMARY }
        emitChange()
    }

    fun setPrimaryPaused(paused: Boolean) {
        val current = activities.entries.firstOrNull { it.value.kind == NotchActivityKind.PRIMARY } ?: return
        activities[current.key] = current.value.copy(
            state = if (paused) NotchActivityState.PAUSED else NotchActivityState.RUNNING,
            subtitle = if (paused) "Paused — tap play to resume" else current.value.subtitle,
        )
        emitChange()
    }

    fun syncQueue(titles: List<String>) {
        activities.entries.removeAll { it.value.kind == NotchActivityKind.QUEUED }
        titles.take(5).forEachIndexed { index, title ->
            upsert(
                "queue_$index",
                title.take(56),
                "Waiting in line",
                NotchActivityKind.QUEUED,
                NotchActivityState.WAITING,
            )
        }
        emitChange()
    }

    fun startBackground(id: String, title: String, subtitle: String = "") {
        upsert(id, title, subtitle, NotchActivityKind.BACKGROUND, NotchActivityState.RUNNING)
    }

    fun endBackground(id: String) {
        activities.remove(id)
        emitChange()
    }

    fun clear() {
        activities.clear()
        emitChange()
    }

    private fun upsert(
        id: String,
        title: String,
        subtitle: String,
        kind: NotchActivityKind,
        state: NotchActivityState,
    ) {
        activities[id] = NotchActivity(id, title, subtitle, kind, state)
        emitChange()
    }
}