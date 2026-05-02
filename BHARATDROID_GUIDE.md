# BharatDroid — The Complete Guide

---

## What Is BharatDroid?

BharatDroid is an AI agent that lives on your Android phone and does things for you. Not just answers questions — actually does them. It opens apps, taps buttons, fills in forms, reads the screen, and confirms when it's done. You send a message on Telegram (or WhatsApp), it executes on the phone, and you get a report back. Order food, book a cab, pay a bill, add a calendar event, search the web, play music, read a PDF — all by typing or speaking a sentence. No app-switching, no remembering which button to tap, no friction. Just describe what you want and BharatDroid handles the rest.

---

## The Superpower — What Makes This Different

Every other AI assistant gives you answers. BharatDroid gives you *actions*.

When you ask Siri or Google Assistant to "order biryani from Swiggy," it opens the Swiggy app and stops there. You still have to search, pick, add to cart, apply coupon, and pay. BharatDroid actually completes the task — it reads the screen at each step, makes decisions, handles edge cases, and reports back when your order is placed.

It also runs 24/7 in the background without you having to unlock your phone. Someone sends you a WhatsApp message that needs a reply? Forward it to Telegram and say "reply with I'll be there by 8." BharatDroid replies in WhatsApp for you. You're in a meeting and need to book a Rapido to pick up your kid? Text your Telegram bot. Done.

The key difference: BharatDroid uses accessibility APIs to see and control every app on your phone — even apps with no API, no integration, no official automation support. If a human can tap it, BharatDroid can tap it.

---

## Quick Start — First 5 Commands to Try After Setup

After completing setup and seeing the BharatDroid dashboard, open Telegram and send these to your bot:

1. **`order a coffee from Swiggy, cheapest option under ₹150`**
   Watch it open Swiggy, search, pick the item, and get to checkout.

2. **`navigate to Connaught Place`**
   Opens Google Maps and starts navigation to the address.

3. **`play Arijit Singh on YouTube`**
   Opens YouTube, searches, and plays the first result.

4. **`set an alarm for 7am tomorrow`**
   Done in under 5 seconds.

5. **`what's the weather in Mumbai today`**
   Uses your AI brain to answer instantly without opening any app.

---

## All Features

### Telegram — The Main Interface

Telegram is how you talk to BharatDroid. Your bot is private — only your account (the one you paired during setup) can control it. Nobody else can send commands.

**How to send commands:**
- Open Telegram → tap your bot → type your command → send.
- Commands can be casual: "order me some chips from blinkit" works just as well as a formal instruction.
- You can ask follow-up questions in the same conversation.

**Built-in bot commands (start with /):**

| Command | What It Does |
|---|---|
| `/start` | Shows the welcome message and your status |
| `/status` | Confirms the agent is alive and responsive |
| `/stop` | Cancels the current running task immediately |
| `/history` | Shows your last 20 actions and their outcomes |
| `/info <topic>` | Quick knowledge lookup (no web search) |
| `/research <query>` | Full web search with AI summary |
| `/routine list` | Lists all your saved routines |
| `/routine add <name> = cmd1, cmd2` | Creates a multi-step routine |
| `/routine run <name>` | Runs a saved routine |
| `/shortcut list` | Lists quick macros |
| `/shortcut add <alias> = <full command>` | Creates a text shortcut |
| `/place list` | Lists saved locations |
| `/place add <name> = <full address>` | Saves a location for later use |
| `/schedule` | Manage scheduled tasks |
| `/memory` | Review what BharatDroid has learned about you |

---

### Voice Notes — Talk Instead of Type

Send a voice note to your Telegram bot and BharatDroid will transcribe it and execute it as a command. No extra setup required.

This works the same as a text command — BharatDroid listens, understands, acts.

**Good for:**
- Hands-free commands while driving
- Fast commands when typing is inconvenient
- Long, detailed instructions that are faster to speak than type

**Example:** Hold the microphone in Telegram, say "Book a Rapido from home to office for now," send. BharatDroid opens Rapido, sets the pickup and drop, and gets to the booking screen.

---

### Notification Relay — Your Phone Follows You Everywhere

If you granted Notification Access during setup, every notification on your phone gets forwarded to Telegram. WhatsApp messages, emails, OTPs, order updates, payment alerts, app pings — all appear in your Telegram as they arrive.

**Replying to notifications via Telegram:**
Quote a relayed notification in Telegram and say "reply: I'll call you back." BharatDroid opens the originating app and sends the reply.

**Why this is useful:**
- Leave your phone in another room and stay in the loop via Telegram on another device.
- Never miss an OTP while using your laptop.
- Screen all notifications without touching your phone.

**To enable after setup:** Go to BharatDroid app → Settings → Grant Notification Access.

---

### WhatsApp Channel — Dual-SIM Control

If you have a second SIM (or WhatsApp Business), you can control BharatDroid from WhatsApp instead of (or in addition to) Telegram.

**Setup:**
1. Install WhatsApp or WhatsApp Business on the agent phone with a second number.
2. Enter that number in BharatDroid settings under "WhatsApp Channel."
3. From your main WhatsApp, send commands to that second number.

BharatDroid watches for messages arriving on that WhatsApp number and treats them as commands — exactly like Telegram messages.

**When to use this:** You're already living in WhatsApp and don't want to switch to Telegram. Or you want to give a trusted family member the ability to send commands without Telegram access.

---

### Floating Notch Overlay — Visual Task Status

When BharatDroid is running a task, a small black pill appears on the screen — the "notch." It shows:
- What task is currently running
- Progress indication
- An × button to cancel instantly

You can drag the notch anywhere on the screen. It disappears automatically when the task finishes.

**To cancel a running task:** Tap the × on the notch. This is faster than sending `/stop` in Telegram.

**To enable:** Grant "Display Over Other Apps" permission in Settings (shown during onboarding, or in BharatDroid Settings).

**The notch never appears over your camera or fingerprint sensor.** It respects safe zone boundaries.

---

### Efficient vs Ultra Mode

BharatDroid has two operating modes, selectable in Settings → Agent Mode.

**Efficient Mode (default)**
- Uses fewer AI tokens per action
- Reads the screen only when needed
- Faster execution on most tasks
- Lower API cost
- Best for: everyday tasks you run repeatedly (order food, book rides, pay bills, set alarms)

**Ultra Mode**
- Reads the screen with vision at every step
- Uses full conversation context
- Handles ambiguous or complex multi-app tasks better
- Higher API cost
- Best for: new tasks you haven't run before, tasks that involve reading content (contracts, menus, receipts), situations where previous attempts in Efficient mode failed

**Switching modes:** BharatDroid Settings → Agent Mode → toggle between Efficient and Ultra. Change takes effect on the next command.

**Tip:** Use Efficient for 90% of tasks. Switch to Ultra when something isn't working right or when the task is genuinely complex.

---

### AI Call Answering — Your Phone Never Goes Unanswered

When you enable AI Call Answering (Settings → AI Call Answering) and set BharatDroid as your default Phone app, every incoming call becomes managed automatically.

**Inbound flow:**
1. A call comes in while you're busy.
2. BharatDroid answers after 2 rings using your ElevenLabs cloned voice.
3. You get an instant Telegram alert: "📞 Incoming call from Rahul — AI answering."
4. The AI conducts the conversation: takes messages, answers basic questions, handles queries.
5. You see the live transcript in Telegram as it happens.
6. Call ends → you receive the full transcript + a 2–3 line AI summary.

**VIP callers — skip the AI:**
Add important numbers (family, your biggest client, your doctor) to the VIP list in Settings. When a VIP calls, AI does NOT answer — you get an urgent Telegram alert instead so you can pick up yourself.

**Take Over — jump in mid-call:**
A persistent "Take Over" notification shows during every AI-answered call. Tap it anytime to join the call directly. The AI stops, speaker switches to your ear.

**Hang up via Telegram:**
Send `hang up` in Telegram to end any active call remotely.

---

### Outbound AI Calls — "Go Talk to Them" Superpower

This is the most powerful call feature. Instead of you calling someone, BharatDroid calls them on your behalf and handles the entire conversation.

**How to trigger it:**
```
call Rahul about the overdue invoice
call +919876543210 and tell them we need to reschedule to Friday
go talk to Priya about the project deadline
call Shyam Electricals and ask for a revised quote by tomorrow
```

**What happens:**
1. BharatDroid looks up the contact in your phone (or dials the number directly).
2. Places the call and starts the AI bridge as soon as it connects.
3. AI introduces itself: "Hi, I'm calling on behalf of [your name] regarding [your briefing]."
4. You receive live transcript in Telegram as the conversation progresses.
5. If the AI can't answer something, it says "Let me check with [your name]" and sends you:
   ```
   ❓ AI needs your input for the call with Rahul:
   "He's asking whether we can accept payment in two installments."
   Type your answer and I'll relay it to the AI.
   ```
   You type back "yes, two equal installments of ₹5000 each, first by Friday" — the AI receives it and continues the call.
6. When the purpose is achieved, AI closes politely: "Is there anything else? … Great, I'll let [your name] know. Goodbye."
7. After the call: full transcript + AI summary sent to Telegram.

**You can hang up at any time** by replying `hang up` in Telegram.

**Best for:**
- Chasing overdue payments without the awkwardness
- Scheduling or rescheduling client meetings
- Getting quotes from vendors
- Following up on pending deliveries or documents
- Calls you know will be short but you're too busy to make right now

---

### Floating Notch Overlay — Visual Task Status (already covered above, this clarifies new behavior)

The notch also shows during AI calls. While an inbound call is being answered, you'll see the caller's name and that the AI is active. During outbound AI calls, you'll see the call target and "AI conducting call."

---

### App Skills — What BharatDroid Can Do in Each App

#### Food & Groceries

**Swiggy**
- `order chicken biryani from Swiggy, cheapest under ₹180`
- `reorder my last Swiggy order`
- `find a restaurant near me with ratings above 4.2 open now`
- `what's my Swiggy order status`

**Zomato**
- `order a pizza from Zomato, apply best coupon, pay via UPI`
- `search for North Indian near Koramangala with free delivery`

**Blinkit**
- `order 1L milk and bread from Blinkit`
- `add eggs and atta to my Blinkit cart`

**Amazon**
- `search for a phone stand on Amazon under ₹300`
- `open my Amazon orders`
- `track my latest Amazon package`

**Flipkart**
- `search for boAt earphones on Flipkart, filter by 4+ stars under ₹1500`

---

#### Rides & Transport

**Uber**
- `book an Uber from home to Indiranagar`
- `book the cheapest Uber ride to the airport`
- `cancel my current Uber`

**Rapido**
- `book a Rapido bike to MG Road`
- `book a Rapido auto to Jayanagar 4th Block`

**Ola** *(where available)*
- `book an Ola cab to the railway station`

**Google Maps / Navigation**
- `navigate to AIIMS Delhi`
- `show me the route from home to office avoiding highways`
- `find a petrol station near me`

---

#### Payments & Finance

**PhonePe**
- `pay ₹500 to 9876543210 on PhonePe`
- `recharge my Airtel number ₹239`
- `check my PhonePe balance`

**Google Pay (GPay)**
- `send ₹200 to Rahul on GPay`
- `pay the electricity bill on GPay`

**CRED**
- `pay my credit card bill on CRED`
- `check my CRED coins`

---

#### Communication

**WhatsApp**
- `send "running 10 mins late" to Priya on WhatsApp`
- `open my WhatsApp chat with Mom`
- `send the address saved as home to Arjun on WhatsApp`

**Contacts**
- `call Ankit`
- `save 9876543210 as Plumber Raju`
- `what's Siddharth's number`

---

#### Productivity

**Calendar**
- `add dentist appointment on Friday at 3pm`
- `what do I have scheduled tomorrow`
- `create a meeting called Sprint Review on Monday at 11am, duration 1 hour`

**Chrome / Web**
- `search for the best monsoon travel destinations in India`
- `open flipkart.com`
- `translate this page to Hindi` *(opens in Chrome and uses built-in translate)*

**File Manager**
- `open the Downloads folder`
- `find photos from last week`

---

#### Entertainment

**YouTube**
- `play Bohemian Rhapsody on YouTube`
- `search for best Kotlin tutorials on YouTube`
- `skip this ad`
- `pause the video`

---

### Knowledge & Research

BharatDroid has two knowledge modes:

**Quick Info (`/info`)**
Uses the AI brain directly — no web search, instant answer. Good for factual questions, definitions, calculations.

```
/info what is the capital of Arunachal Pradesh
/info convert 15000 INR to USD
/info what does "EBITDA" mean
```

**Deep Research (`/research`)**
Searches the web, reads multiple sources, and gives you a synthesized answer with citations. Takes 10–30 seconds.

```
/research best budget smartphones under 15000 in India 2025
/research what are the new income tax slabs for FY 2025-26
/research side effects of metformin in diabetic patients
```

---

### Routines — Multi-Step Automation

A Routine is a saved sequence of commands that runs in order.

**Creating a routine:**
```
/routine add morning = play Arijit Singh on YouTube, check my schedule for today, show me today's weather in Bangalore
```

**Running a routine:**
```
/routine run morning
```

**Listing routines:**
```
/routine list
```

**Deleting a routine:**
```
/routine delete morning
```

**Example routines:**

*Office arrival:*
```
/routine add office arrival = navigate to office, set DND on, open Slack
```

*Night wind-down:*
```
/routine add night = set alarm 7am, turn on do not disturb, open YouTube and pause
```

*Bill day:*
```
/routine add pay bills = pay electricity bill on GPay, pay credit card on CRED, check PhonePe balance
```

---

### Quick Macros — Shorter Commands

Macros let you type a short alias instead of a long command.

**Creating a macro:**
```
/shortcut add bd = book a Rapido bike to office
/shortcut add lunch = order chicken biryani from Swiggy cheapest under ₹150
/shortcut add home = navigate to 42 MG Road Bangalore
```

**Using a macro:**
Just send `bd` or `lunch` in Telegram — BharatDroid expands it and executes the full command.

**Listing macros:**
```
/shortcut list
```

**Removing a macro:**
```
/shortcut remove bd
```

---

### Saved Places — Location Shortcuts

Save addresses so you never have to type them out in navigation or ride-booking commands.

**Saving a place:**
```
/place add home = 42 MG Road, Indiranagar, Bangalore 560038
/place add office = 91springboard, Koramangala, Bangalore
/place add gym = Gold's Gym, HSR Layout, Bangalore
```

**Using a saved place:**
```
book a Rapido to office
navigate to gym
book an Uber from home to airport
```

BharatDroid automatically substitutes the full address when it sees a saved place name.

**Listing places:**
```
/place list
```

---

### Scheduled Commands

Run any command at a future time or on a recurring schedule.

**One-time schedule:**
```
/schedule at 6:30pm pay ₹500 to 9876543210 on PhonePe
/schedule tomorrow 9am remind me to call the bank
/schedule in 2 hours check my Swiggy order status
```

**Recurring schedule:**
```
/schedule every day at 8am play morning news on YouTube
/schedule every Monday 10am open my calendar and show the week
/schedule every 1st of month pay credit card bill on CRED
```

**Viewing scheduled tasks:**
```
/schedule list
```

**Cancelling a scheduled task:**
```
/schedule cancel <id shown in list>
```

---

### Activity Log — See Everything That Happened

```
/history
```

Shows your last 20 actions with timestamps, what was attempted, and whether it succeeded or failed. Use this to verify a task completed, debug something that went wrong, or just review what BharatDroid did while you were away.

---

### Memory & Learning

BharatDroid learns your preferences over time. When you consistently pick the same restaurant, route, or payment method — it remembers and applies those defaults automatically.

**Viewing what it remembers:**
```
/memory
```

**Explicitly teaching it something:**
```
remember that I prefer window seats when booking trains
remember my Airtel number is 9876543210
remember that I'm vegetarian
```

**Clearing a memory:**
```
forget that I prefer window seats
```

Memory is stored locally on your phone. Nothing is sent to external servers.

---

### Document Reading & Summarizing

Send a PDF, image, or document file directly to the Telegram bot and ask BharatDroid to work with it.

**Summarizing a PDF:**
Send the file, then: `summarize this`

**Extracting specific information:**
Send a PDF, then: `what are the key dates and deadlines in this document`

**Reading a contract:**
Send the file, then: `what should I watch out for in this agreement`

**Comparing documents:**
Send two files, then: `what changed between these two versions`

**Reading a screenshot:**
Send an image, then: `what does this error message mean` or `extract the text from this image`

---

### Image Generation

BharatDroid can generate images using AI when connected to an image generation API.

**Generating an image:**
```
generate an image of a mountain lake at sunset, photorealistic
draw a logo for a startup called NimbusPay, minimalist style
create a banner for a Diwali sale, dark background with gold text
```

The image is sent back to you in Telegram. You can save it or share it directly.

*Note: Image generation requires a separate API key (e.g., OpenAI DALL-E or Stability AI). Configure it in BharatDroid Settings → Image Generation.*

---

## Power Tips — 10 Non-Obvious Tricks

**1. Chain commands in one message**
"Pay the electricity bill and then navigate to the gym" works. BharatDroid executes them sequentially without you having to wait and send again.

**2. Reference previous context**
After ordering food: "How long will it take?" BharatDroid knows you're asking about the order it just placed.

**3. Use conversational corrections**
If BharatDroid picks the wrong restaurant: "No, not that one — I meant the one in Koramangala." It adjusts without you repeating the whole command.

**4. Ask for confirmation before paying**
In Ask Permission mode, BharatDroid shows you the total before any payment action and waits for your `yes`. Useful when ordering from a new place.

**5. Send commands while the screen is off**
BharatDroid runs in the background. Send the Telegram message from your laptop, it executes on the phone, and you get a report back — all without touching the phone.

**6. Use voice notes for complex instructions**
"Book a Rapido from home to office, if Rapido isn't available then book an Uber, and send the ETA to Priya on WhatsApp" — it's much faster to speak this than type it.

**7. Stop a task that's stuck**
Tap the × on the floating notch, or send `/stop` in Telegram. BharatDroid will halt within 2–3 seconds and report what it had done so far.

**8. Test a routine before saving it**
Run each command individually first to make sure it works, then save them as a routine.

**9. Name your shortcuts intuitively**
Name macros the way you naturally think: `lunch`, `home`, `office`, `gym`, `bd` (book driver). Short words you'd actually type in a hurry.

**10. Pair Ultra mode with unfamiliar tasks**
The first time you use a new skill or app, run it in Ultra mode. Once it's working reliably, switch back to Efficient for future runs.

---

## For Business — Use Cases for Founders and Teams

Most Indian founders run their business from a single Android phone. Every vendor call, every client follow-up, every payment, every order, every calendar entry — it all happens on that one device, across a dozen apps, throughout a 14-hour day. BharatDroid turns that phone into a leverage machine. One command to your Telegram does what would otherwise take five minutes of app-switching, scrolling, and typing.

This section is written for founders, freelancers, consultants, clinic owners, lawyers, and anyone running a business where the phone is the office.

---

### 1. Why BharatDroid Is a Founder's Unfair Advantage

Every business action on an Android phone — sending a payment, booking a cab, placing a supplier order, reading an email, checking a calendar — is something BharatDroid can do on your behalf, triggered by a single Telegram message.

The leverage comes from three compounding factors:

**Elimination of app-switching tax.** The average knowledge worker switches apps 300+ times per day. Every switch costs 20–60 seconds of reorientation. BharatDroid collapses this to a single chat interface. You type one instruction, the phone executes it, you get a confirmation. You never leave Telegram.

**Parallel and sequential automation.** Routines let you chain actions that would ordinarily take 10 separate steps into a single trigger. Your morning briefing, your vendor payment run, your weekly report — each becomes a one-word command.

**Distance-independent control.** You can be in a client meeting, at the gym, or on a flight in offline mode and still have queued commands execute the moment you reconnect. The phone at the office runs independently; you supervise from anywhere.

For a solo founder, this means fewer dropped balls. For a small team, it means the owner stops being the bottleneck for routine execution.

---

### 2. The AI Receptionist — Never Miss a Business Call Again

This is the most immediately valuable feature for any Indian business that receives calls: clinics, law firms, CA offices, freelancers, real estate agents, service businesses, and e-commerce sellers who field COD queries.

**How it works.**
When a call comes in that you cannot answer, BharatDroid answers it automatically using a cloned ElevenLabs voice — your voice, your name, your business. The caller hears a professional greeting. The AI listens, responds to basic queries, and takes a message. Within seconds of the call ending, you receive on Telegram:

- Full call transcript
- 2-line AI summary ("Caller: Rahul Sharma. Reason: wants to know if you do home visits for dental checkups. Preferred callback time: after 6 PM.")
- Caller's number, auto-formatted for one-tap WhatsApp reply

**VIP list for important callers.**
Add numbers to your VIP list — your biggest client, your CA, your spouse. When a VIP calls, you get an instant "Take Over" notification on Telegram. Tap it and the call comes to you live, interrupting the AI mid-sentence. Everyone else gets handled by the receptionist.

**Setup example for a clinic.**
```
"Set greeting to: Hello, you've reached Dr. Mehta's clinic. The doctor is currently with a patient. Please tell me how I can help you."
```

**For a freelance consultant:**
```
"Set greeting to: Hi, this is Priya Kapoor's office. Priya is in a meeting right now. Please leave your name, company, and the best time to reach you."
```

**For a lawyer:**
```
"Set greeting to: You've reached the office of Advocate Suresh Nair. We are unable to take your call right now. Please state your matter briefly and your contact number."
```

The AI receptionist runs 24/7, including Sundays and public holidays, without a salary, without sick days, and without forgetting to take a message.

**Outbound calling — the other half of the receptionist.**
Not just answering calls — making them. When you're in back-to-back meetings and need to chase someone:

```
call Mahesh from Apex Traders and tell him the payment is processed, reference UTR 2394871, and ask him to dispatch by Thursday
```

```
call the Koramangala clinic number and ask if Dr. Rao has availability on Friday after 4 PM
```

```
go talk to Vikram about the revised scope document — tell him we can approve up to 30% increase but need the timeline updated
```

BharatDroid places the call, conducts the conversation in your voice, live-transcripts it to your Telegram, and if the other person asks something you need to answer personally — pauses the AI and asks you directly via Telegram. You type your answer, it relays it and continues. You get a full call summary the moment it ends.

One founder using this has replaced the entire "call vendor, get put on hold, chase three times, finally get answer" cycle with a single Telegram message sent from their dining table. The AI makes the call, gets the answer, and reports back in under 3 minutes.

---

### 3. Remote Office Management — Vendors, Staff, and Payments

**Vendor payment runs.**
On payment day — whether it is GST filing week, month-end, or a project milestone — you often need to pay five to fifteen vendors in sequence. Instead of opening GPay or PhonePe for each one:

```
Pay Raju Electricals ₹4200 via GPay, then Shivam Plumbing ₹1800, then Mehta Tiles ₹6500, then Poonam Couriers ₹900
```

BharatDroid executes each payment sequentially, confirms each one in Telegram, and stops if a payment fails — giving you time to intervene before it cascades.

**WhatsApp vendor coordination.**
Most Indian suppliers, contractors, and delivery partners use WhatsApp. BharatDroid can send structured messages to any of them without you composing anything manually:

```
Send to Ramesh Wholesale on WhatsApp: "Please dispatch 50 units of SKU-A42 and 30 units of SKU-B17 to our warehouse. Payment done via NEFT, UTR attached."
```

```
Send to all three site supervisors on WhatsApp: "Please send today's progress photo and headcount by 7 PM."
```

**Staff coordination.**
```
Send a WhatsApp broadcast to my staff group: "Tomorrow is a half-day. Office closes at 2 PM. Please plan accordingly."
```

**Expense tracking to Google Sheets.**
```
Open my expense sheet and add a new row: Date today, Category Client Entertainment, Description lunch with Anand Kumar, Amount 1840, Paid by HDFC card
```

BharatDroid opens Sheets, navigates to the last row, and fills in each column. Use this after every business lunch, cab ride, or miscellaneous spend. By month-end, your expense sheet is complete without any reconciliation effort.

**Quick bank balance check before a payment run.**
```
Open PhonePe and tell me my current wallet balance and the last three transactions
```

---

### 4. Sales and Client Management

**Follow-up messages — the most neglected part of any sales process.**
Most deals die because nobody followed up. BharatDroid removes the friction:

```
Send WhatsApp to Sunita Agarwal: "Hi Sunita, just checking in on the proposal I sent last Tuesday. Happy to jump on a quick call if you have questions."
```

```
Send WhatsApp to Ashwin Mehta, Preeti Joshi, and Nandan Rao: "Did you get a chance to review the proposal? Let me know if you'd like to discuss."
```

**Lead research before a call.**
You have a call with a prospect in 10 minutes. You know their company name but nothing else:

```
Research the company BlueSky Logistics and give me: what they do, approximate size, any recent news, and two smart questions I can open the call with
```

You get a briefing note in Telegram in under 30 seconds. Walk into the call prepared.

**Reading and replying to Gmail.**
```
Read my last 5 unread emails from Gmail and summarize each in one line
```

```
Open Gmail, find the email from Vikram at Apex Solutions, and reply: "Thanks for the updated scope. We can accommodate this. I'll send a revised quote by Thursday."
```

**WhatsApp broadcast to a lead list.**
If you manage a lead list saved under group names or individual contacts:

```
Send WhatsApp to Rakesh, Divya, Harish, Meena, and Suresh: "We're running a limited offer this week — 20% off on the annual plan. Let me know if you'd like details."
```

**Tracking inbound leads.**
When a new lead calls and you miss it, the AI receptionist captures the details. When a new lead WhatsApps and you're busy, notification relay delivers it to Telegram and you reply from there — no app-switching.

---

### 5. Operations Automation — Routines That Run Your Day

Routines are the highest-leverage feature for business owners. A routine is a named sequence of actions that runs in full when you call it by name.

**Daily morning briefing routine.**
```
/routine add morning briefing = check my calendar for today and list all meetings, read my Gmail subject lines for unread emails, search news for [your company name], open WhatsApp and tell me if I have any unread messages from my key clients
```

Every morning:
```
morning briefing
```

You get today's schedule, email summary, news mentions, and client message status — in under 60 seconds, without opening a single app.

**End-of-day wrap-up routine.**
```
/routine add EOD wrap = open my expense sheet and tell me today's total spend, check Gmail for any emails I haven't replied to today, send WhatsApp to my business partner: "Wrapping up. Call you in 15 minutes."
```

**Weekly GST invoice run.**
```
/routine add weekly invoices = open Gmail and draft invoice reminder emails to Client A, Client B, and Client C asking for GST invoice submission by Friday
```

**Inventory reorder routine.**
```
/routine add restock = send WhatsApp to Vikram Enterprises: "Please send this week's rate list for paper stock", send WhatsApp to Ravi Supplies: "Need 200 units of product code 44B. Please confirm availability and delivery date."
```

**Calendar scheduling.**
```
Schedule a meeting with Ananya Sharma for Friday at 3 PM, title Project Kickoff, add her email ananya@clientco.in to the invite, and set a 30-minute reminder
```

---

### 6. The Dedicated AI Phone Setup — Your Business's Digital Front Desk

The most powerful configuration for a small business is a dedicated Android phone running BharatDroid full-time, separate from the owner's personal phone.

**The setup:**
- One low-cost Android phone (₹6,000–₹12,000 range works fine — a Redmi or Realme with decent RAM)
- A dedicated business SIM in that phone
- WhatsApp Business registered on that number
- BharatDroid running 24/7 on that phone, always plugged in
- The owner's Telegram account connected to that BharatDroid instance

**What this gives you:**
- All business calls to the business number are answered by the AI receptionist, with full transcripts to your Telegram
- All business WhatsApp messages come through as Telegram notifications — reply from Telegram, message appears from your business WhatsApp number
- You can command the phone remotely to place orders, send messages, make payments, or check information — even while you're on your personal phone doing something else
- The business phone never sleeps, never misses a notification, and never runs out of attention

**Why this works especially well for:**

*Clinics and medical practices:* Patients call at all hours. The AI receptionist handles appointment queries, takes name and number, forwards urgent cases with a VIP notification. The doctor reviews a clean summary log, not a chaotic missed-call list.

*Lawyers and CA firms:* Client calls that come outside office hours are answered professionally. The summary arrives in Telegram. You return the call informed, not blind.

*E-commerce and COD businesses:* Customers calling about delivery status, returns, or order changes get a professional response. The transcript tells you exactly what they want before you call back.

*Freelancers and consultants:* You look like a proper operation, not a one-person hustle. Clients hear a professional voice, leave a message, and get a timely callback — because you saw the Telegram summary the moment it was generated.

**Cost of this setup:** The Android phone is a one-time cost. The SIM is ₹150–300/month. BharatDroid subscription. ElevenLabs voice cloning for the greeting is a one-time setup. Total ongoing cost for the entire AI receptionist + automation layer: less than the salary of a part-time assistant for one day.

---

### 7. Professional Integrations — Research, Email, Calendar, and Notes

**Chrome for client and competitor research.**
```
Open Chrome and search for recent news about Tata Motors Q4 results, then summarize the top 3 points
```

```
Open Chrome and go to the MCA website, search for company registration details of Horizon Tech Solutions
```

**Gmail for professional correspondence.**
BharatDroid reads, drafts, and sends Gmail without you opening the app.

```
Open Gmail and find any emails with the subject containing "invoice" received in the last 7 days. List them with sender name and date.
```

```
Open Gmail and send an email to accounts@clientcompany.in with subject "GST Invoice - April 2026" and body "Dear Team, please find our GST invoice for April 2026 attached. Kindly process payment by the 15th. Regards, [Your Name]"
```

**Calendar for scheduling without back-and-forth.**
```
Check my calendar for next week and tell me which days I have free slots between 10 AM and 5 PM
```

```
Add a recurring calendar event every Monday at 9 AM titled Team Sync, with a 15-minute reminder
```

**Google Keep / Notes for meeting notes.**
```
Open Keep and create a new note titled "Meeting with Apex Solutions - 1 May 2026" with the following content: Discussed revised scope, timeline pushed to June 30, payment milestone at 50% delivery, follow up on NDA signing
```

---

### 8. Example Power Commands — Copy, Paste, Send

These are ready-to-use commands for common Indian business scenarios. Paste them into Telegram with your own names and amounts substituted.

**1. Vendor payment batch**
```
Pay Suresh Hardware ₹8500 via PhonePe, then Priya Packaging ₹3200 via GPay, then confirm both payments to me
```

**2. Client follow-up blast**
```
Send WhatsApp to Amit, Rohini, and Farhan: "Hi, just following up on the proposal sent last week. Do let me know if you have any questions or would like to schedule a call."
```

**3. Pre-call research brief**
```
Research the company GreenPath Agro — what they do, their size, any recent news — and give me 3 smart questions to open a sales call with
```

**4. GST invoice reminder**
```
Open Gmail and draft an email to finance@partnerfirm.com with subject "Pending GST Invoice - Urgent" and body "Dear Team, we have not yet received your GST invoice for the work completed in March. Could you please share it at the earliest so we can process payment? Thank you."
```

**5. Morning briefing**
```
morning briefing
```
*(after saving the routine as shown above)*

**6. Supplier restock order**
```
Send WhatsApp to Rajesh from National Traders: "Please arrange delivery of 100 kg raw cotton Grade A and 50 kg Grade B to our Andheri warehouse by Thursday. Please confirm stock availability."
```

**7. Expense entry**
```
Open my Google Sheet named Business Expenses 2026 and add a new row: today's date, category Travel, description cab to Bandra client meeting, amount 340, mode GPay
```

**8. Missed call follow-up**
```
Send WhatsApp to the last missed call number on my phone: "Hi, sorry I missed your call. This is Priya from DesignCo. Please let me know how I can help you."
```

**9. Calendar check before confirming a meeting**
```
Check my Google Calendar for this Friday between 2 PM and 6 PM and tell me if I have anything scheduled
```

**10. Competitor price check**
```
Open Chrome, search for "interior designer rates Mumbai 2026", and give me a summary of typical per-square-foot rates mentioned on the top results
```

**11. COD order status reply**
```
Send WhatsApp to Meena Sharma: "Hi Meena, your order #4521 has been dispatched today via Delhivery. Expected delivery is 3–5 working days. Tracking number: DEL8823719."
```

**12. End-of-week staff update**
```
Send WhatsApp to my Staff Updates group: "Week ending 2 May 2026. Reminder to submit your weekly reports by Sunday 8 PM. Next week's targets will be shared Monday morning."
```

**13. Outbound AI call — payment follow-up**
```
call Suresh from Mehta Distributors and tell him the payment of ₹18,500 is cleared via NEFT, UTR number 4829301, and ask him to send the delivery challan by end of day
```

**14. Outbound AI call — reschedule a meeting**
```
call Anjali and tell her I need to push our 3 PM meeting to 5 PM today, and ask if that works for her
```

**15. Outbound AI call — vendor quote**
```
go talk to Ramesh Electricals and ask for a quote on installing 10 LED lights in a 1000 sq ft office, ask if they can visit for a site check this week
```

---

## Troubleshooting

**BharatDroid stops responding after a few hours**
This is almost always Android killing the background service. Your phone manufacturer adds battery-saving features that terminate background apps. Follow the setup guide for your specific phone brand.

**Commands run but nothing happens in the app**
Make sure Accessibility Service is still enabled: BharatDroid Settings → Accessibility → verify it's ON. Some phone updates reset this permission.

**Notification relay stopped working**
Check: BharatDroid Settings → Notification Access → must be ON. Also ensure the app wasn't removed from the Notification Listener whitelist by a system update.

**"Overlay not granted" message in the notch**
Go to Settings → Apps → BharatDroid → Display over other apps → Allow.

**Task finishes too quickly without completing**
The app UI may have changed. Try switching to Ultra mode for that task — it uses vision to adapt to layout changes automatically.

**Can't book a ride / food order fails at payment**
UPI apps sometimes require biometric confirmation. Set up an SMS or MPIN fallback in your UPI app settings.

→ If BharatDroid stops responding, see [Don't Kill BharatDroid](DONT_KILL_BHARATDROID.md) for phone-specific setup.
