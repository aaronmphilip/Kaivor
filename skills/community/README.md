# Community Skills

This is where community-contributed skills live.

Each skill folder contains:
- `skill.json` — the manifest (id, name, permissions, allowedPackages)
- `Skill.kt` — the implementation

See [SKILL_SPEC.md](../../SKILL_SPEC.md) for the full guide on building a skill.

## How to submit

1. Fork this repo
2. Create `skills/community/<your-skill-id>/`
3. Add `skill.json` and `YourSkill.kt`
4. Open a pull request

Community skills are reviewed for:
- Declared permissions match actual usage
- No undeclared network calls or file access
- No obfuscated code
- Works on at least one real device

## Ideas wanted

Skills nobody has built yet:
- `ola` / `uber` — book rides
- `gpay` — UPI transfers (PAYMENT)
- `paytm` — wallet + recharge (PAYMENT)
- `hotstar` — search and play content
- `meesho` — browse and order
- `cred` — bill payments
- `nykaa` — beauty shopping
- `blinkit` — grocery delivery
- `amazon` — shopping
- `instagram` — post, browse reels
- `chrome` — web search, form fill
