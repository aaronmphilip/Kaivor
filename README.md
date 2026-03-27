# BharatClaw MVP (Telegram + Cloudflare Free Path)

This repo now supports a free-friendly deployment path for MVP validation:

- Telegram Bot API for inbound/outbound messaging
- Cloudflare Workers for API
- Cloudflare D1 for storage
- Cloudflare Cron trigger for follow-ups

Core promise: **Never miss a lead again.**

## What is implemented

- Vercel-ready premium landing pages (`/` and `/get-started`)
- Telegram webhook ingestion (`POST /webhooks/telegram/:tenantId`)
- Premium auto-reply flow: greet -> language selection -> name -> requirement
- Lead capture and storage
- Follow-up jobs (+30 min, +24h)
- Owner notification to Telegram
- Manual takeover (`#takeover <chat_id>`)
- Interruption handling (`/start` restart, language switch mid-flow, low-signal re-prompts)
- Config APIs and tenant API keys
- Free mode + public self-serve trial signup (`POST /public/free-trial`)

## Endpoints

- `GET /` (premium landing page)
- `POST /public/free-trial` (public free onboarding, no master key)
- `GET /public/free-trial/:tenantId/status` (owner-connect status check)
- `GET /health`
- `POST /webhooks/owner-connect` (pairing code verification for `@bharatclawbot`)
- `POST /webhooks/telegram/:tenantId`
- `POST /admin/tenants`
- `POST /admin/tenants/:tenantId/config`
- `POST /internal/takeover`
- `POST /internal/run-followups` (manual run; master key)

## Files for Cloudflare deploy

- Worker runtime: `apps/cloudflare-worker/src/index.ts`
- Wrangler config: `wrangler.toml`
- D1 schema: `sql/d1_init.sql`
- Local dev secrets template: `.dev.vars.example`

## Files for Vercel landing site

- Static landing pages: `apps/web/index.html`, `apps/web/get-started.html`
- Static assets: `apps/web/styles.css`, `apps/web/get-started.js`
- Vercel routing config: `vercel.json`

## Step-by-step setup (free MVP path)

1. Create Telegram bot
- Open Telegram -> `@BotFather`
- Run `/newbot`
- Copy bot token

2. Create Cloudflare Worker + D1
- Install Wrangler: `npm i -g wrangler` (or use `npx wrangler`)
- Login: `npx wrangler login`
- Create D1 DB: `npx wrangler d1 create bharatclaw`
- Copy `database_id` from output into `wrangler.toml`

3. Apply D1 schema
```bash
npm run cf:migrate:remote
```

If you already had an older DB and are upgrading, run this one-time migration:
```bash
npx wrangler d1 execute bharatclaw --remote --command "ALTER TABLE leads ADD COLUMN preferred_language TEXT;"
```

4. Configure secrets
```bash
npx wrangler secret put MASTER_API_KEY
npx wrangler secret put TELEGRAM_WEBHOOK_SECRET
npx wrangler secret put OWNER_CONNECT_BOT_TOKEN
npx wrangler secret put OWNER_CONNECT_BOT_SECRET
```
Optional var in `wrangler.toml`:
- `FREE_MODE` (`true` keeps automation always on)
- `PUBLIC_SIGNUP_ENABLED` (`true` allows public onboarding endpoint)
- `LANDING_CTA_URL` (where Start Free Trial should go, currently Vercel `/get-started`)
- `OWNER_CONNECT_BOT_USERNAME` (default: `bharatclawbot`)
Optional for billing:
```bash
npx wrangler secret put POLAR_WEBHOOK_SECRET
npx wrangler secret put POLAR_ACCESS_TOKEN
npx wrangler secret put POLAR_PRODUCT_ID
```

5. Deploy worker
```bash
npm run deploy:cf
```

6. Set owner-connect bot webhook once
```bash
curl -X POST "https://api.telegram.org/bot<OWNER_CONNECT_BOT_TOKEN>/setWebhook" \
  -H "Content-Type: application/json" \
  -d '{
    "url":"https://<WORKER_DOMAIN>/webhooks/owner-connect",
    "secret_token":"<OWNER_CONNECT_BOT_SECRET>"
  }'
```

7. Publish landing/onboarding page on Vercel
- Your CTA should point to `https://bharatclawapp.vercel.app/get-started`.

8. Customer onboarding (no-code path)
- Open `/get-started`.
- Fill: business name, owner name, Telegram bot token, optional owner email.
- Click **Create Free Workspace**.
- Backend will automatically:
  - create tenant + API key
  - configure Telegram webhook
  - generate secure webhook secret
  - generate owner pairing code

9. Owner connect (pairing code)
- Open `https://t.me/bharatclawbot`.
- Send pairing code shown on onboarding.
- Owner alerts are now active.
- Until pairing is complete, tenant bot automation is blocked.

10. Smoke test
- Send to bot: `Hi` -> `English` (or `Hindi`) -> `Aman` -> `Need AC repair`
- Verify:
  - bot replies each step
  - lead saved
  - owner alert sent
  - follow-up jobs created

11. Test takeover
```bash
curl -X POST "https://<WORKER_DOMAIN>/internal/takeover" \
  -H "Content-Type: application/json" \
  -H "x-master-api-key: <MASTER_API_KEY>" \
  -d '{
    "tenantId":"<TENANT_ID>",
    "command":"#takeover <CUSTOMER_CHAT_ID>"
  }'
```

## Manual admin onboarding (optional fallback)

```bash
curl -X POST "https://<WORKER_DOMAIN>/admin/tenants" \
  -H "Content-Type: application/json" \
  -H "x-master-api-key: <MASTER_API_KEY>" \
  -d '{
    "name":"Demo Business",
    "slug":"demo-business",
    "ownerName":"Owner",
    "ownerChatId":"<YOUR_TELEGRAM_CHAT_ID>",
    "telegramBotToken":"<BOT_TOKEN>",
    "trialDays":7
  }'
```

## Local Worker dev

1. Copy `.dev.vars.example` to `.dev.vars`
2. Fill values
3. Run:
```bash
npm run dev:cf
```

## Deploy Vercel site

1. Connect this GitHub repo to Vercel project.
2. Keep root directory as repo root.
3. Deploy with `vercel.json` routing (already included).
4. Your public pages:
- `/`
- `/get-started`

## Notes

- Existing Node API/worker implementation is still in repo (`apps/api`, `apps/worker`) if you need non-Cloudflare hosting.
- For fast free MVP: Cloudflare worker handles bot backend, Vercel handles marketing + onboarding pages.
