package com.bharatdroid.agent.skills.builtin

import com.bharatdroid.agent.skills.*
import kotlinx.coroutines.delay

class FileManagerSkill : Skill {

    override val manifest = SkillManifest(
        id = "files",
        name = "File Manager",
        version = "3.0.0",
        description = "Browse files, search documents, open and read entire files by scrolling, share files to WhatsApp or any app via the share button, manage downloads on Android.",
        author = "bharatdroid-team",
        trusted = true,
        permissions = setOf(
            Permission.OPEN_APP, Permission.READ_SCREEN,
            Permission.TAP, Permission.TYPE, Permission.SCROLL,
            Permission.NAVIGATE_BACK,
        ),
        allowedPackages = setOf(
            "com.google.android.apps.nbu.files",
            "com.google.android.documentsui",
            "com.mi.android.globalFileexplorer",
            "com.coloros.filemanager",
        ),
        exampleParamsHint = """{"action":"share_to_whatsapp","file":"invoice.pdf","contact":"Rahul"} | {"action":"read_file","file":"report.pdf"} | {"action":"browse","folder":"Downloads"}""",
    )

    // Supported file manager packages — tried in preference order
    private val fileApps = listOf(
        "com.google.android.apps.nbu.files",    // Google Files (most common)
        "com.google.android.documentsui",        // Android stock Document UI
        "com.mi.android.globalFileexplorer",     // MIUI File Explorer
        "com.coloros.filemanager",               // ColorOS File Manager
    )

    override suspend fun execute(context: SkillContext, params: Map<String, Any>): SkillResult {
        val runner = context.runner
        val agent = context.agent ?: return SkillResult.Failure("Agent not available.")

        val action = (params["action"] as? String)?.lowercase()?.trim() ?: "browse"
        val folder = (params["folder"] as? String)?.trim() ?: ""
        val query = ((params["search"] as? String)
            ?: params["file"] as? String
            ?: params["document"] as? String
            ?: params["query"] as? String
            ?: "").trim()
        val contact = (params["contact"] as? String ?: params["to"] as? String ?: "").trim()

        // ── Share to WhatsApp — uses Android share sheet; no need to open file manager first ──
        if (action in setOf("share_to_whatsapp", "share", "send_to_whatsapp", "whatsapp_share")) {
            if (query.isBlank()) return SkillResult.Failure("Which file should I share? (e.g. invoice.pdf)")
            if (contact.isBlank()) return SkillResult.Failure("Who should I share \"$query\" with on WhatsApp?")
            return shareToWhatsApp(runner, agent, query, contact)
        }

        // ── Read file by scrolling — opens file in viewer and reads all content ──
        if (action in setOf("read_file", "read", "open_read", "view_file")) {
            if (query.isBlank()) return SkillResult.Failure("Which file should I read?")
            return readFileByScrolling(runner, agent, query)
        }

        // ── All other actions need the file manager to be open ──
        val opened = openFileManager(runner)
        if (!opened) return SkillResult.Failure("No file manager found. Install Google Files from the Play Store.")
        delay(600)
        runner.dismissPopups(1)
        delay(200)

        val goal = when (action) {
            "browse", "view", "list" -> buildBrowseGoal(folder)
            "search", "find"        -> buildSearchGoal(query)
            "downloads"             -> buildDownloadsGoal()
            "open"                  -> buildOpenGoal(query)
            "delete"                -> return handleDelete(runner, agent, query)
            else -> params["goal"] as? String
                ?: "Do this in the File Manager app: $action ${folder.ifBlank { query }}".trim()
        }

        val result = agent.executeGoal(runner, goal, maxSteps = 50)
        return SkillResult.Success(result)
    }

    // ── Share a file to a WhatsApp contact using the Android share sheet ───────
    private suspend fun shareToWhatsApp(
        runner: SandboxedRunner,
        agent: com.bharatdroid.agent.ScreenAgent,
        fileName: String,
        contactName: String,
    ): SkillResult {
        // Open file manager first so we can find and share the file
        val opened = openFileManager(runner)
        if (!opened) return SkillResult.Failure("No file manager found. Install Google Files.")
        delay(600)
        runner.dismissPopups(1)
        delay(200)

        val goal = """
Share the file "$fileName" to "$contactName" on WhatsApp using the file manager share button.

STEP-BY-STEP:
1. Tap the search icon (🔍 magnifying glass at the top). Type "$fileName". Wait for results.
2. When "$fileName" appears in results, LONG-PRESS it for ~1 second — a checkmark or selection indicator appears.
3. Look for a SHARE icon in the toolbar (bent arrow ↗, or three dots → Share). Tap it.
4. An Android share sheet pops up listing apps. Find "WhatsApp" (green icon) and TAP it.
5. WhatsApp opens with a contact/chat chooser. TAP the search bar at the top.
6. Type "$contactName". Tap the contact from search results.
7. WhatsApp shows a preview of the file. Tap the green SEND button (arrow ➤ at bottom-right).
8. DONE — report "Shared $fileName to $contactName on WhatsApp ✅".

RULES:
- If "$fileName" is not found in search, try browsing Downloads folder and look there.
- Do NOT tap any chat directly without searching — you might open the wrong chat.
- The share button may appear as 3 dots (⋮) menu → Share if no direct icon is visible.
        """.trimIndent()

        val result = agent.executeGoal(runner, goal, maxSteps = 65)
        return SkillResult.Success(result)
    }

    // ── Open a file in its viewer and read ALL content by scrolling through it ─
    private suspend fun readFileByScrolling(
        runner: SandboxedRunner,
        agent: com.bharatdroid.agent.ScreenAgent,
        fileName: String,
    ): SkillResult {
        val opened = openFileManager(runner)
        if (!opened) return SkillResult.Failure("No file manager found.")
        delay(600)
        runner.dismissPopups(1)
        delay(200)

        val openGoal = """
Open "$fileName" in the File Manager so I can read its content.

STEPS:
1. Tap the search icon and type "$fileName".
2. When the file appears, TAP it once to open it in its viewer (PDF viewer, document reader, etc.).
3. Wait for the file to fully load. Report what you see on the first screen.
4. done — report the first screen content and that the file is open.
        """.trimIndent()

        // First, open the file
        agent.executeGoal(runner, openGoal, maxSteps = 35)
        delay(800)

        // Now scroll and collect all text
        val allContent = StringBuilder()
        val seenLines = mutableSetOf<String>()

        fun addContent(screen: String) {
            screen.lines().forEach { line ->
                val normalized = line.trim()
                if (normalized.length > 2 && seenLines.add(normalized.lowercase())) {
                    allContent.appendLine(normalized)
                }
            }
        }

        // Read first screen
        addContent(runner.readScreen())

        // Scroll down up to 12 times reading each new screen
        var noNewContentCount = 0
        for (i in 1..12) {
            val prevSize = allContent.length
            runner.scrollDown()
            delay(600)
            addContent(runner.readScreen())
            if (allContent.length == prevSize) {
                noNewContentCount++
                if (noNewContentCount >= 2) break // reached end of document
            } else {
                noNewContentCount = 0
            }
        }

        val content = allContent.toString().trim()
        if (content.isBlank()) return SkillResult.Failure("Could not read content from \"$fileName\". Make sure it's a text-readable file (PDF, TXT, DOCX).")

        val preview = content.take(4000)
        val suffix = if (content.length > 4000) "\n\n_...content truncated. ${content.length} chars total._" else ""
        return SkillResult.Success(
            "📄 *$fileName*\n\n```\n$preview\n```$suffix",
            delivery = DeliveryMode.LONG_TEXT,
        )
    }

    // ── File manager helpers ──────────────────────────────────────────────────

    private suspend fun openFileManager(runner: SandboxedRunner): Boolean {
        for (app in fileApps) {
            try {
                runner.openApp(app)
                if (runner.waitForApp(app, timeoutMs = 3500)) return true
            } catch (_: Exception) { continue }
        }
        return false
    }

    private fun buildBrowseGoal(folder: String): String {
        val target = folder.ifBlank { "Downloads" }
        return """You are in a File Manager app. Browse to the "$target" folder.
STEPS:
1. Look for "$target" in the sidebar or the main category list.
2. Tap it to open.
3. Read and list all files visible — name, size, date if shown.
4. Scroll down to see more files if needed.
5. done — report the files found."""
    }

    private fun buildSearchGoal(query: String): String =
        """You are in a File Manager. Search for "$query".
STEPS:
1. Tap the search icon (magnifying glass 🔍) at the top of the screen.
2. Type "$query" in the search field.
3. Press Enter or wait for results.
4. Read all matching file names, sizes, and locations.
5. done — report what was found."""

    private fun buildDownloadsGoal(): String =
        """You are in a File Manager. Open the Downloads folder.
STEPS:
1. Tap "Downloads" from the sidebar or main categories.
2. List the files — name, size, date modified if visible.
3. Scroll down to see more if needed.
4. done — report the file list."""

    private fun buildOpenGoal(query: String): String =
        """You are in a File Manager. Open the file "$query".
STEPS:
1. If not visible on screen, tap the search icon and search for "$query".
2. Tap the file once to open it.
3. If asked which app to use, tap the most appropriate viewer.
4. done — report what app it opened in and what is visible."""

    private suspend fun handleDelete(
        runner: SandboxedRunner,
        agent: com.bharatdroid.agent.ScreenAgent,
        query: String,
    ): SkillResult {
        if (query.isBlank()) return SkillResult.Failure("Which file should I delete?")
        val deleteGoal = """You are in a File Manager. Delete the file "$query".
STEPS:
1. If not visible, tap the search icon and search for "$query".
2. Long-press the file to select it (a checkmark appears).
3. Tap the Delete or Trash icon in the toolbar.
4. Confirm deletion if a dialog appears.
5. done — report success or not found."""
        return SkillResult.NeedsConfirmation(
            prompt = "🗑️ *Delete File*\n\nFile: *$query*\n\n⚠️ Permanently deletes this file. Cannot be undone.\n\nReply *YES* to confirm.",
            onConfirm = {
                val result = agent.executeGoal(runner, deleteGoal, maxSteps = 40)
                SkillResult.Success(result)
            }
        )
    }
}
