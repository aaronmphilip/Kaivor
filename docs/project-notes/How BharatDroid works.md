Run on the Android Studio Path: C:\Users\Astro\OneDrive\Desktop\BharatClaw\android
---
## One-time setup (do once, forget)

**1. Telegram Bot** Create a bot via @BotFather → get the token → paste into the app during onboarding. Then send `/start` to your bot once to pair your chat ID. Done forever.

**2. AI API Key** Gemini (free tier works), Claude, or OpenAI. Paste the key in onboarding. The app stores it locally, never sends it anywhere except the AI provider's API.

**3. Target apps installed** For Swiggy skill to work → Swiggy must be installed. For GPay → GPay must be installed. The agent controls whatever is on the phone — it can't control apps that aren't there.

---

## Must stay ON permanently (the four pillars)

**1. Accessibility Service** `Settings → Accessibility → BharatDroid → ON`

This is the core. Without it the agent is blind and paralysed — can't tap, can't type, can't read the screen, can't do anything. If Android kills it or the user turns it off, zero skills work.

**2. Foreground Service (the app must be running)** The agent runs as a foreground notification — you'll see it in your notification shade. Android keeps foreground services alive. If you force-stop the app from Settings → Apps, everything stops. The BootReceiver re-launches it after a phone restart automatically.

**3. Battery optimisation OFF for BharatDroid** `Settings → Battery → App battery usage / Optimize battery → BharatDroid → Don't restrict`

This is the most commonly missed one. Android will aggressively kill the foreground service and pause background processes to save battery unless you exempt the app. On Samsung/Xiaomi/OnePlus this setting is buried and named differently ("Unrestricted", "No restrictions", "Don't optimize") — but it's critical. Without it the Telegram poller goes to sleep and commands stop arriving.

**4. Internet connection** The agent polls Telegram every ~30 seconds and sends every command to the AI API. WiFi or mobile data must be on. Offline = agent deaf.

---

## For specific features, these additionally need to be ON

| Feature                               | Extra requirement                                                                                                                        |
| ------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------- |
| **Notification Relay**                | `Settings → Notification Access → BharatDroid → ON`                                                                                      |
| **`/screenshot`**                     | Accessibility Service (same as core)                                                                                                     |
| **`/schedule`**                       | Android 12+: `Settings → Apps → Special app access → Alarms & reminders → BharatDroid → Allow`                                           |
| **Screen interaction** (tapping apps) | Screen must turn on — the agent handles this with a WakeLock. But if you have a very aggressive screen lock PIN, it may not get past it. |
| **`/open`, app skills**               | Those specific apps must be installed                                                                                                    |

---

## What happens if something is off

|If this is off|What breaks|
|---|---|
|Accessibility Service|Every skill fails. Agent can only answer text questions.|
|Foreground Service killed|Telegram stops being polled. Commands never arrive.|
|Battery optimisation ON|Works for ~5 minutes after screen off, then goes dark.|
|Notification Access off|Relay stops. `/mute` and `/unmute` still work but nothing forwards.|
|Internet off|Complete silence — commands queue in Telegram but agent never sees them.|

---

## The one users always forget

**Battery optimisation.** Every other issue is obvious — no internet is obvious, accessibility being off throws an error. But battery optimisation silently kills the service 10 minutes after the screen goes off, and the user just thinks "the bot isn't responding" with no error message. This is the #1 support issue for any Telegram bot agent on Android.

Worth adding a check in `/status` that detects if BharatDroid is battery-optimised and warns the user directly. Want me to add that?

