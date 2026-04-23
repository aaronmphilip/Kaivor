package com.bharatdroid.agent.skills.builtin

import android.content.Intent
import android.provider.Settings
import com.bharatdroid.agent.skills.*
import kotlinx.coroutines.delay

class SettingsSkill : Skill {

    override val manifest = SkillManifest(
        id = "settings",
        name = "Phone Settings",
        version = "2.0.0",
        description = "Toggle WiFi, Bluetooth, brightness, airplane mode, any Android setting",
        author = "bharatdroid-team",
        trusted = true,
        permissions = setOf(
            Permission.OPEN_APP, Permission.READ_SCREEN,
            Permission.TAP, Permission.TYPE, Permission.SCROLL,
            Permission.NAVIGATE_BACK,
        ),
        allowedPackages = setOf("com.android.settings"),
        exampleParamsHint = """{"action": "wifi", "state": "off"}""",
    )

    override suspend fun execute(context: SkillContext, params: Map<String, Any>): SkillResult {
        val runner = context.runner
        val agent = context.agent ?: return SkillResult.Failure("Agent not available.")

        val action = (params["action"] as? String)?.lowercase()
            ?: (params["section"] as? String)?.lowercase()
            ?: "view"
        val state = (params["state"] as? String)?.lowercase() ?: ""
        val setting = params["setting"] as? String ?: action

        // Accessibility — use direct intent so AI doesn't confuse it with Bluetooth
        if (action == "accessibility" || setting.contains("accessibility", ignoreCase = true)) {
            val ctx = context.runner.getContext()
            ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            delay(1000)
            return SkillResult.Success(
                "Opened Accessibility Settings.\n\nLook for *BharatDroid Agent* in the list under *Downloaded apps* and tap it to enable it."
            )
        }

        runner.openApp("com.android.settings")
        runner.waitForApp("com.android.settings", timeoutMs = 5000)
        delay(600)
        runner.dismissPopups(1)
        delay(200)

        val onOff = if (state == "off") "OFF" else "ON"

        val goal = when (action) {
            "wifi", "wi-fi" ->
                """You are in Android Settings. Turn WiFi $onOff.
                STEPS: 1) Tap 'Network & internet' or 'Wi-Fi' or 'Connections'. 2) Find the Wi-Fi toggle switch. 3) Tap it to turn $onOff. 4) Confirm the current state."""

            "bluetooth" ->
                """You are in Android Settings. Turn Bluetooth $onOff.
                STEPS: 1) Tap 'Connected devices' or 'Bluetooth'. 2) Find the Bluetooth toggle. 3) Tap to turn $onOff."""

            "brightness", "display" ->
                """You are in Android Settings. Adjust screen brightness${if (state.isNotBlank()) " to $state" else ""}.
                STEPS: 1) Tap 'Display' or 'Display & Brightness'. 2) Find the Brightness slider. 3) Adjust it ${if (state.isNotBlank()) "to $state" else "to maximum"}."""

            "airplane", "flight", "do_not_disturb" ->
                """You are in Android Settings. Toggle Airplane mode $onOff.
                STEPS: 1) Tap 'Network & internet'. 2) Find 'Airplane mode' or 'Flight mode'. 3) Tap to turn $onOff."""

            "battery" ->
                "You are in Android Settings. Show battery status. Tap 'Battery' or 'Battery and device care' and read the percentage and usage details."

            "storage" ->
                "You are in Android Settings. Show storage usage. Tap 'Storage' and read the used/available space."

            "sound", "volume", "ringer" ->
                """You are in Android Settings. Adjust sound settings${if (state.isNotBlank()) " — $state" else ""}.
                STEPS: 1) Tap 'Sound' or 'Sound & vibration'. 2) Adjust the relevant volume slider or toggle."""

            "notifications" ->
                "You are in Android Settings. Open notification settings. Tap 'Notifications' or 'Apps & notifications'."

            "about", "device_info" ->
                "You are in Android Settings. Show device information. Scroll to the bottom and tap 'About phone' or 'About device'."

            "location", "gps" ->
                """You are in Android Settings. Turn location $onOff.
                STEPS: 1) Tap 'Location'. 2) Find the Location toggle. 3) Turn it $onOff."""

            "data", "mobile_data" ->
                """You are in Android Settings. Turn mobile data $onOff.
                STEPS: 1) Tap 'Network & internet' or 'Connections'. 2) Find 'Mobile data'. 3) Toggle $onOff."""

            "hotspot" ->
                """You are in Android Settings. Turn hotspot $onOff.
                STEPS: 1) Tap 'Network & internet' or 'Hotspot & tethering'. 2) Tap 'Wi-Fi hotspot'. 3) Toggle $onOff."""

            else -> {
                val searchQuery = setting.ifBlank { action }
                """You are in Android Settings. Find and change '$searchQuery'${if (state.isNotBlank()) " to $state" else ""}.
                STEPS: 1) Tap the search icon at the top. 2) Type '$searchQuery'. 3) Tap the matching result. 4) Make the change as needed."""
            }
        }

        val result = agent.executeGoal(runner, goal, maxSteps = 15)
        return SkillResult.Success(result)
    }
}
