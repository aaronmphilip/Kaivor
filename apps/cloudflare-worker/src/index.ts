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
  FOLLOWUP_MAX_RETRIES?: string;
}

type Ctx = { waitUntil(p: Promise<unknown>): void };

const j = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });

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

async function isAutomationAllowed(env: Env, tenantId: string) {
  const row = await env.DB.prepare(
    `SELECT t.trial_ends_at, c.auto_reply_enabled, s.status, s.trial_ends_at AS subscription_trial_ends_at
     FROM tenants t JOIN tenant_configs c ON c.tenant_id=t.id
     LEFT JOIN subscriptions s ON s.tenant_id=t.id
     WHERE t.id=?`
  )
    .bind(tenantId)
    .first<{ trial_ends_at: string; auto_reply_enabled: number; status: string | null; subscription_trial_ends_at: string | null }>();
  if (!row || !row.auto_reply_enabled) return false;
  if ((row.status ?? "TRIALING") === "ACTIVE") return true;
  const trial = new Date(row.subscription_trial_ends_at ?? row.trial_ends_at).getTime();
  return trial >= Date.now();
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

      if (lead.bot_paused_until && new Date(lead.bot_paused_until).getTime() > Date.now()) {
        await env.DB.prepare("UPDATE followup_jobs SET status='SKIPPED', updated_at=? WHERE id=?").bind(now(), job.id).run();
        continue;
      }
      if (lead.last_inbound_at && new Date(lead.last_inbound_at).getTime() > new Date(job.created_at).getTime()) {
        await env.DB.prepare("UPDATE followup_jobs SET status='SKIPPED', updated_at=? WHERE id=?").bind(now(), job.id).run();
        continue;
      }

      const metadata = JSON.parse(config.metadata || "{}") as Record<string, unknown>;
      const botToken = String(metadata.telegramBotToken ?? "");
      if (!botToken) throw new Error("Missing telegramBotToken");
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

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    const path = url.pathname.replace(/\/+$/, "") || "/";

    if (request.method === "GET" && path === "/health") return j({ ok: true, service: "bharatclaw-cf" });

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

      const tenantId = crypto.randomUUID();
      const tenantApiKey = `tnt_${crypto.randomUUID().replace(/-/g, "")}`;
      const salt = crypto.randomUUID().replace(/-/g, "");
      const tenantApiKeyHash = `${salt}:${await sha256(`${salt}:${tenantApiKey}`)}`;
      const trialDays = Math.max(1, Math.min(30, Number(body.trialDays ?? 7)));
      const trialEndsAt = new Date(Date.now() + trialDays * 24 * 60 * 60_000).toISOString();

      await env.DB.prepare(
        `INSERT INTO tenants (id,name,slug,whatsapp_phone_number_id,tenant_api_key_hash,trial_ends_at,created_at)
         VALUES (?,?,?,?,?,?,?)`
      )
        .bind(tenantId, name, slug, `telegram:${slug}`, tenantApiKeyHash, trialEndsAt, now())
        .run();
      await env.DB.prepare(
        `INSERT INTO owner_contacts (id,tenant_id,name,phone,email,is_primary,created_at) VALUES (?,?,?,?,?,1,?)`
      )
        .bind(crypto.randomUUID(), tenantId, ownerName, ownerChatId, ownerEmail, now())
        .run();
      await env.DB.prepare(
        `INSERT INTO tenant_configs (tenant_id,auto_reply_enabled,greeting_template,followup_30m_template,followup_24h_template_name,takeover_cooldown_minutes,metadata,updated_at)
         VALUES (?,1,?,?,?,?,?,?)`
      )
        .bind(
          tenantId,
          `Hey thanks for reaching out to ${name}. I will help you with this. Just a couple quick details first.`,
          DEFAULT_FOLLOWUP_30M_EN,
          "lead_followup_24h",
          180,
          JSON.stringify({ telegramBotToken, businessName: name }),
          now()
        )
        .run();
      await env.DB.prepare(
        `INSERT INTO subscriptions (id,tenant_id,provider,status,trial_ends_at,created_at,updated_at)
         VALUES (?,?, 'POLAR','TRIALING',?,?,?)`
      )
        .bind(crypto.randomUUID(), tenantId, trialEndsAt, now(), now())
        .run();

      return j({ tenantId, tenantApiKey, trialEndsAt }, 201);
    }

    if (request.method === "POST" && path.startsWith("/webhooks/telegram/")) {
      const tenantId = path.split("/").pop() || "";
      if (!tenantId) return j({ error: "Missing tenant id" }, 400);
      if (!verifyTelegramSecret(request.headers.get("x-telegram-bot-api-secret-token") ?? undefined, env.TELEGRAM_WEBHOOK_SECRET)) {
        return j({ error: "Invalid Telegram secret" }, 401);
      }

      const tenant = await env.DB.prepare("SELECT * FROM tenants WHERE id=?").bind(tenantId).first();
      if (!tenant) return j({ error: "Tenant not found" }, 404);
      const payload = await request.json().catch(() => null);
      if (!payload) return j({ error: "Invalid JSON" }, 400);
      const inbound = parseTelegramWebhook(payload);

      for (const msg of inbound) {
        const done = await env.DB.prepare("SELECT 1 as ok FROM processed_events WHERE event_id=? AND source='TELEGRAM'")
          .bind(msg.eventId)
          .first<{ ok: number }>();
        if (done) continue;

        if (!(await isAutomationAllowed(env, tenantId))) {
          await env.DB.prepare("INSERT OR IGNORE INTO processed_events (event_id,source,processed_at) VALUES (?,'TELEGRAM',?)")
            .bind(msg.eventId, now())
            .run();
          continue;
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
        if (!lead) continue;

        await env.DB.prepare(
          "INSERT OR IGNORE INTO messages (id,tenant_id,lead_id,direction,message_type,body,external_message_id,idempotency_key,created_at) VALUES (?,?,?,'INBOUND','TEXT',?,?,?,?)"
        )
          .bind(crypto.randomUUID(), tenantId, lead.id, txt(msg.text, 500), msg.eventId, `inbound:${msg.eventId}`, now())
          .run();
        await env.DB.prepare("UPDATE leads SET last_inbound_at=?,updated_at=? WHERE id=?").bind(now(), now(), lead.id).run();

        if (lead.bot_paused_until && new Date(lead.bot_paused_until).getTime() > Date.now()) {
          await env.DB.prepare("INSERT OR IGNORE INTO processed_events (event_id,source,processed_at) VALUES (?,'TELEGRAM',?)")
            .bind(msg.eventId, now())
            .run();
          continue;
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
          .first<{ greeting_template: string; followup_30m_template: string; followup_24h_template_name: string; takeover_cooldown_minutes: number; auto_reply_enabled: number; metadata: string; updated_at: string }>();
        const metadata = JSON.parse(config?.metadata || "{}") as Record<string, unknown>;
        const botToken = String(metadata.telegramBotToken ?? "");
        if (!botToken) throw new Error("Missing telegramBotToken");

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
            .bind(crypto.randomUUID(), tenantId, lead.id, reply, ext, `outbound:reply:${msg.eventId}`, now())
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

        await env.DB.prepare("INSERT OR IGNORE INTO processed_events (event_id,source,processed_at) VALUES (?,'TELEGRAM',?)")
          .bind(msg.eventId, now())
          .run();
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
      const metadata = JSON.parse(existing.metadata || "{}") as Record<string, unknown>;
      if (body.telegramBotToken) metadata.telegramBotToken = txt(body.telegramBotToken, 200);
      if (body.businessName) metadata.businessName = txt(body.businessName, 120);
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
  },

  async scheduled(_event: unknown, env: Env, ctx: Ctx): Promise<void> {
    ctx.waitUntil(processFollowups(env));
  }
};
