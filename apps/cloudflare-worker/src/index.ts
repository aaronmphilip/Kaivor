import { buildAutoReply } from "../../../packages/reply-engine/src/index.js";
import { computeTransition } from "../../../packages/state-machine/src/index.js";
import { parseTelegramWebhook, verifyTelegramSecret } from "../../../packages/telegram/src/index.js";

interface D1Prepared {
  bind(...values: unknown[]): D1Prepared;
  first<T = Record<string, unknown>>(): Promise<T | null>;
  run(): Promise<{ meta?: { changes?: number } }>;
  all<T = Record<string, unknown>>(): Promise<{ results: T[] }>;
}

interface Env {
  DB: { prepare(query: string): D1Prepared };
  MASTER_API_KEY: string;
  TELEGRAM_WEBHOOK_SECRET: string;
  OWNER_CONNECT_BOT_TOKEN?: string;
  OWNER_CONNECT_BOT_SECRET?: string;
  OWNER_CONNECT_BOT_USERNAME?: string;
  FOLLOWUP_MAX_RETRIES?: string;
  LANDING_CTA_URL?: string;
  FREE_MODE?: string;
  PUBLIC_SIGNUP_ENABLED?: string;
}

type Ctx = { waitUntil(p: Promise<unknown>): void };

const jsonHeaders = { "content-type": "application/json" };
const publicCorsHeaders = {
  "access-control-allow-origin": "*",
  "access-control-allow-methods": "GET,POST,OPTIONS",
  "access-control-allow-headers": "content-type"
};

const j = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: jsonHeaders });
const jPublic = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { ...jsonHeaders, ...publicCorsHeaders } });

const now = () => new Date().toISOString();
const plusMins = (m: number) => new Date(Date.now() + m * 60_000).toISOString();
const plusHours = (h: number) => new Date(Date.now() + h * 60 * 60_000).toISOString();
const txt = (v: unknown, n = 320) => String(v ?? "").trim().replace(/\s+/g, " ").slice(0, n);
const DEFAULT_FOLLOWUP_30M_EN = "Hey, just checking in. Are you still looking for this? Reply and we will help.";
const DEFAULT_FOLLOWUP_30M_HI = "Hey, quick check. Kya aapko abhi bhi help chahiye? Reply kar dijiye.";
const FOLLOWUP_24H_EN = "Hey, quick follow-up. Are you still looking for this? Reply and we will help.";
const FOLLOWUP_24H_HI = "Namaste, ek quick follow-up. Kya aapko abhi bhi help chahiye? Reply kar dijiye.";

const sha256 = async (raw: string) => {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(raw));
  return [...new Uint8Array(digest)].map((x) => x.toString(16).padStart(2, "0")).join("");
};

function isTrue(input: string | undefined, fallback = false): boolean {
  if (input === undefined) return fallback;
  return input.trim().toLowerCase() === "true";
}

function slugify(input: string): string {
  const base = input
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9\s-]/g, "")
    .replace(/\s+/g, "-")
    .replace(/-+/g, "-")
    .replace(/^-|-$/g, "")
    .slice(0, 42);
  const suffix = Math.floor(1000 + Math.random() * 9000);
  return `${base || "business"}-${suffix}`;
}

function isSlugConflict(error: unknown): boolean {
  const message = error instanceof Error ? error.message : String(error ?? "");
  return message.includes("UNIQUE constraint failed: tenants.slug");
}

function parseJsonObject(raw: string | null | undefined): Record<string, unknown> {
  if (!raw) return {};
  try {
    const parsed = JSON.parse(raw) as unknown;
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) return {};
    return parsed as Record<string, unknown>;
  } catch {
    return {};
  }
}

function ownerConnectBotUsername(env: Env): string {
  return txt(env.OWNER_CONNECT_BOT_USERNAME ?? "bharatclawbot", 64).replace(/^@/, "") || "bharatclawbot";
}

function ownerConnectBotUrl(env: Env): string {
  return `https://t.me/${ownerConnectBotUsername(env)}`;
}

function ownerConnectStartUrl(env: Env, code: string): string {
  return `${ownerConnectBotUrl(env)}?start=pair_${encodeURIComponent(code)}`;
}

function randomCode(length: number): string {
  const alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  let output = "";
  for (let i = 0; i < length; i += 1) {
    output += alphabet[Math.floor(Math.random() * alphabet.length)];
  }
  return output;
}

function generateOwnerPairCode(): string {
  return `BC${randomCode(6)}`;
}

function parseOwnerPairCodeFromText(message: string): string | null {
  const input = txt(message, 180);
  if (!input) return null;

  let token = input;
  const startMatch = /^\/start\s+pair_([a-z0-9-]{4,40})$/i.exec(input);
  if (startMatch) token = startMatch[1];
  const pairMatch = /^\/pair\s+([a-z0-9-]{4,40})$/i.exec(token);
  if (pairMatch) token = pairMatch[1];

  const normalized = token.toUpperCase().replace(/[^A-Z0-9]/g, "");
  if (!normalized) return null;
  const finalCode = normalized.startsWith("BC") ? normalized : `BC${normalized}`;
  if (finalCode.length < 8 || finalCode.length > 12) return null;
  return finalCode;
}

async function ownerPairCodeExists(env: Env, pairCode: string): Promise<boolean> {
  const rows = await env.DB.prepare("SELECT metadata FROM tenant_configs").all<{ metadata: string }>();
  for (const row of rows.results ?? []) {
    const metadata = parseJsonObject(row.metadata);
    const existing = txt(metadata.ownerPairCode, 20).toUpperCase();
    if (existing === pairCode) return true;
  }
  return false;
}

async function generateUniqueOwnerPairCode(env: Env, attempts = 8): Promise<string> {
  for (let i = 0; i < attempts; i += 1) {
    const pairCode = generateOwnerPairCode();
    if (!(await ownerPairCodeExists(env, pairCode))) return pairCode;
  }
  return `BC${randomCode(10)}`;
}

function generateLeadJoinCode(): string {
  return `LD${randomCode(6)}`;
}

function parseLeadJoinCodeFromText(message: string): string | null {
  const input = txt(message, 180);
  if (!input) return null;
  const match = /^\/start\s+lead_([a-z0-9-]{4,40})$/i.exec(input);
  if (!match) return null;
  const normalized = match[1].toUpperCase().replace(/[^A-Z0-9]/g, "");
  if (!normalized) return null;
  const finalCode = normalized.startsWith("LD") ? normalized : `LD${normalized}`;
  if (finalCode.length < 8 || finalCode.length > 12) return null;
  return finalCode;
}

async function leadJoinCodeExists(env: Env, joinCode: string): Promise<boolean> {
  const rows = await env.DB.prepare("SELECT metadata FROM tenant_configs").all<{ metadata: string }>();
  for (const row of rows.results ?? []) {
    const metadata = parseJsonObject(row.metadata);
    const existing = txt(metadata.leadJoinCode, 20).toUpperCase();
    if (existing === joinCode) return true;
  }
  return false;
}

async function generateUniqueLeadJoinCode(env: Env, attempts = 8): Promise<string> {
  for (let i = 0; i < attempts; i += 1) {
    const joinCode = generateLeadJoinCode();
    if (!(await leadJoinCodeExists(env, joinCode))) return joinCode;
  }
  return `LD${randomCode(10)}`;
}

async function connectOwnerByPairCode(
  env: Env,
  pairCode: string,
  ownerChatId: string,
  ownerNameHint?: string
): Promise<"CONNECTED" | "ALREADY_CONNECTED" | "INVALID_CODE"> {
  const rows = await env.DB.prepare(
    `SELECT o.id AS owner_id,o.tenant_id,o.phone,o.name,c.metadata
     FROM owner_contacts o
     JOIN tenant_configs c ON c.tenant_id=o.tenant_id
     WHERE o.is_primary=1`
  ).all<{ owner_id: string; tenant_id: string; phone: string; name: string; metadata: string }>();

  let matched:
    | { ownerId: string; tenantId: string; phone: string; name: string; metadata: Record<string, unknown> }
    | null = null;
  for (const row of rows.results ?? []) {
    const metadata = parseJsonObject(row.metadata);
    const codeInRow = txt(metadata.ownerPairCode, 20).toUpperCase();
    if (codeInRow === pairCode) {
      matched = {
        ownerId: row.owner_id,
        tenantId: row.tenant_id,
        phone: row.phone,
        name: row.name,
        metadata
      };
      break;
    }
  }

  if (!matched) return "INVALID_CODE";
  if (!matched.phone.startsWith("pending:")) return "ALREADY_CONNECTED";

  await env.DB.prepare("UPDATE owner_contacts SET phone=?, name=? WHERE id=?")
    .bind(ownerChatId, txt(ownerNameHint ?? matched.name, 120) || matched.name, matched.ownerId)
    .run();

  const nextMetadata = {
    ...matched.metadata,
    ownerPairedAt: now(),
    ownerPairStatus: "PAIRED"
  };
  await env.DB.prepare("UPDATE tenant_configs SET metadata=?, updated_at=? WHERE tenant_id=?")
    .bind(JSON.stringify(nextMetadata), now(), matched.tenantId)
    .run();

  return "CONNECTED";
}

async function resolveTenantIdByLeadJoinCode(env: Env, joinCode: string): Promise<string | null> {
  const rows = await env.DB.prepare("SELECT tenant_id, metadata FROM tenant_configs").all<{ tenant_id: string; metadata: string }>();
  for (const row of rows.results ?? []) {
    const metadata = parseJsonObject(row.metadata);
    const codeInRow = txt(metadata.leadJoinCode, 20).toUpperCase();
    if (codeInRow === joinCode) return row.tenant_id;
  }
  return null;
}

async function resolveLatestTenantIdByChat(env: Env, chatId: string): Promise<string | null> {
  const row = await env.DB.prepare("SELECT tenant_id FROM leads WHERE customer_phone=? ORDER BY updated_at DESC LIMIT 1")
    .bind(chatId)
    .first<{ tenant_id: string }>();
  return row?.tenant_id ?? null;
}

function resolveOutboundBotToken(metadata: Record<string, unknown>, env: Env): string {
  return txt(metadata.telegramBotToken, 220) || txt(env.OWNER_CONNECT_BOT_TOKEN, 220);
}

async function telegramApi<T>(
  botToken: string,
  method: string,
  payload?: Record<string, unknown>
): Promise<T> {
  const response = await fetch(`https://api.telegram.org/bot${botToken}/${method}`, {
    method: payload ? "POST" : "GET",
    headers: payload ? { "content-type": "application/json" } : undefined,
    body: payload ? JSON.stringify(payload) : undefined
  });
  const body = (await response.json().catch(() => null)) as
    | { ok: true; result: T }
    | { ok: false; description?: string; error_code?: number }
    | null;
  if (!response.ok || !body || !("ok" in body) || !body.ok) {
    const description =
      body && "description" in body && typeof body.description === "string"
        ? body.description
        : "Telegram API error";
    throw new Error(description);
  }
  return body.result;
}

async function getBotProfile(botToken: string) {
  return telegramApi<{ id: number; username?: string; first_name?: string }>(botToken, "getMe");
}

async function setTelegramWebhook(botToken: string, webhookUrl: string, webhookSecret: string): Promise<void> {
  await telegramApi<{ ok: boolean }>(botToken, "setWebhook", {
    url: webhookUrl,
    secret_token: webhookSecret
  });
}

async function sendTelegram(botToken: string, chatId: string, text: string) {
  const response = await fetch(`https://api.telegram.org/bot${botToken}/sendMessage`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ chat_id: chatId, text })
  });
  const payload = (await response.json()) as { ok: boolean; result?: { message_id?: number }; description?: string };
  if (!response.ok || !payload.ok) throw new Error(payload.description ?? "Telegram send failed");
  return String(payload.result?.message_id ?? "unknown");
}

function html(body: string, status = 200) {
  return new Response(body, {
    status,
    headers: { "content-type": "text/html; charset=utf-8", "cache-control": "public, max-age=300" }
  });
}

function renderLandingPage(ctaUrl: string) {
  const safeUrl = /^https?:\/\//i.test(ctaUrl) ? ctaUrl : "https://t.me";
  return `<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>BharatClaw | Never miss a lead again</title>
  <meta name="description" content="BharatClaw helps Indian businesses capture Telegram leads, follow up automatically, and close faster." />
  <link rel="preconnect" href="https://fonts.googleapis.com">
  <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
  <link href="https://fonts.googleapis.com/css2?family=Sora:wght@500;600;700;800&family=Manrope:wght@400;500;600;700&display=swap" rel="stylesheet">
  <style>
    :root {
      --ink: #0f1a2e;
      --muted: #5a6781;
      --line: #d8deea;
      --paper: #f6f8fc;
      --sun: #f7b341;
      --ocean: #1f5eff;
      --mint: #00b894;
      --card: #ffffff;
      --shadow: 0 20px 50px rgba(15, 26, 46, 0.12);
      --radius: 18px;
    }
    * { box-sizing: border-box; }
    html, body { margin: 0; padding: 0; }
    body {
      font-family: "Manrope", sans-serif;
      color: var(--ink);
      background:
        radial-gradient(45rem 24rem at 10% -10%, rgba(31, 94, 255, 0.18), transparent 60%),
        radial-gradient(42rem 20rem at 92% 8%, rgba(247, 179, 65, 0.18), transparent 60%),
        linear-gradient(180deg, #fbfcff 0%, #f1f5ff 100%);
      min-height: 100vh;
    }
    .noise::before {
      content: "";
      position: fixed;
      inset: 0;
      pointer-events: none;
      opacity: 0.18;
      background-image: radial-gradient(rgba(12, 26, 62, 0.04) 1px, transparent 1px);
      background-size: 4px 4px;
      z-index: 1;
    }
    .container {
      position: relative;
      z-index: 2;
      width: min(1120px, 92vw);
      margin: 0 auto;
    }
    .nav {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 26px 0 14px;
    }
    .brand {
      font-family: "Sora", sans-serif;
      font-weight: 800;
      font-size: 1.1rem;
      letter-spacing: 0.02em;
      text-transform: uppercase;
      color: #17346e;
    }
    .pill {
      display: inline-flex;
      align-items: center;
      gap: 8px;
      background: rgba(255, 255, 255, 0.8);
      border: 1px solid var(--line);
      border-radius: 999px;
      padding: 8px 14px;
      font-size: 0.86rem;
      color: var(--muted);
      backdrop-filter: blur(6px);
    }
    .hero {
      display: grid;
      grid-template-columns: 1.2fr 0.8fr;
      gap: 28px;
      padding: 22px 0 46px;
      align-items: stretch;
    }
    .panel {
      background: var(--card);
      border: 1px solid var(--line);
      border-radius: var(--radius);
      box-shadow: var(--shadow);
      padding: 30px;
      animation: rise 550ms ease both;
    }
    h1 {
      font-family: "Sora", sans-serif;
      font-size: clamp(1.9rem, 4vw, 3rem);
      line-height: 1.07;
      margin: 0 0 14px;
      letter-spacing: -0.02em;
      color: #0f2659;
    }
    .subtitle {
      margin: 0 0 22px;
      color: var(--muted);
      font-size: 1.03rem;
      max-width: 52ch;
    }
    .cta-row {
      display: flex;
      flex-wrap: wrap;
      gap: 10px;
      margin-bottom: 18px;
    }
    .btn {
      text-decoration: none;
      border-radius: 12px;
      padding: 12px 18px;
      font-weight: 700;
      font-size: 0.95rem;
      transition: transform 180ms ease, box-shadow 180ms ease, background 180ms ease;
      border: 1px solid transparent;
      display: inline-flex;
      align-items: center;
      justify-content: center;
    }
    .btn-primary {
      background: linear-gradient(145deg, var(--ocean), #255af2);
      color: #fff;
      box-shadow: 0 12px 26px rgba(31, 94, 255, 0.32);
    }
    .btn-secondary {
      background: #fff;
      border-color: var(--line);
      color: #253453;
    }
    .btn:hover {
      transform: translateY(-1px);
    }
    .stats {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: 10px;
      margin-top: 10px;
    }
    .stat {
      border: 1px solid var(--line);
      border-radius: 12px;
      padding: 11px;
      background: #fff;
    }
    .stat b {
      display: block;
      font-family: "Sora", sans-serif;
      font-size: 1rem;
      color: #163469;
      margin-bottom: 2px;
    }
    .stat span {
      color: var(--muted);
      font-size: 0.83rem;
    }
    .chat-card {
      background: linear-gradient(180deg, #ffffff, #f8faff);
    }
    .chat {
      display: grid;
      gap: 10px;
    }
    .bubble {
      max-width: 92%;
      padding: 11px 13px;
      border-radius: 12px;
      font-size: 0.9rem;
      line-height: 1.35;
      border: 1px solid var(--line);
      background: #fff;
    }
    .bot {
      border-left: 3px solid var(--ocean);
      justify-self: start;
    }
    .user {
      background: #e9fff8;
      border-color: #bdecdc;
      justify-self: end;
      border-left: 3px solid var(--mint);
    }
    .section {
      margin: 18px 0 34px;
    }
    .section h2 {
      font-family: "Sora", sans-serif;
      font-size: clamp(1.4rem, 2.5vw, 2rem);
      margin: 0 0 14px;
      color: #122d61;
    }
    .grid-3 {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: 12px;
    }
    .card {
      background: #fff;
      border: 1px solid var(--line);
      border-radius: 14px;
      padding: 18px;
      animation: rise 620ms ease both;
    }
    .card h3 {
      margin: 0 0 6px;
      font-family: "Sora", sans-serif;
      font-size: 1rem;
      color: #15336d;
    }
    .card p {
      margin: 0;
      color: var(--muted);
      font-size: 0.9rem;
      line-height: 1.45;
    }
    .pricing {
      display: grid;
      grid-template-columns: 1fr;
    }
    .pricing .card {
      display: grid;
      gap: 12px;
      align-items: center;
      grid-template-columns: 1fr auto;
      background: linear-gradient(160deg, #fffef7 0%, #ffffff 52%);
    }
    .price {
      font-family: "Sora", sans-serif;
      font-size: 2rem;
      color: #1a3264;
      letter-spacing: -0.03em;
    }
    .price small {
      font-family: "Manrope", sans-serif;
      font-size: 0.88rem;
      color: var(--muted);
      font-weight: 600;
      margin-left: 4px;
    }
    footer {
      padding: 22px 0 30px;
      color: #596683;
      font-size: 0.86rem;
      text-align: center;
    }
    @keyframes rise {
      from {
        transform: translateY(10px);
        opacity: 0;
      }
      to {
        transform: translateY(0);
        opacity: 1;
      }
    }
    @media (max-width: 960px) {
      .hero { grid-template-columns: 1fr; }
      .grid-3 { grid-template-columns: 1fr; }
      .stats { grid-template-columns: 1fr; }
      .pricing .card { grid-template-columns: 1fr; }
    }
  </style>
</head>
<body class="noise">
  <div class="container">
    <nav class="nav">
      <div class="brand">BharatClaw</div>
      <div class="pill">Telegram-first lead automation for India</div>
    </nav>

    <section class="hero">
      <div class="panel">
        <h1>Never miss a lead again.</h1>
        <p class="subtitle">
          BharatClaw replies instantly, captures lead details, follows up automatically, and alerts the owner so your business closes faster.
        </p>
        <div class="cta-row">
          <a class="btn btn-primary" href="${safeUrl}" target="_blank" rel="noreferrer">Start Free Trial</a>
          <a class="btn btn-secondary" href="#how">See how it works</a>
        </div>
        <div class="stats">
          <div class="stat">
            <b>Fast Setup</b>
            <span>Live in under 15 minutes</span>
          </div>
          <div class="stat">
            <b>24/7 Response</b>
            <span>Every lead gets immediate reply</span>
          </div>
          <div class="stat">
            <b>Built for India</b>
            <span>English + Hindi capture flow</span>
          </div>
        </div>
      </div>

      <div class="panel chat-card">
        <div class="chat">
          <div class="bubble bot">Hey, thanks for reaching out. Choose your language: 1) English 2) Hindi</div>
          <div class="bubble user">English</div>
          <div class="bubble bot">Great. What should I call you?</div>
          <div class="bubble user">Aman</div>
          <div class="bubble bot">Perfect Aman. What do you need help with today?</div>
          <div class="bubble user">Need AC repair in Sector 45 today</div>
          <div class="bubble bot">Got it. Details saved. Owner will contact you shortly.</div>
        </div>
      </div>
    </section>

    <section class="section" id="how">
      <h2>How BharatClaw Works</h2>
      <div class="grid-3">
        <article class="card">
          <h3>1. Instant Response</h3>
          <p>Your bot replies in seconds and starts a guided lead flow.</p>
        </article>
        <article class="card">
          <h3>2. Smart Capture</h3>
          <p>Captures language, name, and requirement with interruption-safe state handling.</p>
        </article>
        <article class="card">
          <h3>3. Follow Up + Alert</h3>
          <p>Automatic nudges and owner alerts ensure no lead is left behind.</p>
        </article>
      </div>
    </section>

    <section class="section pricing">
      <div class="card">
        <div>
          <h3>Free Beta Access</h3>
          <p>No credit card. Start free and onboard your Telegram bot instantly.</p>
        </div>
        <div class="price">FREE<small>for now</small></div>
      </div>
    </section>

    <footer>
      BharatClaw. Premium Telegram lead capture for Indian businesses.
    </footer>
  </div>
</body>
</html>`;
}

function isPublicPath(path: string): boolean {
  return path === "/" || path.startsWith("/public/");
}

function errorDetail(error: unknown): string {
  if (error instanceof Error) return error.message;
  return String(error ?? "Unknown error");
}

function errorResponse(path: string, error: unknown): Response {
  const detail = errorDetail(error).slice(0, 500);
  return (isPublicPath(path) ? jPublic : j)(
    {
      error: "Request failed",
      detail,
      code: "WORKER_RUNTIME_ERROR"
    },
    500
  );
}

async function isAutomationAllowed(env: Env, tenantId: string) {
  const row = await env.DB.prepare(
    `SELECT
      t.trial_ends_at,
      c.auto_reply_enabled,
      s.status,
      s.trial_ends_at AS subscription_trial_ends_at,
      (SELECT phone FROM owner_contacts WHERE tenant_id=t.id AND is_primary=1 ORDER BY created_at LIMIT 1) AS owner_phone
     FROM tenants t JOIN tenant_configs c ON c.tenant_id=t.id
     LEFT JOIN subscriptions s ON s.tenant_id=t.id
     WHERE t.id=?`
  )
    .bind(tenantId)
    .first<{
      trial_ends_at: string;
      auto_reply_enabled: number;
      status: string | null;
      subscription_trial_ends_at: string | null;
      owner_phone: string | null;
    }>();
  if (!row || !row.auto_reply_enabled) return false;
  if (!row.owner_phone || row.owner_phone.startsWith("pending:")) return false;
  if (isTrue(env.FREE_MODE, true)) return true;
  if ((row.status ?? "TRIALING") === "ACTIVE") return true;
  const trial = new Date(row.subscription_trial_ends_at ?? row.trial_ends_at).getTime();
  return trial >= Date.now();
}

async function createTenantRecord(
  env: Env,
  input: {
    name: string;
    slug: string;
    ownerName: string;
    ownerChatId?: string | null;
    telegramBotToken?: string | null;
    sharedBotMode?: boolean;
    ownerEmail?: string | null;
    trialDays?: number;
    forceActive?: boolean;
  }
) {
  const tenantId = crypto.randomUUID();
  const tenantApiKey = `tnt_${crypto.randomUUID().replace(/-/g, "")}`;
  const salt = crypto.randomUUID().replace(/-/g, "");
  const tenantApiKeyHash = `${salt}:${await sha256(`${salt}:${tenantApiKey}`)}`;
  const webhookSecret = crypto.randomUUID().replace(/-/g, "");
  const ownerPairCode = await generateUniqueOwnerPairCode(env);
  const leadJoinCode = await generateUniqueLeadJoinCode(env);
  const ownerPhone = txt(input.ownerChatId ?? "", 64) || `pending:${tenantId}`;
  const trialDays = Math.max(1, Math.min(3650, Number(input.trialDays ?? 3650)));
  const trialEndsAt = new Date(Date.now() + trialDays * 24 * 60 * 60_000).toISOString();
  try {
    await env.DB.prepare(
      `INSERT INTO tenants (id,name,slug,whatsapp_phone_number_id,tenant_api_key_hash,trial_ends_at,created_at)
       VALUES (?,?,?,?,?,?,?)`
    )
      .bind(tenantId, input.name, input.slug, `telegram:${input.slug}`, tenantApiKeyHash, trialEndsAt, now())
      .run();
    await env.DB.prepare(
      `INSERT INTO owner_contacts (id,tenant_id,name,phone,email,is_primary,created_at) VALUES (?,?,?,?,?,1,?)`
    )
      .bind(crypto.randomUUID(), tenantId, input.ownerName, ownerPhone, input.ownerEmail ?? null, now())
      .run();
    await env.DB.prepare(
      `INSERT INTO tenant_configs (tenant_id,auto_reply_enabled,greeting_template,followup_30m_template,followup_24h_template_name,takeover_cooldown_minutes,metadata,updated_at)
       VALUES (?,1,?,?,?,?,?,?)`
    )
      .bind(
        tenantId,
        `Hey thanks for reaching out to ${input.name}. I will help you with this. Just a couple quick details first.`,
        DEFAULT_FOLLOWUP_30M_EN,
        "lead_followup_24h",
        180,
        JSON.stringify(
          (() => {
            const metadata: Record<string, unknown> = {
              businessName: input.name,
              webhookSecret,
              ownerPairCode,
              leadJoinCode,
              ownerPairStatus: ownerPhone.startsWith("pending:") ? "PENDING" : "PAIRED",
              sharedBotMode: Boolean(input.sharedBotMode)
            };
            if (input.telegramBotToken) {
              metadata.telegramBotToken = input.telegramBotToken;
            }
            return metadata;
          })()
        ),
        now()
      )
      .run();
    await env.DB.prepare(
      `INSERT INTO subscriptions (id,tenant_id,provider,status,trial_ends_at,created_at,updated_at)
       VALUES (?,?, 'POLAR',?,?,?,?)`
    )
      .bind(
        crypto.randomUUID(),
        tenantId,
        input.forceActive ? "ACTIVE" : "TRIALING",
        trialEndsAt,
        now(),
        now()
      )
      .run();

    return {
      tenantId,
      tenantApiKey,
      trialEndsAt,
      webhookSecret,
      ownerConnected: !ownerPhone.startsWith("pending:"),
      ownerPairCode,
      leadJoinCode,
      sharedBotMode: Boolean(input.sharedBotMode)
    };
  } catch (error) {
    await env.DB.prepare("DELETE FROM tenants WHERE id=?").bind(tenantId).run().catch(() => undefined);
    throw error;
  }
}

async function processFollowups(env: Env) {
  const maxRetries = Number(env.FOLLOWUP_MAX_RETRIES ?? "3");
  const jobs = await env.DB.prepare(
    `SELECT * FROM followup_jobs WHERE status IN ('PENDING','FAILED') AND run_at<=? ORDER BY run_at LIMIT 20`
  )
    .bind(now())
    .all<{
      id: string;
      tenant_id: string;
      lead_id: string;
      job_type: "FOLLOWUP_30M" | "FOLLOWUP_24H";
      attempt_count: number;
      idempotency_key: string;
      created_at: string;
    }>();

  for (const job of jobs.results ?? []) {
    try {
      await env.DB.prepare(
        `UPDATE followup_jobs SET status='PROCESSING', attempt_count=attempt_count+1, locked_at=?, updated_at=? WHERE id=?`
      )
        .bind(now(), now(), job.id)
        .run();

      const lead = await env.DB.prepare("SELECT * FROM leads WHERE id=? AND tenant_id=?")
        .bind(job.lead_id, job.tenant_id)
        .first<{
          id: string;
          customer_phone: string;
          preferred_language: "en" | "hi" | null;
          bot_paused_until: string | null;
          last_inbound_at: string | null;
        }>();
      const config = await env.DB.prepare("SELECT * FROM tenant_configs WHERE tenant_id=?")
        .bind(job.tenant_id)
        .first<{ metadata: string; followup_30m_template: string }>();
      if (!lead || !config) throw new Error("Lead or config missing");

      const owner = await env.DB.prepare(
        "SELECT phone FROM owner_contacts WHERE tenant_id=? AND is_primary=1 ORDER BY created_at LIMIT 1"
      )
        .bind(job.tenant_id)
        .first<{ phone: string }>();
      if (!owner || owner.phone.startsWith("pending:")) {
        await env.DB.prepare("UPDATE followup_jobs SET status='PENDING', run_at=?, updated_at=? WHERE id=?")
          .bind(plusMins(30), now(), job.id)
          .run();
        continue;
      }

      if (lead.bot_paused_until && new Date(lead.bot_paused_until).getTime() > Date.now()) {
        await env.DB.prepare("UPDATE followup_jobs SET status='SKIPPED', updated_at=? WHERE id=?").bind(now(), job.id).run();
        continue;
      }
      if (lead.last_inbound_at && new Date(lead.last_inbound_at).getTime() > new Date(job.created_at).getTime()) {
        await env.DB.prepare("UPDATE followup_jobs SET status='SKIPPED', updated_at=? WHERE id=?").bind(now(), job.id).run();
        continue;
      }

      const metadata = parseJsonObject(config.metadata);
      const botToken = resolveOutboundBotToken(metadata, env);
      if (!botToken) throw new Error("Missing outbound bot token");
      const language = lead.preferred_language === "hi" ? "hi" : "en";
      const configured30m = txt(config.followup_30m_template);
      const body =
        job.job_type === "FOLLOWUP_30M"
          ? language === "hi" && configured30m === DEFAULT_FOLLOWUP_30M_EN
            ? DEFAULT_FOLLOWUP_30M_HI
            : configured30m
          : language === "hi"
            ? FOLLOWUP_24H_HI
            : FOLLOWUP_24H_EN;

      const externalId = await sendTelegram(botToken, lead.customer_phone, body);
      await env.DB.prepare(
        `INSERT OR IGNORE INTO messages (id,tenant_id,lead_id,direction,message_type,body,external_message_id,idempotency_key,created_at)
         VALUES (?,?,?,'OUTBOUND','TEXT',?,?,?,?)`
      )
        .bind(crypto.randomUUID(), job.tenant_id, job.lead_id, body, externalId, `${job.idempotency_key}:a${job.attempt_count + 1}`, now())
        .run();
      await env.DB.prepare("UPDATE leads SET last_outbound_at=?,updated_at=? WHERE id=?").bind(now(), now(), lead.id).run();
      await env.DB.prepare("UPDATE followup_jobs SET status='SENT', last_error=NULL, updated_at=? WHERE id=?").bind(now(), job.id).run();
    } catch (error) {
      const err = error instanceof Error ? error.message : "Follow-up error";
      const retry = job.attempt_count + 1 >= maxRetries ? "DEAD" : "FAILED";
      await env.DB.prepare(
        "UPDATE followup_jobs SET status=?, last_error=?, run_at=?, updated_at=? WHERE id=?"
      )
        .bind(retry, err.slice(0, 2000), retry === "FAILED" ? plusMins(10) : now(), now(), job.id)
        .run();
    }
  }
}

type ParsedInboundMessage = ReturnType<typeof parseTelegramWebhook>[number];

async function processTenantMessage(
  env: Env,
  tenantId: string,
  msg: ParsedInboundMessage,
  source: "TELEGRAM" | "TELEGRAM_SHARED"
) {
  const done = await env.DB.prepare("SELECT 1 as ok FROM processed_events WHERE event_id=? AND source=?")
    .bind(msg.eventId, source)
    .first<{ ok: number }>();
  if (done) return;

  if (!(await isAutomationAllowed(env, tenantId))) {
    await env.DB.prepare("INSERT OR IGNORE INTO processed_events (event_id,source,processed_at) VALUES (?,?,?)")
      .bind(msg.eventId, source, now())
      .run();
    return;
  }

  let lead = await env.DB.prepare("SELECT * FROM leads WHERE tenant_id=? AND customer_phone=?")
    .bind(tenantId, txt(msg.chatId, 64))
    .first<{
      id: string;
      customer_phone: string;
      customer_name: string | null;
      preferred_language: "en" | "hi" | null;
      requirement: string | null;
      bot_paused_until: string | null;
      status: string;
    }>();
  if (!lead) {
    const leadId = crypto.randomUUID();
    await env.DB.prepare(
      "INSERT INTO leads (id,tenant_id,customer_phone,status,last_inbound_at,created_at,updated_at) VALUES (?,?,?,'NEW',?,?,?)"
    )
      .bind(leadId, tenantId, txt(msg.chatId, 64), now(), now(), now())
      .run();
    lead = await env.DB.prepare("SELECT * FROM leads WHERE id=?").bind(leadId).first<{
      id: string;
      customer_phone: string;
      customer_name: string | null;
      preferred_language: "en" | "hi" | null;
      requirement: string | null;
      bot_paused_until: string | null;
      status: string;
    }>();
  }
  if (!lead) return;

  await env.DB.prepare(
    "INSERT OR IGNORE INTO messages (id,tenant_id,lead_id,direction,message_type,body,external_message_id,idempotency_key,created_at) VALUES (?,?,?,'INBOUND','TEXT',?,?,?,?)"
  )
    .bind(crypto.randomUUID(), tenantId, lead.id, txt(msg.text, 500), msg.eventId, `inbound:${source}:${msg.eventId}`, now())
    .run();
  await env.DB.prepare("UPDATE leads SET last_inbound_at=?,updated_at=? WHERE id=?").bind(now(), now(), lead.id).run();

  if (lead.bot_paused_until && new Date(lead.bot_paused_until).getTime() > Date.now()) {
    await env.DB.prepare("INSERT OR IGNORE INTO processed_events (event_id,source,processed_at) VALUES (?,?,?)")
      .bind(msg.eventId, source, now())
      .run();
    return;
  }

  let convo = await env.DB.prepare("SELECT * FROM conversations WHERE tenant_id=? AND lead_id=?")
    .bind(tenantId, lead.id)
    .first<{ id: string; state: string }>();
  if (!convo) {
    const id = crypto.randomUUID();
    await env.DB.prepare(
      "INSERT INTO conversations (id,tenant_id,lead_id,state,last_message_at,created_at,updated_at) VALUES (?,?,?,'NEW',?,?,?)"
    )
      .bind(id, tenantId, lead.id, now(), now(), now())
      .run();
    convo = { id, state: "NEW" };
  }

  const transition = computeTransition(convo.state as never, txt(msg.text, 500), lead.preferred_language ?? undefined);
  const nextLanguage =
    transition.preferredLanguage !== undefined
      ? transition.preferredLanguage
      : lead.preferred_language;
  const nextName = transition.clearLeadProfile
    ? transition.customerName ?? null
    : transition.customerName ?? lead.customer_name;
  const nextRequirement = transition.clearLeadProfile
    ? transition.requirement ?? null
    : transition.requirement ?? lead.requirement;
  const replyLanguage =
    transition.preferredLanguage !== undefined
      ? transition.preferredLanguage ?? undefined
      : lead.preferred_language ?? undefined;
  await env.DB.prepare("UPDATE leads SET customer_name=?, preferred_language=?, requirement=?, status=?, updated_at=? WHERE id=?")
    .bind(
      nextName,
      nextLanguage,
      nextRequirement,
      transition.nextLeadStatus,
      now(),
      lead.id
    )
    .run();
  await env.DB.prepare("UPDATE conversations SET state=?, last_message_at=?, updated_at=? WHERE id=?")
    .bind(transition.nextState, now(), now(), convo.id)
    .run();

  const config = await env.DB.prepare("SELECT * FROM tenant_configs WHERE tenant_id=?")
    .bind(tenantId)
    .first<{
      greeting_template: string;
      followup_30m_template: string;
      followup_24h_template_name: string;
      takeover_cooldown_minutes: number;
      auto_reply_enabled: number;
      metadata: string;
      updated_at: string;
    }>();
  const metadata = parseJsonObject(config?.metadata);
  const botToken = resolveOutboundBotToken(metadata, env);
  if (!botToken) {
    await env.DB.prepare("INSERT OR IGNORE INTO processed_events (event_id,source,processed_at) VALUES (?,?,?)")
      .bind(msg.eventId, source, now())
      .run();
    return;
  }

  if (transition.shouldReply && config) {
    const reply = buildAutoReply({
      transition,
      customerName: transition.customerName ?? (nextName ?? undefined),
      language: replyLanguage,
      config: {
        tenantId,
        autoReplyEnabled: Boolean(config.auto_reply_enabled),
        greetingTemplate: config.greeting_template,
        followup30mTemplate: config.followup_30m_template,
        followup24hTemplateName: config.followup_24h_template_name,
        takeoverCooldownMinutes: Number(config.takeover_cooldown_minutes),
        metadata,
        updatedAt: new Date(config.updated_at)
      }
    });
    const ext = await sendTelegram(botToken, lead.customer_phone, reply);
    await env.DB.prepare(
      "INSERT OR IGNORE INTO messages (id,tenant_id,lead_id,direction,message_type,body,external_message_id,idempotency_key,created_at) VALUES (?,?,?,'OUTBOUND','TEXT',?,?,?,?)"
    )
      .bind(crypto.randomUUID(), tenantId, lead.id, reply, ext, `outbound:reply:${source}:${msg.eventId}`, now())
      .run();
    await env.DB.prepare("UPDATE leads SET last_outbound_at=?,updated_at=? WHERE id=?").bind(now(), now(), lead.id).run();
  }

  if (transition.shouldScheduleFollowups) {
    await env.DB.prepare(
      "INSERT OR IGNORE INTO followup_jobs (id,tenant_id,lead_id,job_type,run_at,status,attempt_count,idempotency_key,created_at,updated_at) VALUES (?,?,?,'FOLLOWUP_30M',?,'PENDING',0,?,?,?)"
    )
      .bind(crypto.randomUUID(), tenantId, lead.id, plusMins(30), `${tenantId}:${lead.id}:FOLLOWUP_30M`, now(), now())
      .run();
    await env.DB.prepare(
      "INSERT OR IGNORE INTO followup_jobs (id,tenant_id,lead_id,job_type,run_at,status,attempt_count,idempotency_key,created_at,updated_at) VALUES (?,?,?,'FOLLOWUP_24H',?,'PENDING',0,?,?,?)"
    )
      .bind(crypto.randomUUID(), tenantId, lead.id, plusHours(24), `${tenantId}:${lead.id}:FOLLOWUP_24H`, now(), now())
      .run();
  }

  if (transition.shouldNotifyOwner && config) {
    const owners = await env.DB.prepare("SELECT * FROM owner_contacts WHERE tenant_id=? AND is_primary=1 ORDER BY created_at")
      .bind(tenantId)
      .all<{ phone: string }>();
    const updated = await env.DB.prepare("SELECT * FROM leads WHERE id=?")
      .bind(lead.id)
      .first<{ customer_name: string | null; customer_phone: string; requirement: string | null; preferred_language: "en" | "hi" | null }>();
    const summary = [
      "New lead captured.",
      `Name: ${updated?.customer_name ?? "Not provided"}`,
      `Chat: ${updated?.customer_phone ?? lead.customer_phone}`,
      `Requirement: ${updated?.requirement ?? "Not provided"}`,
      `Language: ${updated?.preferred_language === "hi" ? "Hindi" : "English"}`
    ].join("\n");
    for (const owner of owners.results ?? []) {
      try {
        await sendTelegram(botToken, owner.phone, summary);
      } catch {
        // best effort for MVP
      }
    }
  }

  await env.DB.prepare("INSERT OR IGNORE INTO processed_events (event_id,source,processed_at) VALUES (?,?,?)")
    .bind(msg.eventId, source, now())
    .run();
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    const path = url.pathname.replace(/\/+$/, "") || "/";
    try {

      if (request.method === "GET" && path === "/") {
        return html(renderLandingPage(env.LANDING_CTA_URL ?? "https://t.me"));
      }
      if (request.method === "GET" && path === "/health") return j({ ok: true, service: "bharatclaw-cf" });

    if (
      request.method === "OPTIONS" &&
      (path === "/public/free-trial" || /^\/public\/free-trial\/[a-z0-9-]+\/status$/i.test(path))
    ) {
      return new Response(null, { status: 204, headers: publicCorsHeaders });
    }

    if (request.method === "GET" && /^\/public\/free-trial\/[a-z0-9-]+\/status$/i.test(path)) {
      const tenantId = path.split("/")[3] ?? "";
      if (!tenantId) return jPublic({ error: "Missing tenant id" }, 400);
      const owner = await env.DB.prepare(
        "SELECT phone FROM owner_contacts WHERE tenant_id=? AND is_primary=1 ORDER BY created_at LIMIT 1"
      )
        .bind(tenantId)
        .first<{ phone: string }>();
      if (!owner) return jPublic({ error: "Tenant not found" }, 404);
      return jPublic({
        ok: true,
        tenantId,
        ownerConnected: !owner.phone.startsWith("pending:")
      });
    }

    if (request.method === "POST" && path === "/public/free-trial") {
      if (!isTrue(env.PUBLIC_SIGNUP_ENABLED, true)) {
        return jPublic({ error: "Public signup is disabled" }, 403);
      }
      const body = (await request.json().catch(() => null)) as Record<string, unknown> | null;
      if (!body || typeof body !== "object" || Array.isArray(body)) return jPublic({ error: "Invalid JSON" }, 400);

      const name = txt(body.businessName ?? body.name ?? body.business, 120);
      const ownerName = txt(body.ownerName ?? body.owner ?? body.contactName, 120);
      const ownerChatId = txt(body.ownerChatId, 64);
      const telegramBotToken = txt(body.telegramBotToken, 200);
      const ownerEmail = body.ownerEmail ? txt(body.ownerEmail, 200) : null;

      if (!name || !ownerName) {
        return jPublic(
          {
            error: "Missing required fields",
            missing: {
              businessName: !name,
              ownerName: !ownerName
            },
            receivedKeys: Object.keys(body)
          },
          400
        );
      }

      const sharedBotMode = !telegramBotToken;
      if (sharedBotMode && !txt(env.OWNER_CONNECT_BOT_TOKEN, 220)) {
        return jPublic({ error: "Shared bot is not configured on server" }, 503);
      }

      let botUsername = "";
      if (telegramBotToken) {
        try {
          const profile = await getBotProfile(telegramBotToken);
          botUsername = String(profile.username ?? "");
        } catch {
          return jPublic({ error: "Invalid Telegram Bot Token" }, 400);
        }
      } else {
        botUsername = ownerConnectBotUsername(env);
      }
      if (!botUsername) {
        return jPublic({ error: "Bot username not found on Telegram" }, 400);
      }

      const slugBase = txt(body.slug ?? name, 64) || name;
      let created:
        | {
            tenantId: string;
            tenantApiKey: string;
            trialEndsAt: string;
            webhookSecret: string;
            ownerConnected: boolean;
            ownerPairCode: string;
            leadJoinCode: string;
            sharedBotMode: boolean;
          }
        | null = null;
      let lastCreateError: unknown = null;
      for (let attempt = 0; attempt < 5; attempt += 1) {
        const slug = slugify(slugBase);
        try {
          created = await createTenantRecord(env, {
            name,
            slug,
            ownerName,
            ownerChatId: ownerChatId || undefined,
            telegramBotToken: telegramBotToken || null,
            sharedBotMode,
            ownerEmail,
            trialDays: 3650,
            forceActive: true
          });
          break;
        } catch (error) {
          if (!isSlugConflict(error)) throw error;
          lastCreateError = error;
        }
      }
      if (!created) {
        return jPublic(
          {
            error: "Could not create workspace right now",
            detail: lastCreateError instanceof Error ? lastCreateError.message : "Slug collision retry limit reached"
          },
          409
        );
      }

      const webhookUrl = sharedBotMode
        ? `${url.origin}/webhooks/owner-connect`
        : `${url.origin}/webhooks/telegram/${created.tenantId}`;
      if (!sharedBotMode) {
        try {
          await setTelegramWebhook(telegramBotToken, webhookUrl, created.webhookSecret);
        } catch (error) {
          await env.DB.prepare("DELETE FROM tenants WHERE id=?").bind(created.tenantId).run();
          return jPublic(
            {
              error: "Could not configure Telegram webhook",
              detail: error instanceof Error ? error.message : "Unknown Telegram error"
            },
            502
          );
        }
      }

      return jPublic(
        {
          ok: true,
          free: true,
          tenantId: created.tenantId,
          tenantApiKey: created.tenantApiKey,
          trialEndsAt: created.trialEndsAt,
          webhookSecret: created.webhookSecret,
          webhookUrl,
          webhookConfigured: true,
          botUsername,
          sharedBotMode: created.sharedBotMode,
          leadJoinCode: created.leadJoinCode,
          leadEntryUrl: `${ownerConnectBotUrl(env)}?start=lead_${created.leadJoinCode}`,
          ownerConnected: created.ownerConnected,
          ownerPairCode: created.ownerPairCode,
          ownerConnectBot: ownerConnectBotUsername(env),
          ownerConnectBotUrl: ownerConnectBotUrl(env),
          ownerConnectUrl: created.ownerConnected ? null : ownerConnectStartUrl(env, created.ownerPairCode),
          nextStep: created.ownerConnected
            ? "Workspace is paired. Share leadEntryUrl and start receiving leads on @bharatclawbot."
            : "Open @bharatclawbot, send the pairing code, then check owner connection status."
        },
        201
      );
    }

    if (request.method === "POST" && path === "/admin/tenants") {
      if (request.headers.get("x-master-api-key") !== env.MASTER_API_KEY) return j({ error: "Unauthorized" }, 401);
      const body = (await request.json().catch(() => null)) as Record<string, unknown> | null;
      if (!body) return j({ error: "Invalid JSON" }, 400);

      const name = txt(body.name, 120);
      const slug = txt(body.slug, 64).toLowerCase();
      const ownerName = txt(body.ownerName, 120);
      const ownerChatId = txt(body.ownerChatId, 64);
      const telegramBotToken = txt(body.telegramBotToken, 200);
      const ownerEmail = body.ownerEmail ? txt(body.ownerEmail, 200) : null;
      if (!name || !slug || !ownerName || !ownerChatId || !telegramBotToken) return j({ error: "Missing fields" }, 400);
      const created = await createTenantRecord(env, {
        name,
        slug,
        ownerName,
        ownerChatId,
        telegramBotToken,
        ownerEmail,
        trialDays: Number(body.trialDays ?? 7),
        forceActive: isTrue(env.FREE_MODE, true)
      });
      return j(created, 201);
    }

    if (request.method === "POST" && path === "/webhooks/owner-connect") {
      const expectedSecret = txt(env.OWNER_CONNECT_BOT_SECRET ?? env.TELEGRAM_WEBHOOK_SECRET, 220);
      if (!verifyTelegramSecret(request.headers.get("x-telegram-bot-api-secret-token") ?? undefined, expectedSecret)) {
        return j({ error: "Invalid Telegram secret" }, 401);
      }
      const payload = await request.json().catch(() => null);
      if (!payload) return j({ error: "Invalid JSON" }, 400);
      const inbound = parseTelegramWebhook(payload);
      const ownerConnectBotToken = txt(env.OWNER_CONNECT_BOT_TOKEN, 220);

      for (const msg of inbound) {
        const pairCode = parseOwnerPairCodeFromText(msg.text);
        if (pairCode) {
          const done = await env.DB.prepare("SELECT 1 as ok FROM processed_events WHERE event_id=? AND source='TELEGRAM_OWNER_CONNECT'")
            .bind(msg.eventId)
            .first<{ ok: number }>();
          if (done) continue;

          let reply = "Invalid code. Open BharatClaw onboarding and copy the latest pairing code.";
          const connected = await connectOwnerByPairCode(env, pairCode, txt(msg.chatId, 64));
          if (connected === "CONNECTED") {
            reply = "Pairing successful. Go back to BharatClaw and click Check Owner Connection.";
          } else if (connected === "ALREADY_CONNECTED") {
            reply = "This workspace is already paired.";
          }
          if (ownerConnectBotToken) {
            await sendTelegram(ownerConnectBotToken, txt(msg.chatId, 64), reply).catch(() => {});
          }
          await env.DB.prepare("INSERT OR IGNORE INTO processed_events (event_id,source,processed_at) VALUES (?,'TELEGRAM_OWNER_CONNECT',?)")
            .bind(msg.eventId, now())
            .run();
          continue;
        }

        const joinCode = parseLeadJoinCodeFromText(msg.text);
        let tenantId: string | null = null;
        if (joinCode) {
          tenantId = await resolveTenantIdByLeadJoinCode(env, joinCode);
        } else {
          tenantId = await resolveLatestTenantIdByChat(env, txt(msg.chatId, 64));
        }

        if (!tenantId) {
          const done = await env.DB.prepare("SELECT 1 as ok FROM processed_events WHERE event_id=? AND source='TELEGRAM_OWNER_CONNECT'")
            .bind(msg.eventId)
            .first<{ ok: number }>();
          if (done) continue;
          if (ownerConnectBotToken) {
            await sendTelegram(
              ownerConnectBotToken,
              txt(msg.chatId, 64),
              "Workspace not linked. Owner must pair first, then leads should start from the lead link."
            ).catch(() => {});
          }
          await env.DB.prepare("INSERT OR IGNORE INTO processed_events (event_id,source,processed_at) VALUES (?,'TELEGRAM_OWNER_CONNECT',?)")
            .bind(msg.eventId, now())
            .run();
          continue;
        }

        await processTenantMessage(env, tenantId, msg, "TELEGRAM_SHARED");
      }

      return j({ ok: true });
    }

    if (request.method === "POST" && path.startsWith("/webhooks/telegram/")) {
      const tenantId = path.split("/").pop() || "";
      if (!tenantId) return j({ error: "Missing tenant id" }, 400);

      const tenant = await env.DB.prepare("SELECT * FROM tenants WHERE id=?").bind(tenantId).first();
      if (!tenant) return j({ error: "Tenant not found" }, 404);
      const tenantConfig = await env.DB.prepare("SELECT metadata FROM tenant_configs WHERE tenant_id=?")
        .bind(tenantId)
        .first<{ metadata: string }>();
      const metadata = parseJsonObject(tenantConfig?.metadata);
      const expectedSecret = String(metadata.webhookSecret ?? env.TELEGRAM_WEBHOOK_SECRET ?? "");
      if (!verifyTelegramSecret(request.headers.get("x-telegram-bot-api-secret-token") ?? undefined, expectedSecret)) {
        return j({ error: "Invalid Telegram secret" }, 401);
      }
      const payload = await request.json().catch(() => null);
      if (!payload) return j({ error: "Invalid JSON" }, 400);
      const inbound = parseTelegramWebhook(payload);

      for (const msg of inbound) {
        await processTenantMessage(env, tenantId, msg, "TELEGRAM");
      }

      return j({ ok: true });
    }

    if (request.method === "POST" && path.startsWith("/admin/tenants/") && path.endsWith("/config")) {
      const tenantId = path.split("/")[3] ?? "";
      if (!tenantId) return j({ error: "Missing tenant id" }, 400);
      const master = request.headers.get("x-master-api-key") === env.MASTER_API_KEY;
      const tenantKey = request.headers.get("x-tenant-api-key");
      let tenantAuthorized = false;
      if (tenantKey) {
        const tenant = await env.DB.prepare("SELECT tenant_api_key_hash FROM tenants WHERE id=?").bind(tenantId).first<{ tenant_api_key_hash: string }>();
        if (tenant) {
          const [salt] = tenant.tenant_api_key_hash.split(":");
          tenantAuthorized = tenant.tenant_api_key_hash === `${salt}:${await sha256(`${salt}:${tenantKey}`)}`;
        }
      }
      if (!master && !tenantAuthorized) return j({ error: "Unauthorized" }, 401);

      const body = (await request.json().catch(() => null)) as Record<string, unknown> | null;
      if (!body) return j({ error: "Invalid JSON" }, 400);
      const existing = await env.DB.prepare("SELECT * FROM tenant_configs WHERE tenant_id=?")
        .bind(tenantId)
        .first<{ auto_reply_enabled: number; greeting_template: string; followup_30m_template: string; followup_24h_template_name: string; takeover_cooldown_minutes: number; metadata: string }>();
      if (!existing) return j({ error: "Tenant config missing" }, 404);
      const metadata = parseJsonObject(existing.metadata);
      if (body.telegramBotToken) metadata.telegramBotToken = txt(body.telegramBotToken, 200);
      if (body.businessName) metadata.businessName = txt(body.businessName, 120);
      if (body.webhookSecret) metadata.webhookSecret = txt(body.webhookSecret, 120);
      await env.DB.prepare(
        "UPDATE tenant_configs SET auto_reply_enabled=?, greeting_template=?, followup_30m_template=?, followup_24h_template_name=?, takeover_cooldown_minutes=?, metadata=?, updated_at=? WHERE tenant_id=?"
      )
        .bind(
          body.autoReplyEnabled !== undefined ? (body.autoReplyEnabled ? 1 : 0) : existing.auto_reply_enabled,
          body.greetingTemplate ? txt(body.greetingTemplate) : existing.greeting_template,
          body.followup30mTemplate ? txt(body.followup30mTemplate) : existing.followup_30m_template,
          body.followup24hTemplateName ? txt(body.followup24hTemplateName, 100) : existing.followup_24h_template_name,
          body.takeoverCooldownMinutes ? Math.max(5, Math.min(1440, Number(body.takeoverCooldownMinutes))) : existing.takeover_cooldown_minutes,
          JSON.stringify(metadata),
          now(),
          tenantId
        )
        .run();
      return j({ ok: true });
    }

    if (request.method === "POST" && path === "/internal/takeover") {
      const body = (await request.json().catch(() => null)) as Record<string, unknown> | null;
      if (!body) return j({ error: "Invalid JSON" }, 400);
      const tenantId = txt(body.tenantId, 64);
      const command = txt(body.command, 80);
      const match = /^#takeover\s+(\d{5,20})$/i.exec(command);
      if (!match) return j({ error: "Command must be '#takeover <chat_id>'" }, 400);
      const chatId = match[1];
      const master = request.headers.get("x-master-api-key") === env.MASTER_API_KEY;
      if (!master) return j({ error: "Unauthorized" }, 401);
      const lead = await env.DB.prepare("SELECT id FROM leads WHERE tenant_id=? AND customer_phone=?").bind(tenantId, chatId).first<{ id: string }>();
      if (!lead) return j({ error: "Lead not found" }, 404);
      const cfg = await env.DB.prepare("SELECT takeover_cooldown_minutes FROM tenant_configs WHERE tenant_id=?").bind(tenantId).first<{ takeover_cooldown_minutes: number }>();
      const pause = plusMins(Math.max(5, Math.min(1440, Number(cfg?.takeover_cooldown_minutes ?? 180))));
      await env.DB.prepare("UPDATE leads SET status='OWNER_TAKEOVER', bot_paused_until=?, updated_at=? WHERE id=?").bind(pause, now(), lead.id).run();
      await env.DB.prepare("UPDATE conversations SET state='OWNER_TAKEOVER', updated_at=? WHERE lead_id=?").bind(now(), lead.id).run();
      return j({ ok: true, leadId: lead.id, pausedUntil: pause });
    }

    if (request.method === "POST" && path === "/internal/run-followups") {
      if (request.headers.get("x-master-api-key") !== env.MASTER_API_KEY) return j({ error: "Unauthorized" }, 401);
      await processFollowups(env);
      return j({ ok: true });
    }

      return j({ error: "Not found" }, 404);
    } catch (error) {
      console.error("bharatclaw-worker-error", { path, detail: errorDetail(error) });
      return errorResponse(path, error);
    }
  },

  async scheduled(_event: unknown, env: Env, ctx: Ctx): Promise<void> {
    ctx.waitUntil(processFollowups(env));
  }
};
