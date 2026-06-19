<div align="center">

# Kaivor

**Execute. Not explain.**

Personal Android agent — controlled from Telegram, running entirely on your phone.

<p>
<img src="https://img.shields.io/badge/Platform-Android%2011+-000000?style=for-the-badge&logo=android&logoColor=white"/>
<img src="https://img.shields.io/badge/Kotlin-000000?style=for-the-badge&logo=kotlin&logoColor=white"/>
<img src="https://img.shields.io/badge/AI-Gemini%20%7C%20Claude%20%7C%20GPT-000000?style=for-the-badge"/>
<img src="https://img.shields.io/badge/License-MIT-000000?style=for-the-badge"/>
<img src="https://img.shields.io/badge/Status-Active-000000?style=for-the-badge"/>
</p>

[Download APK](downloads/Kaivor-v1.0.apk) · [Website](index.html) · [Full Guide](KAIVOR_GUIDE.md) · [Report Bug](https://github.com/aaronmphilip/Kaivor/issues)

</div>

---

## What is Kaivor?

Most AI assistants tell you what to do. **Kaivor does it for you.**

Kaivor is an open-source AI agent that lives on your Android phone. You send a command over Telegram — text or voice — and it opens apps, reads screens, fills forms, relays notifications, and reports back with proof. No cloud server. No subscription. Your phone, your data, your AI.

```
You:   "Order biryani from Swiggy under ₹200"
Kaivor: Opens Swiggy → searches → adds to cart → sends screenshot proof

You:   [WhatsApp notification arrives]
Kaivor: Forwards to Telegram. You reply in chat. Message lands in WhatsApp.
```

---

## Features

| | Capability | What it does |
|---|---|---|
| | **Plan → Execute** | Generates a plan, then acts step by step |
| | **Universal UI** | Works on any Android app via accessibility + vision |
| | **Screenshot proof** | Sends a photo after every task |
| | **Multi-step chaining** | "Do X, then Y, then Z" in one command |
| | **Notification relay** | Every app notification → Telegram, with two-way reply |
| | **Voice commands** | Send a Telegram voice note, Kaivor transcribes and runs it |
| | **Hindi + English** | Understands commands in both languages |
| | **Any AI provider** | Gemini (recommended), Claude, or OpenAI — auto-detected |
| | **100% on-device** | Phone control runs locally. No Kaivor server. |

---

## Quick Start

### Requirements

- Android 11+
- Telegram account
- One AI API key ([Gemini](https://aistudio.google.com/apikey) recommended)

### 1. Download the APK

**[downloads/Kaivor-v1.0.apk](downloads/Kaivor-v1.0.apk)**

Install on your Android phone (enable "Install from unknown sources" if prompted).

### 2. Create a Telegram bot

```
Telegram → @BotFather → /newbot → copy the token (7123456789:AAF...)
```

### 3. Configure Kaivor

Open the app and complete onboarding:

1. Paste your **Telegram bot token**
2. Paste your **AI API key** (Gemini / Claude / OpenAI)
3. Enable **Accessibility Service** (required)
4. Enable **Notification Access** (optional — powers 24/7 relay)
5. Grant **Display Overlay** (recommended — shows live progress notch)
6. Tap **Launch Kaivor**

### 4. Test it

Message your bot:

```
"Search YouTube for AR Rahman"
"Navigate to Connaught Place"
"What's on my calendar today?"
```

---

## Supported Apps

| Category | Apps |
|---|---|
| Food & Grocery | Swiggy, Zomato, Blinkit, Zepto |
| Shopping | Amazon, Flipkart |
| Messaging | WhatsApp, Instagram |
| Entertainment | YouTube |
| Payments | PhonePe, Google Pay, Paytm, CRED |
| Transport | Ola, Uber, Rapido |
| Productivity | Gmail, Calendar, Keep, Chrome |
| Navigation | Google Maps |
| General | **Any app** — if a human can tap it, Kaivor can tap it |

---

## Architecture

```
Telegram command (text or voice)
        │
        ▼
┌───────────────────┐
│     AI Brain      │  Intent → skills → multi-step plan
│  Gemini / Claude  │
└────────┬──────────┘
         │
         ▼
┌───────────────────┐
│   Screen Agent    │  Accessibility tree first, vision when needed
│  Plan → Execute   │
└────────┬──────────┘
         │
         ▼
┌───────────────────┐
│  Accessibility    │  Tap, type, scroll, screenshot
│    Service        │
└────────┬──────────┘
         │
         ▼
  Telegram reply + proof
```

---

## Project Structure

```
Kaivor/
├── android/                 # Kotlin Android app
│   └── app/src/main/kotlin/com/kaivor/agent/
│       ├── AIBrain.kt       # Intent → skill routing
│       ├── ScreenAgent.kt   # Universal screen controller
│       ├── AgentOrchestrator.kt
│       ├── TelegramPoller.kt
│       ├── NotificationRelay.kt
│       └── skills/builtin/  # 30+ built-in skills
├── index.html               # Landing page
├── styles.css               # SpaceX/xAI black-white theme
├── downloads/               # Public APK
├── skills/official/         # Skill definitions
├── packages/                # API, state machine, telegram, etc.
└── tests/                   # Vitest suite
```

---

## Build from Source

### Android

```bash
git clone https://github.com/aaronmphilip/Kaivor.git
cd Kaivor/android
./gradlew test assembleDebug
# APK: android/app/build/outputs/apk/debug/app-debug.apk
```

### Tests

```bash
npm install
npm test
```

---

## Safety & Privacy

- **Payment skills** always require explicit confirmation before executing
- Skills are sandboxed — they can only open declared apps
- No analytics, no tracking, no Kaivor cloud server
- API keys stored locally in Android SharedPreferences
- Commands are sent to your chosen AI provider only

---

## Contributing

Read **[CONTRIBUTING.md](CONTRIBUTING.md)** for the full guide.

- Found a bug? [Open an issue](https://github.com/aaronmphilip/Kaivor/issues)
- Add a skill — any Android app in ~50 lines of Kotlin
- Improve docs, add languages, star the repo

---

## Roadmap

**Shipped**
- [x] 24/7 notification relay with Telegram reply
- [x] 30+ built-in skills
- [x] Voice note → command (STT)
- [x] Document reading (PDF, screenshots)
- [x] Black-white landing page + on-device APK

**In progress**
- [ ] Vision-based screen understanding
- [ ] Better WhatsApp contact resolution
- [ ] Community skill browser (no rebuild)

**Next**
- [ ] Scheduled tasks
- [ ] Multi-device control
- [ ] iOS support

---

## License

MIT — see [LICENSE](LICENSE).

---

<div align="center">

Built for real phone work.

**[Star this repo](https://github.com/aaronmphilip/Kaivor)** · **[Download APK](downloads/Kaivor-v1.0.apk)** · **[Issues](https://github.com/aaronmphilip/Kaivor/issues)**

</div>
