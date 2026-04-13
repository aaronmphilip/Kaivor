package com.bharatdroid.agent.skills.builtin

import com.bharatdroid.agent.skills.*
import kotlinx.coroutines.delay

class ContactsSkill : Skill {

    override val manifest = SkillManifest(
        id = "contacts",
        name = "Contacts & Phone",
        version = "3.0.0",
        description = "Search contacts, view details, add new contacts, check call history",
        author = "bharatdroid-team",
        trusted = true,
        permissions = setOf(
            Permission.OPEN_APP, Permission.READ_SCREEN,
            Permission.TAP, Permission.TYPE, Permission.SCROLL,
            Permission.NAVIGATE_BACK, Permission.SENSITIVE_READ,
        ),
        allowedPackages = setOf(
            "com.google.android.contacts",
            "com.google.android.dialer",
            "com.android.contacts",
            "com.android.dialer",
        ),
        exampleParamsHint = """{"action": "search", "query": "Mom"}""",
    )

    override suspend fun execute(context: SkillContext, params: Map<String, Any>): SkillResult {
        val runner = context.runner
        val agent = context.agent ?: return SkillResult.Failure("Agent not available.")

        val action = (params["action"] as? String)?.lowercase() ?: "search"
        val query = params["query"] as? String ?: params["contact"] as? String ?: params["name"] as? String ?: ""
        val phone = params["phone"] as? String ?: ""
        val email = params["email"] as? String ?: ""

        // Try contacts apps in order
        val apps = listOf("com.google.android.contacts", "com.android.contacts")
        var opened = false
        for (app in apps) {
            try {
                runner.openApp(app)
                if (runner.waitForApp(app, timeoutMs = 3000)) { opened = true; break }
            } catch (_: Exception) { continue }
        }
        if (!opened) return SkillResult.Failure("Contacts app not found.")
        delay(600)
        runner.dismissPopups(1)
        delay(200)

        val goal = when (action) {
            "search", "find", "view" ->
                """You are in Contacts app. Find contact "$query".
                STEPS: 1) Tap the search icon or 'Search contacts' bar. 2) Type "$query". 3) Tap the matching contact. 4) Read and report the phone number and other details shown."""

            "add", "create", "new" ->
                """You are in Contacts app. Add a new contact.
                STEPS: 1) Tap the '+' or 'Add contact' / 'Create contact' button. 2) Tap the name field and type "$query". ${if (phone.isNotBlank()) "3) Tap the phone field and type '$phone'." else ""} ${if (email.isNotBlank()) "4) Tap the email field and type '$email'." else ""} 5) Tap 'Save'."""

            "call" ->
                """You are in Contacts. Find "$query" and show their number (do NOT dial automatically).
                STEPS: 1) Tap search icon. 2) Type "$query". 3) Tap the contact. 4) Read the phone number shown."""

            "history" ->
                """You are in Contacts. Show recent call history.
                STEPS: 1) If there is a 'Recents' or 'History' tab visible, tap it. 2) Read the recent calls list — who called, when."""

            else ->
                params["goal"] as? String ?: "Do this in Contacts: $action $query"
        }

        val result = agent.executeGoal(runner, goal, maxSteps = 15)
        return SkillResult.Success(result)
    }
}
