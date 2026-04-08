package com.bharatdroid.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// Auto-starts the agent service when the phone boots
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = context.getSharedPreferences("bharatdroid", Context.MODE_PRIVATE)
            val configured = prefs.getString("bot_token", null) != null
            if (configured) {
                context.startForegroundService(
                    Intent(context, AgentForegroundService::class.java)
                )
            }
        }
    }
}
