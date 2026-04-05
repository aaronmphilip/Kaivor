import { buildAutoReply } from "../../../packages/reply-engine/src/premium.js";
import { computeTransition } from "../../../packages/state-machine/src/premium.js";
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
  AI_AGENT_ENABLED?: string;
  AI_AGENT_API_KEY?: string;
  AI_AGENT_MODEL?: string;
  AI_AGENT_API_URL?: string;
  AI_AGENT_SYSTEM_PROMPT?: string;
}

type Ctx = { waitUntil(p: Promise<unknown>): void };

type WorkspaceLeadRow = {
  customer_name: string | null;
  customer_phone: string;
  requirement: string | null;
  status: string;
  preferred_language: string | null;
  last_inbound_at: string | null;
  last_outbound_at: string | null;
  updated_at: string;
  created_at: string;
};

type TranscriptRow = {
  direction: string;
  body: string;
  created_at: string;
};

type AiCopilotOutput = {
  reply: string;
  ownerSummary: string;
  ownerShouldTakeover: boolean;
  leadTemperature: "hot" | "warm" | "cold";
  intentLabel: string;
  followupHint: string;
};

const jsonHeaders = { "content-type": "application/json" };
const publicCorsHeaders = {
  "access-control-allow-origin": "*",
  "access-control-allow-methods": "GET,POST,OPTIONS",
  "access-control-allow-headers": "content-type,authorization"
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
const launchFocusCatalog = {
  lead_capture: {
    label: "Lead capture",
    summary: "Reply instantly, collect contact intent, and keep every new conversation inside one funnel.",
    availabilityLabel: "Live now"
  },
  followup_recovery: {
    label: "Follow-up recovery",
    summary: "Bring quiet conversations back with timed nudges and owner visibility.",
    availabilityLabel: "Live now"
  },
  owner_handoff: {
    label: "Owner handoff",
    summary: "Pause the bot and bring a human into the chat when the lead needs judgment or pricing.",
    availabilityLabel: "Live now"
  },
  quote_requests: {
    label: "Quote requests",
    summary: "Shape the workspace around scope capture for pricing-heavy conversations.",
    availabilityLabel: "Configured focus"
  },
  appointment_requests: {
    label: "Appointment requests",
    summary: "Orient intake around date, timing, and availability-driven conversations.",
    availabilityLabel: "Configured focus"
  }
} as const;
const defaultLaunchFocusKeys = ["lead_capture", "followup_recovery", "owner_handoff"] as const;

type LaunchFocusKey = keyof typeof launchFocusCatalog;

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

function normalizeLaunchFocusKey(input: unknown): string {
  return txt(input, 60)
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "_")
    .replace(/^_+|_+$/g, "");
}

function sanitizeLaunchFocusKeys(input: unknown): LaunchFocusKey[] {
  const rawValues = Array.isArray(input)
    ? input
    : typeof input === "string"
      ? input.split(",")
      : [];
  const output: LaunchFocusKey[] = [];
  const seen = new Set<LaunchFocusKey>();

  for (const rawValue of rawValues) {
    const normalized = normalizeLaunchFocusKey(rawValue);
    if (!normalized || !(normalized in launchFocusCatalog)) continue;
    const key = normalized as LaunchFocusKey;
    if (seen.has(key)) continue;
    seen.add(key);
    output.push(key);
  }

  return output.length ? output.slice(0, 4) : [...defaultLaunchFocusKeys];
}

function workspaceProfileFromMetadata(metadata: Record<string, unknown>) {
  const launchFocusKeys = sanitizeLaunchFocusKeys(
    metadata.launchFocusKeys ?? metadata.launchFocus ?? metadata.useCases ?? metadata.focusAreas
  );

  return {
    industry: txt(metadata.industry, 80) || null,
    teamSize: txt(metadata.teamSize, 40) || null,
    sharedBotMode: Boolean(metadata.sharedBotMode),
    channelMode: Boolean(metadata.sharedBotMode) ? "Shared Telegram inbox" : "Dedicated Telegram bot",
    launchFocusKeys,
    launchFocus: launchFocusKeys.map((key) => ({
      key,
      ...launchFocusCatalog[key]
    }))
  };
}

function buildWorkspaceInsights(input: {
  ownerConnected: boolean;
  totalLeads: number;
  openLeads: number;
  followupPending: number;
  profile: ReturnType<typeof workspaceProfileFromMetadata>;
  leads: WorkspaceLeadRow[];
}) {
  const languageTotals = input.leads.reduce(
    (acc, lead) => {
      if (lead.preferred_language === "hi") acc.hi += 1;
      else if (lead.preferred_language === "en") acc.en += 1;
      return acc;
    },
    { en: 0, hi: 0 }
  );

  const readinessScore = Math.min(
    100,
    (input.ownerConnected ? 45 : 18) +
      Math.min(18, input.profile.launchFocusKeys.length * 6) +
      (input.profile.industry ? 8 : 0) +
      (input.profile.teamSize ? 7 : 0) +
      (input.totalLeads > 0 ? 12 : 0) +
      (input.totalLeads > 3 ? 10 : 0)
  );

  const recommendedActions: string[] = [];
  if (!input.ownerConnected) {
    recommendedActions.push("Pair the owner chat so BharatClaw can send alerts, handoffs, and live status updates.");
  }
  if (!input.totalLeads) {
    recommendedActions.push("Share the lead link in bios, QR cards, pinned messages, and profile links to start filling the funnel.");
  }
  if (input.followupPending > 0) {
    recommendedActions.push("Review the pending follow-ups and jump into warm conversations before intent cools down.");
  }
  if (input.openLeads > 0) {
    recommendedActions.push("Use owner takeover for high-intent leads that need pricing, availability, or trust-building.");
  }
  if (!recommendedActions.length) {
    recommendedActions.push("Keep routing customer conversations into BharatClaw and watch the owner console for fresh intent.");
  }

  let launchStage = "Workspace created and ready for traffic";
  if (!input.ownerConnected) {
    launchStage = "Waiting for owner pairing";
  } else if (input.totalLeads === 0) {
    launchStage = "Live and waiting for the first lead";
  } else if (input.totalLeads < 10) {
    launchStage = "Collecting early conversion data";
  } else {
    launchStage = "Running an active intake engine";
  }

  let topLanguage = "No language signal yet";
  if (languageTotals.hi > languageTotals.en) topLanguage = "Hindi-heavy conversations";
  else if (languageTotals.en > languageTotals.hi) topLanguage = "English-heavy conversations";
  else if (languageTotals.en || languageTotals.hi) topLanguage = "Balanced English and Hindi mix";

  return {
    launchStage,
    readinessScore,
    nextAction: recommendedActions[0],
    recommendedActions,
    coverageLabel: input.ownerConnected
      ? "Automation and owner alerts are armed."
      : "Automation is prepared, but owner alerts are still waiting for pairing.",
    leadPulse: input.totalLeads
      ? `${input.totalLeads} lead${input.totalLeads === 1 ? "" : "s"} captured, ${input.openLeads} active right now.`
      : "No captured leads yet. Your first lead will appear here as soon as the workspace starts getting traffic.",
    followupLabel: input.followupPending
      ? `${input.followupPending} follow-up${input.followupPending === 1 ? "" : "s"} waiting in the queue.`
      : input.totalLeads
        ? "No follow-ups are pending right now."
        : "Follow-ups will appear automatically after leads start replying.",
    topLanguage
  };
}

function capitalize(input: string): string {
  return input ? input.charAt(0).toUpperCase() + input.slice(1) : "";
}

function aiAgentEnabled(env: Env, metadata: Record<string, unknown>): boolean {
  if (!isTrue(env.AI_AGENT_ENABLED, false)) return false;
  const mode = txt(metadata.aiAgentMode, 20).toLowerCase();
  if (mode === "off" || mode === "disabled") return false;
  return Boolean(txt(env.AI_AGENT_API_KEY, 240) && txt(env.AI_AGENT_MODEL, 120));
}

function aiAgentApiUrl(env: Env): string {
  return txt(env.AI_AGENT_API_URL, 320) || "https://api.openai.com/v1/chat/completions";
}

function aiBusinessContext(metadata: Record<string, unknown>) {
  const profile = workspaceProfileFromMetadata(metadata);
  const focusLabels = profile.launchFocus.map((item) => item.label).join(", ");

  return {
    businessName: txt(metadata.businessName, 120) || "the business",
    industry: profile.industry || "General business",
    teamSize: profile.teamSize || "Not specified",
    channelMode: profile.channelMode,
    focusLabels: focusLabels || "Lead capture, follow-up recovery, owner handoff",
    offerSummary: txt(metadata.offerSummary ?? metadata.businessDescription, 240) || "Capture inbound leads, qualify them, and guide them toward the owner.",
    faqSummary: txt(metadata.faqSummary, 280) || "If pricing, availability, or custom scope is uncertain, the owner should step in.",
    brandVoice: txt(metadata.brandVoice, 120) || "confident, warm, concise, helpful",
    ownerGoal: txt(metadata.ownerGoal, 180) || "convert serious inbound leads without sounding robotic"
  };
}

function normalizeAiCopilotOutput(input: unknown): AiCopilotOutput | null {
  if (!input || typeof input !== "object" || Array.isArray(input)) return null;
  const row = input as Record<string, unknown>;
  const leadTemperatureRaw = txt(row.leadTemperature, 20).toLowerCase();
  const leadTemperature: "hot" | "warm" | "cold" =
    leadTemperatureRaw === "hot" || leadTemperatureRaw === "cold" ? (leadTemperatureRaw as "hot" | "cold") : "warm";
  const reply = txt(row.reply, 420);
  const ownerSummary = txt(row.ownerSummary, 320);
  const intentLabel = txt(row.intentLabel, 80) || "General inquiry";
  const followupHint = txt(row.followupHint, 160) || "Keep the conversation moving toward a clear next step.";
  const ownerShouldTakeover =
    row.ownerShouldTakeover === true || txt(row.ownerShouldTakeover, 10).toLowerCase() === "true";

  if (!reply && !ownerSummary) return null;

  return {
    reply: reply || "Thanks for the message. I have shared it with the team and we will guide you on the next step shortly.",
    ownerSummary: ownerSummary || "AI copilot did not produce a detailed owner brief, but the lead looks active.",
    ownerShouldTakeover,
    leadTemperature,
    intentLabel,
    followupHint
  };
}

async function ensureWaitlistTable(env: Env): Promise<void> {
  await env.DB.prepare(
    `CREATE TABLE IF NOT EXISTS waitlist_signups (
      id TEXT PRIMARY KEY,
      name TEXT NOT NULL,
      business_name TEXT NOT NULL,
      email TEXT,
      created_at TEXT NOT NULL
    )`
  ).run();
}

function isStartCreatePath(path: string): boolean {
  return path === "/public/start" || path === "/public/free-trial";
}

function isStartStatusPath(path: string): boolean {
  return /^\/public\/(?:start|free-trial)\/[a-z0-9-]+\/status$/i.test(path);
}

function normalizeEmail(input: unknown): string {
  return txt(input, 200).toLowerCase();
}

async function ensureAuthTables(env: Env): Promise<void> {
  await env.DB.prepare(
    `CREATE TABLE IF NOT EXISTS app_users (
      id TEXT PRIMARY KEY,
      name TEXT NOT NULL,
      email TEXT NOT NULL UNIQUE,
      password_hash TEXT NOT NULL,
      created_at TEXT NOT NULL,
      updated_at TEXT NOT NULL
    )`
  ).run();
  await env.DB.prepare(
    `CREATE TABLE IF NOT EXISTS app_sessions (
      id TEXT PRIMARY KEY,
      user_id TEXT NOT NULL,
      token_hash TEXT NOT NULL,
      expires_at TEXT NOT NULL,
      created_at TEXT NOT NULL
    )`
  ).run();
}

async function hashWithSalt(raw: string, salt?: string): Promise<string> {
  const actualSalt = salt ?? crypto.randomUUID().replace(/-/g, "");
  return `${actualSalt}:${await sha256(`${actualSalt}:${raw}`)}`;
}

async function verifySaltedHash(raw: string, stored: string): Promise<boolean> {
  const [salt, hash] = stored.split(":");
  if (!salt || !hash) return false;
  const actual = await sha256(`${salt}:${raw}`);
  return actual === hash;
}

function generateSessionToken(): string {
  return `bcsess_${crypto.randomUUID().replace(/-/g, "")}`;
}

function readBearerToken(request: Request): string {
  const auth = txt(request.headers.get("authorization"), 500);
  const match = /^bearer\s+(.+)$/i.exec(auth);
  return match ? txt(match[1], 220) : "";
}

async function resolveAuthenticatedUser(
  env: Env,
  request: Request
): Promise<{ id: string; name: string; email: string } | null> {
  const token = readBearerToken(request);
  if (!token) return null;
  await ensureAuthTables(env);
  const tokenHash = await sha256(token);
  const row = await env.DB.prepare(
    `SELECT u.id,u.name,u.email,s.expires_at
     FROM app_sessions s
     JOIN app_users u ON u.id=s.user_id
     WHERE s.token_hash=?
     ORDER BY s.created_at DESC
     LIMIT 1`
  )
    .bind(tokenHash)
    .first<{ id: string; name: string; email: string; expires_at: string }>();
  if (!row) return null;
  if (new Date(row.expires_at).getTime() < Date.now()) return null;
  return {
    id: row.id,
    name: row.name,
    email: normalizeEmail(row.email)
  };
}

async function createSessionForUser(
  env: Env,
  userId: string
): Promise<{ token: string; expiresAt: string }> {
  await ensureAuthTables(env);
  const token = generateSessionToken();
  const expiresAt = new Date(Date.now() + 30 * 24 * 60 * 60_000).toISOString();
  await env.DB.prepare("INSERT INTO app_sessions (id,user_id,token_hash,expires_at,created_at) VALUES (?,?,?,?,?)")
    .bind(crypto.randomUUID(), userId, await sha256(token), expiresAt, now())
    .run();
  return { token, expiresAt };
}

function summarizeTenantAccess(
  env: Env,
  input: {
    tenantId: string;
    businessName: string;
    ownerName: string;
    ownerEmail: string | null;
    ownerPhone: string;
    metadata: Record<string, unknown>;
  }
) {
  const pairCode = txt(input.metadata.ownerPairCode, 20).toUpperCase();
  const ownerConsole = workspaceUrlFromMetadata(env, input.tenantId, input.metadata);
  const ownerConnected = !input.ownerPhone.startsWith("pending:");
  const profile = workspaceProfileFromMetadata(input.metadata);
  return {
    tenantId: input.tenantId,
    businessName: input.businessName,
    ownerName: input.ownerName,
    ownerEmail: input.ownerEmail,
    ownerConnected,
    ownerPairCode: pairCode,
    pairingCode: pairCode,
    ownerPairStatus: txt(input.metadata.ownerPairStatus, 30) || (ownerConnected ? "PAIRED" : "PENDING"),
    pairedAt: txt(input.metadata.ownerPairedAt, 80) || null,
    leadEntryUrl: leadEntryUrlFromMetadata(env, input.metadata),
    ownerConsoleUrl: ownerConsole,
    workspaceUrl: ownerConsole,
    ownerConnectBot: ownerConnectBotUsername(env),
    ownerConnectBotUrl: ownerConnectBotUrl(env),
    ownerConnectUrl: ownerConnected || !pairCode ? null : ownerConnectStartUrl(env, pairCode),
    profile
  };
}

async function listAuthenticatedTenants(
  env: Env,
  email: string
): Promise<
  Array<{
    tenantId: string;
    businessName: string;
    ownerName: string;
    ownerEmail: string | null;
    ownerConnected: boolean;
    ownerPairCode: string;
    pairingCode: string;
    ownerPairStatus: string;
    pairedAt: string | null;
    leadEntryUrl: string;
    ownerConsoleUrl: string | null;
    workspaceUrl: string | null;
    ownerConnectBot: string;
    ownerConnectBotUrl: string;
    ownerConnectUrl: string | null;
    profile: ReturnType<typeof workspaceProfileFromMetadata>;
  }>
> {
  const rows = await env.DB.prepare(
    `SELECT t.id AS tenant_id,t.name AS business_name,o.name AS owner_name,o.email,o.phone,c.metadata
     FROM owner_contacts o
     JOIN tenants t ON t.id=o.tenant_id
     JOIN tenant_configs c ON c.tenant_id=o.tenant_id
     WHERE o.is_primary=1 AND lower(o.email)=?
     ORDER BY t.created_at DESC`
  )
    .bind(normalizeEmail(email))
    .all<{
      tenant_id: string;
      business_name: string;
      owner_name: string;
      email: string | null;
      phone: string;
      metadata: string;
    }>();

  return (rows.results ?? []).map((row) =>
    summarizeTenantAccess(env, {
      tenantId: row.tenant_id,
      businessName: row.business_name,
      ownerName: row.owner_name,
      ownerEmail: row.email,
      ownerPhone: row.phone,
      metadata: parseJsonObject(row.metadata)
    })
  );
}

async function findExistingTenantForOwner(
  env: Env,
  businessName: string,
  ownerEmail: string
): Promise<{
  tenantId: string;
  businessName: string;
  ownerName: string;
  ownerEmail: string | null;
  ownerPhone: string;
  metadata: Record<string, unknown>;
} | null> {
  const row = await env.DB.prepare(
    `SELECT t.id AS tenant_id,t.name AS business_name,o.name AS owner_name,o.email,o.phone,c.metadata
     FROM owner_contacts o
     JOIN tenants t ON t.id=o.tenant_id
     JOIN tenant_configs c ON c.tenant_id=o.tenant_id
     WHERE o.is_primary=1 AND lower(o.email)=? AND lower(t.name)=?
     ORDER BY t.created_at DESC
     LIMIT 1`
  )
    .bind(normalizeEmail(ownerEmail), txt(businessName, 120).toLowerCase())
    .first<{
      tenant_id: string;
      business_name: string;
      owner_name: string;
      email: string | null;
      phone: string;
      metadata: string;
    }>();
  if (!row) return null;
  return {
    tenantId: row.tenant_id,
    businessName: row.business_name,
    ownerName: row.owner_name,
    ownerEmail: row.email,
    ownerPhone: row.phone,
    metadata: parseJsonObject(row.metadata)
  };
}

async function userOwnsTenant(env: Env, tenantId: string, email: string): Promise<boolean> {
  const row = await env.DB.prepare(
    "SELECT 1 AS ok FROM owner_contacts WHERE tenant_id=? AND is_primary=1 AND lower(email)=? LIMIT 1"
  )
    .bind(tenantId, normalizeEmail(email))
    .first<{ ok: number }>();
  return Boolean(row?.ok);
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

function appBaseUrl(env: Env): string {
  const source = txt(env.LANDING_CTA_URL ?? "https://bharatclawapp.vercel.app/get-started", 500);
  try {
    return new URL(source).origin;
  } catch {
    return "https://bharatclawapp.vercel.app";
  }
}

function workspaceUrl(env: Env, tenantId: string, token: string): string {
  const base = appBaseUrl(env);
  return `${base}/workspace?tenantId=${encodeURIComponent(tenantId)}&token=${encodeURIComponent(token)}`;
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
  const startMatch = /^\/start\s+pair_([a-z0-9-]{4,40})$/i.exec(input);
  if (startMatch) {
    const normalized = startMatch[1].toUpperCase().replace(/[^A-Z0-9]/g, "");
    const finalCode = normalized.startsWith("BC") ? normalized : `BC${normalized}`;
    return /^BC[A-Z0-9]{6,10}$/.test(finalCode) ? finalCode : null;
  }

  const pairMatch = /^\/pair\s+([a-z0-9-]{4,40})$/i.exec(input);
  if (pairMatch) {
    const normalized = pairMatch[1].toUpperCase().replace(/[^A-Z0-9]/g, "");
    const finalCode = normalized.startsWith("BC") ? normalized : `BC${normalized}`;
    return /^BC[A-Z0-9]{6,10}$/.test(finalCode) ? finalCode : null;
  }

  const compact = input.toUpperCase().replace(/[^A-Z0-9]/g, "");
  if (!/^[A-Z0-9\s-]+$/i.test(input)) return null;
  if (/^BC[A-Z0-9]{6,10}$/.test(compact)) return compact;
  return null;
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

function generateOwnerAccessToken(): string {
  return `wksp_${crypto.randomUUID().replace(/-/g, "")}`;
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

function leadEntryUrlFromMetadata(env: Env, metadata: Record<string, unknown>): string {
  const joinCode = txt(metadata.leadJoinCode, 20).toUpperCase();
  return joinCode ? `${ownerConnectBotUrl(env)}?start=lead_${joinCode}` : ownerConnectBotUrl(env);
}

function workspaceUrlFromMetadata(env: Env, tenantId: string, metadata: Record<string, unknown>): string | null {
  const accessToken = txt(metadata.ownerAccessToken, 120);
  if (!accessToken) return null;
  return workspaceUrl(env, tenantId, accessToken);
}

async function connectOwnerByPairCode(
  env: Env,
  pairCode: string,
  ownerChatId: string,
  ownerNameHint?: string
): Promise<{ status: "CONNECTED" | "ALREADY_CONNECTED" | "INVALID_CODE"; tenantId?: string }> {
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

  if (!matched) return { status: "INVALID_CODE" };
  if (!matched.phone.startsWith("pending:")) return { status: "ALREADY_CONNECTED", tenantId: matched.tenantId };

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

  await env.DB.prepare(
    "INSERT INTO audit_events (id,tenant_id,lead_id,actor,action,metadata,created_at) VALUES (?,?,?,?,?,?,?)"
  )
    .bind(
      crypto.randomUUID(),
      matched.tenantId,
      null,
      "OWNER",
      "OWNER_PAIRED",
      JSON.stringify({ ownerChatId, pairCode }),
      now()
    )
    .run();

  return { status: "CONNECTED", tenantId: matched.tenantId };
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

async function resolveOwnerWorkspaceByChat(
  env: Env,
  chatId: string
): Promise<{ tenantId: string; ownerName: string; metadata: Record<string, unknown>; businessName: string } | null> {
  const row = await env.DB.prepare(
    `SELECT o.tenant_id,o.name,c.metadata,t.name AS business_name
     FROM owner_contacts o
     JOIN tenant_configs c ON c.tenant_id=o.tenant_id
     JOIN tenants t ON t.id=o.tenant_id
     WHERE o.phone=?
     ORDER BY o.created_at DESC
     LIMIT 1`
  )
    .bind(chatId)
    .first<{ tenant_id: string; name: string; metadata: string; business_name: string }>();
  if (!row) return null;
  return {
    tenantId: row.tenant_id,
    ownerName: row.name,
    metadata: parseJsonObject(row.metadata),
    businessName: row.business_name
  };
}

async function loadWorkspaceAccess(
  env: Env,
  tenantId: string,
  token: string
): Promise<{ tenantName: string; metadata: Record<string, unknown> } | null> {
  const row = await env.DB.prepare(
    `SELECT t.name AS tenant_name,c.metadata
     FROM tenants t
     JOIN tenant_configs c ON c.tenant_id=t.id
     WHERE t.id=?`
  )
    .bind(tenantId)
    .first<{ tenant_name: string; metadata: string }>();
  if (!row) return null;
  const metadata = parseJsonObject(row.metadata);
  if (txt(metadata.ownerAccessToken, 120) !== txt(token, 120)) return null;
  return { tenantName: row.tenant_name, metadata };
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

async function sendTelegram(botToken: string, chatId: string, text: string, extra?: Record<string, unknown>) {
  const response = await fetch(`https://api.telegram.org/bot${botToken}/sendMessage`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ chat_id: chatId, text, ...(extra ?? {}) })
  });
  const payload = (await response.json()) as { ok: boolean; result?: { message_id?: number }; description?: string };
  if (!response.ok || !payload.ok) throw new Error(payload.description ?? "Telegram send failed");
  return String(payload.result?.message_id ?? "unknown");
}

async function loadLeadTranscript(env: Env, leadId: string, limit = 8): Promise<TranscriptRow[]> {
  const rows = await env.DB.prepare(
    `SELECT direction,body,created_at
     FROM messages
     WHERE lead_id=?
     ORDER BY created_at DESC
     LIMIT ?`
  )
    .bind(leadId, limit)
    .all<TranscriptRow>();

  return [...(rows.results ?? [])].reverse();
}

async function callAiCopilot(
  env: Env,
  input: {
    metadata: Record<string, unknown>;
    customerName: string | null;
    requirement: string | null;
    preferredLanguage: string | null;
    latestInboundText: string;
    transcript: TranscriptRow[];
    mode: "customer_reply" | "owner_draft" | "workspace_digest";
    businessName?: string;
    ownerName?: string;
    workspaceStats?: { totalLeads: number; openLeads: number; followupPending: number };
  }
): Promise<AiCopilotOutput | null> {
  if (!aiAgentEnabled(env, input.metadata)) return null;

  const apiKey = txt(env.AI_AGENT_API_KEY, 240);
  const model = txt(env.AI_AGENT_MODEL, 120);
  if (!apiKey || !model) return null;

  const business = aiBusinessContext(input.metadata);
  const transcript = input.transcript
    .map((row) => `${row.direction === "INBOUND" ? "Lead" : "BharatClaw"} (${row.created_at}): ${txt(row.body, 280)}`)
    .join("\n");

  const systemPrompt = [
    "You are BharatClaw Copilot, a high-conviction conversational sales assistant for Indian businesses.",
    "Your job is to help qualify leads, keep replies concise, sound human, and know when the owner should take over.",
    "Never mention being an AI, a model, or an assistant.",
    "Avoid hallucinating prices, promises, timings, or policies.",
    "If the business context is missing a fact, stay helpful and move the lead toward a clear next step.",
    "Return strict JSON with keys: reply, ownerSummary, ownerShouldTakeover, leadTemperature, intentLabel, followupHint.",
    "leadTemperature must be one of: hot, warm, cold.",
    "ownerShouldTakeover should be true only when the lead is high-intent, pricing-sensitive, urgent, or trust-sensitive.",
    txt(env.AI_AGENT_SYSTEM_PROMPT, 600)
  ]
    .filter(Boolean)
    .join("\n");

  const userPayload = {
    mode: input.mode,
    businessName: input.businessName || business.businessName,
    ownerName: input.ownerName || null,
    businessContext: business,
    lead: {
      customerName: input.customerName,
      requirement: input.requirement,
      preferredLanguage: input.preferredLanguage || "en",
      latestInboundText: input.latestInboundText
    },
    workspaceStats: input.workspaceStats ?? null,
    transcript
  };

  const response = await fetch(aiAgentApiUrl(env), {
    method: "POST",
    headers: {
      "content-type": "application/json",
      authorization: `Bearer ${apiKey}`
    },
    body: JSON.stringify({
      model,
      temperature: 0.35,
      response_format: { type: "json_object" },
      messages: [
        { role: "system", content: systemPrompt },
        { role: "user", content: JSON.stringify(userPayload) }
      ]
    })
  }).catch(() => null);

  if (!response || !response.ok) return null;

  const payload = (await response.json().catch(() => null)) as
    | { choices?: Array<{ message?: { content?: string | null } }> }
    | null;
  const rawContent = txt(payload?.choices?.[0]?.message?.content, 8000);
  if (!rawContent) return null;

  try {
    return normalizeAiCopilotOutput(JSON.parse(rawContent));
  } catch {
    return null;
  }
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

function telegramReplyOptionsForKey(replyKey: string): Record<string, unknown> | undefined {
  if (replyKey === "ASK_LANGUAGE" || replyKey === "ASK_LANGUAGE_RETRY") {
    return {
      reply_markup: {
        keyboard: [["English", "Hindi"]],
        resize_keyboard: true,
        one_time_keyboard: true,
        input_field_placeholder: "Choose language"
      }
    };
  }

  if (replyKey === "ASK_NAME" || replyKey === "ASK_REQUIREMENT" || replyKey === "REASK_REQUIREMENT" || replyKey === "CONFIRM_CAPTURE") {
    return {
      reply_markup: {
        remove_keyboard: true
      }
    };
  }

  return undefined;
}

function ownerActionMarkup(leadUrl: string, ownerWorkspaceUrl: string | null): Record<string, unknown> {
  const firstRow = [];
  if (ownerWorkspaceUrl) {
    firstRow.push({ text: "Open Owner Console", url: ownerWorkspaceUrl });
  }
  firstRow.push({ text: "Open Lead Link", url: leadUrl });
  return {
    reply_markup: {
      inline_keyboard: [firstRow]
    }
  };
}

function formatLeadStatus(status: string): string {
  if (status === "FOLLOWUP_PENDING") return "Follow-up pending";
  if (status === "OWNER_TAKEOVER") return "Owner takeover";
  if (status === "IN_PROGRESS") return "In progress";
  if (status === "CAPTURED") return "Captured";
  if (status === "CLOSED") return "Closed";
  return "New";
}

function buildOwnerWelcomeMessage(input: {
  businessName: string;
  leadUrl: string;
  ownerWorkspaceUrl: string | null;
}): string {
  return [
    `BharatClaw is live for ${input.businessName}.`,
    "",
    "Share this lead link with customers:",
    input.leadUrl,
    "",
    input.ownerWorkspaceUrl ? `Owner console:\n${input.ownerWorkspaceUrl}\n` : "",
    "Owner commands:",
    "/status  system health",
    "/leads  recent leads",
    "/copilot  AI workspace advice",
    "/link  resend lead link",
    "/workspace  open owner console",
    "#takeover <chat_id>  pause bot for a lead",
    "#resume <chat_id>  resume bot replies",
    "#reply <chat_id> <message>  send a manual reply",
    "#ai <chat_id>  get an AI reply draft"
  ]
    .filter(Boolean)
    .join("\n");
}

function buildLeadDigest(
  leads: Array<{
    customer_name: string | null;
    customer_phone: string;
    requirement: string | null;
    status: string;
    preferred_language: string | null;
    updated_at: string;
  }>
): string {
  if (!leads.length) {
    return "No leads captured yet. Share your lead link and the first lead will show up here.";
  }

  return leads
    .map((lead, index) => {
      const name = txt(lead.customer_name, 80) || "Unknown";
      const need = txt(lead.requirement, 80) || "Requirement pending";
      const language = lead.preferred_language === "hi" ? "Hindi" : "English";
      return `${index + 1}. ${name} | ${need}\nChat: ${lead.customer_phone}\nStatus: ${formatLeadStatus(lead.status)} | Language: ${language} | Updated: ${lead.updated_at}`;
    })
    .join("\n\n")
    .slice(0, 3400);
}

function buildAiLeadBrief(leadPhone: string, ai: AiCopilotOutput): string {
  return [
    `AI brief for ${leadPhone}`,
    `Intent: ${ai.intentLabel}`,
    `Temperature: ${capitalize(ai.leadTemperature)}`,
    `Owner takeover: ${ai.ownerShouldTakeover ? "Recommended" : "Not required yet"}`,
    `Why: ${ai.ownerSummary}`,
    `Next move: ${ai.followupHint}`,
    "",
    "Suggested reply:",
    ai.reply
  ].join("\n");
}

function buildOwnerLeadSummary(input: {
  businessName: string;
  customerName: string;
  customerPhone: string;
  requirement: string;
  preferredLanguage: string | null;
  ai?: AiCopilotOutput | null;
}) {
  const lines = [
    `New lead for ${input.businessName || "your business"}.`,
    `Name: ${input.customerName || "Not provided"}`,
    `Chat: ${input.customerPhone}`,
    `Requirement: ${input.requirement || "Not provided"}`,
    `Language: ${input.preferredLanguage === "hi" ? "Hindi" : "English"}`,
    `Time: ${now()}`
  ];

  if (input.ai) {
    lines.push(`Intent: ${input.ai.intentLabel}`);
    lines.push(`Temperature: ${capitalize(input.ai.leadTemperature)}`);
    lines.push(`AI brief: ${input.ai.ownerSummary}`);
    if (input.ai.ownerShouldTakeover) {
      lines.push("AI signal: Owner takeover is recommended.");
    }
  }

  lines.push("", "Reply here with:", "/leads to see recent leads", `#takeover ${input.customerPhone} to pause the bot for this lead`);
  return lines.join("\n");
}

async function notifyOwners(
  env: Env,
  input: {
    tenantId: string;
    leadId: string;
    botToken: string;
    metadata: Record<string, unknown>;
    body: string;
  }
): Promise<void> {
  const owners = await env.DB.prepare("SELECT * FROM owner_contacts WHERE tenant_id=? AND is_primary=1 ORDER BY created_at")
    .bind(input.tenantId)
    .all<{ phone: string; email?: string | null }>();
  const leadUrl = leadEntryUrlFromMetadata(env, input.metadata);
  const ownerWorkspaceUrl = workspaceUrlFromMetadata(env, input.tenantId, input.metadata);

  for (const owner of owners.results ?? []) {
    try {
      const notificationId = crypto.randomUUID();
      await sendTelegram(input.botToken, owner.phone, input.body, ownerActionMarkup(leadUrl, ownerWorkspaceUrl));
      await env.DB.prepare(
        "INSERT INTO notifications (id,tenant_id,lead_id,channel,status,payload,error,created_at) VALUES (?,?,?,?,?,?,?,?)"
      )
        .bind(
          notificationId,
          input.tenantId,
          input.leadId,
          "TELEGRAM",
          "SENT",
          JSON.stringify({ ownerPhone: owner.phone, body: input.body }),
          null,
          now()
        )
        .run();
    } catch {
      await env.DB.prepare(
        "INSERT INTO notifications (id,tenant_id,lead_id,channel,status,payload,error,created_at) VALUES (?,?,?,?,?,?,?,?)"
      )
        .bind(
          crypto.randomUUID(),
          input.tenantId,
          input.leadId,
          "TELEGRAM",
          "FAILED",
          JSON.stringify({ ownerPhone: owner.phone, body: input.body }),
          "Telegram send failed",
          now()
        )
        .run();
    }
  }
}

async function handleOwnerMessage(
  env: Env,
  input: {
    tenantId: string;
    ownerChatId: string;
    ownerName: string;
    businessName: string;
    metadata: Record<string, unknown>;
    botToken: string;
    text: string;
  }
): Promise<void> {
  const normalized = txt(input.text, 240).toLowerCase();
  const leadUrl = leadEntryUrlFromMetadata(env, input.metadata);
  const ownerWorkspaceUrl = workspaceUrlFromMetadata(env, input.tenantId, input.metadata);

  if (/^#takeover\s+\d{5,20}$/i.test(input.text)) {
    const chatId = input.text.trim().split(/\s+/)[1] ?? "";
    const lead = await env.DB.prepare("SELECT id FROM leads WHERE tenant_id=? AND customer_phone=?")
      .bind(input.tenantId, chatId)
      .first<{ id: string }>();
    if (!lead) {
      await sendTelegram(input.botToken, input.ownerChatId, "Lead not found for that chat id.");
      return;
    }
    const cfg = await env.DB.prepare("SELECT takeover_cooldown_minutes FROM tenant_configs WHERE tenant_id=?")
      .bind(input.tenantId)
      .first<{ takeover_cooldown_minutes: number }>();
    const pause = plusMins(Math.max(5, Math.min(1440, Number(cfg?.takeover_cooldown_minutes ?? 180))));
    await env.DB.prepare("UPDATE leads SET status='OWNER_TAKEOVER', bot_paused_until=?, updated_at=? WHERE id=?")
      .bind(pause, now(), lead.id)
      .run();
    await env.DB.prepare("UPDATE conversations SET state='OWNER_TAKEOVER', updated_at=? WHERE lead_id=?")
      .bind(now(), lead.id)
      .run();
    await env.DB.prepare(
      "INSERT INTO audit_events (id,tenant_id,lead_id,actor,action,metadata,created_at) VALUES (?,?,?,?,?,?,?)"
    )
      .bind(
        crypto.randomUUID(),
        input.tenantId,
        lead.id,
        "OWNER",
        "OWNER_TAKEOVER_ENABLED",
        JSON.stringify({ ownerChatId: input.ownerChatId, targetChatId: chatId, pausedUntil: pause }),
        now()
      )
      .run();
    await sendTelegram(input.botToken, input.ownerChatId, `Bot paused for ${chatId} until ${pause}.`);
    return;
  }

  if (/^#resume\s+\d{5,20}$/i.test(input.text)) {
    const chatId = input.text.trim().split(/\s+/)[1] ?? "";
    const lead = await env.DB.prepare("SELECT id FROM leads WHERE tenant_id=? AND customer_phone=?")
      .bind(input.tenantId, chatId)
      .first<{ id: string }>();
    if (!lead) {
      await sendTelegram(input.botToken, input.ownerChatId, "Lead not found for that chat id.");
      return;
    }
    await env.DB.prepare("UPDATE leads SET status='FOLLOWUP_PENDING', bot_paused_until=NULL, updated_at=? WHERE id=?")
      .bind(now(), lead.id)
      .run();
    await env.DB.prepare("UPDATE conversations SET state='FOLLOWUP_PENDING', updated_at=? WHERE lead_id=?")
      .bind(now(), lead.id)
      .run();
    await env.DB.prepare(
      "INSERT INTO audit_events (id,tenant_id,lead_id,actor,action,metadata,created_at) VALUES (?,?,?,?,?,?,?)"
    )
      .bind(
        crypto.randomUUID(),
        input.tenantId,
        lead.id,
        "OWNER",
        "OWNER_TAKEOVER_DISABLED",
        JSON.stringify({ ownerChatId: input.ownerChatId, targetChatId: chatId }),
        now()
      )
      .run();
    await sendTelegram(input.botToken, input.ownerChatId, `Bot resumed for ${chatId}.`);
    return;
  }

  if (normalized === "/status" || normalized === "status") {
    const total = await env.DB.prepare("SELECT COUNT(*) AS count FROM leads WHERE tenant_id=?")
      .bind(input.tenantId)
      .first<{ count: number }>();
    const open = await env.DB.prepare("SELECT COUNT(*) AS count FROM leads WHERE tenant_id=? AND status!='CLOSED'")
      .bind(input.tenantId)
      .first<{ count: number }>();
    const followup = await env.DB.prepare("SELECT COUNT(*) AS count FROM leads WHERE tenant_id=? AND status='FOLLOWUP_PENDING'")
      .bind(input.tenantId)
      .first<{ count: number }>();
    const pairedAt = txt(input.metadata.ownerPairedAt, 80) || "Not tracked";
    const body = [
      `${input.businessName} owner console`,
      `Owner: ${input.ownerName}`,
      `Owner paired: ${pairedAt}`,
      `Total leads: ${Number(total?.count ?? 0)}`,
      `Open leads: ${Number(open?.count ?? 0)}`,
      `Waiting follow-up: ${Number(followup?.count ?? 0)}`
    ].join("\n");
    await sendTelegram(input.botToken, input.ownerChatId, body, ownerActionMarkup(leadUrl, ownerWorkspaceUrl));
    return;
  }

  if (normalized === "/leads" || normalized === "leads") {
    const leads = await env.DB.prepare(
      `SELECT customer_name,customer_phone,requirement,status,preferred_language,updated_at
       FROM leads
       WHERE tenant_id=?
       ORDER BY updated_at DESC
       LIMIT 8`
    )
      .bind(input.tenantId)
      .all<{
        customer_name: string | null;
        customer_phone: string;
        requirement: string | null;
        status: string;
        preferred_language: string | null;
        updated_at: string;
      }>();
    await sendTelegram(
      input.botToken,
      input.ownerChatId,
      `Recent leads for ${input.businessName}\n\n${buildLeadDigest(leads.results ?? [])}`,
      ownerActionMarkup(leadUrl, ownerWorkspaceUrl)
    );
    return;
  }

  if (normalized === "/copilot" || normalized === "copilot") {
    const total = await env.DB.prepare("SELECT COUNT(*) AS count FROM leads WHERE tenant_id=?")
      .bind(input.tenantId)
      .first<{ count: number }>();
    const open = await env.DB.prepare("SELECT COUNT(*) AS count FROM leads WHERE tenant_id=? AND status!='CLOSED'")
      .bind(input.tenantId)
      .first<{ count: number }>();
    const followup = await env.DB.prepare("SELECT COUNT(*) AS count FROM leads WHERE tenant_id=? AND status='FOLLOWUP_PENDING'")
      .bind(input.tenantId)
      .first<{ count: number }>();
    const recentLeads = await env.DB.prepare(
      `SELECT customer_name,customer_phone,requirement,status,preferred_language,updated_at
       FROM leads
       WHERE tenant_id=?
       ORDER BY updated_at DESC
       LIMIT 5`
    )
      .bind(input.tenantId)
      .all<{
        customer_name: string | null;
        customer_phone: string;
        requirement: string | null;
        status: string;
        preferred_language: string | null;
        updated_at: string;
      }>();
    const ai = await callAiCopilot(env, {
      metadata: input.metadata,
      customerName: null,
      requirement: null,
      preferredLanguage: null,
      latestInboundText: "Owner requested workspace copilot advice.",
      transcript: (recentLeads.results ?? []).map((lead) => ({
        direction: "INBOUND",
        body: `${txt(lead.customer_name, 80) || "Unknown"} | ${txt(lead.requirement, 120) || "Requirement pending"} | ${formatLeadStatus(lead.status)} | ${lead.preferred_language === "hi" ? "Hindi" : "English"}`,
        created_at: lead.updated_at
      })),
      mode: "workspace_digest",
      businessName: input.businessName,
      ownerName: input.ownerName,
      workspaceStats: {
        totalLeads: Number(total?.count ?? 0),
        openLeads: Number(open?.count ?? 0),
        followupPending: Number(followup?.count ?? 0)
      }
    });

    const body = ai
      ? [
          `${input.businessName} copilot`,
          `Intent mix: ${ai.intentLabel}`,
          `Lead temperature: ${capitalize(ai.leadTemperature)}`,
          `Takeover signal: ${ai.ownerShouldTakeover ? "Watch high-intent leads closely" : "Automation can keep handling routine replies"}`,
          `Copilot note: ${ai.ownerSummary}`,
          `Recommended move: ${ai.followupHint}`
        ].join("\n")
      : [
          `${input.businessName} copilot`,
          `Total leads: ${Number(total?.count ?? 0)}`,
          `Open leads: ${Number(open?.count ?? 0)}`,
          `Waiting follow-up: ${Number(followup?.count ?? 0)}`,
          "Enable AI_AGENT_* env vars to unlock AI reply drafts and workspace copilot advice."
        ].join("\n");

    await sendTelegram(input.botToken, input.ownerChatId, body, ownerActionMarkup(leadUrl, ownerWorkspaceUrl));
    return;
  }

  const replyMatch = /^#reply\s+(\d{5,20})\s+([\s\S]{2,320})$/i.exec(input.text.trim());
  if (replyMatch) {
    const [, chatId, rawReply] = replyMatch;
    const lead = await env.DB.prepare("SELECT id FROM leads WHERE tenant_id=? AND customer_phone=?")
      .bind(input.tenantId, chatId)
      .first<{ id: string }>();
    if (!lead) {
      await sendTelegram(input.botToken, input.ownerChatId, "Lead not found for that chat id.");
      return;
    }

    const message = txt(rawReply, 320);
    const externalId = await sendTelegram(input.botToken, chatId, message);
    await env.DB.prepare(
      "INSERT OR IGNORE INTO messages (id,tenant_id,lead_id,direction,message_type,body,external_message_id,idempotency_key,created_at) VALUES (?,?,?,'OUTBOUND','TEXT',?,?,?,?)"
    )
      .bind(crypto.randomUUID(), input.tenantId, lead.id, message, externalId, `owner-reply:${chatId}:${Date.now()}`, now())
      .run();
    await env.DB.prepare("UPDATE leads SET last_outbound_at=?,updated_at=? WHERE id=?")
      .bind(now(), now(), lead.id)
      .run();
    await env.DB.prepare(
      "INSERT INTO audit_events (id,tenant_id,lead_id,actor,action,metadata,created_at) VALUES (?,?,?,?,?,?,?)"
    )
      .bind(
        crypto.randomUUID(),
        input.tenantId,
        lead.id,
        "OWNER",
        "OWNER_SENT_MANUAL_REPLY",
        JSON.stringify({ ownerChatId: input.ownerChatId, targetChatId: chatId, body: message }),
        now()
      )
      .run();
    await sendTelegram(input.botToken, input.ownerChatId, `Manual reply sent to ${chatId}.`, ownerActionMarkup(leadUrl, ownerWorkspaceUrl));
    return;
  }

  if (/^#ai\s+\d{5,20}$/i.test(input.text)) {
    const chatId = input.text.trim().split(/\s+/)[1] ?? "";
    const lead = await env.DB.prepare(
      "SELECT id,customer_name,requirement,preferred_language FROM leads WHERE tenant_id=? AND customer_phone=?"
    )
      .bind(input.tenantId, chatId)
      .first<{ id: string; customer_name: string | null; requirement: string | null; preferred_language: string | null }>();
    if (!lead) {
      await sendTelegram(input.botToken, input.ownerChatId, "Lead not found for that chat id.");
      return;
    }

    const transcript = await loadLeadTranscript(env, lead.id);
    const ai = await callAiCopilot(env, {
      metadata: input.metadata,
      customerName: lead.customer_name,
      requirement: lead.requirement,
      preferredLanguage: lead.preferred_language,
      latestInboundText: transcript.length ? txt(transcript[transcript.length - 1]?.body, 220) : "",
      transcript,
      mode: "owner_draft",
      businessName: input.businessName,
      ownerName: input.ownerName
    });
    if (!ai) {
      await sendTelegram(
        input.botToken,
        input.ownerChatId,
        "AI draft is not available yet. Configure AI_AGENT_ENABLED, AI_AGENT_API_KEY, AI_AGENT_MODEL, and optionally AI_AGENT_API_URL.",
        ownerActionMarkup(leadUrl, ownerWorkspaceUrl)
      );
      return;
    }

    await sendTelegram(input.botToken, input.ownerChatId, buildAiLeadBrief(chatId, ai), ownerActionMarkup(leadUrl, ownerWorkspaceUrl));
    return;
  }

  if (normalized === "/link" || normalized === "link" || normalized === "/leadlink") {
    await sendTelegram(
      input.botToken,
      input.ownerChatId,
      `Share this lead link with customers:\n${leadUrl}`,
      ownerActionMarkup(leadUrl, ownerWorkspaceUrl)
    );
    return;
  }

  if (normalized === "/workspace" || normalized === "workspace") {
    await sendTelegram(
      input.botToken,
      input.ownerChatId,
      ownerWorkspaceUrl ? `Open your BharatClaw owner console:\n${ownerWorkspaceUrl}` : "Owner console link is not ready yet.",
      ownerActionMarkup(leadUrl, ownerWorkspaceUrl)
    );
    return;
  }

  await sendTelegram(
    input.botToken,
    input.ownerChatId,
    buildOwnerWelcomeMessage({ businessName: input.businessName, leadUrl, ownerWorkspaceUrl }),
    ownerActionMarkup(leadUrl, ownerWorkspaceUrl)
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
    industry?: string | null;
    teamSize?: string | null;
    launchFocusKeys?: string[];
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
  const ownerAccessToken = generateOwnerAccessToken();
  const ownerPhone = txt(input.ownerChatId ?? "", 64) || `pending:${tenantId}`;
  const launchFocusKeys = sanitizeLaunchFocusKeys(input.launchFocusKeys);
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
              ownerAccessToken,
              ownerPairStatus: ownerPhone.startsWith("pending:") ? "PENDING" : "PAIRED",
              sharedBotMode: Boolean(input.sharedBotMode),
              launchFocusKeys
            };
            if (input.industry) {
              metadata.industry = input.industry;
            }
            if (input.teamSize) {
              metadata.teamSize = input.teamSize;
            }
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
    await env.DB.prepare(
      "INSERT INTO audit_events (id,tenant_id,lead_id,actor,action,metadata,created_at) VALUES (?,?,?,?,?,?,?)"
    )
      .bind(
        crypto.randomUUID(),
        tenantId,
        null,
        "SYSTEM",
        "WORKSPACE_CREATED",
        JSON.stringify({ sharedBotMode: Boolean(input.sharedBotMode), leadJoinCode, launchFocusKeys }),
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
      ownerAccessToken,
      sharedBotMode: Boolean(input.sharedBotMode)
    };
  } catch (error) {
    await env.DB.prepare("DELETE FROM tenants WHERE id=?").bind(tenantId).run().catch(() => undefined);
    throw error;
  }
}

async function disconnectOwnerPairing(env: Env, tenantId: string) {
  const owner = await env.DB.prepare(
    "SELECT id,name,email FROM owner_contacts WHERE tenant_id=? AND is_primary=1 ORDER BY created_at LIMIT 1"
  )
    .bind(tenantId)
    .first<{ id: string; name: string; email: string | null }>();
  const config = await env.DB.prepare("SELECT metadata FROM tenant_configs WHERE tenant_id=?")
    .bind(tenantId)
    .first<{ metadata: string }>();
  const tenant = await env.DB.prepare("SELECT name FROM tenants WHERE id=?")
    .bind(tenantId)
    .first<{ name: string }>();
  if (!owner || !config || !tenant) {
    throw new Error("Tenant not found");
  }

  const metadata = parseJsonObject(config.metadata);
  const nextPairCode = await generateUniqueOwnerPairCode(env);
  const nextMetadata = {
    ...metadata,
    ownerPairCode: nextPairCode,
    ownerPairStatus: "PENDING",
    ownerPairedAt: null
  };

  await env.DB.prepare("UPDATE owner_contacts SET phone=? WHERE id=?")
    .bind(`pending:${tenantId}`, owner.id)
    .run();
  await env.DB.prepare("UPDATE tenant_configs SET metadata=?, updated_at=? WHERE tenant_id=?")
    .bind(JSON.stringify(nextMetadata), now(), tenantId)
    .run();
  await env.DB.prepare(
    "INSERT INTO audit_events (id,tenant_id,lead_id,actor,action,metadata,created_at) VALUES (?,?,?,?,?,?,?)"
  )
    .bind(
      crypto.randomUUID(),
      tenantId,
      null,
      "OWNER",
      "OWNER_DISCONNECTED",
      JSON.stringify({ nextPairCode }),
      now()
    )
    .run();

  return summarizeTenantAccess(env, {
    tenantId,
    businessName: tenant.name,
    ownerName: owner.name,
    ownerEmail: owner.email,
    ownerPhone: `pending:${tenantId}`,
    metadata: nextMetadata
  });
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

  const aiEligible = Boolean(config) && (transition.replyKey === "ACK" || transition.shouldNotifyOwner);
  const transcript = aiEligible ? await loadLeadTranscript(env, lead.id) : [];
  const aiCopilot = aiEligible
    ? await callAiCopilot(env, {
        metadata,
        customerName: nextName,
        requirement: nextRequirement,
        preferredLanguage: nextLanguage,
        latestInboundText: txt(msg.text, 280),
        transcript,
        mode: transition.replyKey === "ACK" ? "customer_reply" : "owner_draft",
        businessName: txt(metadata.businessName, 120) || tenantId
      })
    : null;

  if (transition.shouldReply && config) {
    const defaultReply = buildAutoReply({
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
    const reply = transition.replyKey === "ACK" && aiCopilot?.reply ? txt(aiCopilot.reply, 420) : defaultReply;
    const ext = await sendTelegram(botToken, lead.customer_phone, reply, telegramReplyOptionsForKey(transition.replyKey));
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
    await env.DB.prepare(
      "INSERT INTO audit_events (id,tenant_id,lead_id,actor,action,metadata,created_at) VALUES (?,?,?,?,?,?,?)"
    )
      .bind(
        crypto.randomUUID(),
        tenantId,
        lead.id,
        "SYSTEM",
        "LEAD_CAPTURED",
        JSON.stringify({
          customerName: nextName,
          requirement: nextRequirement,
          language: nextLanguage ?? "en"
        }),
        now()
      )
      .run();
    const owners = await env.DB.prepare("SELECT * FROM owner_contacts WHERE tenant_id=? AND is_primary=1 ORDER BY created_at")
      .bind(tenantId)
      .all<{ phone: string; email?: string | null }>();
    const updated = await env.DB.prepare("SELECT * FROM leads WHERE id=?")
      .bind(lead.id)
      .first<{ customer_name: string | null; customer_phone: string; requirement: string | null; preferred_language: "en" | "hi" | null }>();
    const metadataBusinessName = txt(metadata.businessName, 120);
    const summary = buildOwnerLeadSummary({
      businessName: metadataBusinessName || "your business",
      customerName: updated?.customer_name ?? "Not provided",
      customerPhone: updated?.customer_phone ?? lead.customer_phone,
      requirement: updated?.requirement ?? "Not provided",
      preferredLanguage: updated?.preferred_language ?? "en",
      ai: aiCopilot
    });
    await notifyOwners(env, {
      tenantId,
      leadId: lead.id,
      botToken,
      metadata,
      body: summary
    });
  } else if (transition.replyKey === "ACK" && aiCopilot?.ownerShouldTakeover) {
    await env.DB.prepare(
      "INSERT INTO audit_events (id,tenant_id,lead_id,actor,action,metadata,created_at) VALUES (?,?,?,?,?,?,?)"
    )
      .bind(
        crypto.randomUUID(),
        tenantId,
        lead.id,
        "SYSTEM",
        "AI_TAKEOVER_RECOMMENDED",
        JSON.stringify({
          customerName: nextName,
          requirement: nextRequirement,
          intentLabel: aiCopilot.intentLabel,
          leadTemperature: aiCopilot.leadTemperature
        }),
        now()
      )
      .run();
    await notifyOwners(env, {
      tenantId,
      leadId: lead.id,
      botToken,
      metadata,
      body: [
        `Hot lead activity for ${txt(metadata.businessName, 120) || "your business"}.`,
        `Chat: ${lead.customer_phone}`,
        `Intent: ${aiCopilot.intentLabel}`,
        `Temperature: ${capitalize(aiCopilot.leadTemperature)}`,
        `AI brief: ${aiCopilot.ownerSummary}`,
        "",
        `Suggested move: #takeover ${lead.customer_phone}`
      ].join("\n")
    });
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

      if (request.method === "OPTIONS" && path.startsWith("/public/")) {
        return new Response(null, { status: 204, headers: publicCorsHeaders });
      }

      if (request.method === "POST" && path === "/public/auth/signup") {
        const body = (await request.json().catch(() => null)) as Record<string, unknown> | null;
        if (!body || typeof body !== "object" || Array.isArray(body)) return jPublic({ error: "Invalid JSON" }, 400);
        const name = txt(body.name, 120);
        const email = normalizeEmail(body.email);
        const password = txt(body.password, 200);
        if (!name || !email || !password) return jPublic({ error: "Missing required fields" }, 400);
        if (password.length < 8) return jPublic({ error: "Password must be at least 8 characters" }, 400);

        await ensureAuthTables(env);
        const existing = await env.DB.prepare("SELECT id FROM app_users WHERE email=?")
          .bind(email)
          .first<{ id: string }>();
        if (existing) return jPublic({ error: "Account already exists" }, 409);

        const userId = crypto.randomUUID();
        await env.DB.prepare(
          "INSERT INTO app_users (id,name,email,password_hash,created_at,updated_at) VALUES (?,?,?,?,?,?)"
        )
          .bind(userId, name, email, await hashWithSalt(password), now(), now())
          .run();
        const session = await createSessionForUser(env, userId);
        return jPublic(
          {
            ok: true,
            token: session.token,
            expiresAt: session.expiresAt,
            user: { id: userId, name, email },
            tenants: await listAuthenticatedTenants(env, email)
          },
          201
        );
      }

      if (request.method === "POST" && path === "/public/auth/login") {
        const body = (await request.json().catch(() => null)) as Record<string, unknown> | null;
        if (!body || typeof body !== "object" || Array.isArray(body)) return jPublic({ error: "Invalid JSON" }, 400);
        const email = normalizeEmail(body.email);
        const password = txt(body.password, 200);
        if (!email || !password) return jPublic({ error: "Missing required fields" }, 400);

        await ensureAuthTables(env);
        const user = await env.DB.prepare("SELECT id,name,email,password_hash FROM app_users WHERE email=?")
          .bind(email)
          .first<{ id: string; name: string; email: string; password_hash: string }>();
        if (!user || !(await verifySaltedHash(password, user.password_hash))) {
          return jPublic({ error: "Invalid email or password" }, 401);
        }

        const session = await createSessionForUser(env, user.id);
        return jPublic({
          ok: true,
          token: session.token,
          expiresAt: session.expiresAt,
          user: { id: user.id, name: user.name, email: normalizeEmail(user.email) },
          tenants: await listAuthenticatedTenants(env, email)
        });
      }

      if (request.method === "POST" && path === "/public/auth/logout") {
        const token = readBearerToken(request);
        if (token) {
          await ensureAuthTables(env);
          await env.DB.prepare("DELETE FROM app_sessions WHERE token_hash=?")
            .bind(await sha256(token))
            .run()
            .catch(() => undefined);
        }
        return jPublic({ ok: true });
      }

      if (request.method === "GET" && path === "/public/auth/me") {
        const user = await resolveAuthenticatedUser(env, request);
        if (!user) return jPublic({ error: "Unauthorized" }, 401);
        return jPublic({
          ok: true,
          user,
          tenants: await listAuthenticatedTenants(env, user.email)
        });
      }

      if (request.method === "GET" && path === "/public/auth/tenants") {
        const user = await resolveAuthenticatedUser(env, request);
        if (!user) return jPublic({ error: "Unauthorized" }, 401);
        return jPublic({
          ok: true,
          user,
          tenants: await listAuthenticatedTenants(env, user.email)
        });
      }

      if (request.method === "POST" && /^\/public\/auth\/tenants\/[a-z0-9-]+\/disconnect-owner$/i.test(path)) {
        const user = await resolveAuthenticatedUser(env, request);
        if (!user) return jPublic({ error: "Unauthorized" }, 401);
        const tenantId = path.split("/")[4] ?? "";
        if (!tenantId) return jPublic({ error: "Missing tenant id" }, 400);
        if (!(await userOwnsTenant(env, tenantId, user.email))) return jPublic({ error: "Forbidden" }, 403);
        const disconnected = await disconnectOwnerPairing(env, tenantId);
        return jPublic({ ok: true, tenant: disconnected });
      }

      if (request.method === "GET" && isStartStatusPath(path)) {
        const tenantId = path.split("/")[3] ?? "";
        if (!tenantId) return jPublic({ error: "Missing tenant id" }, 400);
        const owner = await env.DB.prepare(
          "SELECT phone FROM owner_contacts WHERE tenant_id=? AND is_primary=1 ORDER BY created_at LIMIT 1"
        )
          .bind(tenantId)
          .first<{ phone: string }>();
        if (!owner) return jPublic({ error: "Tenant not found" }, 404);
        const config = await env.DB.prepare("SELECT metadata FROM tenant_configs WHERE tenant_id=?")
          .bind(tenantId)
          .first<{ metadata: string }>();
        const metadata = parseJsonObject(config?.metadata);
        return jPublic({
          ok: true,
          tenantId,
          ownerConnected: !owner.phone.startsWith("pending:"),
          ownerPairStatus: txt(metadata.ownerPairStatus, 30) || (!owner.phone.startsWith("pending:") ? "PAIRED" : "PENDING"),
          ownerPairCode: txt(metadata.ownerPairCode, 20).toUpperCase(),
          pairingCode: txt(metadata.ownerPairCode, 20).toUpperCase(),
          pairedAt: txt(metadata.ownerPairedAt, 80) || null,
          leadEntryUrl: leadEntryUrlFromMetadata(env, metadata),
          workspaceUrl: workspaceUrlFromMetadata(env, tenantId, metadata),
          ownerConsoleUrl: workspaceUrlFromMetadata(env, tenantId, metadata)
        });
      }

    if (request.method === "GET" && /^\/public\/workspaces\/[a-z0-9-]+$/i.test(path)) {
      const tenantId = path.split("/")[3] ?? "";
      const token = txt(url.searchParams.get("token"), 160);
      if (!tenantId || !token) return jPublic({ error: "Unauthorized" }, 401);
      const access = await loadWorkspaceAccess(env, tenantId, token);
      if (!access) return jPublic({ error: "Unauthorized" }, 401);
      const profile = workspaceProfileFromMetadata(access.metadata);

      const owner = await env.DB.prepare(
        "SELECT name,phone,email FROM owner_contacts WHERE tenant_id=? AND is_primary=1 ORDER BY created_at LIMIT 1"
      )
        .bind(tenantId)
        .first<{ name: string; phone: string; email: string | null }>();
      const total = await env.DB.prepare("SELECT COUNT(*) AS count FROM leads WHERE tenant_id=?")
        .bind(tenantId)
        .first<{ count: number | string }>();
      const open = await env.DB.prepare("SELECT COUNT(*) AS count FROM leads WHERE tenant_id=? AND status!='CLOSED'")
        .bind(tenantId)
        .first<{ count: number | string }>();
      const followup = await env.DB.prepare("SELECT COUNT(*) AS count FROM leads WHERE tenant_id=? AND status='FOLLOWUP_PENDING'")
        .bind(tenantId)
        .first<{ count: number | string }>();
      const recentLeads = await env.DB.prepare(
        `SELECT customer_name,customer_phone,requirement,status,preferred_language,last_inbound_at,last_outbound_at,updated_at,created_at
         FROM leads
         WHERE tenant_id=?
         ORDER BY updated_at DESC
         LIMIT 20`
      )
        .bind(tenantId)
        .all<WorkspaceLeadRow>();
      const leadRows = recentLeads.results ?? [];
      const totalLeads = Number(total?.count ?? 0);
      const openLeads = Number(open?.count ?? 0);
      const followupPending = Number(followup?.count ?? 0);
      const ownerConnected = Boolean(owner && !owner.phone.startsWith("pending:"));

      return jPublic({
        ok: true,
        tenantId,
        businessName: access.tenantName,
        ownerConnected,
        owner: owner
          ? {
              name: owner.name,
              phone: owner.phone,
              email: owner.email
            }
          : null,
        leadEntryUrl: leadEntryUrlFromMetadata(env, access.metadata),
        workspaceUrl: workspaceUrlFromMetadata(env, tenantId, access.metadata),
        pairedAt: txt(access.metadata.ownerPairedAt, 80) || null,
        profile,
        insights: buildWorkspaceInsights({
          ownerConnected,
          totalLeads,
          openLeads,
          followupPending,
          profile,
          leads: leadRows
        }),
        stats: {
          totalLeads,
          openLeads,
          followupPending
        },
        leads: leadRows.map((lead) => ({
          name: lead.customer_name,
          phone: lead.customer_phone,
          requirement: lead.requirement,
          status: lead.status,
          language: lead.preferred_language === "hi" ? "Hindi" : "English",
          lastInboundAt: lead.last_inbound_at,
          lastOutboundAt: lead.last_outbound_at,
          updatedAt: lead.updated_at,
          createdAt: lead.created_at
        }))
      });
    }

    if (request.method === "POST" && path === "/public/waitlist") {
      const body = (await request.json().catch(() => null)) as Record<string, unknown> | null;
      if (!body || typeof body !== "object" || Array.isArray(body)) return jPublic({ error: "Invalid JSON" }, 400);
      const name = txt(body.name, 120);
      const businessName = txt(body.businessName ?? body.business, 120);
      const email = body.email ? txt(body.email, 200) : null;
      if (!name || !businessName) {
        return jPublic(
          {
            error: "Missing required fields",
            missing: {
              name: !name,
              businessName: !businessName
            }
          },
          400
        );
      }
      await ensureWaitlistTable(env);
      await env.DB.prepare(
        "INSERT INTO waitlist_signups (id,name,business_name,email,created_at) VALUES (?,?,?,?,?)"
      )
        .bind(crypto.randomUUID(), name, businessName, email, now())
        .run();
      return jPublic(
        {
          ok: true,
          message: "You are on the BharatClaw waitlist. We will send product updates as we ship them."
        },
        201
      );
    }

    if (request.method === "POST" && isStartCreatePath(path)) {
      if (!isTrue(env.PUBLIC_SIGNUP_ENABLED, true)) {
        return jPublic({ error: "Public signup is disabled" }, 403);
      }
      const body = (await request.json().catch(() => null)) as Record<string, unknown> | null;
      if (!body || typeof body !== "object" || Array.isArray(body)) return jPublic({ error: "Invalid JSON" }, 400);

      const name = txt(body.businessName ?? body.name ?? body.business, 120);
      const sessionUser = await resolveAuthenticatedUser(env, request);
      const ownerName = txt(body.ownerName ?? body.owner ?? body.contactName, 120) || txt(sessionUser?.name, 120);
      const ownerChatId = txt(body.ownerChatId, 64);
      const telegramBotToken = txt(body.telegramBotToken, 200);
      const ownerEmail = sessionUser?.email || (body.ownerEmail ? normalizeEmail(body.ownerEmail) : "");
      const industry = txt(body.industry, 80) || null;
      const teamSize = txt(body.teamSize, 40) || null;
      const launchFocusKeys = sanitizeLaunchFocusKeys(body.launchFocusKeys ?? body.launchFocus ?? body.useCases);

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

      if (ownerEmail) {
        const existing = await findExistingTenantForOwner(env, name, ownerEmail);
        if (existing) {
          const summary = summarizeTenantAccess(env, existing);
          return jPublic(
            {
              ok: true,
              existing: true,
              free: true,
              ...summary,
              statusUrl: `${url.origin}/public/start/${existing.tenantId}/status`,
              nextStep: summary.ownerConnected
                ? "This BharatClaw setup is already paired. Open your owner console or share the lead link."
                : "This BharatClaw setup already exists. Open @bharatclawbot and finish pairing."
            },
            200
          );
        }
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
            ownerAccessToken: string;
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
            ownerEmail: ownerEmail || null,
            industry,
            teamSize,
            launchFocusKeys,
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
            error: "Could not start BharatClaw right now",
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
          businessName: name,
          ownerName,
          ownerEmail: ownerEmail || null,
          tenantApiKey: created.tenantApiKey,
          trialEndsAt: created.trialEndsAt,
          webhookSecret: created.webhookSecret,
          webhookUrl,
          webhookConfigured: true,
          botUsername,
          sharedBotMode: created.sharedBotMode,
          leadJoinCode: created.leadJoinCode,
          pairingCode: created.ownerPairCode,
          leadEntryUrl: `${ownerConnectBotUrl(env)}?start=lead_${created.leadJoinCode}`,
          workspaceUrl: workspaceUrl(env, created.tenantId, created.ownerAccessToken),
          ownerConsoleUrl: workspaceUrl(env, created.tenantId, created.ownerAccessToken),
          ownerConnected: created.ownerConnected,
          ownerPairCode: created.ownerPairCode,
          ownerConnectBot: ownerConnectBotUsername(env),
          ownerConnectBotUrl: ownerConnectBotUrl(env),
          ownerConnectUrl: created.ownerConnected ? null : ownerConnectStartUrl(env, created.ownerPairCode),
          profile: workspaceProfileFromMetadata({
            sharedBotMode: created.sharedBotMode,
            industry,
            teamSize,
            launchFocusKeys
          }),
          statusUrl: `${url.origin}/public/start/${created.tenantId}/status`,
          nextStep: created.ownerConnected
            ? "BharatClaw is paired. Share your lead link and start receiving leads."
            : "Open @bharatclawbot, send the pairing code, and BharatClaw will activate."
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
        const done = await env.DB.prepare("SELECT 1 as ok FROM processed_events WHERE event_id=? AND source='TELEGRAM_OWNER_CONNECT'")
          .bind(msg.eventId)
          .first<{ ok: number }>();
        if (done) continue;

        const pairCode = parseOwnerPairCodeFromText(msg.text);
        if (pairCode) {
          let reply = "Invalid code. Open BharatClaw onboarding and copy the latest pairing code.";
          const connected = await connectOwnerByPairCode(env, pairCode, txt(msg.chatId, 64));
          if (connected.status === "CONNECTED" || connected.status === "ALREADY_CONNECTED") {
            const ownerWorkspace = await resolveOwnerWorkspaceByChat(env, txt(msg.chatId, 64));
            if (ownerConnectBotToken && ownerWorkspace) {
              const leadUrl = leadEntryUrlFromMetadata(env, ownerWorkspace.metadata);
              const ownerWorkspaceUrl = workspaceUrlFromMetadata(env, ownerWorkspace.tenantId, ownerWorkspace.metadata);
              const welcome = buildOwnerWelcomeMessage({
                businessName: ownerWorkspace.businessName,
                leadUrl,
                ownerWorkspaceUrl
              });
              reply =
                connected.status === "CONNECTED"
                  ? `Pairing complete.\n\n${welcome}`
                  : `Workspace already paired.\n\n${welcome}`;
              await sendTelegram(
                ownerConnectBotToken,
                txt(msg.chatId, 64),
                reply,
                ownerActionMarkup(leadUrl, ownerWorkspaceUrl)
              ).catch(() => {});
            }
          }
          if (ownerConnectBotToken && connected.status === "INVALID_CODE") {
            await sendTelegram(ownerConnectBotToken, txt(msg.chatId, 64), reply).catch(() => {});
          }
          await env.DB.prepare("INSERT OR IGNORE INTO processed_events (event_id,source,processed_at) VALUES (?,'TELEGRAM_OWNER_CONNECT',?)")
            .bind(msg.eventId, now())
            .run();
          continue;
        }

        const ownerWorkspace = await resolveOwnerWorkspaceByChat(env, txt(msg.chatId, 64));
        if (ownerWorkspace && ownerConnectBotToken) {
          await handleOwnerMessage(env, {
            tenantId: ownerWorkspace.tenantId,
            ownerChatId: txt(msg.chatId, 64),
            ownerName: ownerWorkspace.ownerName,
            businessName: ownerWorkspace.businessName,
            metadata: ownerWorkspace.metadata,
            botToken: ownerConnectBotToken,
            text: msg.text
          });
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
          if (ownerConnectBotToken) {
            await sendTelegram(
              ownerConnectBotToken,
              txt(msg.chatId, 64),
              "This chat is not linked yet.\n\nIf you own a workspace, send your BharatClaw pairing code.\nIf you are a customer, ask the business for their BharatClaw lead link."
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
