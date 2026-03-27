# BharatClaw MVP (Telegram + Cloudflare Free Path)

This repo now supports a free-friendly deployment path for MVP validation:

- Telegram Bot API for inbound/outbound messaging
- Cloudflare Workers for API
- Cloudflare D1 for storage
- Cloudflare Cron trigger for follow-ups

Core promise: **Never miss a lead again.**

## What is implemented

- Telegram webhook ingestion (`POST /webhooks/telegram/:tenantId`)
- Premium auto-reply flow: greet -> language selection -> name -> requirement
- Lead capture and storage
- Follow-up jobs (+30 min, +24h)
- Owner notification to Telegram
- Manual takeover (`#takeover <chat_id>`)
- Interruption handling (`/start` restart, language switch mid-flow, low-signal re-prompts)
- Config APIs and tenant API keys
- Billing gate logic (trial/subscription)

## Endpoints

- `GET /` (premium landing page)
- `GET /health`
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
```
Optional var in `wrangler.toml`:
- `LANDING_CTA_URL` (your Telegram onboarding link)
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

6. Create tenant
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
Save `tenantId` and `tenantApiKey` from response.

7. Set Telegram webhook
```bash
curl -X POST "https://api.telegram.org/bot<BOT_TOKEN>/setWebhook" \
  -H "Content-Type: application/json" \
  -d '{
    "url":"https://<WORKER_DOMAIN>/webhooks/telegram/<TENANT_ID>",
    "secret_token":"<TELEGRAM_WEBHOOK_SECRET>"
  }'
```

8. Smoke test
- Send to bot: `Hi` -> `English` (or `Hindi`) -> `Aman` -> `Need AC repair`
- Verify:
  - bot replies each step
  - lead saved
  - owner alert sent
  - follow-up jobs created

9. Test takeover
```bash
curl -X POST "https://<WORKER_DOMAIN>/internal/takeover" \
  -H "Content-Type: application/json" \
  -H "x-master-api-key: <MASTER_API_KEY>" \
  -d '{
    "tenantId":"<TENANT_ID>",
    "command":"#takeover <CUSTOMER_CHAT_ID>"
  }'
```

## Local Worker dev

1. Copy `.dev.vars.example` to `.dev.vars`
2. Fill values
3. Run:
```bash
npm run dev:cf
```

## Notes

- Existing Node API/worker implementation is still in repo (`apps/api`, `apps/worker`) if you need non-Cloudflare hosting.
- For fast free MVP, use the Cloudflare path above.
