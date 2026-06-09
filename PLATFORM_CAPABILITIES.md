# BharatClaw — Android vs iOS Platform Capabilities

---

## Android — Full Capabilities

| Feature | Status | Notes |
|---------|--------|-------|
| Task execution (open apps, tap, type, scroll) | ✅ Full | AccessibilityService — works on all Android 5+ |
| Telegram command channel | ✅ Full | Long-polling bot, works screen-off |
| Notification relay (forward other apps' alerts to Telegram) | ✅ Full | NotificationListenerService |
| Floating overlay pill (notch UI) | ✅ Full | WindowManager TYPE_APPLICATION_OVERLAY |
| WhatsApp as command channel | ✅ Full | Read notification → route as command → reply back |
| AI call answering (cellular) | ✅ Full | InCallService — any Android 8+ |
| Cloned voice calls (ElevenLabs) | ✅ Full | Audio injected into call stream, silent to room |
| Live call transcript to Telegram | ✅ Full | STT → Telegram while call is in progress |
| Live call handoff to human | ✅ Full | Tap notification or press volume button |
| Vision / screenshot analysis | ✅ Full | Gemini Vision / Claude Vision |
| App knowledge base | ✅ Full | Learns patterns per app over time |
| Efficient / Ultra mode | ✅ Full | Toggle model, steps, vision, context |
| Routines + macros | ✅ Full | Named command sequences |
| User memory | ✅ Full | Persists preferences and context |
| Wake-on-command (screen off) | ✅ Full | Foreground service + wake lock |

---

## iOS — What Works, What Doesn't, and Options

---

### Telegram command channel
**Status: ✅ Works fully**
Telegram receives messages on any platform. No special permissions needed. Users can command BharatClaw from iPhone via Telegram exactly the same way.

---

### Floating overlay / Dynamic Island pill
**Status: ✅ Works — via Live Activities API**
Apple officially supports this since iOS 16.1. Any App Store app can request the Live Activities entitlement and display a real-time updating pill in the Dynamic Island (iPhone 14 Pro+) or on the Lock Screen (older iPhones). Submit app with entitlement → Apple approves → done.

*Limitation: iPhone 14 Pro or later for Dynamic Island. Older iPhones get a Lock Screen banner instead.*

---

### Task execution (open apps, tap, type, scroll)
**Status: ❌ Impossible**
iOS does not allow third-party apps to control other apps via accessibility in the same way Android does. Apple's accessibility APIs are read-only for assistive purposes and cannot programmatically tap or type inside other apps.

**Options:**
1. **Shortcuts app integration** — iOS Shortcuts can automate some app actions. BharatClaw could trigger pre-built Shortcuts via URL schemes. Limited but works for simple tasks like sending a message or setting an alarm.
2. **Web-based tasks only** — BharatClaw on iOS handles web search, knowledge queries, calendar (via EventKit), contacts (via CNContactStore), and reminders natively without needing accessibility.
3. **Android as execution engine** — iOS user sends command via Telegram → BharatClaw Android phone (side device) executes it. iOS is just the remote control, Android does the work.

---

### Notification relay (reading other apps' notifications)
**Status: ❌ Impossible**
iOS apps cannot read notification content from other apps. Period. No API exists for this.

**Options:**
1. **Email forwarding** — Set up email rules to forward important emails to Telegram. Works for email-based alerts.
2. **Focus filters** — iOS Focus modes can suppress notifications; BharatClaw can toggle Focus modes via Shortcuts.
3. **Accept the gap** — Notification relay is Android-only. iOS users use BharatClaw for commands and knowledge, not relay.

---

### WhatsApp as command channel
**Status: ❌ Impossible on iOS**
Same reason as notification relay — iOS cannot read WhatsApp's notification content.

**Options:**
1. **Telegram only** — iOS users use Telegram as the command channel. Clean, reliable, purpose-built for this.
2. **WhatsApp Business API (cloud)** — Set up a Meta-verified business number. User messages that number from WhatsApp. Works on any platform including iOS. Requires Meta business verification (takes 1-2 weeks). Not personal — business use only.
3. **Android side phone** — WhatsApp channel runs on the Android device. iOS user controls via Telegram.

---

### AI call answering — cellular calls
**Status: ❌ Impossible on iOS**
Apple does not allow any third-party app to intercept or inject audio into a cellular call. No entitlement exists for this. Even Truecaller cannot record calls on iOS.

**Options:**
1. **Voicemail processing** — Call goes to voicemail → BharatClaw downloads recording via carrier voicemail → transcribes → sends summary to Telegram. Passive, not real-time.
2. **VoIP number** — BharatClaw provides a second VoIP number (via Twilio/Exotel). Caller calls that number → BharatClaw answers via CallKit (Apple's official VoIP API) → full AI conversation works → handoff possible. Different number, but full AI conversation works on iOS.
3. **Call forwarding to Android** — Forward iPhone calls to BharatClaw's Android number when busy. Android handles everything. iOS doesn't need to do anything — carrier-level forwarding.

---

### Cloned voice (ElevenLabs)
**Status: ✅ Works — API is platform-agnostic**
ElevenLabs is a cloud API. Works on iOS, Android, or any platform. If using the VoIP option above, cloned voice works fully on iOS too.

---

### Vision / screenshot analysis
**Status: ⚠️ Partial**
iOS does not allow apps to capture other apps' screens (no screenshot of another app's UI). BharatClaw can analyze images the user manually shares, camera photos, or screenshots the user takes and sends.

---

## Summary

```
Feature                          Android    iOS
─────────────────────────────────────────────────
Telegram commands                  ✅         ✅
Task execution                     ✅         ❌ (Shortcuts workaround)
Notification relay                 ✅         ❌
WhatsApp channel                   ✅         ❌ (Telegram only)
Floating overlay                   ✅         ✅ (Live Activities)
AI call answering (cellular)       ✅         ❌ (VoIP or forwarding)
Cloned voice                       ✅         ✅ (via VoIP)
Vision / screen analysis           ✅         ⚠️ (manual images only)
Efficient / Ultra mode             ✅         ✅
App knowledge base                 ✅         ❌
User memory                        ✅         ✅
```

**BharatClaw is an Android-first product.**
Android's openness is the moat. iOS can run the command layer and show status — Android is where the execution happens.
iOS users get: Telegram commands, knowledge queries, memory, cloned voice calls (VoIP), and the Dynamic Island status pill.
Android users get everything.

---

## Works on ALL Android phones — not just Pixel

Google Call Screen is Pixel-exclusive (Google built it into their own hardware).
BharatClaw is an APK. It uses only standard public Android APIs:

| API | Minimum Android | Coverage |
|-----|----------------|---------|
| AccessibilityService | Android 5 (2014) | 99%+ of active devices |
| NotificationListenerService | Android 4.3 (2013) | 99%+ of active devices |
| InCallService (call answering) | Android 8 (2017) | 95%+ of active devices |
| WindowManager overlay | Android 6 (2015) | 98%+ of active devices |

Works on: Samsung, OnePlus, Realme, Redmi, Xiaomi, Vivo, Oppo, Motorola, Nokia, Nothing, and every other Android brand.
BharatClaw brings Pixel-level intelligence (and far beyond) to every Android phone ever made.
