import { buildAutoReply } from "../../../packages/reply-engine/src/premium.js";
import { computeTransition } from "../../../packages/state-machine/src/premium.js";
import { parseTelegramWebhook, verifyTelegramSecret } from "../../../packages/telegram/src/index.js";
const jsonHeaders = { "content-type": "application/json" };
const publicCorsHeaders = {
    "access-control-allow-origin": "*",
    "access-control-allow-methods": "GET,POST,OPTIONS",
    "access-control-allow-headers": "content-type,authorization"
};
let compatibilitySchemaReady = false;
let compatibilitySchemaPromise = null;
const j = (body, status = 200) => new Response(JSON.stringify(body), { status, headers: jsonHeaders });
const jPublic = (body, status = 200) => new Response(JSON.stringify(body), { status, headers: { ...jsonHeaders, ...publicCorsHeaders } });
const now = () => new Date().toISOString();
const plusMins = (m) => new Date(Date.now() + m * 60_000).toISOString();
const plusHours = (h) => new Date(Date.now() + h * 60 * 60_000).toISOString();
const txt = (v, n = 320) => String(v ?? "").trim().replace(/\s+/g, " ").slice(0, n);
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
};
const defaultLaunchFocusKeys = ["lead_capture", "followup_recovery", "owner_handoff"];
const workflowModeCatalog = {
    lead_capture: {
        label: "Lead capture",
        summary: "Qualify the need fast, keep the conversation warm, and route the owner in only when context matters.",
        detailLabel: "Lead context"
    },
    quote_request: {
        label: "Quote intake",
        summary: "Capture scope, location, timing, and budget before the owner spends time pricing.",
        detailLabel: "Quote details"
    },
    appointment_request: {
        label: "Appointment booking",
        summary: "Capture the request plus the customer's preferred slot before the owner confirms availability.",
        detailLabel: "Preferred slot"
    }
};
const sha256 = async (raw) => {
    const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(raw));
    return [...new Uint8Array(digest)].map((x) => x.toString(16).padStart(2, "0")).join("");
};
function isTrue(input, fallback = false) {
    if (input === undefined)
        return fallback;
    return input.trim().toLowerCase() === "true";
}
function slugify(input) {
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
function isSlugConflict(error) {
    const message = error instanceof Error ? error.message : String(error ?? "");
    return message.includes("UNIQUE constraint failed: tenants.slug");
}
function parseJsonObject(raw) {
    if (!raw)
        return {};
    try {
        const parsed = JSON.parse(raw);
        if (!parsed || typeof parsed !== "object" || Array.isArray(parsed))
            return {};
        return parsed;
    }
    catch {
        return {};
    }
}
function normalizeLaunchFocusKey(input) {
    return txt(input, 60)
        .toLowerCase()
        .replace(/[^a-z0-9]+/g, "_")
        .replace(/^_+|_+$/g, "");
}
function sanitizeLaunchFocusKeys(input) {
    const rawValues = Array.isArray(input)
        ? input
        : typeof input === "string"
            ? input.split(",")
            : [];
    const output = [];
    const seen = new Set();
    for (const rawValue of rawValues) {
        const normalized = normalizeLaunchFocusKey(rawValue);
        if (!normalized || !(normalized in launchFocusCatalog))
            continue;
        const key = normalized;
        if (seen.has(key))
            continue;
        seen.add(key);
        output.push(key);
    }
    return output.length ? output.slice(0, 4) : [...defaultLaunchFocusKeys];
}
function normalizeWorkflowMode(input) {
    const normalized = txt(input, 40).toLowerCase().replace(/[^a-z_]+/g, "_").replace(/^_+|_+$/g, "");
    if (normalized === "quote_request" || normalized === "appointment_request" || normalized === "lead_capture") {
        return normalized;
    }
    return null;
}
function workflowModeFromLaunchFocus(launchFocusKeys) {
    if (launchFocusKeys.includes("appointment_requests"))
        return "appointment_request";
    if (launchFocusKeys.includes("quote_requests"))
        return "quote_request";
    return "lead_capture";
}
function workflowModeFromMetadata(metadata, launchFocusKeys) {
    return (normalizeWorkflowMode(metadata.workflowMode) ??
        workflowModeFromLaunchFocus(launchFocusKeys ?? sanitizeLaunchFocusKeys(metadata.launchFocusKeys)));
}
function workflowDetailLabel(mode) {
    return workflowModeCatalog[mode].detailLabel;
}
function leadNeedSummary(requirement, workflowMode, workflowDetails) {
    const primary = txt(requirement, 220);
    const details = txt(workflowDetails, 220);
    if (!details)
        return primary;
    return [primary, `${workflowDetailLabel(workflowMode)}: ${details}`].filter(Boolean).join(" | ");
}
function workspaceProfileFromMetadata(metadata) {
    const launchFocusKeys = sanitizeLaunchFocusKeys(metadata.launchFocusKeys ?? metadata.launchFocus ?? metadata.useCases ?? metadata.focusAreas);
    const workflowMode = workflowModeFromMetadata(metadata, launchFocusKeys);
    const workflowProfile = workflowModeCatalog[workflowMode];
    return {
        industry: txt(metadata.industry, 80) || null,
        teamSize: txt(metadata.teamSize, 40) || null,
        sharedBotMode: Boolean(metadata.sharedBotMode),
        channelMode: Boolean(metadata.sharedBotMode) ? "Shared Telegram inbox" : "Dedicated Telegram bot",
        workflowMode,
        workflowLabel: workflowProfile.label,
        workflowSummary: workflowProfile.summary,
        workflowDetailLabel: workflowProfile.detailLabel,
        launchFocusKeys,
        launchFocus: launchFocusKeys.map((key) => ({
            key,
            ...launchFocusCatalog[key]
        }))
    };
}
function hoursSince(value) {
    if (!value)
        return null;
    const timestamp = Date.parse(value);
    if (!Number.isFinite(timestamp))
        return null;
    return Math.max(0, (Date.now() - timestamp) / 3_600_000);
}
function inferIntentLabel(requirement, latestInboundText) {
    const text = `${txt(requirement, 180)} ${txt(latestInboundText, 180)}`.toLowerCase();
    if (!text)
        return "General inquiry";
    if (/(price|pricing|cost|fees|budget|quote|rate)/i.test(text))
        return "Pricing request";
    if (/(book|booking|appointment|schedule|slot|visit|trial|demo)/i.test(text))
        return "Booking intent";
    if (/(repair|issue|problem|broken|not working|support|service)/i.test(text))
        return "Service issue";
    if (/(consult|consultation|treatment|admission|course|membership)/i.test(text))
        return "Qualified interest";
    return "General inquiry";
}
function shouldEscalateToOwner(requirement, latestInboundText) {
    const text = `${txt(requirement, 220)} ${txt(latestInboundText, 220)}`.toLowerCase();
    return /(urgent|asap|today|tomorrow|now|immediately|price|pricing|cost|quote|speak|call|owner|manager|custom|discount|availability)/i.test(text);
}
function buildPriorityQueue(leads) {
    return leads
        .filter((lead) => lead.status !== "CLOSED")
        .map((lead) => {
        const workflowMode = normalizeWorkflowMode(lead.workflow_mode) ?? "lead_capture";
        const latestText = leadNeedSummary(lead.requirement, workflowMode, lead.workflow_details);
        const hoursFromInbound = hoursSince(lead.last_inbound_at);
        const hoursFromOutbound = hoursSince(lead.last_outbound_at);
        const hoursFromUpdate = hoursSince(lead.updated_at) ?? 0;
        const ownerUrgent = shouldEscalateToOwner(lead.requirement, latestText);
        const staleWithoutReply = hoursFromInbound !== null &&
            (hoursFromOutbound === null || (lead.last_inbound_at || "") >= (lead.last_outbound_at || "")) &&
            hoursFromInbound >= 1;
        let score = 18;
        if (lead.status === "OWNER_TAKEOVER")
            score += 30;
        if (lead.status === "FOLLOWUP_PENDING")
            score += 22;
        if (lead.status === "IN_PROGRESS")
            score += 12;
        if (ownerUrgent)
            score += 25;
        if (staleWithoutReply)
            score += 18;
        if (hoursFromUpdate >= 24)
            score += 14;
        else if (hoursFromUpdate <= 2)
            score += 8;
        if (!latestText)
            score -= 6;
        let reason = "Lead needs a human check-in.";
        let nextMove = `Send a direct reply to ${lead.customer_phone}.`;
        if (ownerUrgent) {
            reason = "The lead sounds urgent, pricing-sensitive, or trust-sensitive.";
            nextMove = `Use owner takeover for ${lead.customer_phone} and confirm the next step personally.`;
        }
        else if (staleWithoutReply) {
            reason = "The latest inbound message may not have received a strong human follow-up.";
            nextMove = `Send a rescue follow-up to ${lead.customer_phone} before the lead cools.`;
        }
        else if (lead.status === "FOLLOWUP_PENDING") {
            reason = "This lead is already in the recovery lane and could still be won.";
            nextMove = `Review the pending follow-up and restart the conversation with context.`;
        }
        return {
            customerName: txt(lead.customer_name, 80) || "Unknown lead",
            customerPhone: lead.customer_phone,
            status: lead.status,
            score,
            reason,
            nextMove,
            updatedAt: lead.updated_at
        };
    })
        .sort((a, b) => (b.score !== a.score ? b.score - a.score : b.updatedAt.localeCompare(a.updatedAt)))
        .slice(0, 5);
}
function buildWorkspaceInsights(input) {
    const priorityQueue = buildPriorityQueue(input.leads);
    const languageTotals = input.leads.reduce((acc, lead) => {
        if (lead.preferred_language === "hi")
            acc.hi += 1;
        else if (lead.preferred_language === "en")
            acc.en += 1;
        return acc;
    }, { en: 0, hi: 0 });
    const readinessScore = Math.min(100, (input.ownerConnected ? 45 : 18) +
        Math.min(18, input.profile.launchFocusKeys.length * 6) +
        (input.profile.industry ? 8 : 0) +
        (input.profile.teamSize ? 7 : 0) +
        (input.totalLeads > 0 ? 12 : 0) +
        (input.totalLeads > 3 ? 10 : 0));
    const recommendedActions = [];
    if (!input.ownerConnected) {
        recommendedActions.push("Pair the owner chat so Kaivor can send alerts, handoffs, and live status updates.");
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
    if (priorityQueue.length) {
        recommendedActions.push(`Start with ${priorityQueue[0]?.customerName || "the top lead"} because it currently has the strongest rescue or conversion signal.`);
    }
    if (!recommendedActions.length) {
        recommendedActions.push("Keep routing customer conversations into Kaivor and watch the owner console for fresh intent.");
    }
    let launchStage = "Workspace created and ready for traffic";
    if (!input.ownerConnected) {
        launchStage = "Waiting for owner pairing";
    }
    else if (input.totalLeads === 0) {
        launchStage = "Live and waiting for the first lead";
    }
    else if (input.totalLeads < 10) {
        launchStage = "Collecting early conversion data";
    }
    else {
        launchStage = "Running an active intake engine";
    }
    let topLanguage = "No language signal yet";
    if (languageTotals.hi > languageTotals.en)
        topLanguage = "Hindi-heavy conversations";
    else if (languageTotals.en > languageTotals.hi)
        topLanguage = "English-heavy conversations";
    else if (languageTotals.en || languageTotals.hi)
        topLanguage = "Balanced English and Hindi mix";
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
        topLanguage,
        priorityQueue
    };
}
function capitalize(input) {
    return input ? input.charAt(0).toUpperCase() + input.slice(1) : "";
}
function aiAgentMode(env, metadata) {
    const metadataMode = txt(metadata.aiAgentMode, 20).toLowerCase();
    if (metadataMode === "remote")
        return "remote";
    const envMode = txt(env.AI_AGENT_MODE, 20).toLowerCase();
    return envMode === "remote" ? "remote" : "mock";
}
function aiAgentEnabled(env, metadata) {
    if (!isTrue(env.AI_AGENT_ENABLED, true))
        return false;
    const mode = txt(metadata.aiAgentMode, 20).toLowerCase();
    if (mode === "off" || mode === "disabled")
        return false;
    return true;
}
function aiAgentApiUrl(env) {
    return txt(env.AI_AGENT_API_URL, 320) || "https://api.openai.com/v1/chat/completions";
}
function aiBusinessContext(metadata) {
    const profile = workspaceProfileFromMetadata(metadata);
    const focusLabels = profile.launchFocus.map((item) => item.label).join(", ");
    return {
        businessName: txt(metadata.businessName, 120) || "the business",
        industry: profile.industry || "General business",
        teamSize: profile.teamSize || "Not specified",
        channelMode: profile.channelMode,
        primaryWorkflow: profile.workflowLabel,
        focusLabels: focusLabels || "Lead capture, follow-up recovery, owner handoff",
        offerSummary: txt(metadata.offerSummary ?? metadata.businessDescription, 240) || "Capture inbound leads, qualify them, and guide them toward the owner.",
        faqSummary: txt(metadata.faqSummary, 280) || "If pricing, availability, or custom scope is uncertain, the owner should step in.",
        brandVoice: txt(metadata.brandVoice, 120) || "confident, warm, concise, helpful",
        ownerGoal: txt(metadata.ownerGoal, 180) || "convert serious inbound leads without sounding robotic"
    };
}
function normalizeAiCopilotOutput(input) {
    if (!input || typeof input !== "object" || Array.isArray(input))
        return null;
    const row = input;
    const leadTemperatureRaw = txt(row.leadTemperature, 20).toLowerCase();
    const leadTemperature = leadTemperatureRaw === "hot" || leadTemperatureRaw === "cold" ? leadTemperatureRaw : "warm";
    const reply = txt(row.reply, 420);
    const ownerSummary = txt(row.ownerSummary, 320);
    const intentLabel = txt(row.intentLabel, 80) || "General inquiry";
    const followupHint = txt(row.followupHint, 160) || "Keep the conversation moving toward a clear next step.";
    const ownerShouldTakeover = row.ownerShouldTakeover === true || txt(row.ownerShouldTakeover, 10).toLowerCase() === "true";
    if (!reply && !ownerSummary)
        return null;
    return {
        reply: reply || "Thanks for the message. I have shared it with the team and we will guide you on the next step shortly.",
        ownerSummary: ownerSummary || "AI copilot did not produce a detailed owner brief, but the lead looks active.",
        ownerShouldTakeover,
        leadTemperature,
        intentLabel,
        followupHint
    };
}
function buildMockAiCopilot(input, business) {
    const latestInboundText = txt(input.latestInboundText, 240);
    const language = input.preferredLanguage === "hi" ? "hi" : "en";
    const intentLabel = inferIntentLabel(input.requirement, latestInboundText);
    const ownerShouldTakeover = shouldEscalateToOwner(input.requirement, latestInboundText);
    const leadTemperature = ownerShouldTakeover
        ? "hot"
        : txt(input.requirement || latestInboundText, 24)
            ? "warm"
            : "cold";
    if (input.mode === "workspace_digest") {
        const totalLeads = Number(input.workspaceStats?.totalLeads ?? 0);
        const openLeads = Number(input.workspaceStats?.openLeads ?? 0);
        const followupPending = Number(input.workspaceStats?.followupPending ?? 0);
        return {
            reply: "Workspace digest ready.",
            ownerSummary: totalLeads === 0
                ? "No leads have landed yet, so the fastest path is getting the lead link into more traffic sources."
                : followupPending > 0
                    ? "The main risk right now is follow-up drift. Focus on leads that are waiting for a human-quality nudge."
                    : openLeads > 0
                        ? "Open leads are active. Prioritize replies that confirm price, timing, or next steps before intent cools."
                        : "The workspace looks stable. Keep feeding traffic into the bot and monitor for new high-intent chats.",
            ownerShouldTakeover: followupPending > 0,
            leadTemperature: totalLeads > 0 ? "warm" : "cold",
            intentLabel: totalLeads > 0 ? "Workspace review" : "Pre-launch workspace",
            followupHint: totalLeads === 0
                ? "Share the lead link in your bio, pinned message, and QR placements to start collecting signals."
                : followupPending > 0
                    ? "Use the owner console or Telegram commands to rescue the oldest pending leads first."
                    : "Keep the owner loop fast for any leads asking about price, availability, or custom scope."
        };
    }
    const customerName = txt(input.customerName, 80) || "there";
    const defaultReply = txt(input.defaultReply, 420);
    const reply = defaultReply ||
        (input.mode === "owner_draft"
            ? language === "hi"
                ? `Namaste ${customerName}, aapka message mil gaya hai. Main details dekh kar aapko next step ke saath jaldi reply karta hoon.`
                : `Thanks ${customerName}, I have reviewed your message. I will get back to you shortly with the next step.`
            : language === "hi"
                ? `Namaste ${customerName}, aapka message mil gaya hai. Main aapki help karne ke liye yahan hoon.`
                : `Thanks ${customerName}, I have your message and I am here to help.`);
    const ownerSummary = ownerShouldTakeover
        ? `${customerName} looks high-intent for ${business.businessName}. The message suggests urgency, pricing sensitivity, or a need for human reassurance.`
        : `${customerName} is active and qualified enough to keep moving. The conversation can stay guided, but a quick owner reply would still strengthen trust.`;
    return {
        reply,
        ownerSummary,
        ownerShouldTakeover,
        leadTemperature,
        intentLabel,
        followupHint: ownerShouldTakeover
            ? `Reply personally with a clear next step for ${customerName}, especially around timing, pricing, or availability.`
            : "Keep the chat moving toward one concrete next step instead of a generic acknowledgment."
    };
}
async function ensureWaitlistTable(env) {
    await env.DB.prepare(`CREATE TABLE IF NOT EXISTS waitlist_signups (
      id TEXT PRIMARY KEY,
      name TEXT NOT NULL,
      business_name TEXT NOT NULL,
      email TEXT,
      created_at TEXT NOT NULL
    )`).run();
}
async function ensureCompatibilitySchema(env) {
    if (compatibilitySchemaReady)
        return;
    if (compatibilitySchemaPromise)
        return compatibilitySchemaPromise;
    compatibilitySchemaPromise = (async () => {
    await env.DB.prepare(`CREATE TABLE IF NOT EXISTS leads (
        id TEXT PRIMARY KEY,
        tenant_id TEXT NOT NULL,
        customer_phone TEXT NOT NULL,
        customer_name TEXT,
        preferred_language TEXT,
        requirement TEXT,
        workflow_mode TEXT,
        workflow_details TEXT,
        status TEXT NOT NULL,
        bot_paused_until TEXT,
        last_inbound_at TEXT,
        last_outbound_at TEXT,
        created_at TEXT NOT NULL,
        updated_at TEXT NOT NULL
      )`).run();
        await env.DB.prepare(`CREATE TABLE IF NOT EXISTS chat_routes (
        chat_id TEXT NOT NULL,
        source TEXT NOT NULL,
        tenant_id TEXT NOT NULL,
        lead_join_code TEXT,
        bound_via TEXT NOT NULL,
        created_at TEXT NOT NULL,
        updated_at TEXT NOT NULL,
        PRIMARY KEY (chat_id, source)
      )`).run();
        await env.DB.prepare("CREATE INDEX IF NOT EXISTS idx_chat_routes_tenant ON chat_routes (tenant_id, updated_at)").run();
        const tableInfo = await env.DB.prepare("PRAGMA table_info(leads)").all();
        const existingColumns = new Set((tableInfo.results ?? []).map((row) => txt(row.name, 80)));
        const requiredColumns = [
            { name: "customer_name", ddl: "ALTER TABLE leads ADD COLUMN customer_name TEXT" },
            { name: "preferred_language", ddl: "ALTER TABLE leads ADD COLUMN preferred_language TEXT" },
            { name: "requirement", ddl: "ALTER TABLE leads ADD COLUMN requirement TEXT" },
            { name: "workflow_mode", ddl: "ALTER TABLE leads ADD COLUMN workflow_mode TEXT" },
            { name: "workflow_details", ddl: "ALTER TABLE leads ADD COLUMN workflow_details TEXT" },
            { name: "bot_paused_until", ddl: "ALTER TABLE leads ADD COLUMN bot_paused_until TEXT" },
            { name: "last_inbound_at", ddl: "ALTER TABLE leads ADD COLUMN last_inbound_at TEXT" },
            { name: "last_outbound_at", ddl: "ALTER TABLE leads ADD COLUMN last_outbound_at TEXT" },
            { name: "created_at", ddl: "ALTER TABLE leads ADD COLUMN created_at TEXT" },
            { name: "updated_at", ddl: "ALTER TABLE leads ADD COLUMN updated_at TEXT" }
        ];
        for (const column of requiredColumns) {
            if (existingColumns.has(column.name))
                continue;
            await env.DB.prepare(column.ddl).run();
        }
        compatibilitySchemaReady = true;
    })().catch((error) => {
        compatibilitySchemaPromise = null;
        throw error;
    });
    return compatibilitySchemaPromise;
}
function isStartCreatePath(path) {
    return path === "/public/start" || path === "/public/free-trial";
}
function isStartStatusPath(path) {
    return /^\/public\/(?:start|free-trial)\/[a-z0-9-]+\/status$/i.test(path);
}
function normalizeEmail(input) {
    return txt(input, 200).toLowerCase();
}
async function ensureAuthTables(env) {
    await env.DB.prepare(`CREATE TABLE IF NOT EXISTS app_users (
      id TEXT PRIMARY KEY,
      name TEXT NOT NULL,
      email TEXT NOT NULL UNIQUE,
      password_hash TEXT NOT NULL,
      created_at TEXT NOT NULL,
      updated_at TEXT NOT NULL
    )`).run();
    await env.DB.prepare(`CREATE TABLE IF NOT EXISTS app_sessions (
      id TEXT PRIMARY KEY,
      user_id TEXT NOT NULL,
      token_hash TEXT NOT NULL,
      expires_at TEXT NOT NULL,
      created_at TEXT NOT NULL
    )`).run();
}
async function hashWithSalt(raw, salt) {
    const actualSalt = salt ?? crypto.randomUUID().replace(/-/g, "");
    return `${actualSalt}:${await sha256(`${actualSalt}:${raw}`)}`;
}
async function verifySaltedHash(raw, stored) {
    const [salt, hash] = stored.split(":");
    if (!salt || !hash)
        return false;
    const actual = await sha256(`${salt}:${raw}`);
    return actual === hash;
}
function generateSessionToken() {
    return `bcsess_${crypto.randomUUID().replace(/-/g, "")}`;
}
function readBearerToken(request) {
    const auth = txt(request.headers.get("authorization"), 500);
    const match = /^bearer\s+(.+)$/i.exec(auth);
    return match ? txt(match[1], 220) : "";
}
async function resolveAuthenticatedUser(env, request) {
    const token = readBearerToken(request);
    if (!token)
        return null;
    await ensureAuthTables(env);
    const tokenHash = await sha256(token);
    const row = await env.DB.prepare(`SELECT u.id,u.name,u.email,s.expires_at
     FROM app_sessions s
     JOIN app_users u ON u.id=s.user_id
     WHERE s.token_hash=?
     ORDER BY s.created_at DESC
     LIMIT 1`)
        .bind(tokenHash)
        .first();
    if (!row)
        return null;
    if (new Date(row.expires_at).getTime() < Date.now())
        return null;
    return {
        id: row.id,
        name: row.name,
        email: normalizeEmail(row.email)
    };
}
async function createSessionForUser(env, userId) {
    await ensureAuthTables(env);
    const token = generateSessionToken();
    const expiresAt = new Date(Date.now() + 30 * 24 * 60 * 60_000).toISOString();
    await env.DB.prepare("INSERT INTO app_sessions (id,user_id,token_hash,expires_at,created_at) VALUES (?,?,?,?,?)")
        .bind(crypto.randomUUID(), userId, await sha256(token), expiresAt, now())
        .run();
    return { token, expiresAt };
}
function summarizeTenantAccess(env, input) {
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
async function listAuthenticatedTenants(env, email) {
    const rows = await env.DB.prepare(`SELECT t.id AS tenant_id,t.name AS business_name,o.name AS owner_name,o.email,o.phone,c.metadata
     FROM owner_contacts o
     JOIN tenants t ON t.id=o.tenant_id
     JOIN tenant_configs c ON c.tenant_id=o.tenant_id
     WHERE o.is_primary=1 AND lower(o.email)=?
     ORDER BY t.created_at DESC`)
        .bind(normalizeEmail(email))
        .all();
    return (rows.results ?? []).map((row) => summarizeTenantAccess(env, {
        tenantId: row.tenant_id,
        businessName: row.business_name,
        ownerName: row.owner_name,
        ownerEmail: row.email,
        ownerPhone: row.phone,
        metadata: parseJsonObject(row.metadata)
    }));
}
async function findExistingTenantForOwner(env, businessName, ownerEmail) {
    const row = await env.DB.prepare(`SELECT t.id AS tenant_id,t.name AS business_name,o.name AS owner_name,o.email,o.phone,c.metadata
     FROM owner_contacts o
     JOIN tenants t ON t.id=o.tenant_id
     JOIN tenant_configs c ON c.tenant_id=o.tenant_id
     WHERE o.is_primary=1 AND lower(o.email)=? AND lower(t.name)=?
     ORDER BY t.created_at DESC
     LIMIT 1`)
        .bind(normalizeEmail(ownerEmail), txt(businessName, 120).toLowerCase())
        .first();
    if (!row)
        return null;
    return {
        tenantId: row.tenant_id,
        businessName: row.business_name,
        ownerName: row.owner_name,
        ownerEmail: row.email,
        ownerPhone: row.phone,
        metadata: parseJsonObject(row.metadata)
    };
}
async function userOwnsTenant(env, tenantId, email) {
    const row = await env.DB.prepare("SELECT 1 AS ok FROM owner_contacts WHERE tenant_id=? AND is_primary=1 AND lower(email)=? LIMIT 1")
        .bind(tenantId, normalizeEmail(email))
        .first();
    return Boolean(row?.ok);
}
function ownerConnectBotUsername(env) {
    return txt(env.OWNER_CONNECT_BOT_USERNAME ?? "kaivorbot", 64).replace(/^@/, "") || "kaivorbot";
}
function ownerConnectBotUrl(env) {
    return `https://t.me/${ownerConnectBotUsername(env)}`;
}
function ownerConnectStartUrl(env, code) {
    return `${ownerConnectBotUrl(env)}?start=pair_${encodeURIComponent(code)}`;
}
function appBaseUrl(env) {
    const source = txt(env.LANDING_CTA_URL ?? "https://kaivorapp.vercel.app/get-started", 500);
    try {
        return new URL(source).origin;
    }
    catch {
        return "https://kaivorapp.vercel.app";
    }
}
function workspaceUrl(env, tenantId, token) {
    const base = appBaseUrl(env);
    return `${base}/workspace?tenantId=${encodeURIComponent(tenantId)}&token=${encodeURIComponent(token)}`;
}
function randomCode(length) {
    const alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    let output = "";
    for (let i = 0; i < length; i += 1) {
        output += alphabet[Math.floor(Math.random() * alphabet.length)];
    }
    return output;
}
function generateOwnerPairCode() {
    return `KV${randomCode(6)}`;
}
function parseOwnerPairCodeFromText(message) {
    const input = txt(message, 180);
    if (!input)
        return null;
    const startMatch = /^\/start\s+pair_([a-z0-9-]{4,40})$/i.exec(input);
    if (startMatch) {
        const normalized = startMatch[1].toUpperCase().replace(/[^A-Z0-9]/g, "");
        const finalCode = normalized.startsWith("KV") ? normalized : `KV${normalized}`;
        return /^KV[A-Z0-9]{6,10}$/.test(finalCode) ? finalCode : null;
    }
    const pairMatch = /^\/pair\s+([a-z0-9-]{4,40})$/i.exec(input);
    if (pairMatch) {
        const normalized = pairMatch[1].toUpperCase().replace(/[^A-Z0-9]/g, "");
        const finalCode = normalized.startsWith("KV") ? normalized : `KV${normalized}`;
        return /^KV[A-Z0-9]{6,10}$/.test(finalCode) ? finalCode : null;
    }
    const compact = input.toUpperCase().replace(/[^A-Z0-9]/g, "");
    if (!/^[A-Z0-9\s-]+$/i.test(input))
        return null;
    if (/^KV[A-Z0-9]{6,10}$/.test(compact))
        return compact;
    return null;
}
async function ownerPairCodeExists(env, pairCode) {
    const rows = await env.DB.prepare("SELECT metadata FROM tenant_configs").all();
    for (const row of rows.results ?? []) {
        const metadata = parseJsonObject(row.metadata);
        const existing = txt(metadata.ownerPairCode, 20).toUpperCase();
        if (existing === pairCode)
            return true;
    }
    return false;
}
async function generateUniqueOwnerPairCode(env, attempts = 8) {
    for (let i = 0; i < attempts; i += 1) {
        const pairCode = generateOwnerPairCode();
        if (!(await ownerPairCodeExists(env, pairCode)))
            return pairCode;
    }
    return `KV${randomCode(10)}`;
}
function generateLeadJoinCode() {
    return `LD${randomCode(6)}`;
}
function generateOwnerAccessToken() {
    return `wksp_${crypto.randomUUID().replace(/-/g, "")}`;
}
function parseLeadJoinCodeFromText(message) {
    const input = txt(message, 180);
    if (!input)
        return null;
    const match = /^\/start\s+lead_([a-z0-9-]{4,40})$/i.exec(input);
    if (!match)
        return null;
    const normalized = match[1].toUpperCase().replace(/[^A-Z0-9]/g, "");
    if (!normalized)
        return null;
    const finalCode = normalized.startsWith("LD") ? normalized : `LD${normalized}`;
    if (finalCode.length < 8 || finalCode.length > 12)
        return null;
    return finalCode;
}
async function leadJoinCodeExists(env, joinCode) {
    const rows = await env.DB.prepare("SELECT metadata FROM tenant_configs").all();
    for (const row of rows.results ?? []) {
        const metadata = parseJsonObject(row.metadata);
        const existing = txt(metadata.leadJoinCode, 20).toUpperCase();
        if (existing === joinCode)
            return true;
    }
    return false;
}
async function generateUniqueLeadJoinCode(env, attempts = 8) {
    for (let i = 0; i < attempts; i += 1) {
        const joinCode = generateLeadJoinCode();
        if (!(await leadJoinCodeExists(env, joinCode)))
            return joinCode;
    }
    return `LD${randomCode(10)}`;
}
function leadEntryUrlFromMetadata(env, metadata) {
    const joinCode = txt(metadata.leadJoinCode, 20).toUpperCase();
    return joinCode ? `${ownerConnectBotUrl(env)}?start=lead_${joinCode}` : ownerConnectBotUrl(env);
}
function workspaceUrlFromMetadata(env, tenantId, metadata) {
    const accessToken = txt(metadata.ownerAccessToken, 120);
    if (!accessToken)
        return null;
    return workspaceUrl(env, tenantId, accessToken);
}
async function connectOwnerByPairCode(env, pairCode, ownerChatId, ownerNameHint) {
    const rows = await env.DB.prepare(`SELECT o.id AS owner_id,o.tenant_id,o.phone,o.name,c.metadata
     FROM owner_contacts o
     JOIN tenant_configs c ON c.tenant_id=o.tenant_id
     WHERE o.is_primary=1`).all();
    let matched = null;
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
    if (!matched)
        return { status: "INVALID_CODE" };
    if (!matched.phone.startsWith("pending:"))
        return { status: "ALREADY_CONNECTED", tenantId: matched.tenantId };
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
    await env.DB.prepare("INSERT INTO audit_events (id,tenant_id,lead_id,actor,action,metadata,created_at) VALUES (?,?,?,?,?,?,?)")
        .bind(crypto.randomUUID(), matched.tenantId, null, "OWNER", "OWNER_PAIRED", JSON.stringify({ ownerChatId, pairCode }), now())
        .run();
    return { status: "CONNECTED", tenantId: matched.tenantId };
}
async function resolveTenantIdByLeadJoinCode(env, joinCode) {
    const rows = await env.DB.prepare("SELECT tenant_id, metadata FROM tenant_configs").all();
    for (const row of rows.results ?? []) {
        const metadata = parseJsonObject(row.metadata);
        const codeInRow = txt(metadata.leadJoinCode, 20).toUpperCase();
        if (codeInRow === joinCode)
            return row.tenant_id;
    }
    return null;
}
async function resolveLatestTenantIdByChat(env, chatId) {
    const row = await env.DB.prepare("SELECT tenant_id FROM leads WHERE customer_phone=? ORDER BY updated_at DESC LIMIT 1")
        .bind(chatId)
        .first();
    return row?.tenant_id ?? null;
}
async function resolveBoundTenantIdByChat(env, chatId, source) {
    const row = await env.DB.prepare("SELECT tenant_id FROM chat_routes WHERE chat_id=? AND source=?")
        .bind(chatId, source)
        .first();
    return row?.tenant_id ?? null;
}
async function bindChatRoute(env, input) {
    await env.DB.prepare(`INSERT INTO chat_routes (chat_id,source,tenant_id,lead_join_code,bound_via,created_at,updated_at)
     VALUES (?,?,?,?,?,?,?)
     ON CONFLICT(chat_id,source) DO UPDATE SET
       tenant_id=excluded.tenant_id,
       lead_join_code=excluded.lead_join_code,
       bound_via=excluded.bound_via,
       updated_at=excluded.updated_at`)
        .bind(input.chatId, input.source, input.tenantId, input.leadJoinCode ?? null, input.boundVia, now(), now())
        .run();
}
async function resolveOwnerWorkspaceByChat(env, chatId) {
    const row = await env.DB.prepare(`SELECT o.tenant_id,o.name,c.metadata,t.name AS business_name
     FROM owner_contacts o
     JOIN tenant_configs c ON c.tenant_id=o.tenant_id
     JOIN tenants t ON t.id=o.tenant_id
     WHERE o.phone=?
     ORDER BY o.created_at DESC
     LIMIT 1`)
        .bind(chatId)
        .first();
    if (!row)
        return null;
    return {
        tenantId: row.tenant_id,
        ownerName: row.name,
        metadata: parseJsonObject(row.metadata),
        businessName: row.business_name
    };
}
async function loadWorkspaceAccess(env, tenantId, token) {
    const row = await env.DB.prepare(`SELECT t.name AS tenant_name,c.metadata
     FROM tenants t
     JOIN tenant_configs c ON c.tenant_id=t.id
     WHERE t.id=?`)
        .bind(tenantId)
        .first();
    if (!row)
        return null;
    const metadata = parseJsonObject(row.metadata);
    if (txt(metadata.ownerAccessToken, 120) !== txt(token, 120))
        return null;
    return { tenantName: row.tenant_name, metadata };
}
function resolveOutboundBotToken(metadata, env) {
    return txt(metadata.telegramBotToken, 220) || txt(env.OWNER_CONNECT_BOT_TOKEN, 220);
}
async function telegramApi(botToken, method, payload) {
    const response = await fetch(`https://api.telegram.org/bot${botToken}/${method}`, {
        method: payload ? "POST" : "GET",
        headers: payload ? { "content-type": "application/json" } : undefined,
        body: payload ? JSON.stringify(payload) : undefined
    });
    const body = (await response.json().catch(() => null));
    if (!response.ok || !body || !("ok" in body) || !body.ok) {
        const description = body && "description" in body && typeof body.description === "string"
            ? body.description
            : "Telegram API error";
        throw new Error(description);
    }
    return body.result;
}
async function getBotProfile(botToken) {
    return telegramApi(botToken, "getMe");
}
async function setTelegramWebhook(botToken, webhookUrl, webhookSecret) {
    await telegramApi(botToken, "setWebhook", {
        url: webhookUrl,
        secret_token: webhookSecret
    });
}
async function sendTelegram(botToken, chatId, text, extra) {
    const response = await fetch(`https://api.telegram.org/bot${botToken}/sendMessage`, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ chat_id: chatId, text, ...(extra ?? {}) })
    });
    const payload = (await response.json());
    if (!response.ok || !payload.ok)
        throw new Error(payload.description ?? "Telegram send failed");
    return String(payload.result?.message_id ?? "unknown");
}
async function loadLeadTranscript(env, leadId, limit = 8) {
    const rows = await env.DB.prepare(`SELECT direction,body,created_at
     FROM messages
     WHERE lead_id=?
     ORDER BY created_at DESC
     LIMIT ?`)
        .bind(leadId, limit)
        .all();
    return [...(rows.results ?? [])].reverse();
}
async function callAiCopilot(env, input) {
    if (!aiAgentEnabled(env, input.metadata))
        return null;
    const business = aiBusinessContext(input.metadata);
    const fallback = buildMockAiCopilot(input, business);
    if (aiAgentMode(env, input.metadata) !== "remote")
        return fallback;
    const apiKey = txt(env.AI_AGENT_API_KEY, 240);
    const model = txt(env.AI_AGENT_MODEL, 120);
    if (!apiKey || !model)
        return fallback;
    const transcript = input.transcript
        .map((row) => `${row.direction === "INBOUND" ? "Lead" : "Kaivor"} (${row.created_at}): ${txt(row.body, 280)}`)
        .join("\n");
    const systemPrompt = [
        "You are Kaivor Copilot, a high-conviction conversational sales assistant for Indian businesses.",
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
        replyGuide: {
            replyKey: input.replyKey || null,
            defaultReply: input.defaultReply || null
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
    if (!response || !response.ok)
        return fallback;
    const payload = (await response.json().catch(() => null));
    const rawContent = txt(payload?.choices?.[0]?.message?.content, 8000);
    if (!rawContent)
        return fallback;
    try {
        return normalizeAiCopilotOutput(JSON.parse(rawContent)) ?? fallback;
    }
    catch {
        return fallback;
    }
}
function html(body, status = 200) {
    return new Response(body, {
        status,
        headers: { "content-type": "text/html; charset=utf-8", "cache-control": "public, max-age=300" }
    });
}
function renderLandingPage(ctaUrl) {
    const safeUrl = /^https?:\/\//i.test(ctaUrl) ? ctaUrl : "https://t.me";
    return `<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>Kaivor | Never miss a lead again</title>
  <meta name="description" content="Kaivor helps Indian businesses capture Telegram leads, follow up automatically, and close faster." />
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
      <div class="brand">Kaivor</div>
      <div class="pill">Telegram-first lead automation for India</div>
    </nav>

    <section class="hero">
      <div class="panel">
        <h1>Never miss a lead again.</h1>
        <p class="subtitle">
          Kaivor replies instantly, captures lead details, follows up automatically, and alerts the owner so your business closes faster.
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
      <h2>How Kaivor Works</h2>
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
      Kaivor. Premium Telegram lead capture for Indian businesses.
    </footer>
  </div>
</body>
</html>`;
}
function isPublicPath(path) {
    return path === "/" || path.startsWith("/public/");
}
function errorDetail(error) {
    if (error instanceof Error)
        return error.message;
    return String(error ?? "Unknown error");
}
function errorResponse(path, error) {
    const detail = errorDetail(error).slice(0, 500);
    return (isPublicPath(path) ? jPublic : j)({
        error: "Request failed",
        detail,
        code: "WORKER_RUNTIME_ERROR"
    }, 500);
}
function telegramReplyOptionsForKey(replyKey) {
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
    if (replyKey === "ASK_NAME" ||
        replyKey === "ASK_REQUIREMENT" ||
        replyKey === "ASK_QUOTE_DETAILS" ||
        replyKey === "ASK_APPOINTMENT_DETAILS" ||
        replyKey === "REASK_REQUIREMENT" ||
        replyKey === "CONFIRM_CAPTURE") {
        return {
            reply_markup: {
                remove_keyboard: true
            }
        };
    }
    return undefined;
}
function ownerActionMarkup(leadUrl, ownerWorkspaceUrl) {
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
function formatLeadStatus(status) {
    if (status === "FOLLOWUP_PENDING")
        return "Follow-up pending";
    if (status === "OWNER_TAKEOVER")
        return "Owner takeover";
    if (status === "IN_PROGRESS")
        return "In progress";
    if (status === "CAPTURED")
        return "Captured";
    if (status === "CLOSED")
        return "Closed";
    return "New";
}
function buildOwnerWelcomeMessage(input) {
    return [
        `Kaivor is live for ${input.businessName}.`,
        "",
        "Share this lead link with customers:",
        input.leadUrl,
        "",
        "Important: every new customer should enter through this lead link so Kaivor routes the chat into your workspace correctly.",
        "",
        input.ownerWorkspaceUrl ? `Owner console:\n${input.ownerWorkspaceUrl}\n` : "",
        "Owner commands:",
        "/status  system health",
        "/leads  recent leads",
        "/priority  top rescue queue",
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
function buildLeadDigest(leads) {
    if (!leads.length) {
        return "No leads captured yet. Share your lead link and the first lead will show up here.";
    }
    return leads
        .map((lead, index) => {
        const name = txt(lead.customer_name, 80) || "Unknown";
        const workflowMode = normalizeWorkflowMode(lead.workflow_mode) ?? "lead_capture";
        const need = txt(leadNeedSummary(lead.requirement, workflowMode, lead.workflow_details), 120) || "Requirement pending";
        const language = lead.preferred_language === "hi" ? "Hindi" : "English";
        return `${index + 1}. ${name} | ${need}\nChat: ${lead.customer_phone}\nStatus: ${formatLeadStatus(lead.status)} | Language: ${language} | Updated: ${lead.updated_at}`;
    })
        .join("\n\n")
        .slice(0, 3400);
}
function buildPriorityDigest(priorityQueue) {
    if (!priorityQueue.length) {
        return "No urgent rescue queue right now. New leads and follow-up candidates will appear here automatically.";
    }
    return priorityQueue
        .map((lead, index) => `${index + 1}. ${lead.customerName} | ${lead.customerPhone}\nScore: ${lead.score} | Status: ${formatLeadStatus(lead.status)}\nWhy now: ${lead.reason}\nNext move: ${lead.nextMove}`)
        .join("\n\n")
        .slice(0, 3600);
}
function buildAiLeadBrief(leadPhone, ai) {
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
function buildOwnerLeadSummary(input) {
    const lines = [
        `New lead for ${input.businessName || "your business"}.`,
        `Name: ${input.customerName || "Not provided"}`,
        `Chat: ${input.customerPhone}`,
        `Requirement: ${input.requirement || "Not provided"}`,
        input.workflowDetails ? `${workflowDetailLabel(input.workflowMode)}: ${input.workflowDetails}` : "",
        `Language: ${input.preferredLanguage === "hi" ? "Hindi" : "English"}`,
        `Time: ${now()}`
    ].filter(Boolean);
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
async function notifyOwners(env, input) {
    const owners = await env.DB.prepare("SELECT * FROM owner_contacts WHERE tenant_id=? AND is_primary=1 ORDER BY created_at")
        .bind(input.tenantId)
        .all();
    const leadUrl = leadEntryUrlFromMetadata(env, input.metadata);
    const ownerWorkspaceUrl = workspaceUrlFromMetadata(env, input.tenantId, input.metadata);
    for (const owner of owners.results ?? []) {
        try {
            const notificationId = crypto.randomUUID();
            await sendTelegram(input.botToken, owner.phone, input.body, ownerActionMarkup(leadUrl, ownerWorkspaceUrl));
            await env.DB.prepare("INSERT INTO notifications (id,tenant_id,lead_id,channel,status,payload,error,created_at) VALUES (?,?,?,?,?,?,?,?)")
                .bind(notificationId, input.tenantId, input.leadId, "TELEGRAM", "SENT", JSON.stringify({ ownerPhone: owner.phone, body: input.body }), null, now())
                .run();
        }
        catch {
            await env.DB.prepare("INSERT INTO notifications (id,tenant_id,lead_id,channel,status,payload,error,created_at) VALUES (?,?,?,?,?,?,?,?)")
                .bind(crypto.randomUUID(), input.tenantId, input.leadId, "TELEGRAM", "FAILED", JSON.stringify({ ownerPhone: owner.phone, body: input.body }), "Telegram send failed", now())
                .run();
        }
    }
}
async function handleOwnerMessage(env, input) {
    const normalized = txt(input.text, 240).toLowerCase();
    const leadUrl = leadEntryUrlFromMetadata(env, input.metadata);
    const ownerWorkspaceUrl = workspaceUrlFromMetadata(env, input.tenantId, input.metadata);
    if (/^#takeover\s+\d{5,20}$/i.test(input.text)) {
        const chatId = input.text.trim().split(/\s+/)[1] ?? "";
        const lead = await env.DB.prepare("SELECT id FROM leads WHERE tenant_id=? AND customer_phone=?")
            .bind(input.tenantId, chatId)
            .first();
        if (!lead) {
            await sendTelegram(input.botToken, input.ownerChatId, "Lead not found for that chat id.");
            return;
        }
        const cfg = await env.DB.prepare("SELECT takeover_cooldown_minutes FROM tenant_configs WHERE tenant_id=?")
            .bind(input.tenantId)
            .first();
        const pause = plusMins(Math.max(5, Math.min(1440, Number(cfg?.takeover_cooldown_minutes ?? 180))));
        await env.DB.prepare("UPDATE leads SET status='OWNER_TAKEOVER', bot_paused_until=?, updated_at=? WHERE id=?")
            .bind(pause, now(), lead.id)
            .run();
        await env.DB.prepare("UPDATE conversations SET state='OWNER_TAKEOVER', updated_at=? WHERE lead_id=?")
            .bind(now(), lead.id)
            .run();
        await env.DB.prepare("INSERT INTO audit_events (id,tenant_id,lead_id,actor,action,metadata,created_at) VALUES (?,?,?,?,?,?,?)")
            .bind(crypto.randomUUID(), input.tenantId, lead.id, "OWNER", "OWNER_TAKEOVER_ENABLED", JSON.stringify({ ownerChatId: input.ownerChatId, targetChatId: chatId, pausedUntil: pause }), now())
            .run();
        await sendTelegram(input.botToken, input.ownerChatId, `Bot paused for ${chatId} until ${pause}.`);
        return;
    }
    if (/^#resume\s+\d{5,20}$/i.test(input.text)) {
        const chatId = input.text.trim().split(/\s+/)[1] ?? "";
        const lead = await env.DB.prepare("SELECT id FROM leads WHERE tenant_id=? AND customer_phone=?")
            .bind(input.tenantId, chatId)
            .first();
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
        await env.DB.prepare("INSERT INTO audit_events (id,tenant_id,lead_id,actor,action,metadata,created_at) VALUES (?,?,?,?,?,?,?)")
            .bind(crypto.randomUUID(), input.tenantId, lead.id, "OWNER", "OWNER_TAKEOVER_DISABLED", JSON.stringify({ ownerChatId: input.ownerChatId, targetChatId: chatId }), now())
            .run();
        await sendTelegram(input.botToken, input.ownerChatId, `Bot resumed for ${chatId}.`);
        return;
    }
    if (normalized === "/status" || normalized === "status") {
        const total = await env.DB.prepare("SELECT COUNT(*) AS count FROM leads WHERE tenant_id=?")
            .bind(input.tenantId)
            .first();
        const open = await env.DB.prepare("SELECT COUNT(*) AS count FROM leads WHERE tenant_id=? AND status!='CLOSED'")
            .bind(input.tenantId)
            .first();
        const followup = await env.DB.prepare("SELECT COUNT(*) AS count FROM leads WHERE tenant_id=? AND status='FOLLOWUP_PENDING'")
            .bind(input.tenantId)
            .first();
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
        const leads = await env.DB.prepare(`SELECT customer_name,customer_phone,requirement,workflow_mode,workflow_details,status,preferred_language,updated_at
       FROM leads
       WHERE tenant_id=?
       ORDER BY updated_at DESC
       LIMIT 8`)
            .bind(input.tenantId)
            .all();
        await sendTelegram(input.botToken, input.ownerChatId, `Recent leads for ${input.businessName}\n\n${buildLeadDigest(leads.results ?? [])}`, ownerActionMarkup(leadUrl, ownerWorkspaceUrl));
        return;
    }
    if (normalized === "/priority" || normalized === "priority") {
        const leads = await env.DB.prepare(`SELECT customer_name,customer_phone,requirement,workflow_mode,workflow_details,status,preferred_language,last_inbound_at,last_outbound_at,updated_at,created_at
       FROM leads
       WHERE tenant_id=?
       ORDER BY updated_at DESC
       LIMIT 16`)
            .bind(input.tenantId)
            .all();
        const priorityQueue = buildPriorityQueue(leads.results ?? []);
        await sendTelegram(input.botToken, input.ownerChatId, `Priority queue for ${input.businessName}\n\n${buildPriorityDigest(priorityQueue)}`, ownerActionMarkup(leadUrl, ownerWorkspaceUrl));
        return;
    }
    if (normalized === "/copilot" || normalized === "copilot") {
        const total = await env.DB.prepare("SELECT COUNT(*) AS count FROM leads WHERE tenant_id=?")
            .bind(input.tenantId)
            .first();
        const open = await env.DB.prepare("SELECT COUNT(*) AS count FROM leads WHERE tenant_id=? AND status!='CLOSED'")
            .bind(input.tenantId)
            .first();
        const followup = await env.DB.prepare("SELECT COUNT(*) AS count FROM leads WHERE tenant_id=? AND status='FOLLOWUP_PENDING'")
            .bind(input.tenantId)
            .first();
        const recentLeads = await env.DB.prepare(`SELECT customer_name,customer_phone,requirement,workflow_mode,workflow_details,status,preferred_language,updated_at
       FROM leads
       WHERE tenant_id=?
       ORDER BY updated_at DESC
       LIMIT 5`)
            .bind(input.tenantId)
            .all();
        const ai = await callAiCopilot(env, {
            metadata: input.metadata,
            customerName: null,
            requirement: null,
            preferredLanguage: null,
            latestInboundText: "Owner requested workspace copilot advice.",
            transcript: (recentLeads.results ?? []).map((lead) => ({
                direction: "INBOUND",
                body: `${txt(lead.customer_name, 80) || "Unknown"} | ${txt(leadNeedSummary(lead.requirement, normalizeWorkflowMode(lead.workflow_mode) ?? "lead_capture", lead.workflow_details), 160) || "Requirement pending"} | ${formatLeadStatus(lead.status)} | ${lead.preferred_language === "hi" ? "Hindi" : "English"}`,
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
                "AI copilot is turned off for this workspace."
            ].join("\n");
        await sendTelegram(input.botToken, input.ownerChatId, body, ownerActionMarkup(leadUrl, ownerWorkspaceUrl));
        return;
    }
    const replyMatch = /^#reply\s+(\d{5,20})\s+([\s\S]{2,320})$/i.exec(input.text.trim());
    if (replyMatch) {
        const [, chatId, rawReply] = replyMatch;
        const lead = await env.DB.prepare("SELECT id FROM leads WHERE tenant_id=? AND customer_phone=?")
            .bind(input.tenantId, chatId)
            .first();
        if (!lead) {
            await sendTelegram(input.botToken, input.ownerChatId, "Lead not found for that chat id.");
            return;
        }
        const message = txt(rawReply, 320);
        const externalId = await sendTelegram(input.botToken, chatId, message);
        await env.DB.prepare("INSERT OR IGNORE INTO messages (id,tenant_id,lead_id,direction,message_type,body,external_message_id,idempotency_key,created_at) VALUES (?,?,?,'OUTBOUND','TEXT',?,?,?,?)")
            .bind(crypto.randomUUID(), input.tenantId, lead.id, message, externalId, `owner-reply:${chatId}:${Date.now()}`, now())
            .run();
        await env.DB.prepare("UPDATE leads SET last_outbound_at=?,updated_at=? WHERE id=?")
            .bind(now(), now(), lead.id)
            .run();
        await env.DB.prepare("INSERT INTO audit_events (id,tenant_id,lead_id,actor,action,metadata,created_at) VALUES (?,?,?,?,?,?,?)")
            .bind(crypto.randomUUID(), input.tenantId, lead.id, "OWNER", "OWNER_SENT_MANUAL_REPLY", JSON.stringify({ ownerChatId: input.ownerChatId, targetChatId: chatId, body: message }), now())
            .run();
        await sendTelegram(input.botToken, input.ownerChatId, `Manual reply sent to ${chatId}.`, ownerActionMarkup(leadUrl, ownerWorkspaceUrl));
        return;
    }
    if (/^#ai\s+\d{5,20}$/i.test(input.text)) {
        const chatId = input.text.trim().split(/\s+/)[1] ?? "";
        const lead = await env.DB.prepare("SELECT id,customer_name,requirement,workflow_mode,workflow_details,preferred_language FROM leads WHERE tenant_id=? AND customer_phone=?")
            .bind(input.tenantId, chatId)
            .first();
        if (!lead) {
            await sendTelegram(input.botToken, input.ownerChatId, "Lead not found for that chat id.");
            return;
        }
        const transcript = await loadLeadTranscript(env, lead.id);
        const ai = await callAiCopilot(env, {
            metadata: input.metadata,
            customerName: lead.customer_name,
            requirement: leadNeedSummary(lead.requirement, normalizeWorkflowMode(lead.workflow_mode) ?? workflowModeFromMetadata(input.metadata), lead.workflow_details),
            preferredLanguage: lead.preferred_language,
            latestInboundText: transcript.length ? txt(transcript[transcript.length - 1]?.body, 220) : "",
            transcript,
            mode: "owner_draft",
            businessName: input.businessName,
            ownerName: input.ownerName
        });
        if (!ai) {
            await sendTelegram(input.botToken, input.ownerChatId, "AI drafting is turned off for this workspace right now.", ownerActionMarkup(leadUrl, ownerWorkspaceUrl));
            return;
        }
        await sendTelegram(input.botToken, input.ownerChatId, buildAiLeadBrief(chatId, ai), ownerActionMarkup(leadUrl, ownerWorkspaceUrl));
        return;
    }
    if (normalized === "/link" || normalized === "link" || normalized === "/leadlink") {
        await sendTelegram(input.botToken, input.ownerChatId, `Share this lead link with customers:\n${leadUrl}\n\nImportant: every new customer should start from this link so Kaivor routes the chat into your workspace correctly.`, ownerActionMarkup(leadUrl, ownerWorkspaceUrl));
        return;
    }
    if (normalized === "/workspace" || normalized === "workspace") {
        await sendTelegram(input.botToken, input.ownerChatId, ownerWorkspaceUrl ? `Open your Kaivor owner console:\n${ownerWorkspaceUrl}` : "Owner console link is not ready yet.", ownerActionMarkup(leadUrl, ownerWorkspaceUrl));
        return;
    }
    await sendTelegram(input.botToken, input.ownerChatId, buildOwnerWelcomeMessage({ businessName: input.businessName, leadUrl, ownerWorkspaceUrl }), ownerActionMarkup(leadUrl, ownerWorkspaceUrl));
}
async function isAutomationAllowed(env, tenantId) {
    const row = await env.DB.prepare(`SELECT
      t.trial_ends_at,
      c.auto_reply_enabled,
      s.status,
      s.trial_ends_at AS subscription_trial_ends_at,
      (SELECT phone FROM owner_contacts WHERE tenant_id=t.id AND is_primary=1 ORDER BY created_at LIMIT 1) AS owner_phone
     FROM tenants t JOIN tenant_configs c ON c.tenant_id=t.id
     LEFT JOIN subscriptions s ON s.tenant_id=t.id
     WHERE t.id=?`)
        .bind(tenantId)
        .first();
    if (!row || !row.auto_reply_enabled)
        return false;
    if (!row.owner_phone || row.owner_phone.startsWith("pending:"))
        return false;
    if (isTrue(env.FREE_MODE, true))
        return true;
    if ((row.status ?? "TRIALING") === "ACTIVE")
        return true;
    const trial = new Date(row.subscription_trial_ends_at ?? row.trial_ends_at).getTime();
    return trial >= Date.now();
}
async function createTenantRecord(env, input) {
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
    const workflowMode = workflowModeFromLaunchFocus(launchFocusKeys);
    const trialDays = Math.max(1, Math.min(3650, Number(input.trialDays ?? 3650)));
    const trialEndsAt = new Date(Date.now() + trialDays * 24 * 60 * 60_000).toISOString();
    try {
        await env.DB.prepare(`INSERT INTO tenants (id,name,slug,whatsapp_phone_number_id,tenant_api_key_hash,trial_ends_at,created_at)
       VALUES (?,?,?,?,?,?,?)`)
            .bind(tenantId, input.name, input.slug, `telegram:${input.slug}`, tenantApiKeyHash, trialEndsAt, now())
            .run();
        await env.DB.prepare(`INSERT INTO owner_contacts (id,tenant_id,name,phone,email,is_primary,created_at) VALUES (?,?,?,?,?,1,?)`)
            .bind(crypto.randomUUID(), tenantId, input.ownerName, ownerPhone, input.ownerEmail ?? null, now())
            .run();
        await env.DB.prepare(`INSERT INTO tenant_configs (tenant_id,auto_reply_enabled,greeting_template,followup_30m_template,followup_24h_template_name,takeover_cooldown_minutes,metadata,updated_at)
       VALUES (?,1,?,?,?,?,?,?)`)
            .bind(tenantId, `Hey thanks for reaching out to ${input.name}. I will help you with this. Just a couple quick details first.`, DEFAULT_FOLLOWUP_30M_EN, "lead_followup_24h", 180, JSON.stringify((() => {
            const metadata = {
                businessName: input.name,
                webhookSecret,
                ownerPairCode,
                leadJoinCode,
                        ownerAccessToken,
                        ownerPairStatus: ownerPhone.startsWith("pending:") ? "PENDING" : "PAIRED",
                        sharedBotMode: Boolean(input.sharedBotMode),
                        launchFocusKeys,
                        workflowMode
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
        })()), now())
            .run();
        await env.DB.prepare(`INSERT INTO subscriptions (id,tenant_id,provider,status,trial_ends_at,created_at,updated_at)
       VALUES (?,?, 'POLAR',?,?,?,?)`)
            .bind(crypto.randomUUID(), tenantId, input.forceActive ? "ACTIVE" : "TRIALING", trialEndsAt, now(), now())
            .run();
        await env.DB.prepare("INSERT INTO audit_events (id,tenant_id,lead_id,actor,action,metadata,created_at) VALUES (?,?,?,?,?,?,?)")
            .bind(crypto.randomUUID(), tenantId, null, "SYSTEM", "WORKSPACE_CREATED", JSON.stringify({ sharedBotMode: Boolean(input.sharedBotMode), leadJoinCode, launchFocusKeys }), now())
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
    }
    catch (error) {
        await env.DB.prepare("DELETE FROM tenants WHERE id=?").bind(tenantId).run().catch(() => undefined);
        throw error;
    }
}
async function disconnectOwnerPairing(env, tenantId) {
    const owner = await env.DB.prepare("SELECT id,name,email FROM owner_contacts WHERE tenant_id=? AND is_primary=1 ORDER BY created_at LIMIT 1")
        .bind(tenantId)
        .first();
    const config = await env.DB.prepare("SELECT metadata FROM tenant_configs WHERE tenant_id=?")
        .bind(tenantId)
        .first();
    const tenant = await env.DB.prepare("SELECT name FROM tenants WHERE id=?")
        .bind(tenantId)
        .first();
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
    await env.DB.prepare("INSERT INTO audit_events (id,tenant_id,lead_id,actor,action,metadata,created_at) VALUES (?,?,?,?,?,?,?)")
        .bind(crypto.randomUUID(), tenantId, null, "OWNER", "OWNER_DISCONNECTED", JSON.stringify({ nextPairCode }), now())
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
async function processFollowups(env) {
    const maxRetries = Number(env.FOLLOWUP_MAX_RETRIES ?? "3");
    const jobs = await env.DB.prepare(`SELECT * FROM followup_jobs WHERE status IN ('PENDING','FAILED') AND run_at<=? ORDER BY run_at LIMIT 20`)
        .bind(now())
        .all();
    for (const job of jobs.results ?? []) {
        try {
            await env.DB.prepare(`UPDATE followup_jobs SET status='PROCESSING', attempt_count=attempt_count+1, locked_at=?, updated_at=? WHERE id=?`)
                .bind(now(), now(), job.id)
                .run();
            const lead = await env.DB.prepare("SELECT * FROM leads WHERE id=? AND tenant_id=?")
                .bind(job.lead_id, job.tenant_id)
                .first();
            const config = await env.DB.prepare("SELECT * FROM tenant_configs WHERE tenant_id=?")
                .bind(job.tenant_id)
                .first();
            if (!lead || !config)
                throw new Error("Lead or config missing");
            const owner = await env.DB.prepare("SELECT phone FROM owner_contacts WHERE tenant_id=? AND is_primary=1 ORDER BY created_at LIMIT 1")
                .bind(job.tenant_id)
                .first();
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
            if (!botToken)
                throw new Error("Missing outbound bot token");
            const language = lead.preferred_language === "hi" ? "hi" : "en";
            const configured30m = txt(config.followup_30m_template);
            const body = job.job_type === "FOLLOWUP_30M"
                ? language === "hi" && configured30m === DEFAULT_FOLLOWUP_30M_EN
                    ? DEFAULT_FOLLOWUP_30M_HI
                    : configured30m
                : language === "hi"
                    ? FOLLOWUP_24H_HI
                    : FOLLOWUP_24H_EN;
            const externalId = await sendTelegram(botToken, lead.customer_phone, body);
            await env.DB.prepare(`INSERT OR IGNORE INTO messages (id,tenant_id,lead_id,direction,message_type,body,external_message_id,idempotency_key,created_at)
         VALUES (?,?,?,'OUTBOUND','TEXT',?,?,?,?)`)
                .bind(crypto.randomUUID(), job.tenant_id, job.lead_id, body, externalId, `${job.idempotency_key}:a${job.attempt_count + 1}`, now())
                .run();
            await env.DB.prepare("UPDATE leads SET last_outbound_at=?,updated_at=? WHERE id=?").bind(now(), now(), lead.id).run();
            await env.DB.prepare("UPDATE followup_jobs SET status='SENT', last_error=NULL, updated_at=? WHERE id=?").bind(now(), job.id).run();
        }
        catch (error) {
            const err = error instanceof Error ? error.message : "Follow-up error";
            const retry = job.attempt_count + 1 >= maxRetries ? "DEAD" : "FAILED";
            await env.DB.prepare("UPDATE followup_jobs SET status=?, last_error=?, run_at=?, updated_at=? WHERE id=?")
                .bind(retry, err.slice(0, 2000), retry === "FAILED" ? plusMins(10) : now(), now(), job.id)
                .run();
        }
    }
}
async function processTenantMessage(env, tenantId, msg, source) {
    const done = await env.DB.prepare("SELECT 1 as ok FROM processed_events WHERE event_id=? AND source=?")
        .bind(msg.eventId, source)
        .first();
    if (done)
        return;
    if (!(await isAutomationAllowed(env, tenantId))) {
        await env.DB.prepare("INSERT OR IGNORE INTO processed_events (event_id,source,processed_at) VALUES (?,?,?)")
            .bind(msg.eventId, source, now())
            .run();
        return;
    }
    let lead = await env.DB.prepare("SELECT * FROM leads WHERE tenant_id=? AND customer_phone=?")
        .bind(tenantId, txt(msg.chatId, 64))
        .first();
    if (!lead) {
        const leadId = crypto.randomUUID();
        await env.DB.prepare("INSERT INTO leads (id,tenant_id,customer_phone,status,last_inbound_at,created_at,updated_at) VALUES (?,?,?,'NEW',?,?,?)")
            .bind(leadId, tenantId, txt(msg.chatId, 64), now(), now(), now())
            .run();
        lead = await env.DB.prepare("SELECT * FROM leads WHERE id=?").bind(leadId).first();
    }
    if (!lead)
        return;
    await env.DB.prepare("INSERT OR IGNORE INTO messages (id,tenant_id,lead_id,direction,message_type,body,external_message_id,idempotency_key,created_at) VALUES (?,?,?,'INBOUND','TEXT',?,?,?,?)")
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
        .first();
    if (!convo) {
        const id = crypto.randomUUID();
        await env.DB.prepare("INSERT INTO conversations (id,tenant_id,lead_id,state,last_message_at,created_at,updated_at) VALUES (?,?,?,'NEW',?,?,?)")
            .bind(id, tenantId, lead.id, now(), now(), now())
            .run();
        convo = { id, state: "NEW" };
    }
    const config = await env.DB.prepare("SELECT * FROM tenant_configs WHERE tenant_id=?")
        .bind(tenantId)
        .first();
    const metadata = parseJsonObject(config?.metadata);
    const workspaceWorkflowMode = workflowModeFromMetadata(metadata);
    const transition = computeTransition(convo.state, txt(msg.text, 500), lead.preferred_language ?? undefined, workspaceWorkflowMode);
    const nextLanguage = transition.preferredLanguage !== undefined
        ? transition.preferredLanguage
        : lead.preferred_language;
    const nextName = transition.clearLeadProfile
        ? transition.customerName ?? null
        : transition.customerName ?? lead.customer_name;
    const nextRequirement = transition.clearLeadProfile
        ? transition.requirement ?? null
        : transition.requirement ?? lead.requirement;
    const nextWorkflowMode = transition.clearLeadProfile
        ? transition.workflowMode ?? workspaceWorkflowMode
        : transition.workflowMode ?? normalizeWorkflowMode(lead.workflow_mode) ?? workspaceWorkflowMode;
    const nextWorkflowDetails = transition.clearLeadProfile
        ? transition.workflowDetails ?? null
        : transition.workflowDetails ?? lead.workflow_details;
    const replyLanguage = transition.preferredLanguage !== undefined
        ? transition.preferredLanguage ?? undefined
        : lead.preferred_language ?? undefined;
    await env.DB.prepare("UPDATE leads SET customer_name=?, preferred_language=?, requirement=?, workflow_mode=?, workflow_details=?, status=?, updated_at=? WHERE id=?")
        .bind(nextName, nextLanguage, nextRequirement, nextWorkflowMode, nextWorkflowDetails, transition.nextLeadStatus, now(), lead.id)
        .run();
    await env.DB.prepare("UPDATE conversations SET state=?, last_message_at=?, updated_at=? WHERE id=?")
        .bind(transition.nextState, now(), now(), convo.id)
        .run();
    const botToken = resolveOutboundBotToken(metadata, env);
    if (!botToken) {
        await env.DB.prepare("INSERT OR IGNORE INTO processed_events (event_id,source,processed_at) VALUES (?,?,?)")
            .bind(msg.eventId, source, now())
            .run();
        return;
    }
    const defaultReply = transition.shouldReply && config
        ? buildAutoReply({
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
        })
        : "";
    const aiEligible = Boolean(config) && (transition.shouldReply || transition.shouldNotifyOwner);
    const transcript = aiEligible ? await loadLeadTranscript(env, lead.id) : [];
    const aiRequirement = leadNeedSummary(nextRequirement, nextWorkflowMode, nextWorkflowDetails);
    const aiCopilot = aiEligible
        ? await callAiCopilot(env, {
            metadata,
            customerName: nextName,
            requirement: aiRequirement || nextRequirement,
            preferredLanguage: nextLanguage,
            latestInboundText: txt(msg.text, 280),
            transcript,
            mode: transition.shouldReply ? "customer_reply" : "owner_draft",
            businessName: txt(metadata.businessName, 120) || tenantId,
            defaultReply,
            replyKey: transition.replyKey
        })
        : null;
    if (transition.shouldReply && config) {
        const reply = aiCopilot?.reply ? txt(aiCopilot.reply, 420) : defaultReply;
        const ext = await sendTelegram(botToken, lead.customer_phone, reply, telegramReplyOptionsForKey(transition.replyKey));
        await env.DB.prepare("INSERT OR IGNORE INTO messages (id,tenant_id,lead_id,direction,message_type,body,external_message_id,idempotency_key,created_at) VALUES (?,?,?,'OUTBOUND','TEXT',?,?,?,?)")
            .bind(crypto.randomUUID(), tenantId, lead.id, reply, ext, `outbound:reply:${source}:${msg.eventId}`, now())
            .run();
        await env.DB.prepare("UPDATE leads SET last_outbound_at=?,updated_at=? WHERE id=?").bind(now(), now(), lead.id).run();
    }
    if (transition.shouldScheduleFollowups) {
        await env.DB.prepare("INSERT OR IGNORE INTO followup_jobs (id,tenant_id,lead_id,job_type,run_at,status,attempt_count,idempotency_key,created_at,updated_at) VALUES (?,?,?,'FOLLOWUP_30M',?,'PENDING',0,?,?,?)")
            .bind(crypto.randomUUID(), tenantId, lead.id, plusMins(30), `${tenantId}:${lead.id}:FOLLOWUP_30M`, now(), now())
            .run();
        await env.DB.prepare("INSERT OR IGNORE INTO followup_jobs (id,tenant_id,lead_id,job_type,run_at,status,attempt_count,idempotency_key,created_at,updated_at) VALUES (?,?,?,'FOLLOWUP_24H',?,'PENDING',0,?,?,?)")
            .bind(crypto.randomUUID(), tenantId, lead.id, plusHours(24), `${tenantId}:${lead.id}:FOLLOWUP_24H`, now(), now())
            .run();
    }
    if (transition.shouldNotifyOwner && config) {
        await env.DB.prepare("INSERT INTO audit_events (id,tenant_id,lead_id,actor,action,metadata,created_at) VALUES (?,?,?,?,?,?,?)")
            .bind(crypto.randomUUID(), tenantId, lead.id, "SYSTEM", "LEAD_CAPTURED", JSON.stringify({
            customerName: nextName,
            requirement: nextRequirement,
            workflowMode: nextWorkflowMode,
            workflowDetails: nextWorkflowDetails,
            language: nextLanguage ?? "en"
        }), now())
            .run();
        const updated = await env.DB.prepare("SELECT * FROM leads WHERE id=?")
            .bind(lead.id)
            .first();
        const metadataBusinessName = txt(metadata.businessName, 120);
        const summary = buildOwnerLeadSummary({
            businessName: metadataBusinessName || "your business",
            customerName: updated?.customer_name ?? "Not provided",
            customerPhone: updated?.customer_phone ?? lead.customer_phone,
            requirement: updated?.requirement ?? "Not provided",
            workflowMode: normalizeWorkflowMode(updated?.workflow_mode) ?? nextWorkflowMode,
            workflowDetails: updated?.workflow_details ?? nextWorkflowDetails,
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
    }
    else if (transition.replyKey === "ACK" && aiCopilot?.ownerShouldTakeover) {
        await env.DB.prepare("INSERT INTO audit_events (id,tenant_id,lead_id,actor,action,metadata,created_at) VALUES (?,?,?,?,?,?,?)")
            .bind(crypto.randomUUID(), tenantId, lead.id, "SYSTEM", "AI_TAKEOVER_RECOMMENDED", JSON.stringify({
            customerName: nextName,
            requirement: nextRequirement,
            intentLabel: aiCopilot.intentLabel,
            leadTemperature: aiCopilot.leadTemperature
        }), now())
            .run();
        await notifyOwners(env, {
            tenantId,
            leadId: lead.id,
            botToken,
            metadata,
            body: [
                `Hot lead activity for ${txt(metadata.businessName, 120) || "your business"}.`,
                `Chat: ${lead.customer_phone}`,
                aiRequirement ? `Lead context: ${aiRequirement}` : "",
                `Intent: ${aiCopilot.intentLabel}`,
                `Temperature: ${capitalize(aiCopilot.leadTemperature)}`,
                `AI brief: ${aiCopilot.ownerSummary}`,
                "",
                `Suggested move: #takeover ${lead.customer_phone}`
            ].filter(Boolean).join("\n")
        });
    }
    await env.DB.prepare("INSERT OR IGNORE INTO processed_events (event_id,source,processed_at) VALUES (?,?,?)")
        .bind(msg.eventId, source, now())
        .run();
}
export default {
    async fetch(request, env) {
        const url = new URL(request.url);
        const path = url.pathname.replace(/\/+$/, "") || "/";
        try {
            if (request.method === "GET" && path === "/") {
                return html(renderLandingPage(env.LANDING_CTA_URL ?? "https://t.me"));
            }
            if (request.method === "GET" && path === "/health")
                return j({ ok: true, service: "kaivor-cf" });
            const requiresDb = path !== "/" && !(request.method === "OPTIONS" && path.startsWith("/public/"));
            if (requiresDb) {
                await ensureCompatibilitySchema(env);
            }
            if (request.method === "OPTIONS" && path.startsWith("/public/")) {
                return new Response(null, { status: 204, headers: publicCorsHeaders });
            }
            if (request.method === "POST" && path === "/public/auth/signup") {
                const body = (await request.json().catch(() => null));
                if (!body || typeof body !== "object" || Array.isArray(body))
                    return jPublic({ error: "Invalid JSON" }, 400);
                const name = txt(body.name, 120);
                const email = normalizeEmail(body.email);
                const password = txt(body.password, 200);
                if (!name || !email || !password)
                    return jPublic({ error: "Missing required fields" }, 400);
                if (password.length < 8)
                    return jPublic({ error: "Password must be at least 8 characters" }, 400);
                await ensureAuthTables(env);
                const existing = await env.DB.prepare("SELECT id FROM app_users WHERE email=?")
                    .bind(email)
                    .first();
                if (existing)
                    return jPublic({ error: "Account already exists" }, 409);
                const userId = crypto.randomUUID();
                await env.DB.prepare("INSERT INTO app_users (id,name,email,password_hash,created_at,updated_at) VALUES (?,?,?,?,?,?)")
                    .bind(userId, name, email, await hashWithSalt(password), now(), now())
                    .run();
                const session = await createSessionForUser(env, userId);
                return jPublic({
                    ok: true,
                    token: session.token,
                    expiresAt: session.expiresAt,
                    user: { id: userId, name, email },
                    tenants: await listAuthenticatedTenants(env, email)
                }, 201);
            }
            if (request.method === "POST" && path === "/public/auth/login") {
                const body = (await request.json().catch(() => null));
                if (!body || typeof body !== "object" || Array.isArray(body))
                    return jPublic({ error: "Invalid JSON" }, 400);
                const email = normalizeEmail(body.email);
                const password = txt(body.password, 200);
                if (!email || !password)
                    return jPublic({ error: "Missing required fields" }, 400);
                await ensureAuthTables(env);
                const user = await env.DB.prepare("SELECT id,name,email,password_hash FROM app_users WHERE email=?")
                    .bind(email)
                    .first();
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
                if (!user)
                    return jPublic({ error: "Unauthorized" }, 401);
                return jPublic({
                    ok: true,
                    user,
                    tenants: await listAuthenticatedTenants(env, user.email)
                });
            }
            if (request.method === "GET" && path === "/public/auth/tenants") {
                const user = await resolveAuthenticatedUser(env, request);
                if (!user)
                    return jPublic({ error: "Unauthorized" }, 401);
                return jPublic({
                    ok: true,
                    user,
                    tenants: await listAuthenticatedTenants(env, user.email)
                });
            }
            if (request.method === "POST" && /^\/public\/auth\/tenants\/[a-z0-9-]+\/disconnect-owner$/i.test(path)) {
                const user = await resolveAuthenticatedUser(env, request);
                if (!user)
                    return jPublic({ error: "Unauthorized" }, 401);
                const tenantId = path.split("/")[4] ?? "";
                if (!tenantId)
                    return jPublic({ error: "Missing tenant id" }, 400);
                if (!(await userOwnsTenant(env, tenantId, user.email)))
                    return jPublic({ error: "Forbidden" }, 403);
                const disconnected = await disconnectOwnerPairing(env, tenantId);
                return jPublic({ ok: true, tenant: disconnected });
            }
            if (request.method === "GET" && isStartStatusPath(path)) {
                const tenantId = path.split("/")[3] ?? "";
                if (!tenantId)
                    return jPublic({ error: "Missing tenant id" }, 400);
                const owner = await env.DB.prepare("SELECT phone FROM owner_contacts WHERE tenant_id=? AND is_primary=1 ORDER BY created_at LIMIT 1")
                    .bind(tenantId)
                    .first();
                if (!owner)
                    return jPublic({ error: "Tenant not found" }, 404);
                const config = await env.DB.prepare("SELECT metadata FROM tenant_configs WHERE tenant_id=?")
                    .bind(tenantId)
                    .first();
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
                if (!tenantId || !token)
                    return jPublic({ error: "Unauthorized" }, 401);
                const access = await loadWorkspaceAccess(env, tenantId, token);
                if (!access)
                    return jPublic({ error: "Unauthorized" }, 401);
                const profile = workspaceProfileFromMetadata(access.metadata);
                const owner = await env.DB.prepare("SELECT name,phone,email FROM owner_contacts WHERE tenant_id=? AND is_primary=1 ORDER BY created_at LIMIT 1")
                    .bind(tenantId)
                    .first();
                const total = await env.DB.prepare("SELECT COUNT(*) AS count FROM leads WHERE tenant_id=?")
                    .bind(tenantId)
                    .first();
                const open = await env.DB.prepare("SELECT COUNT(*) AS count FROM leads WHERE tenant_id=? AND status!='CLOSED'")
                    .bind(tenantId)
                    .first();
                const followup = await env.DB.prepare("SELECT COUNT(*) AS count FROM leads WHERE tenant_id=? AND status='FOLLOWUP_PENDING'")
                    .bind(tenantId)
                    .first();
                const recentLeads = await env.DB.prepare(`SELECT customer_name,customer_phone,requirement,workflow_mode,workflow_details,status,preferred_language,last_inbound_at,last_outbound_at,updated_at,created_at
         FROM leads
         WHERE tenant_id=?
         ORDER BY updated_at DESC
         LIMIT 20`)
                    .bind(tenantId)
                    .all();
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
                        workflowMode: normalizeWorkflowMode(lead.workflow_mode) ?? profile.workflowMode,
                        workflowLabel: workflowModeCatalog[normalizeWorkflowMode(lead.workflow_mode) ?? profile.workflowMode].label,
                        workflowDetails: lead.workflow_details,
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
                const body = (await request.json().catch(() => null));
                if (!body || typeof body !== "object" || Array.isArray(body))
                    return jPublic({ error: "Invalid JSON" }, 400);
                const name = txt(body.name, 120);
                const businessName = txt(body.businessName ?? body.business, 120);
                const email = body.email ? txt(body.email, 200) : null;
                if (!name || !businessName) {
                    return jPublic({
                        error: "Missing required fields",
                        missing: {
                            name: !name,
                            businessName: !businessName
                        }
                    }, 400);
                }
                await ensureWaitlistTable(env);
                await env.DB.prepare("INSERT INTO waitlist_signups (id,name,business_name,email,created_at) VALUES (?,?,?,?,?)")
                    .bind(crypto.randomUUID(), name, businessName, email, now())
                    .run();
                return jPublic({
                    ok: true,
                    message: "You are on the Kaivor waitlist. We will send product updates as we ship them."
                }, 201);
            }
            if (request.method === "POST" && isStartCreatePath(path)) {
                if (!isTrue(env.PUBLIC_SIGNUP_ENABLED, true)) {
                    return jPublic({ error: "Public signup is disabled" }, 403);
                }
                const body = (await request.json().catch(() => null));
                if (!body || typeof body !== "object" || Array.isArray(body))
                    return jPublic({ error: "Invalid JSON" }, 400);
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
                    return jPublic({
                        error: "Missing required fields",
                        missing: {
                            businessName: !name,
                            ownerName: !ownerName
                        },
                        receivedKeys: Object.keys(body)
                    }, 400);
                }
                const sharedBotMode = !telegramBotToken;
                if (sharedBotMode && !txt(env.OWNER_CONNECT_BOT_TOKEN, 220)) {
                    return jPublic({ error: "Shared bot is not configured on server" }, 503);
                }
                if (ownerEmail) {
                    const existing = await findExistingTenantForOwner(env, name, ownerEmail);
                    if (existing) {
                        const summary = summarizeTenantAccess(env, existing);
                        return jPublic({
                            ok: true,
                            existing: true,
                            free: true,
                            ...summary,
                            statusUrl: `${url.origin}/public/start/${existing.tenantId}/status`,
                            nextStep: summary.ownerConnected
                                ? "This Kaivor setup is already paired. Open your owner console or share the lead link."
                                : "This Kaivor setup already exists. Open @kaivorbot and finish pairing."
                        }, 200);
                    }
                }
                let botUsername = "";
                if (telegramBotToken) {
                    try {
                        const profile = await getBotProfile(telegramBotToken);
                        botUsername = String(profile.username ?? "");
                    }
                    catch {
                        return jPublic({ error: "Invalid Telegram Bot Token" }, 400);
                    }
                }
                else {
                    botUsername = ownerConnectBotUsername(env);
                }
                if (!botUsername) {
                    return jPublic({ error: "Bot username not found on Telegram" }, 400);
                }
                const slugBase = txt(body.slug ?? name, 64) || name;
                let created = null;
                let lastCreateError = null;
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
                    }
                    catch (error) {
                        if (!isSlugConflict(error))
                            throw error;
                        lastCreateError = error;
                    }
                }
                if (!created) {
                    return jPublic({
                        error: "Could not start Kaivor right now",
                        detail: lastCreateError instanceof Error ? lastCreateError.message : "Slug collision retry limit reached"
                    }, 409);
                }
                const webhookUrl = sharedBotMode
                    ? `${url.origin}/webhooks/owner-connect`
                    : `${url.origin}/webhooks/telegram/${created.tenantId}`;
                if (!sharedBotMode) {
                    try {
                        await setTelegramWebhook(telegramBotToken, webhookUrl, created.webhookSecret);
                    }
                    catch (error) {
                        await env.DB.prepare("DELETE FROM tenants WHERE id=?").bind(created.tenantId).run();
                        return jPublic({
                            error: "Could not configure Telegram webhook",
                            detail: error instanceof Error ? error.message : "Unknown Telegram error"
                        }, 502);
                    }
                }
                return jPublic({
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
                        ? "Kaivor is paired. Share your lead link and start receiving leads."
                        : "Open @kaivorbot, send the pairing code, and Kaivor will activate."
                }, 201);
            }
            if (request.method === "POST" && path === "/admin/tenants") {
                if (request.headers.get("x-master-api-key") !== env.MASTER_API_KEY)
                    return j({ error: "Unauthorized" }, 401);
                const body = (await request.json().catch(() => null));
                if (!body)
                    return j({ error: "Invalid JSON" }, 400);
                const name = txt(body.name, 120);
                const slug = txt(body.slug, 64).toLowerCase();
                const ownerName = txt(body.ownerName, 120);
                const ownerChatId = txt(body.ownerChatId, 64);
                const telegramBotToken = txt(body.telegramBotToken, 200);
                const ownerEmail = body.ownerEmail ? txt(body.ownerEmail, 200) : null;
                if (!name || !slug || !ownerName || !ownerChatId || !telegramBotToken)
                    return j({ error: "Missing fields" }, 400);
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
                if (!payload)
                    return j({ error: "Invalid JSON" }, 400);
                const inbound = parseTelegramWebhook(payload);
                const ownerConnectBotToken = txt(env.OWNER_CONNECT_BOT_TOKEN, 220);
                for (const msg of inbound) {
                    const done = await env.DB.prepare("SELECT 1 as ok FROM processed_events WHERE event_id=? AND source='TELEGRAM_OWNER_CONNECT'")
                        .bind(msg.eventId)
                        .first();
                    if (done)
                        continue;
                    const pairCode = parseOwnerPairCodeFromText(msg.text);
                    if (pairCode) {
                        let reply = "Invalid code. Open Kaivor onboarding and copy the latest pairing code.";
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
                                await sendTelegram(ownerConnectBotToken, txt(msg.chatId, 64), reply, ownerActionMarkup(leadUrl, ownerWorkspaceUrl)).catch(() => { });
                            }
                        }
                        if (ownerConnectBotToken && connected.status === "INVALID_CODE") {
                            await sendTelegram(ownerConnectBotToken, txt(msg.chatId, 64), reply).catch(() => { });
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
                let tenantId = null;
                if (joinCode) {
                    tenantId = await resolveTenantIdByLeadJoinCode(env, joinCode);
                    if (tenantId) {
                        await bindChatRoute(env, {
                            chatId: txt(msg.chatId, 64),
                            source: "TELEGRAM_SHARED",
                            tenantId,
                            leadJoinCode: joinCode,
                            boundVia: "LEAD_LINK"
                        });
                    }
                }
                else {
                    tenantId = await resolveBoundTenantIdByChat(env, txt(msg.chatId, 64), "TELEGRAM_SHARED");
                    if (!tenantId) {
                        tenantId = await resolveLatestTenantIdByChat(env, txt(msg.chatId, 64));
                        if (tenantId) {
                            await bindChatRoute(env, {
                                chatId: txt(msg.chatId, 64),
                                source: "TELEGRAM_SHARED",
                                tenantId,
                                boundVia: "LEGACY_HISTORY"
                            });
                        }
                    }
                }
                    if (!tenantId) {
                        if (ownerConnectBotToken) {
                            await sendTelegram(ownerConnectBotToken, txt(msg.chatId, 64), "This chat is not linked yet.\n\nIf you own a workspace, send your Kaivor pairing code.\nIf you are a customer, ask the business for their Kaivor lead link.").catch(() => { });
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
                if (!tenantId)
                    return j({ error: "Missing tenant id" }, 400);
                const tenant = await env.DB.prepare("SELECT * FROM tenants WHERE id=?").bind(tenantId).first();
                if (!tenant)
                    return j({ error: "Tenant not found" }, 404);
                const tenantConfig = await env.DB.prepare("SELECT metadata FROM tenant_configs WHERE tenant_id=?")
                    .bind(tenantId)
                    .first();
                const metadata = parseJsonObject(tenantConfig?.metadata);
                const expectedSecret = String(metadata.webhookSecret ?? env.TELEGRAM_WEBHOOK_SECRET ?? "");
                if (!verifyTelegramSecret(request.headers.get("x-telegram-bot-api-secret-token") ?? undefined, expectedSecret)) {
                    return j({ error: "Invalid Telegram secret" }, 401);
                }
                const payload = await request.json().catch(() => null);
                if (!payload)
                    return j({ error: "Invalid JSON" }, 400);
                const inbound = parseTelegramWebhook(payload);
                for (const msg of inbound) {
                    await bindChatRoute(env, {
                        chatId: txt(msg.chatId, 64),
                        source: "TELEGRAM",
                        tenantId,
                        boundVia: "DIRECT_WEBHOOK"
                    });
                    await processTenantMessage(env, tenantId, msg, "TELEGRAM");
                }
                return j({ ok: true });
            }
            if (request.method === "POST" && path.startsWith("/admin/tenants/") && path.endsWith("/config")) {
                const tenantId = path.split("/")[3] ?? "";
                if (!tenantId)
                    return j({ error: "Missing tenant id" }, 400);
                const master = request.headers.get("x-master-api-key") === env.MASTER_API_KEY;
                const tenantKey = request.headers.get("x-tenant-api-key");
                let tenantAuthorized = false;
                if (tenantKey) {
                    const tenant = await env.DB.prepare("SELECT tenant_api_key_hash FROM tenants WHERE id=?").bind(tenantId).first();
                    if (tenant) {
                        const [salt] = tenant.tenant_api_key_hash.split(":");
                        tenantAuthorized = tenant.tenant_api_key_hash === `${salt}:${await sha256(`${salt}:${tenantKey}`)}`;
                    }
                }
                if (!master && !tenantAuthorized)
                    return j({ error: "Unauthorized" }, 401);
                const body = (await request.json().catch(() => null));
                if (!body)
                    return j({ error: "Invalid JSON" }, 400);
                const existing = await env.DB.prepare("SELECT * FROM tenant_configs WHERE tenant_id=?")
                    .bind(tenantId)
                    .first();
                if (!existing)
                    return j({ error: "Tenant config missing" }, 404);
                const metadata = parseJsonObject(existing.metadata);
                if (body.telegramBotToken)
                    metadata.telegramBotToken = txt(body.telegramBotToken, 200);
                if (body.businessName)
                    metadata.businessName = txt(body.businessName, 120);
                if (body.webhookSecret)
                    metadata.webhookSecret = txt(body.webhookSecret, 120);
                await env.DB.prepare("UPDATE tenant_configs SET auto_reply_enabled=?, greeting_template=?, followup_30m_template=?, followup_24h_template_name=?, takeover_cooldown_minutes=?, metadata=?, updated_at=? WHERE tenant_id=?")
                    .bind(body.autoReplyEnabled !== undefined ? (body.autoReplyEnabled ? 1 : 0) : existing.auto_reply_enabled, body.greetingTemplate ? txt(body.greetingTemplate) : existing.greeting_template, body.followup30mTemplate ? txt(body.followup30mTemplate) : existing.followup_30m_template, body.followup24hTemplateName ? txt(body.followup24hTemplateName, 100) : existing.followup_24h_template_name, body.takeoverCooldownMinutes ? Math.max(5, Math.min(1440, Number(body.takeoverCooldownMinutes))) : existing.takeover_cooldown_minutes, JSON.stringify(metadata), now(), tenantId)
                    .run();
                return j({ ok: true });
            }
            if (request.method === "POST" && path === "/internal/takeover") {
                const body = (await request.json().catch(() => null));
                if (!body)
                    return j({ error: "Invalid JSON" }, 400);
                const tenantId = txt(body.tenantId, 64);
                const command = txt(body.command, 80);
                const match = /^#takeover\s+(\d{5,20})$/i.exec(command);
                if (!match)
                    return j({ error: "Command must be '#takeover <chat_id>'" }, 400);
                const chatId = match[1];
                const master = request.headers.get("x-master-api-key") === env.MASTER_API_KEY;
                if (!master)
                    return j({ error: "Unauthorized" }, 401);
                const lead = await env.DB.prepare("SELECT id FROM leads WHERE tenant_id=? AND customer_phone=?").bind(tenantId, chatId).first();
                if (!lead)
                    return j({ error: "Lead not found" }, 404);
                const cfg = await env.DB.prepare("SELECT takeover_cooldown_minutes FROM tenant_configs WHERE tenant_id=?").bind(tenantId).first();
                const pause = plusMins(Math.max(5, Math.min(1440, Number(cfg?.takeover_cooldown_minutes ?? 180))));
                await env.DB.prepare("UPDATE leads SET status='OWNER_TAKEOVER', bot_paused_until=?, updated_at=? WHERE id=?").bind(pause, now(), lead.id).run();
                await env.DB.prepare("UPDATE conversations SET state='OWNER_TAKEOVER', updated_at=? WHERE lead_id=?").bind(now(), lead.id).run();
                return j({ ok: true, leadId: lead.id, pausedUntil: pause });
            }
            if (request.method === "POST" && path === "/internal/run-followups") {
                if (request.headers.get("x-master-api-key") !== env.MASTER_API_KEY)
                    return j({ error: "Unauthorized" }, 401);
                await processFollowups(env);
                return j({ ok: true });
            }
            return j({ error: "Not found" }, 404);
        }
        catch (error) {
            console.error("kaivor-worker-error", { path, detail: errorDetail(error) });
            return errorResponse(path, error);
        }
    },
    async scheduled(_event, env, ctx) {
        ctx.waitUntil(processFollowups(env));
    }
};
