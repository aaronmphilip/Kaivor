# Don't Kill BharatDroid — Setup Guide

BharatDroid runs as a background service 24/7. Android manufacturers add aggressive battery saving features that kill background apps. If BharatDroid stops responding to Telegram commands, stops relaying notifications, or misses tasks — this is almost always why.

Follow the steps for your phone brand below.

---

## Universal — Do These on Every Phone

These apply regardless of brand.

### 1. Disable Battery Optimization for BharatDroid
Settings → Apps → BharatDroid → Battery → **Unrestricted** (or "Don't optimize")

This is the single most important setting. Without it, Android will suspend BharatDroid when the screen is off.

### 2. Allow Background Activity
Settings → Apps → BharatDroid → Battery → **Allow background activity: ON**

### 3. Turn Off Adaptive Battery (if present)
Settings → Battery → Adaptive Battery → **OFF**

Adaptive Battery learns which apps you "don't use" and restricts them. BharatDroid runs in background by design — Adaptive Battery will wrongly flag it.

### 4. Never Use a Task Killer App
Apps like "Clean Master", "Phone Cleaner", "RAM Booster", "Speed Booster" — delete them entirely. They kill background services including BharatDroid. They don't actually help performance. They only cause problems.

### 5. Never Swipe BharatDroid Away From Recents
When you swipe an app away from the recent apps list, Android kills it. Lock BharatDroid in recents instead:
- Open Recents → find BharatDroid → **long press the app card** → tap the lock icon 🔒
- Now swiping away others won't kill BharatDroid

### 6. Keep BharatDroid in the Notification Bar
If you see BharatDroid's notification in the status bar — that's a good sign. It means the foreground service is alive. Don't dismiss it. Don't block that notification channel.

### 7. Don't Force Stop BharatDroid
Settings → Apps → BharatDroid → **Never tap Force Stop** unless you want to reset it. Force Stop kills everything including the background service.

### 8. Keep Stable Internet
BharatDroid needs internet for all AI calls. On WiFi: keep WiFi on during sleep (Settings → WiFi → Advanced → Keep WiFi on during sleep → **Always**). On mobile data: disable data saver.

---

## Samsung (One UI)

Samsung is the most aggressive at killing background apps. Do all universal steps above plus:

### Auto-restart / Background limits
Settings → Device Care → Battery → **Background usage limits**
- Sleeping apps: Make sure BharatDroid is NOT listed here
- Deep sleeping apps: BharatDroid must NOT be here
- Never sleeping apps: **Add BharatDroid here**

### App Power Management
Settings → Device Care → Battery → **App power management**
- Adaptive power saving: **OFF** for BharatDroid
- Put unused apps to sleep: **OFF** (or exclude BharatDroid)

### Allow Background Data
Settings → Apps → BharatDroid → Mobile data → **Allow background data usage: ON**

### Samsung-specific: Remove from "Sleeping Apps" list
Device Care → Battery → Background usage limits → Sleeping apps
If BharatDroid appears here, tap it and remove it.

### One UI 6+ specific
Settings → Battery → More battery settings → **Restrict background activity: OFF** for BharatDroid

---

## Xiaomi / Redmi / POCO (MIUI / HyperOS)

MIUI and HyperOS are extremely aggressive. This is the hardest brand to configure correctly.

### Autostart — Critical
Settings → Apps → Manage Apps → BharatDroid → **Autostart: ON**

Without this, BharatDroid cannot restart itself after being killed. This is the #1 fix on Xiaomi.

### Battery Saver Exclusion
Settings → Battery & Performance → App battery saver → BharatDroid → **No restrictions**

### Background Speed Boost
Settings → Battery & Performance → **Speed boost** or **Turbo boost** → Make sure BharatDroid is excluded from being suspended.

### MIUI Optimization (if available)
Settings → Additional Settings → **MIUI Optimization: OFF** — This is a developer-level toggle that removes some of MIUI's interference. Optional but helps.

### Lock in Recents — Very Important on Xiaomi
Open Recents → find BharatDroid card → swipe down on the card (not away) → a lock icon appears → tap it. This prevents the aggressive recents cleaner from killing it.

### Data Usage in Background
Settings → Apps → Manage Apps → BharatDroid → Data usage → **Background data: ON**

### Don't Use the Built-in Cleaner
The "Cleaner" or "Speed" button in the phone manager app kills background services. Never tap it.

---

## OnePlus / OPPO / Realme (OxygenOS / ColorOS)

### Battery Optimization
Settings → Battery → Battery Optimization → All apps → BharatDroid → **Don't optimize**

### Auto Launch
Settings → Apps → BharatDroid → **Auto launch: ON** (if available)

### Background App Refresh
Settings → Apps → BharatDroid → **Allow background activity: ON**

### Doze Mode Exclusion
Settings → Battery → **App quick freeze** or **App hibernation** → Make sure BharatDroid is excluded

### ColorOS specific: App Freeze
Settings → Apps → BharatDroid → **Freeze app when closed: OFF**

### OnePlus specific: Advanced Optimization
Settings → Battery → **Adaptive charging** and **Optimized charging** — these are fine to keep on. The one to watch is "Optimize app usage" — exclude BharatDroid.

### Lock in Recents
Open Recents → long press BharatDroid card → **Lock** 🔒

---

## Vivo (FuntouchOS / OriginOS)

### Background Power Consumption
Settings → Battery → **Background power consumption management** → BharatDroid → **No restrictions**

### High Background Power Consumption Alert
If Vivo shows "BharatDroid is using too much battery in background" — tap **Allow** and turn off the alert for it.

### Auto Launch Manager
Settings → Privacy → **Auto Launch manager** → BharatDroid → **ON**

### Allow Association Launch
Settings → Privacy → Auto Launch → BharatDroid → **Associated launch: ON**

---

## Motorola

Motorola runs close to stock Android and is the most friendly to background apps. Still do:

### Battery Optimization
Settings → Apps → BharatDroid → Battery → **Unrestricted**

### Moto Actions
Settings → Moto → **Moto Gametime** — if enabled, exclude BharatDroid from game mode restrictions.

### Data Saver
Settings → Network → Data Saver → Unrestricted apps → **Add BharatDroid**

---

## Nothing Phone (Nothing OS)

Nothing OS is close to stock but has some restrictions:

### Battery Optimization
Settings → Battery → Battery Optimization → BharatDroid → **Don't optimize**

### Background App Management
Settings → Apps → BharatDroid → Battery → **Unrestricted**

Nothing is generally well-behaved. The above two steps are usually enough.

---

## Nokia / Stock Android / Android One

These are the most well-behaved. Standard steps apply:

### Battery Optimization
Settings → Apps → BharatDroid → Battery → **Unrestricted**

### Adaptive Battery
Settings → Battery → Adaptive Battery → **OFF**

That's usually all you need on stock Android.

---

## Signs BharatDroid Has Been Killed

- Telegram commands stop working
- Notifications stop being relayed
- The BharatDroid notification disappears from the status bar
- App shows "offline" status in the dashboard

**Fix:** Open the BharatDroid app manually. It will restart the service. Then check which settings above you missed.

---

## The Golden Rule

The phone is the agent. **Its job is to run BharatDroid, not to save battery.**

If it's a dedicated side phone: set it to **Performance mode**, plug it in overnight, and don't worry about battery at all. If it's your daily phone: the settings above will keep BharatDroid alive without meaningfully affecting battery life — the foreground service is lightweight.

---

*This guide covers Android 10 and above. UI paths may vary slightly by OS version.*
