package com.bharatdroid.agent

import android.content.Context

data class TaskProgressSnapshot(
    val status: String,
    val command: String,
    val stage: String,
    val skill: String,
    val updatedAt: Long,
)

class TaskProgressStore(context: Context) {
    private val prefs = context.getSharedPreferences("bharatdroid_task_progress", Context.MODE_PRIVATE)

    companion object {
        const val STATUS_IDLE = "idle"
        const val STATUS_RUNNING = "running"
        const val STATUS_WAITING = "waiting"
        const val STATUS_DONE = "done"
        const val STATUS_FAILED = "failed"
        const val STATUS_STOPPED = "stopped"
    }

    fun start(command: String) {
        write(STATUS_RUNNING, command, "Starting task", "")
    }

    fun update(stage: String, skill: String? = null, status: String = STATUS_RUNNING) {
        val current = current()
        write(status, current.command, stage, skill ?: current.skill)
    }

    fun waiting(stage: String, skill: String? = null) {
        update(stage, skill, STATUS_WAITING)
    }

    fun done(summary: String) {
        val current = current()
        write(STATUS_DONE, current.command, summary.ifBlank { "Task complete" }, current.skill)
    }

    fun failed(reason: String) {
        val current = current()
        write(STATUS_FAILED, current.command, reason.ifBlank { "Task failed" }, current.skill)
    }

    fun stopped(summary: String = "Stopped by user") {
        val current = current()
        write(STATUS_STOPPED, current.command, summary, current.skill)
    }

    fun current(): TaskProgressSnapshot {
        return TaskProgressSnapshot(
            status = prefs.getString("status", STATUS_IDLE) ?: STATUS_IDLE,
            command = prefs.getString("command", "") ?: "",
            stage = prefs.getString("stage", "Idle") ?: "Idle",
            skill = prefs.getString("skill", "") ?: "",
            updatedAt = prefs.getLong("updated_at", 0L),
        )
    }

    private fun write(status: String, command: String, stage: String, skill: String) {
        prefs.edit()
            .putString("status", status)
            .putString("command", command.take(240))
            .putString("stage", stage.take(240))
            .putString("skill", skill.take(80))
            .putLong("updated_at", System.currentTimeMillis())
            .apply()
    }
}
