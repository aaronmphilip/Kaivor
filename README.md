# BharatClaw MVP

WhatsApp-first lead recovery backend for Indian SMBs.

Promise: **Never miss a WhatsApp lead again.**

This MVP is built for:
- freelancers
- salons
- gyms
- repair services
- creators
- local service businesses

## Hard Product Boundaries

- Uses **official WhatsApp Cloud API** only.
- No QR login mode.
- No dashboard.
- No multi-channel.
- Fast backend-first MVP for real lead recovery.

## Architecture

- `apps/api`: webhook ingestion, lead state engine, admin APIs, takeover endpoint, Polar webhook.
- `apps/worker`: follow-up scheduler and retry processor.
- `packages/*`: modular domain components.
- `sql/001_init.sql`: schema migration.

Target deployment:
- API on Vercel
- Worker on Railway
- Database on Vercel Postgres

## Project Structure

```text
apps/
  api/
    src/
      app.ts
      server.ts
  worker/
    src/
      worker.ts
packages/
  billing/
  config/
  notifications/
  reply-engine/
  scheduler/
  state-machine/
  storage/
  whatsapp/
sql/
  001_init.sql
tests/
```

## Core Behavior

Inbound flow:
1. Detect inbound message via WhatsApp webhook.
2. Map `phone_number_id` to `tenant_id`.
3. Create/find lead.
4. State machine runs:
   - `NEW` -> ask name
   - `AWAITING_NAME` -> capture name, ask requirement
   - `AWAITING_REQUIREMENT` -> capture requirement, notify owner, queue follow-ups
5. Store every inbound/outbound event.
6. Respect manual takeover pause.

Follow-ups:
- +30 min: session text
- +24h: approved template only

Owner notifications:
- Primary: WhatsApp
- Fallback: Email

Manual takeover:
- Command: `#takeover <phone>`
- Route: `POST /internal/takeover`
- Effect: pauses bot for lead using tenant cooldown.

## API Endpoints

- `GET /health`
- `GET /webhooks/whatsapp`
- `POST /webhooks/whatsapp`
- `POST /webhooks/polar`
- `POST /admin/tenants`
- `POST /admin/tenants/:tenantId/config`
- `POST /internal/takeover`

## Environment

Copy `.env.example` to `.env` and fill required values.

Important:
- `MASTER_API_KEY` secures admin/internal routes.
- `WHATSAPP_*` variables are mandatory.
- `DATABASE_URL` must point to Vercel Postgres.
- `POLAR_*` required for billing sync and checkout.

## Local Setup

```bash
npm install
cp .env.example .env
```

Run migration:

```bash
psql "$DATABASE_URL" -f sql/001_init.sql
```

Run API:

```bash
npm run dev:api
```

Run worker:

```bash
npm run dev:worker
```

Run tests:

```bash
npm test
```

## Meta WhatsApp Cloud Setup Checklist

1. Create Meta app and enable WhatsApp product.
2. Add business phone number.
3. Set webhook callback to `GET/POST /webhooks/whatsapp`.
4. Set verify token to `WHATSAPP_WEBHOOK_VERIFY_TOKEN`.
5. Add `WHATSAPP_ACCESS_TOKEN` and `WHATSAPP_APP_SECRET`.
6. Approve and create 24h follow-up template (`lead_followup_24h` or custom).

## Vercel Setup (API)

1. Import repo using:
   - `https://github.com/aaronmphilip/BharatClaw.git`
2. Set Vercel project root to repo root.
3. Build command: `npm run build`
4. Start command: `npm run dev:api` (or equivalent runtime command in your deployment config).
5. Set all environment variables from `.env.example`.
6. Attach Vercel Postgres and copy `DATABASE_URL`.

## Railway Setup (Worker)

1. Create new Railway service from same repo.
2. Start command: `npm run dev:worker`
3. Set the same env vars as API.
4. Ensure worker has persistent runtime enabled.

## Polar Billing

- Price target: `₹1499/month` with `7-day` trial.
- `POST /webhooks/polar` updates subscription status.
- Checkout links can be created during tenant creation flow.

## Security Notes

- Use long random `MASTER_API_KEY`.
- Issue per-tenant keys from `POST /admin/tenants`.
- Rotate keys if exposed.
- Keep webhook secrets private.

## Done Criteria Mapping

Implemented:
- Cloud API webhook listener
- deterministic auto-reply
- lead capture (name + requirement)
- DB storage for all required entities
- follow-up scheduler (+30m, +24h template)
- owner notification with fallback
- manual takeover
- tenant config and onboarding APIs
- billing-gated automation
