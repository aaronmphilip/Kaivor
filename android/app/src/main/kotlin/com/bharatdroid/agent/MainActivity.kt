package com.bharatdroid.agent

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS

class MainActivity : AppCompatActivity() {

    private val activityLog by lazy { ActivityLog(this) }
    private var isAgentRunning = false
    private var agentStartElapsed: Long = 0L  // SystemClock.elapsedRealtime() when agent last started

    // All skill metadata for the dashboard grid
    private val allSkills = listOf(
        SkillCard("Swiggy", "Food delivery", "#FF6B00"),
        SkillCard("Zomato", "Food delivery", "#E23744"),
        SkillCard("Zepto", "10-min grocery", "#6C2DC7"),
        SkillCard("YouTube", "Videos", "#FF0000"),
        SkillCard("PhonePe", "UPI / Recharge", "#5F259F"),
        SkillCard("Google Maps", "Navigation", "#1EA362"),
        SkillCard("Flipkart", "Shopping", "#2874F0"),
        SkillCard("GPay", "UPI Payments", "#4285F4"),
        SkillCard("Paytm", "Wallet / Bills", "#00BAF2"),
        SkillCard("CRED", "Bill payments", "#1A1A2E"),
        SkillCard("Ola", "Cab booking", "#1C8C3C"),
        SkillCard("Uber", "Cab booking", "#000000"),
        SkillCard("Amazon", "Shopping", "#FF9900"),
        SkillCard("Blinkit", "Grocery delivery", "#F8CB46"),
        SkillCard("WhatsApp", "Messaging", "#25D366"),
        SkillCard("Instagram", "Social media", "#E4405F"),
        SkillCard("Chrome", "Web browsing", "#4285F4"),
        SkillCard("Gmail", "Email", "#EA4335"),
        SkillCard("Calendar", "Schedule", "#4285F4"),
        SkillCard("Contacts", "Phone & Contacts", "#1A73E8"),
        SkillCard("Notes", "Google Keep", "#FBBC04"),
        SkillCard("Files", "File Manager", "#5F6368"),
        SkillCard("Settings", "Phone Settings", "#607D8B"),
        SkillCard("Screen", "Screen Reader", "#00BCD4"),
        SkillCard("General", "Any Task", "#FF4500"),
    )

    data class SkillCard(val name: String, val description: String, val color: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // If not onboarded, go to onboarding
        val prefs = getSharedPreferences("bharatdroid", MODE_PRIVATE)
        if (prefs.getString("bot_token", null) == null) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        setupToggleButton()
        setupSettingsButton()
        setupNotifRelayCard()
        populateSkillsGrid()
        refreshStatus()
        populateActivityLog()

        // Auto-start agent if not already running
        startForegroundService(Intent(this, AgentForegroundService::class.java))
        isAgentRunning = true
        agentStartElapsed = SystemClock.elapsedRealtime()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        populateActivityLog()
    }

    private fun setupNotifRelayCard() {
        findViewById<Button>(R.id.btnEnableNotifRelay).setOnClickListener {
            startActivity(Intent(ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
    }

    /** Format elapsed milliseconds as "1h 23m" or "45m" or "—" */
    private fun formatUptime(elapsedMs: Long): String {
        if (elapsedMs <= 0L) return "—"
        val totalMinutes = (elapsedMs / 60_000).toInt()
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }

    private fun refreshStatus() {
        val prefs = getSharedPreferences("bharatdroid", MODE_PRIVATE)
        val serviceOk = AgentAccessibilityService.isConnected

        val dot = findViewById<View>(R.id.statusDot)
        val statusText = findViewById<TextView>(R.id.tvAgentStatus)
        val accessText = findViewById<TextView>(R.id.tvAccessibilityStatus)
        val skillCount = findViewById<TextView>(R.id.tvSkillCount)
        val todayCount = findViewById<TextView>(R.id.tvTodayCount)
        val toggleBtn = findViewById<Button>(R.id.btnToggleAgent)

        // Populate stat strip
        val todayCnt = activityLog.todayCount()
        findViewById<TextView>(R.id.tvCommandCount).text = todayCnt.toString()
        findViewById<TextView>(R.id.tvStatSkills).text = allSkills.size.toString()
        val elapsedMs = if (isAgentRunning && agentStartElapsed > 0L)
            SystemClock.elapsedRealtime() - agentStartElapsed else 0L
        findViewById<TextView>(R.id.tvUptime).text = formatUptime(elapsedMs)

        if (isAgentRunning) {
            dot.setBackgroundResource(R.drawable.dot_green)
            statusText.text = "Agent Running"
            statusText.setTextColor(Color.WHITE)
            toggleBtn.text = "Stop Agent"
            toggleBtn.setBackgroundColor(0xFF331111.toInt())
            toggleBtn.setTextColor(0xFFFF6B6B.toInt())
        } else {
            dot.setBackgroundResource(R.drawable.dot_red)
            statusText.text = "Agent Stopped"
            statusText.setTextColor(0xFFFF6B6B.toInt())
            toggleBtn.text = "Start Agent"
            toggleBtn.setBackgroundColor(0xFF113311.toInt())
            toggleBtn.setTextColor(0xFF00CC88.toInt())
        }

        accessText.text = if (serviceOk) "Accessibility: Connected" else "Accessibility: Not connected (tap to fix)"
        accessText.setTextColor(if (serviceOk) 0xFF888888.toInt() else 0xFFFF6B6B.toInt())
        if (!serviceOk) {
            accessText.setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        val mode = prefs.getBoolean("ask_permission", true)
        val modeText = if (mode) "Ask Permission" else "Just Do It"
        val agentProvider = prefs.getString("agent_ai_provider", prefs.getString("ai_provider", "GEMINI")) ?: "GEMINI"
        val researchKey = prefs.getString("research_ai_key", "")?.trim().orEmpty()
        val researchProvider = if (researchKey.isBlank()) {
            "$agentProvider fallback"
        } else {
            prefs.getString("research_ai_provider", agentProvider) ?: agentProvider
        }
        skillCount.text = "${allSkills.size} skills  |  $modeText  |  Agent: $agentProvider  |  Research: $researchProvider"
        todayCount.text = "$todayCnt commands today"

        // Notification relay card
        val notifGranted = NotificationRelay.isPermissionGranted(this)
        val badge = findViewById<TextView>(R.id.tvNotifRelayBadge)
        val desc = findViewById<TextView>(R.id.tvNotifRelayDesc)
        val btn = findViewById<Button>(R.id.btnEnableNotifRelay)
        if (notifGranted) {
            badge.text = "LIVE"
            badge.setTextColor(0xFF00CC88.toInt())
            badge.setBackgroundColor(0xFF0D2B1E.toInt())
            desc.text = "Active — forwarding notifications from all apps to your Telegram. Reply to any forwarded message to reply inside that app.\n\nTip: send /muted to your bot to manage per-app muting."
            btn.text = "Manage in Settings"
        } else {
            badge.text = "OFF"
            badge.setTextColor(0xFFFF6B6B.toInt())
            badge.setBackgroundColor(0xFF331111.toInt())
            desc.text = "Every app notification → forwarded to your Telegram bot. Reply in Telegram → reply lands in the app."
            btn.text = "Enable in Settings → Notification Access"
        }
    }

    private fun setupToggleButton() {
        findViewById<Button>(R.id.btnToggleAgent).setOnClickListener {
            if (isAgentRunning) {
                stopService(Intent(this, AgentForegroundService::class.java))
                isAgentRunning = false
                agentStartElapsed = 0L
            } else {
                startForegroundService(Intent(this, AgentForegroundService::class.java))
                isAgentRunning = true
                agentStartElapsed = SystemClock.elapsedRealtime()
            }
            refreshStatus()
        }
    }

    private fun setupSettingsButton() {
        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun populateSkillsGrid() {
        val container = findViewById<LinearLayout>(R.id.skillsGrid)
        container.removeAllViews()

        // 3 skills per row for compactness
        var row: LinearLayout? = null
        allSkills.forEachIndexed { index, skill ->
            if (index % 3 == 0) {
                row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = 6.dp }
                }
                container.addView(row)
            }
            row?.addView(buildSkillChip(skill))
        }
    }

    private fun buildSkillChip(skill: SkillCard): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.card_bg)
            setPadding(10.dp, 8.dp, 10.dp, 8.dp)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 6.dp
            }

            addView(TextView(this@MainActivity).apply {
                text = skill.name
                setTextColor(Color.parseColor(skill.color))
                textSize = 12f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(TextView(this@MainActivity).apply {
                text = skill.description
                setTextColor(0xFF666666.toInt())
                textSize = 10f
            })
        }
    }

    private fun populateActivityLog() {
        val container = findViewById<LinearLayout>(R.id.activityList)
        container.removeAllViews()

        val entries = activityLog.getRecent(8)
        if (entries.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "No activity yet. Send a command via Telegram!"
                setTextColor(0xFF555555.toInt())
                textSize = 13f
                setPadding(0, 8.dp, 0, 0)
            })
            return
        }

        entries.forEach { entry ->
            val icon = if (entry.result == "success") "\u2713" else "\u2717"
            val iconColor = if (entry.result == "success") 0xFF00CC88.toInt() else 0xFFFF6B6B.toInt()

            container.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 8.dp, 0, 8.dp)
                gravity = Gravity.TOP

                addView(TextView(this@MainActivity).apply {
                    text = icon
                    setTextColor(iconColor)
                    textSize = 14f
                    setPadding(0, 0, 8.dp, 0)
                })

                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

                    addView(TextView(this@MainActivity).apply {
                        text = entry.command.take(50)
                        setTextColor(Color.WHITE)
                        textSize = 13f
                    })
                    addView(TextView(this@MainActivity).apply {
                        text = "${entry.summary.take(60)} \u00b7 ${entry.timeFormatted}"
                        setTextColor(0xFF666666.toInt())
                        textSize = 11f
                    })
                })
            })
        }
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}
