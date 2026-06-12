package com.kaivor.agent.skills.builtin

import android.content.Intent
import android.provider.Settings
import com.kaivor.agent.skills.*
import kotlinx.coroutines.delay

class SettingsSkill : Skill {

    override val manifest = SkillManifest(
        id = "settings",
        name = "Phone Settings",
        version = "2.0.0",
        description = "Toggle WiFi, Bluetooth, brightness, airplane mode, any Android setting",
        author = "Kaivor-team",
        trusted = true,
        permissions = setOf(
            Permission.OPEN_APP, Permission.READ_SCREEN,
            Permission.TAP, Permission.TYPE, Permission.SCROLL,
            Permission.NAVIGATE_BACK,
        ),
        allowedPackages = setOf("com.android.settings"),
        exampleParamsHint = """{"action": "wifi", "state": "off"}""",
        uiKnowledge = """
Android Settings UI guide:
- Home screen: vertical list of setting categories - Network & internet, Connected devices, Apps, Battery, Display, Sound, Storage, Privacy, Location, Security, Accessibility, About phone.
- Search: magnifying glass icon at the top right - tap and type any setting name to jump directly to it. Use this for settings not in the main list.
- Toggles: blue/green toggle switch to the right of a setting means ON; grey means OFF. Tap to flip.
- Wi-Fi: Network & internet ? Wi-Fi ? toggle at the top. Wi-Fi network list appears below.
- Bluetooth: Connected devices ? Connection preferences ? Bluetooth, OR directly "Bluetooth" if shown.
- Mobile Data: Network & internet ? Mobile network ? Mobile data toggle.
- Airplane Mode: Network & internet ? Airplane mode toggle.
- Hotspot: Network & internet ? Hotspot & tethering ? Wi-Fi hotspot.
- Display / Brightness: Display ? Brightness level slider; also Adaptive brightness toggle.
- Sound / Volume: Sound & vibration ? sliders for Media, Call, Ring, Notification, Alarm volumes.
- Location: Location ? toggle at the top to enable/disable GPS.
- Battery: Battery ? shows percentage and estimated remaining time; Battery saver toggle.
- Storage: Storage ? shows used/available space with category breakdown.
- Accessibility: Accessibility ? Downloaded apps section ? Kaivor Agent.
- About phone: last item in main list ? shows Android version, device model, software info.
- Samsung devices: top-level may differ - "Connections" instead of "Network & internet", "Display" directly accessible, "Device care" for battery + storage.
""".trimIndent(),
    )

    override suspend fun execute(context: SkillContext, params: Map<String, Any>): SkillResult {
        val runner = context.runner
        val agent = context.agent ?: return SkillResult.Failure("Agent not available.")

        val action = (params["action"] as? String)?.lowercase()
            ?: (params["section"] as? String)?.lowercase()
            ?: "view"
        val state = (params["state"] as? String)?.lowercase() ?: ""
        val setting = params["setting"] as? String ?: action

        val ctx = context.runner.getContext()
        val onOff = if (state == "off") "OFF" else "ON"

        // -- Direct-intent shortcuts ----------------------------------------------
        // Jump straight to the correct settings page so the AI never has to
        // navigate from Settings home - eliminates back-loop bugs entirely.
        val directIntent: Intent? = when {
            action == "accessibility" || setting.contains("accessibility", ignoreCase = true) ->
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            action == "bluetooth" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            action == "wifi" || action == "wi-fi" -> Intent(Settings.ACTION_WIFI_SETTINGS)
            action == "location" || action == "gps" -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            action == "airplane" || action == "flight" -> Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
            action == "sound" || action == "volume" || action == "ringer" -> Intent(Settings.ACTION_SOUND_SETTINGS)
            action == "display" || action == "brightness" -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
            action == "data" || action == "mobile_data" -> Intent(Settings.ACTION_DATA_ROAMING_SETTINGS)
            action == "hotspot" -> Intent(Settings.ACTION_WIRELESS_SETTINGS)
            action == "battery" -> Intent(Intent.ACTION_POWER_USAGE_SUMMARY)
            action == "storage" -> Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
            action == "about" || action == "device_info" -> Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)
            else -> null
        }

        if (directIntent != null) {
            directIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(directIntent)
            delay(1200)
            runner.dismissPopups(1)
            delay(300)
        } else {
            runner.openApp("com.android.settings")
            runner.waitForApp("com.android.settings", timeoutMs = 5000)
            delay(600)
            runner.dismissPopups(1)
            delay(200)
        }

        // Special-case accessibility: no AI step needed, just return
        if (action == "accessibility" || setting.contains("accessibility", ignoreCase = true)) {
            return SkillResult.Success(
                "Opened Accessibility Settings.\n\nLook for *Kaivor Agent* under *Downloaded apps* and tap it to enable it."
            )
        }

        val strictRules = """
STRICT RULES - obey exactly:
- You are ALREADY on the correct settings page. DO NOT press Back. DO NOT go anywhere else.
- Look only at the CURRENT screen. Identify the toggle or control described and act on it.
- If already in the desired state, report that and STOP - do not tap anything.
- Tap the toggle ONCE, then read the new state and report. You are done.
- NEVER navigate backwards. NEVER open a different section.""".trimIndent()

        val goal = when (action) {
            "bluetooth" -> """
$strictRules

TASK: Turn Bluetooth $onOff.
You are on the Bluetooth settings screen.
Find the Bluetooth toggle (ON/OFF switch). If it is already $onOff, say so and stop.
Tap it once. Report: "Bluetooth is now $onOff." """.trimIndent()

            "wifi", "wi-fi" -> """
$strictRules

TASK: Turn Wi-Fi $onOff.
You are on the Wi-Fi settings screen.
Find the Wi-Fi toggle at the top. If already $onOff, say so and stop.
Tap it once. Report: "Wi-Fi is now $onOff." """.trimIndent()

            "location", "gps" -> """
$strictRules

TASK: Turn Location $onOff.
You are on the Location settings screen.
Find the Location toggle at the top. Tap to turn it $onOff. Report the result.""".trimIndent()

            "airplane", "flight" -> """
$strictRules

TASK: Turn Airplane mode $onOff.
Find "Airplane mode" or "Flight mode" toggle on this screen.
Tap to turn it $onOff. Report the result.""".trimIndent()

            "brightness", "display" -> """
$strictRules

TASK: Adjust screen brightness${if (state.isNotBlank()) " to $state" else " to maximum"}.
You are on the Display settings screen.
Find the Brightness slider. Drag it ${if (state.isNotBlank()) "to $state" else "all the way right (maximum)"}.
Report the result.""".trimIndent()

            "sound", "volume", "ringer" -> """
$strictRules

TASK: Adjust sound/volume${if (state.isNotBlank()) " - $state" else ""}.
You are on the Sound settings screen.
Find the relevant volume slider or toggle and adjust it. Report the result.""".trimIndent()

            "data", "mobile_data" -> """
$strictRules

TASK: Turn Mobile Data $onOff.
Find "Mobile data" toggle on this screen. Tap once to turn it $onOff. Report the result.""".trimIndent()

            "hotspot" -> """
$strictRules

TASK: Turn Wi-Fi Hotspot $onOff.
Find "Wi-Fi hotspot" or "Mobile hotspot". Tap it, then find the hotspot toggle and set it $onOff.
Report the result.""".trimIndent()

            "battery" -> """
$strictRules

TASK: Read battery status.
You are on the Battery settings screen. Read and report: current percentage, charging status, and any usage info shown.""".trimIndent()

            "storage" -> """
$strictRules

TASK: Read storage info.
You are on the Storage settings screen. Read and report: total space, used space, and available space.""".trimIndent()

            "about", "device_info" -> """
$strictRules

TASK: Read device info.
You are on the About phone screen. Read and report: device model, Android version, and build number.""".trimIndent()

            else -> {
                val searchQuery = setting.ifBlank { action }
                """$strictRules

TASK: Find and change '$searchQuery'${if (state.isNotBlank()) " to $state" else ""}.
Tap the search icon (magnifier) at the top. Type '$searchQuery'. Tap the best match. Make the change. Report the result.""".trimIndent()
            }
        }

        val result = agent.executeGoal(runner, goal, maxSteps = 70)
        return SkillResult.Success(result)
    }
}
