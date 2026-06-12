package com.kaivor.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver that fires when a scheduled command's alarm goes off.
 * Retrieves the command from ScheduleStore and dispatches it through the orchestrator.
 */
class ScheduledCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ScheduleStore.ACTION_RUN_SCHEDULED) return

        val scheduleId = intent.getStringExtra(ScheduleStore.EXTRA_SCHEDULE_ID) ?: return
        val chatId = intent.getLongExtra(ScheduleStore.EXTRA_CHAT_ID, -1L)
        if (chatId == -1L) return

        val store = ScheduleStore(context)
        val command = store.onFired(scheduleId) ?: return

        // Dispatch to the live orchestrator if the service is running
        val orchestrator = AgentForegroundService.orchestratorInstance
        val scope = AgentForegroundService.serviceScope
        if (orchestrator != null && scope != null) {
            scope.launch(Dispatchers.IO) {
                orchestrator.dispatchScheduledCommand(chatId, command)
            }
        }
    }
}
