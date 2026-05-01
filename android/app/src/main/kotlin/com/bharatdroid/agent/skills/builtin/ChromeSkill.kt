package com.bharatdroid.agent.skills.builtin

import com.bharatdroid.agent.skills.*
import kotlinx.coroutines.delay

class ChromeSkill : Skill {

    override val manifest = SkillManifest(
        id = "chrome",
        name = "Chrome Web Browser",
        version = "6.0.0",
        description = "Search the web, open URLs, fill forms, read pages, incognito, bookmarks — any Chrome task",
        author = "bharatdroid-team",
        trusted = true,
        permissions = setOf(
            Permission.OPEN_APP, Permission.READ_SCREEN,
            Permission.TAP, Permission.TYPE, Permission.SCROLL,
            Permission.NAVIGATE_BACK,
        ),
        allowedPackages = setOf("com.android.chrome"),
        exampleParamsHint = """{"action": "search", "query": "weather in Mumbai today"}""",
        uiKnowledge = """
Google Chrome UI guide:
- Address bar: at the very top of the screen; tap it to type a URL or search query; shows current domain when on a page
- New tab: tap the + button at the bottom right, or tap the tab-count square (e.g. "3") to open the tab switcher then tap +
- Tab switcher: grid of open tab cards, each showing a page thumbnail and title with an X to close; tap a card to switch to it
- Three-dot menu: top right corner — opens Bookmarks, History, Downloads, Settings, Share, Find in page, New incognito tab
- Incognito mode: black/dark header replaces the white header when an incognito tab is active
- Bottom toolbar: back arrow, forward arrow, share, bookmark, tab switcher, three-dot menu (left to right)
- Page loading: circular spinner in address bar while loading; tap X to cancel
- Find in page: three-dot menu → "Find in page" → search field appears at the bottom of the screen
""".trimIndent(),
    )

    override suspend fun execute(context: SkillContext, params: Map<String, Any>): SkillResult {
        val runner = context.runner
        val agent = context.agent ?: return SkillResult.Failure("Agent not available.")

        val action = (params["action"] as? String)?.lowercase() ?: "search"
        val query = params["query"] as? String ?: ""
        val url = params["url"] as? String ?: ""

        runner.openApp("com.android.chrome")
        runner.waitForApp("com.android.chrome", timeoutMs = 7000)
        delay(600)
        runner.dismissPopups(2)
        delay(200)

        // For search/open: type directly into the address bar before AI takes over
        val inputText = url.ifBlank { query }
        if (inputText.isNotBlank() && action in setOf("search", "open", "navigate")) {
            // Chrome address bar is at the top ~7% of the screen
            val (w, h) = runner.getScreenSize()
            val typed = runner.typeInFieldWithHint("Search or type URL", inputText)
                || runner.typeInFieldWithHint("Search or type web address", inputText)
                || runner.typeInFieldWithHint("Address", inputText)
            if (typed) {
                delay(300)
                runner.pressEnter()
                delay(2000) // wait for page to start loading
            } else {
                runner.tapAtPoint(w * 0.5f, h * 0.055f)
                delay(400)
                runner.typeReliably(inputText)
                delay(200)
                runner.pressEnter()
                delay(2000)
            }
        }

        val goal = when (action) {
            "search" ->
                """You are in Chrome. The search "${query}" has been submitted — page is loading or already showing.
STEPS:
1. Wait for the page to fully load
2. Scroll through the search results or page
3. Read and summarize the key information found
4. Report the answer clearly"""

            "open", "navigate" ->
                """You are in Chrome. The URL "${url.ifBlank { query }}" has been entered — page should be loading.
STEPS:
1. Wait for the page to load completely
2. Read the page title and key content
3. Summarize what the page shows"""

            "read" -> {
                val screen = runner.readScreen()
                return SkillResult.Success("Current page content:\n```\n${screen.take(1000)}\n```")
            }

            "incognito" ->
                """You are in Chrome. Open a new incognito tab.
STEPS:
1. Tap the three-dot menu (⋮) at the top right
2. Tap "New Incognito tab"
3. Confirm the dark/black incognito tab is open${if (query.isNotBlank()) "\n4. Tap the address bar and type \"$query\"\n5. Press Enter" else ""}"""

            "bookmark" ->
                """You are in Chrome. Bookmark the current page.
STEPS:
1. Tap the three-dot menu (⋮) at the top right
2. Tap the star/bookmark icon or "Bookmark this page"
3. Confirm the bookmark was saved"""

            "history" ->
                """You are in Chrome. Open browsing history.
STEPS:
1. Tap the three-dot menu (⋮) at the top right
2. Tap "History"
3. Read the last 5-10 visited sites — URL and title"""

            "find" ->
                """You are in Chrome. Find "${query}" on the current page.
STEPS:
1. Tap the three-dot menu (⋮)
2. Tap "Find in page"
3. Type "$query" in the search field that appears at the bottom
4. Report how many matches were found and what context they appear in"""

            "new_tab" ->
                """You are in Chrome. Open a new tab.
STEPS:
1. Tap the tab count button (shows number like "1" or "2") at the top right
2. Tap the + button to open a new tab${if (query.isNotBlank()) "\n3. Type \"$query\" in the address bar\n4. Press Enter" else ""}"""

            else ->
                params["goal"] as? String ?: "Do this in Chrome: $action $query $url".trim()
        }

        val result = agent.executeGoal(runner, goal, maxSteps = 60)
        return SkillResult.Success(result)
    }
}
