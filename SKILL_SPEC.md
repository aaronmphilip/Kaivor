# BharatClaw Skill Specification

Build a skill. Add it to your agent. Share it with the community.

---

## What is a Skill?

A skill is a Kotlin class that teaches BharatClaw how to control one specific app.
The agent gets a sandboxed runner — it can only do what it declared in its manifest.
No arbitrary code execution. No hidden permissions. No surprises.

---

## Skill Anatomy

```kotlin
class MyAppSkill : Skill {

    override val manifest = SkillManifest(
        id = "myapp",                          // unique, lowercase, no spaces
        name = "MyApp Skill",                  // human readable
        version = "1.0.0",
        description = "Does X on MyApp",       // shown to users
        author = "your-github-username",
        trusted = false,                        // only BharatClaw team sets true
        permissions = setOf(
            Permission.OPEN_APP,
            Permission.READ_SCREEN,
            Permission.TAP,
            Permission.TYPE,
            Permission.SCROLL,
            // Permission.PAYMENT — only if you NEED to touch payment screens
        ),
        allowedPackages = setOf("com.myapp.android"),  // required with OPEN_APP
        exampleParamsHint = """{"query": "something"}""",  // helps the AI know what to pass
    )

    override suspend fun execute(context: SkillContext, params: Map<String, Any>): SkillResult {
        val runner = context.runner

        // 1. Get params from AI
        val query = params["query"] as? String
            ?: return SkillResult.Failure("No query provided")

        // 2. Open the app (only works if OPEN_APP is declared + package is in allowedPackages)
        runner.openApp("com.myapp.android")

        // 3. Wait for app to load
        if (!runner.waitForApp("com.myapp.android", timeoutMs = 6000)) {
            return SkillResult.Failure("App didn't open. Is it installed?")
        }

        // 4. Interact
        runner.tapByText("Search")
        runner.typeInFieldWithHint("Search", query)

        // 5. Read result
        val screen = runner.readScreen()

        // 6. Optionally ask user to confirm before doing something risky
        return SkillResult.NeedsConfirmation(
            prompt = "Found: XYZ. Proceed? Reply YES.",
            onConfirm = {
                runner.tapByText("Order")
                SkillResult.Success("Done! Order placed.")
            }
        )
    }
}
```

---

## Available Runner Methods

These are the ONLY things a skill can do. Attempting anything else throws a SecurityException.

### App Control
| Method | Permission Required |
|--------|-------------------|
| `openApp(packageName: String)` | `OPEN_APP` + package in `allowedPackages` |

### Screen Reading
| Method | Permission Required |
|--------|-------------------|
| `readScreen(): String` | `READ_SCREEN` |
| `findByText(text: String): AccessibilityNodeInfo?` | `READ_SCREEN` |
| `findById(viewId: String): AccessibilityNodeInfo?` | `READ_SCREEN` |
| `screenContains(text: String): Boolean` | `READ_SCREEN` |

### Interaction
| Method | Permission Required |
|--------|-------------------|
| `tap(node: AccessibilityNodeInfo): Boolean` | `TAP` |
| `tapByText(text: String): Boolean` | `TAP` |
| `typeText(node, text: String): Boolean` | `TYPE` |
| `typeInFieldWithHint(hint: String, text: String): Boolean` | `TYPE` |
| `scrollDown(): Boolean` | `SCROLL` |
| `scrollUp(): Boolean` | `SCROLL` |
| `pressBack()` | `NAVIGATE_BACK` |

### Clipboard
| Method | Permission Required |
|--------|-------------------|
| `setClipboard(text: String)` | `CLIPBOARD` |

### Waiting (suspend)
| Method | Permission Required |
|--------|-------------------|
| `waitForText(text: String, timeoutMs: Long): Boolean` | `READ_SCREEN` |
| `waitForApp(packageName: String, timeoutMs: Long): Boolean` | none |

---

## Permissions Reference

| Permission | What it allows | Trust level |
|-----------|---------------|------------|
| `READ_SCREEN` | Read all visible text/UI | Low |
| `TAP` | Tap UI elements | Low |
| `TYPE` | Type text into fields | Low |
| `SCROLL` | Scroll up/down | Low |
| `OPEN_APP` | Launch other apps | Low (package-restricted) |
| `NAVIGATE_BACK` | Press back button | Low |
| `CLIPBOARD` | Read/write clipboard | Medium |
| `NOTIFICATIONS` | Read notifications | Medium |
| `SCREENSHOT` | Capture screen | Medium |
| `PAYMENT` | Touch payment/checkout UI | **HIGH** — user confirms every run |
| `SENSITIVE_READ` | Read messages/banking data | **HIGH** — user confirms every run |

---

## Skill Safety Rules

1. **Declare all permissions** — the sandbox enforces this. Your skill will crash if you use an undeclared permission.
2. **List all packages** — if you use `OPEN_APP`, every package you open must be in `allowedPackages`.
3. **Never request `PAYMENT` unless you actually need it** — it adds friction for users.
4. **Community skills always show a warning** — users see your name and permissions before running.
5. **No network calls** — skills can't make HTTP requests. The runner doesn't expose network access. If you need data, read it from the screen.
6. **No file system access** — skills run in a sandboxed context with no file I/O.

---

## Submitting a Community Skill

1. Fork this repo
2. Create `skills/community/<your-skill-id>/` with:
   - `MySkill.kt` — the skill implementation
   - `skill.json` — metadata (id, name, version, description, author, permissions, allowedPackages)
3. Open a pull request
4. Community votes + maintainer review
5. Merged skills are available in the skills browser

### skill.json format
```json
{
  "id": "myapp",
  "name": "MyApp Skill",
  "version": "1.0.0",
  "description": "Does X on MyApp",
  "author": "your-github-username",
  "permissions": ["OPEN_APP", "READ_SCREEN", "TAP", "TYPE"],
  "allowedPackages": ["com.myapp.android"],
  "exampleParamsHint": "{\"query\": \"something\"}"
}
```

---

## Examples of Good Skills to Build

- `phonepe` — check balance, send money (PAYMENT required)
- `paytm` — recharge, bill payment (PAYMENT required)
- `flipkart` — search and track orders
- `meesho` — browse and order
- `gpay` — UPI payments (PAYMENT required)
- `zomato` — order food
- `ola` / `uber` — book rides
- `chrome` — search the web, fill forms
- `instagram` — post, DM (SENSITIVE_READ if reading DMs)
- `whatsapp` — send messages to contacts (SENSITIVE_READ)
- `hotstar` — search and play content
- `irctc` — check PNR status, seat availability (do NOT attempt booking automation)
