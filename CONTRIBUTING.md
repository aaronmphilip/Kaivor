# Contributing to BharatDroid

First off — thank you for wanting to help! 🙏

BharatDroid is built for India and the world. Whether you fix a bug, add a new skill, improve docs, or just star the repo — every bit helps.

---

## 🐛 Reporting Bugs

1. Check [existing issues](https://github.com/aaronmphilip/BharatDroid/issues) first
2. Open a new issue with:
   - What you said to the bot
   - What it did vs what you expected
   - Your Android version + phone model
   - Which AI provider (Gemini / Claude / OpenAI)

---

## ➕ Adding a New Skill

A **Skill** is a thin wrapper that opens an app and describes a goal to the AI. The AI figures out what to tap — you just write the goal string.

### Skill Template

```kotlin
package com.bharatdroid.agent.skills.builtin

import com.bharatdroid.agent.skills.*
import kotlinx.coroutines.delay

class MyAppSkill : Skill {

    override val manifest = SkillManifest(
        id = "myapp",                          // unique lowercase id
        name = "My App",                       // display name
        version = "1.0.0",
        description = "What this skill does",
        author = "your-github-username",
        trusted = false,                       // true = BharatDroid team only
        permissions = setOf(
            Permission.OPEN_APP,
            Permission.READ_SCREEN,
            Permission.TAP,
            Permission.TYPE,
            Permission.SCROLL,
            Permission.NAVIGATE_BACK,
            // Permission.PAYMENT   ← add if skill handles money
        ),
        allowedPackages = setOf("com.example.myapp"),
        exampleParamsHint = """{"action": "search", "query": "shoes"}""",
    )

    override suspend fun execute(context: SkillContext, params: Map<String, Any>): SkillResult {
        val runner = context.runner
        val agent = context.agent ?: return SkillResult.Failure("Agent not available.")

        val action = (params["action"] as? String)?.lowercase() ?: "search"
        val query = params["query"] as? String ?: ""

        // 1. Open the app
        runner.openApp("com.example.myapp")
        runner.waitForApp("com.example.myapp", timeoutMs = 6000)
        delay(800)
        runner.dismissPopups(2)
        delay(200)

        // 2. Describe the goal — AI handles the actual navigation
        val goal = when (action) {
            "search" ->
                """You are in My App. Search for "$query".
                STEPS: 1) Tap the search bar. 2) Type "$query". 3) Press Enter. 4) Read results."""

            else ->
                params["goal"] as? String ?: "Do this in My App: $action $query".trim()
        }

        // 3. Let the AI execute
        val result = agent.executeGoal(runner, goal, maxSteps = 20)
        return SkillResult.Success(result)
    }
}
```

### Register your skill

Add it to the skill registry in `SkillRunner.kt`:
```kotlin
register(MyAppSkill())
```

### Skill Rules

- ✅ Use `executeGoal()` — don't hardcode tap coordinates
- ✅ Write clear, step-by-step goal strings — the AI follows them
- ✅ Add an `else` branch with `params["goal"]` fallback
- ✅ Always call `runner.dismissPopups()` after opening an app
- ❌ Don't use `tapByText()` for main flow — let the AI decide
- ❌ Don't hardcode UI element indices
- ❌ Don't add app-specific logic that won't generalize

### Permissions Reference

| Permission | When to use |
|---|---|
| `OPEN_APP` | Skill opens an app |
| `READ_SCREEN` | Skill reads screen content |
| `TAP` | Skill taps elements |
| `TYPE` | Skill types text |
| `SCROLL` | Skill scrolls |
| `NAVIGATE_BACK` | Skill presses back |
| `PAYMENT` | Skill handles money — requires user confirmation every time |
| `SENSITIVE_READ` | Skill reads private info (emails, messages) |

---

## 🔧 Development Setup

```bash
# Clone
git clone https://github.com/aaronmphilip/BharatDroid.git
cd BharatDroid

# Open in Android Studio (Hedgehog or later)
# File → Open → select the `android/` folder

# Build debug APK
cd android
./gradlew assembleDebug

# APK location:
# android/app/build/outputs/apk/debug/app-debug.apk
```

**Requirements:**
- Android Studio Hedgehog+
- JDK 17
- Android SDK 34

---

## 📦 Submitting a PR

1. Fork the repo
2. Create a branch: `git checkout -b skill/myapp` or `fix/issue-123`
3. Make your changes
4. Test on a real device (emulators don't support Accessibility Service well)
5. Open a PR with:
   - What you added/fixed
   - How to test it
   - Screenshot or screen recording if it's a UI change

---

## 💡 Ideas We'd Love Help With

- **New skills** — any popular Indian app: IRCTC, Meesho, Snapdeal, Dunzo, Porter
- **More languages** — Tamil, Telugu, Bengali, Marathi support
- **Better voice dismissal** — catch more voice search patterns across apps
- **UI improvements** — the setup screen, status display
- **Tests** — unit tests for ScreenAgent parsing, AIBrain routing

---

## Code Style

- Kotlin idiomatic style
- No magic numbers — use named constants
- Keep skill `execute()` functions short — delegate complexity to `executeGoal()`
- Comment the *why*, not the *what*

---

Thanks for contributing! 🙌
