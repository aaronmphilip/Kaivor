## App rename feasibility — **YES, easy. Don't implement yet, but here's what it touches:**

**Three layers, ~15 minutes to swap:**

**1. Display name (what users see)**

- `app/src/main/res/values/strings.xml` → `<string name="app_name">BharatDroid</string>`
- That's the launcher icon label and any "BharatDroid" in UI

**2. Package ID (the unique app identifier)**

- `app/build.gradle.kts` → `applicationId = "com.bharatdroid.agent"`
- This is what Android uses to identify your app
- **CRITICAL CAVEAT**: Changing this means existing users' app upgrades won't work — Play Store treats it as a brand new app. SharedPreferences, accessibility permissions, all reset
- For pre-launch (which you are), totally fine to change. Once you have users, never touch it
- The Kotlin `package com.bharatdroid.agent` declarations don't have to match — but for cleanliness, also rename the `kotlin/com/bharatdroid/` folder

**3. Hardcoded strings in code**

- ~12 places where "BharatDroid" appears in user-facing text (notification title, welcome message, accessibility label, etc.)
- Quick `grep -r "BharatDroid"` finds them all

**What does NOT need changing:**

- Telegram bot names (those are user-chosen)
- API endpoints, models, skills, anything functional
- The orange brand color, icon design

**Recommendation:** Decide the new name BEFORE you have real users. After that, the `applicationId` is locked forever. The Kotlin package path can still be renamed later without breaking installs, but the `applicationId` becomes your permanent identifier in Play Store and on every device.