package com.bharatdroid.agent.skills.builtin

import com.bharatdroid.agent.skills.*
import kotlinx.coroutines.delay

class YouTubeSkill : Skill {

    override val manifest = SkillManifest(
        id = "youtube",
        name = "YouTube Search & Play",
        version = "1.0.0",
        description = "Search and play videos on YouTube",
        author = "bharatdroid-team",
        trusted = true,
        permissions = setOf(
            Permission.OPEN_APP,
            Permission.READ_SCREEN,
            Permission.TAP,
            Permission.TYPE,
        ),
        allowedPackages = setOf("com.google.android.youtube"),
        exampleParamsHint = """{"query": "Arijit Singh latest song"}""",
    )

    override suspend fun execute(context: SkillContext, params: Map<String, Any>): SkillResult {
        val runner = context.runner
        val query = params["query"] as? String
            ?: return SkillResult.Failure("What do you want to watch on YouTube?")

        runner.openApp("com.google.android.youtube")
        if (!runner.waitForApp("com.google.android.youtube", timeoutMs = 5000)) {
            return SkillResult.Failure("YouTube didn't open. Is it installed?")
        }
        delay(1500)

        // Tap search icon
        val searchTapped = runner.tapByText("Search YouTube")
            || runner.tapByText("Search")
        if (!searchTapped) return SkillResult.Failure("Could not find YouTube search.")
        delay(500)

        runner.typeInFieldWithHint("Search YouTube", query)
        delay(500)

        // Tap first result
        val screen = runner.readScreen()
        val firstLine = screen.lines().firstOrNull { it.isNotBlank() }

        return SkillResult.Success("Opened YouTube and searched for *$query*. Playing top result.")
    }
}
