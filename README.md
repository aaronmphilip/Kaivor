# BharatDroid

An AI agent that runs on your Android phone and is controlled via Telegram.

Tell it what to do in plain English or Hindi. It controls your apps for you.

```
You: "Order biryani from Swiggy under ₹200"
Bot: Searched Swiggy for biryani. Found Behrouz Chicken Biryani ₹189. Add to cart?
You: YES
Bot: Order placed. OTP: 8421. Arriving in 35 mins.
```

No server. No cloud. Runs entirely on your phone.

---

## How It Works

```
Your Telegram message
        ↓
Claude AI understands the task
        ↓
Picks the right Skill (Swiggy, Zepto, YouTube...)
        ↓
Accessibility Service controls the app on your phone
        ↓
Reports back on Telegram
```

Your phone IS the agent. Nothing leaves your device except the text of your messages (sent to Claude API for understanding).

---

## Setup (5 minutes)

### What you need
- An Android phone (Android 8.0+) with the apps you want to control installed
- A Telegram account
- A Claude API key (get one at console.anthropic.com)
- A Telegram Bot Token (create one via @BotFather)

### Steps

**1. Create your Telegram bot**
- Open Telegram → message @BotFather → `/newbot`
- Copy the bot token it gives you

**2. Get your Telegram Chat ID**
- Message @userinfobot on Telegram
- Copy your numeric ID

**3. Get your Claude API key**
- Go to console.anthropic.com → API Keys → Create key
- Copy the key (starts with `sk-ant-`)

**4. Install BharatDroid**
- Download the APK from Releases or build from source (see below)
- Install it on your Android phone

**5. Configure**
- Open BharatDroid
- Paste your Bot Token, Claude API Key, and Chat ID
- Tap "Enable Accessibility Service" → find BharatDroid in the list → toggle it on
- Go back → tap "Start Agent"

**6. Test it**
- Open Telegram → open your bot
- Send: `/start`
- Send: "Search YouTube for AR Rahman"

---

## Building from Source

Requirements: Android Studio Hedgehog or later, JDK 17

```bash
git clone https://github.com/bharatdroid/bharatdroid
cd bharatdroid/android
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

---

## Available Commands

| Command | What it does |
|---------|-------------|
| `/start` | Welcome message + instructions |
| `/skills` | List all loaded skills |
| `/status` | Agent health check |
| `/clear` | Clear conversation memory |

Everything else is natural language — just tell it what you want.

---

## Built-in Skills

| Skill | What it does |
|-------|-------------|
| `swiggy` | Search and order food from Swiggy |
| `zepto` | Order groceries from Zepto |
| `youtube` | Search and play YouTube videos |

More skills → see [SKILL_SPEC.md](./SKILL_SPEC.md) to build your own or browse community skills.

---

## Skill Safety

BharatDroid skills run in a sandbox. Each skill declares exactly what permissions it needs — the runner enforces this at runtime.

**Community skills** (from the community, not BharatDroid team):
- You see a warning with the skill name, author, and permissions before it runs
- You must approve by replying YES
- They can't do anything they didn't declare

**PAYMENT permission** = you confirm on every single execution, not just the first time.

Skills cannot:
- Make network calls (no data exfiltration)
- Access files on your device
- Use permissions they didn't declare
- Open apps not in their `allowedPackages` list

---

## Privacy

- Your commands are sent to Claude API (Anthropic) for understanding
- App control happens entirely on your device
- No data is stored on any server
- No analytics, no tracking
- Your Claude API key and Bot Token are stored in Android SharedPreferences (local only)

---

## Contributing a Skill

See [SKILL_SPEC.md](./SKILL_SPEC.md) — full spec, permission reference, and submission guide.

---

## Roadmap

- [ ] In-app skill browser (install community skills without APK rebuild)
- [ ] WhatsApp interface (when Business API access is available)
- [ ] Scheduled tasks ("Every morning at 8am, order milk from Zepto if stock is low")
- [ ] Voice commands via phone mic
- [ ] Multi-device support (control phone from desktop)
- [ ] Skill signing + verification
