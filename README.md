<div align="center">

<h1>🤖 BharatDroid</h1>

<p><strong>Your Android phone, controlled by AI — via Telegram</strong></p>

<p>
<img src="https://img.shields.io/badge/Platform-Android%2011+-3DDC84?style=for-the-badge&logo=android&logoColor=white"/>
<img src="https://img.shields.io/badge/Kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white"/>
<img src="https://img.shields.io/badge/AI-Gemini%20%7C%20Claude%20%7C%20GPT-FF6B35?style=for-the-badge"/>
<img src="https://img.shields.io/badge/License-MIT-blue?style=for-the-badge"/>
<img src="https://img.shields.io/badge/PRs-Welcome-brightgreen?style=for-the-badge"/>
<img src="https://img.shields.io/badge/Status-In%20Development-yellow?style=for-the-badge"/>
</p>

<p>Order food. Send messages. Play music. Book cabs. Pay bills.<br/>
Just tell it what to do — in <strong>English or Hindi</strong>.</p>

> ⚠️ **Active Development** — We are actively building this. Features work but expect rough edges. Star the repo to follow progress.

</div>

---

## 🌟 The Vision

Most AI assistants tell you what to do. BharatDroid **does it for you**.

We are building an AI agent that lives on your Android phone and understands your intent the same way a human assistant would — it looks at the screen, figures out what's there, makes a plan, and executes it step by step. No cloud server doing the heavy lifting. No subscription. Your phone, your data, your AI.

**The goal:** You should be able to say *"Book me an AC cab to the airport, message my wife I'm leaving, and put on some driving music"* — and it handles all three, back to back, faster than you could unlock your phone.

We're not there yet — but we're building toward it, one skill at a time. This is open source so anyone can help get there faster.

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
| 🧠 | **Plan → Execute** | Generates a numbered plan before acting — like a human thinks first |
| 📱 | **Universal UI** | Works on ANY Android app — no hardcoded per-app logic |
| 📸 | **Screenshot Proof** | Sends a photo after every task so you see exactly what happened |
| ⚡ | **Superhuman Speed** | Executes faster than any human can tap |
| 🔗 | **Multi-step Chaining** | "Do X then Y then Z" — runs all steps automatically |
| 🇮🇳 | **Hindi + English** | Understands commands in both languages |
| 🔌 | **Any AI Provider** | Gemini, Claude, or OpenAI — paste key, it auto-detects |
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
| 🤖 General | **Any app** — if it's not listed, the AI figures it out |

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

### 🔑 Getting an API Key

BharatDroid works with **any of these AI providers** — pick one:

| Provider | Key starts with | Free tier | Link |
|---|---|---|---|
| **Gemini** *(recommended)* | `AIza...` | Yes — generous free quota | [Get key](https://aistudio.google.com/apikey) |
| **Claude** | `sk-ant-...` | No — pay per use | [Get key](https://console.anthropic.com) |
| **OpenAI** | `sk-...` | No — pay per use | [Get key](https://platform.openai.com) |

> 💡 **Recommendation: Start with Gemini.** Google gives a free quota that's enough for heavy daily use. After you hit the limit, it starts charging — but the free tier is very generous (1,500 requests/day on Gemini 2.5 Flash). You can see your usage at [aistudio.google.com](https://aistudio.google.com).

### What You Need
- Android phone (Android 11+)
- Telegram account
- One API key from the table above

### Setup (5 minutes)

**1. Create a Telegram bot**
```
Open Telegram → message @BotFather → /newbot → give it any name ending in "bot"
Copy the token it gives you (looks like: 7123456789:AAF...)

You can name your bot anything — "MyPhoneBot", "HomeAssistantBot", etc.
BharatDroid is the app on your phone — your Telegram bot name is up to you.
```

**2. Install the app**

👉 **[Download latest APK from Releases](https://github.com/aaronmphilip/BharatDroid/releases)**

Install it on your Android phone like any APK (enable "Install from unknown sources" if prompted).

**3. Open BharatDroid and configure**
- Paste your **Telegram Bot Token**
- Paste your **AI API Key** (Gemini/Claude/OpenAI — app auto-detects which)
- Tap **Enable Accessibility Service** → find BharatDroid in the list → toggle ON → go back
- Tap **Start Agent**

**4. Test it — message your Telegram bot**
```
"Search YouTube for AR Rahman"
"What's on my calendar today?"
"Play lofi music"
```

> 🔧 **For developers** who want to build from source:
> ```bash
> git clone https://github.com/aaronmphilip/BharatDroid.git
> cd BharatDroid/android && ./gradlew assembleDebug
> # APK: android/app/build/outputs/apk/debug/app-debug.apk
> ```

---

## 🧩 Project Structure

```
BharatDroid/
├── android/                          # Android app (Kotlin)
│   └── app/src/main/kotlin/com/bharatdroid/agent/
│       ├── AIBrain.kt                # Intent → skill routing
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
├── CONTRIBUTING.md                   # How to add skills / contribute
└── LICENSE                           # MIT
```

---

## 🔒 Safety & Privacy

**Skill sandboxing** — every skill declares what it can access. The runner enforces it at runtime.
- `PAYMENT` skills always require your explicit confirmation before executing
- Skills cannot make network calls (no data exfiltration)
- Skills can only open apps they declared in `allowedPackages`
- Voice/mic buttons are always blocked — agent uses text input only

**Privacy**
- Your commands go to your chosen AI API (Gemini/Claude/OpenAI)
- All phone control happens on-device
- No analytics, no tracking, no server
- API keys stored locally in Android SharedPreferences only

---

## 🤝 Contributing

**We want your help building this!** Read **[CONTRIBUTING.md](CONTRIBUTING.md)** for the full guide.

Quick ways to contribute:
- 🐛 **Found a bug?** → [Open an issue](https://github.com/aaronmphilip/BharatDroid/issues)
- ➕ **Add a new skill** → Any Android app can become a skill in ~50 lines
- 🌐 **Add language support** → Hindi built-in, add Tamil, Telugu, Bengali...
- 📝 **Improve docs** → Always welcome
- ⭐ **Star the repo** → Helps others find this project

---

## 🗺️ Roadmap

**Currently working on:**
- [ ] Vision-based screen understanding (see icons, not just text)
- [ ] Better WhatsApp contact finding
- [ ] Smarter element detection (mic vs search field)

**Coming next:**
- [ ] In-app skill browser (community skills without rebuild)
- [ ] Scheduled tasks ("Every morning order milk if I'm low")
- [ ] Voice commands via phone mic
- [ ] Multi-device support (control phone from PC)
- [ ] iOS support (Shortcuts API)

---

## 📄 License

MIT — free to use, modify, and distribute. See [LICENSE](LICENSE).

The MIT license means: do whatever you want with this code. Use it in your own projects, sell products built on it, modify it — just keep the copyright notice. No registration needed anywhere — the license file in this repo is all that's required.

---

<div align="center">

Built with ❤️ for India 🇮🇳 — but works everywhere

**[⭐ Star this repo](https://github.com/aaronmphilip/BharatDroid)** · **[🐛 Report Bug](https://github.com/aaronmphilip/BharatDroid/issues)** · **[💡 Request Feature](https://github.com/aaronmphilip/BharatDroid/issues)**

</div>
