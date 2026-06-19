# Don't Kill Kaivor — Setup Guide

Kaivor runs as a background service 24/7. Android manufacturers add aggressive battery saving features that kill background apps. If Kaivor stops responding to Telegram commands, stops relaying notifications, or misses tasks — this is almost always why.

Follow the steps for your phone brand below.

---

## Universal — Do These on Every Phone

These apply regardless of brand.

### 1. Disable Battery Optimization for Kaivor
Settings → Apps → Kaivor → Battery → **Unrestricted** (or "Don't optimize")

This is the single most important setting. Without it, Android will suspend Kaivor when the screen is off.

### 2. Allow Background Activity
Settings → Apps → Kaivor → Battery → **Allow background activity: ON**

### 3. Turn Off Adaptive Battery (if present)
Settings → Battery → Adaptive Battery → **OFF**

Adaptive Battery learns which apps you "don't use" and restricts them. Kaivor runs in background by design — Adaptive Battery will wrongly flag it.

### 4. Never Use a Task Killer App
Apps like "Clean Master", "Phone Cleaner", "RAM Booster", "Speed Booster" — delete them entirely. They kill background services including Kaivor. They don't actually help performance. They only cause problems.

### 5. Never Swipe Kaivor Away From Recents
When you swipe an app away from the recent apps list, Android kills it. Lock Kaivor in recents instead:
- Open Recents → find Kaivor → **long press the app card** → tap the lock icon 🔒
- Now swiping away others won't kill Kaivor

### 6. Keep Kaivor in the Notification Bar
If you see Kaivor's notification in the status bar — that's a good sign. It means the foreground service is alive. Don't dismiss it. Don't block that notification channel.

### 7. Don't Force Stop Kaivor
Settings → Apps → Kaivor → **Never tap Force Stop** unless you want to reset it. Force Stop kills everything including the background service.

### 8. Keep Stable Internet
Kaivor needs internet for all AI calls. On WiFi: keep WiFi on during sleep (Settings → WiFi → Advanced → Keep WiFi on during sleep → **Always**). On mobile data: disable data saver.

---

## Samsung (One UI)

Samsung is the most aggressive at killing background apps. Do all universal steps above plus:

### Auto-restart / Background limits
Settings → Device Care → Battery → **Background usage limits**
- Sleeping apps: Make sure Kaivor is NOT listed here
- Deep sleeping apps: Kaivor must NOT be here
- Never sleeping apps: **Add Kaivor here**

### App Power Management
Settings → Device Care → Battery → **App power management**
- Adaptive power saving: **OFF** for Kaivor
- Put unused apps to sleep: **OFF** (or exclude Kaivor)

### Allow Background Data
Settings → Apps → Kaivor → Mobile data → **Allow background data usage: ON**

### Samsung-specific: Remove from "Sleeping Apps" list
Device Care → Battery → Background usage limits → Sleeping apps
If Kaivor appears here, tap it and remove it.

### One UI 6+ specific
Settings → Battery → More battery settings → **Restrict background activity: OFF** for Kaivor

---

## Xiaomi / Redmi / POCO (MIUI / HyperOS)

MIUI and HyperOS are extremely aggressive. This is the hardest brand to configure correctly.

### Autostart — Critical
Settings → Apps → Manage Apps → Kaivor → **Autostart: ON**

Without this, Kaivor cannot restart itself after being killed. This is the #1 fix on Xiaomi.

### Battery Saver Exclusion
Settings → Battery & Performance → App battery saver → Kaivor → **No restrictions**

### Background Speed Boost
Settings → Battery & Performance → **Speed boost** or **Turbo boost** → Make sure Kaivor is excluded from being suspended.

### MIUI Optimization (if available)
Settings → Additional Settings → **MIUI Optimization: OFF** — This is a developer-level toggle that removes some of MIUI's interference. Optional but helps.

### Lock in Recents — Very Important on Xiaomi
Open Recents → find Kaivor card → swipe down on the card (not away) → a lock icon appears → tap it. This prevents the aggressive recents cleaner from killing it.

### Data Usage in Background
Settings → Apps → Manage Apps → Kaivor → Data usage → **Background data: ON**

### Don't Use the Built-in Cleaner
The "Cleaner" or "Speed" button in the phone manager app kills background services. Never tap it.

---

## OnePlus / OPPO / Realme (OxygenOS / ColorOS)

### Battery Optimization
Settings → Battery → Battery Optimization → All apps → Kaivor → **Don't optimize**

### Auto Launch
Settings → Apps → Kaivor → **Auto launch: ON** (if available)

### Background App Refresh
Settings → Apps → Kaivor → **Allow background activity: ON**

### Doze Mode Exclusion
Settings → Battery → **App quick freeze** or **App hibernation** → Make sure Kaivor is excluded

### ColorOS specific: App Freeze
Settings → Apps → Kaivor → **Freeze app when closed: OFF**

### OnePlus specific: Advanced Optimization
Settings → Battery → **Adaptive charging** and **Optimized charging** — these are fine to keep on. The one to watch is "Optimize app usage" — exclude Kaivor.

### Lock in Recents
Open Recents → long press Kaivor card → **Lock** 🔒

---

## Vivo (FuntouchOS / OriginOS)

### Background Power Consumption
Settings → Battery → **Background power consumption management** → Kaivor → **No restrictions**

### High Background Power Consumption Alert
If Vivo shows "Kaivor is using too much battery in background" — tap **Allow** and turn off the alert for it.

### Auto Launch Manager
Settings → Privacy → **Auto Launch manager** → Kaivor → **ON**

### Allow Association Launch
Settings → Privacy → Auto Launch → Kaivor → **Associated launch: ON**

---

## Motorola

Motorola runs close to stock Android and is the most friendly to background apps. Still do:

### Battery Optimization
Settings → Apps → Kaivor → Battery → **Unrestricted**

### Moto Actions
Settings → Moto → **Moto Gametime** — if enabled, exclude Kaivor from game mode restrictions.

### Data Saver
Settings → Network → Data Saver → Unrestricted apps → **Add Kaivor**

---

## Nothing Phone (Nothing OS)

Nothing OS is close to stock but has some restrictions:

### Battery Optimization
Settings → Battery → Battery Optimization → Kaivor → **Don't optimize**

### Background App Management
Settings → Apps → Kaivor → Battery → **Unrestricted**

Nothing is generally well-behaved. The above two steps are usually enough.

---

## Nokia / Stock Android / Android One

These are the most well-behaved. Standard steps apply:

### Battery Optimization
Settings → Apps → Kaivor → Battery → **Unrestricted**

### Adaptive Battery
Settings → Battery → Adaptive Battery → **OFF**

That's usually all you need on stock Android.

---

## Signs Kaivor Has Been Killed

- Telegram commands stop working
- Notifications stop being relayed
- The Kaivor notification disappears from the status bar
- App shows "offline" status in the dashboard

**Fix:** Open the Kaivor app manually. It will restart the service. Then check which settings above you missed.

---

## The Golden Rule

The phone is the agent. **Its job is to run Kaivor, not to save battery.**

If it's a dedicated side phone: set it to **Performance mode**, plug it in overnight, and don't worry about battery at all. If it's your daily phone: the settings above will keep Kaivor alive without meaningfully affecting battery life — the foreground service is lightweight.

---

*This guide covers Android 10 and above. UI paths may vary slightly by OS version.*
