<div align="center">

<h1>🤖 BharatDroid</h1>

<p><strong>Your Android phone, controlled by AI — via Telegram</strong></p>

<p>
<img src="https://img.shields.io/badge/Platform-Android%2011+-3DDC84?style=for-the-badge&logo=android&logoColor=white"/>
<img src="https://img.shields.io/badge/Kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white"/>
<img src="https://img.shields.io/badge/AI-Gemini%20%7C%20Claude%20%7C%20GPT-FF6B35?style=for-the-badge"/>
<img src="https://img.shields.io/badge/License-MIT-blue?style=for-the-badge"/>
<img src="https://img.shields.io/badge/PRs-Welcome-brightgreen?style=for-the-badge"/>
</p>

<p>Order food. Send messages. Play music. Book cabs. Pay bills.<br/>
Just tell it what to do — in <strong>English or Hindi</strong>.</p>

</div>

---

## 💬 See It In Action

```
You:  "Order biryani from Swiggy under ₹200"
Bot:  📋 Plan: Open Swiggy → Search biryani → Pick best match → Add to cart
Bot:  ✅ Done! Behrouz Chicken Biryani ₹189 added. Arriving in 35 mins.
      📸 [screenshot of order confirmation]
```

```
You:  "Send ₹500 to Rahul on GPay then WhatsApp him saying sent"
Bot:  ✅ Step 1: Sent ₹500 to Rahul on Google Pay
Bot:  ✅ Step 2: WhatsApp message sent to Rahul: "sent"
      📸 [screenshot proof]
```

```
You:  "Play Arijit Singh on YouTube"
Bot:  ✅ Playing: Tum Hi Ho — Arijit Singh
      📸 [screenshot of video playing]
```

---

## ✨ Features

| | Feature | Description |
|---|---|---|
| 🧠 | **Plan → Execute** | Generates a numbered plan before acting — like a human would think first |
| 📱 | **Universal UI** | Works on ANY Android app — no hardcoded per-app logic |
| 📸 | **Screenshot Proof** | Sends a photo after every task so you see exactly what happened |
| ⚡ | **Superhuman Speed** | Executes faster than any human can tap |
| 🔗 | **Multi-step Chaining** | "Do X then Y then Z" — runs all steps automatically |
| 🇮🇳 | **Hindi + English** | Understands commands in both languages |
| 🔌 | **Any AI Provider** | Gemini (free), Claude, or OpenAI — paste key, it auto-detects |
| 🏠 | **100% On-Device** | No cloud server. Everything runs on your Android phone |

---

## 📱 Supported Apps

| Category | Apps |
|---|---|
| 🍕 Food & Grocery | Swiggy, Zomato, Blinkit, Zepto |
| 🛒 Shopping | Amazon, Flipkart |
| 💬 Messaging | WhatsApp, Instagram |
| 🎵 Entertainment | YouTube |
| 💰 Payments | PhonePe, Google Pay, Paytm, CRED |
| 🚗 Transport | Ola, Uber |
| 📧 Productivity | Gmail, Google Calendar, Google Keep |
| 🗺️ Navigation | Google Maps, Chrome |
| ⚙️ System | Settings, Contacts, Files |
| 🤖 General | **Any app** not listed above |

---

## 🏗️ How It Works

```
Your Telegram message
        │
        ▼
┌───────────────────┐
│     AI Brain      │  Understands intent, picks skills, chains steps
│  (Gemini/Claude)  │
└────────┬──────────┘
         │
         ▼
┌───────────────────┐
│   Screen Agent    │  Generates plan → reads live screen → decides each action
│  Plan → Execute   │  Works on ANY app universally
└────────┬──────────┘
         │
         ▼
┌───────────────────┐
│  Accessibility    │  Executes taps, swipes, typing, scrolling on screen
│    Service        │  Takes screenshot as proof
└────────┬──────────┘
         │
         ▼
  Telegram reply + 📸 screenshot
```

---

## 🚀 Quick Start

### What You Need
- Android phone (Android 11+)
- Telegram account
- Free AI key: [Gemini](https://aistudio.google.com/apikey) *(recommended, free)* | [Claude](https://console.anthropic.com) | [OpenAI](https://platform.openai.com)

### Setup (5 minutes)

**1. Create a Telegram bot**
```
Open Telegram → message @BotFather → /newbot → copy the token
```

**2. Build & install**
```bash
git clone https://github.com/aaronmphilip/BharatClaw.git
cd BharatClaw/android
./gradlew assembleDebug
# Install: android/app/build/outputs/apk/debug/app-debug.apk
```

**3. Configure on your phone**
- Open BharatDroid → paste your **Bot Token** + **AI API Key**
- Grant **Accessibility Service** permission
- Tap **Start Agent**

**4. Test it**
```
Send your bot: "Search YouTube for AR Rahman"
```

---

## 🧩 Project Structure

```
BharatClaw/
├── android/                          # Android app
│   └── app/src/main/kotlin/com/bharatdroid/agent/
│       ├── AIBrain.kt                # Intent → skill routing (Gemini/Claude/OpenAI)
│       ├── ScreenAgent.kt            # Universal AI screen controller
│       ├── AgentAccessibilityService.kt  # Gestures + screenshot
│       ├── AgentOrchestrator.kt      # Skill runner + Telegram replies
│       ├── TelegramPoller.kt         # Telegram bot API
│       └── skills/
│           ├── Skill.kt              # Skill interface + permissions
│           ├── SandboxedRunner.kt    # Safe action executor
│           └── builtin/              # 24 built-in skills
├── skills/
│   ├── official/                     # Official skill definitions
│   └── community/                    # Community-contributed skills
├── CONTRIBUTING.md
└── LICENSE
```

---

## 🔒 Safety & Privacy

**Skill sandboxing** — every skill declares what it can access. The runner enforces it.
- `PAYMENT` actions always require your confirmation before executing
- Skills cannot make network calls (no data sent anywhere)
- Skills can only open apps they declared in `allowedPackages`
- Voice/mic buttons are blocked — agent always uses text input

**Privacy**
- Commands are sent to your chosen AI API (Gemini/Claude/OpenAI)
- All phone control happens on-device
- No analytics, no tracking, no server
- API keys stored locally in Android SharedPreferences

---

## 🤝 Contributing

**We want your help!** Read **[CONTRIBUTING.md](CONTRIBUTING.md)** for the full guide.

Quick ways to contribute:
- 🐛 **Found a bug?** → [Open an issue](https://github.com/aaronmphilip/BharatClaw/issues)
- ➕ **Add a new skill** → Any Android app can become a skill (see CONTRIBUTING.md)
- 🌐 **Add language support** → Hindi built-in, add more
- 📝 **Improve docs** → Always welcome
- ⭐ **Star the repo** → Helps others find this project

---

## 🗺️ Roadmap

- [ ] In-app skill browser (community skills without rebuild)
- [ ] Scheduled tasks ("Every morning order milk if I'm low")
- [ ] Voice commands via phone mic
- [ ] Multi-device support (control phone from PC)
- [ ] Skill signing & verification
- [ ] iOS support (Shortcuts API)

---

## 📄 License

MIT — free to use, modify, and distribute. See [LICENSE](LICENSE).

---

<div align="center">

Built with ❤️ for India 🇮🇳 — but works everywhere

**[⭐ Star this repo](https://github.com/aaronmphilip/BharatClaw)** · **[🐛 Report Bug](https://github.com/aaronmphilip/BharatClaw/issues)** · **[💡 Request Feature](https://github.com/aaronmphilip/BharatClaw/issues)**

</div>
