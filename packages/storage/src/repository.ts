import crypto from "crypto";
import { randomUUID } from "crypto";
import type { PoolClient } from "pg";
import { Database } from "./db.js";
import type {
  AuditEventInput,
  Conversation,
  ConversationState,
  CreateTenantInput,
  FollowupJob,
  FollowupJobType,
  InboundMessageInput,
  Lead,
  LeadRepository,
  NotificationRecordInput,
  OutboundMessageInput,
  OwnerContact,
  SubscriptionUpsertInput,
  Tenant,
  TenantConfig,
  UpsertTenantConfigInput
} from "./types.js";

const DEFAULT_GREETING =
  "Namaste! BharatClaw se bol raha hoon. Aapka naam bata do please?";
const DEFAULT_FOLLOWUP_30M =
  "Quick follow-up. Kya aapko abhi bhi help chahiye? Reply karke bata do.";
const DEFAULT_FOLLOWUP_24H_TEMPLATE = "lead_followup_24h";
const DEFAULT_TAKEOVER_COOLDOWN_MINUTES = 180;

interface HashParts {
  salt: string;
  hash: string;
}

function normalizePhone(phone: string): string {
  return phone.replace(/\D/g, "");
}

function hashTenantApiKey(plaintext: string, salt?: string): string {
  const actualSalt = salt ?? crypto.randomBytes(16).toString("hex");
  const hash = crypto.createHash("sha256").update(`${actualSalt}:${plaintext}`).digest("hex");
  return `${actualSalt}:${hash}`;
}

function splitHash(encoded: string): HashParts {
  const [salt, hash] = encoded.split(":");
  if (!salt || !hash) {
    throw new Error("Invalid API key hash format");
  }
  return { salt, hash };
}

function mapTenant(row: Record<string, unknown>): Tenant {
  return {
    id: String(row.id),
    name: String(row.name),
    slug: String(row.slug),
    whatsappPhoneNumberId: String(row.whatsapp_phone_number_id),
    tenantApiKeyHash: String(row.tenant_api_key_hash),
    trialEndsAt: new Date(String(row.trial_ends_at)),
    createdAt: new Date(String(row.created_at))
  };
}

function mapTenantConfig(row: Record<string, unknown>): TenantConfig {
  return {
    tenantId: String(row.tenant_id),
    autoReplyEnabled: Boolean(row.auto_reply_enabled),
    greetingTemplate: String(row.greeting_template),
    followup30mTemplate: String(row.followup_30m_template),
    followup24hTemplateName: String(row.followup_24h_template_name),
    takeoverCooldownMinutes: Number(row.takeover_cooldown_minutes),
    metadata: (row.metadata as Record<string, unknown>) ?? {},
    updatedAt: new Date(String(row.updated_at))
  };
}

function mapOwner(row: Record<string, unknown>): OwnerContact {
  return {
    id: String(row.id),
    tenantId: String(row.tenant_id),
    name: String(row.name),
    phone: String(row.phone),
    email: row.email ? String(row.email) : undefined,
    isPrimary: Boolean(row.is_primary),
    createdAt: new Date(String(row.created_at))
  };
}

function mapLead(row: Record<string, unknown>): Lead {
  return {
    id: String(row.id),
    tenantId: String(row.tenant_id),
    customerPhone: String(row.customer_phone),
    customerName: row.customer_name ? String(row.customer_name) : undefined,
    requirement: row.requirement ? String(row.requirement) : undefined,
    status: String(row.status) as Lead["status"],
    botPausedUntil: row.bot_paused_until ? new Date(String(row.bot_paused_until)) : undefined,
    lastInboundAt: row.last_inbound_at ? new Date(String(row.last_inbound_at)) : undefined,
    lastOutboundAt: row.last_outbound_at ? new Date(String(row.last_outbound_at)) : undefined,
    createdAt: new Date(String(row.created_at)),
    updatedAt: new Date(String(row.updated_at))
  };
}

function mapConversation(row: Record<string, unknown>): Conversation {
  return {
    id: String(row.id),
    tenantId: String(row.tenant_id),
    leadId: String(row.lead_id),
    state: String(row.state) as ConversationState,
    lastMessageAt: new Date(String(row.last_message_at)),
    createdAt: new Date(String(row.created_at)),
    updatedAt: new Date(String(row.updated_at))
  };
}

function mapFollowupJob(row: Record<string, unknown>): FollowupJob {
  return {
    id: String(row.id),
    tenantId: String(row.tenant_id),
    leadId: String(row.lead_id),
    jobType: String(row.job_type) as FollowupJobType,
    runAt: new Date(String(row.run_at)),
    status: String(row.status) as FollowupJob["status"],
    attemptCount: Number(row.attempt_count),
    lastError: row.last_error ? String(row.last_error) : undefined,
    lockedAt: row.locked_at ? new Date(String(row.locked_at)) : undefined,
    idempotencyKey: String(row.idempotency_key),
    createdAt: new Date(String(row.created_at)),
    updatedAt: new Date(String(row.updated_at))
  };
}

export class PostgresLeadRepository implements LeadRepository {
  constructor(private readonly db: Database) {}

  public async createTenant(input: CreateTenantInput): Promise<{ tenant: Tenant; tenantApiKey: string }> {
    const tenantApiKey = `tnt_${crypto.randomBytes(24).toString("hex")}`;
    const tenantApiKeyHash = hashTenantApiKey(tenantApiKey);
    const tenantId = randomUUID();
    const ownerId = randomUUID();
    const trialDays = input.trialDays ?? 7;
    const trialEndsAt = new Date(Date.now() + trialDays * 24 * 60 * 60 * 1000);
    const channelIdentifier = `telegram:${input.slug}`;

    const tenant = await this.db.transaction(async (client) => {
      await client.query(
        `INSERT INTO tenants (id, name, slug, whatsapp_phone_number_id, tenant_api_key_hash, trial_ends_at)
         VALUES ($1, $2, $3, $4, $5, $6)`,
        [tenantId, input.name, input.slug, channelIdentifier, tenantApiKeyHash, trialEndsAt]
      );

      await client.query(
        `INSERT INTO owner_contacts (id, tenant_id, name, phone, email, is_primary)
         VALUES ($1, $2, $3, $4, $5, true)`,
        [ownerId, tenantId, input.ownerName, input.ownerChatId, input.ownerEmail ?? null]
      );

      await client.query(
        `INSERT INTO tenant_configs (
            tenant_id,
            auto_reply_enabled,
            greeting_template,
            followup_30m_template,
            followup_24h_template_name,
            takeover_cooldown_minutes,
            metadata
          ) VALUES ($1, true, $2, $3, $4, $5, '{}'::jsonb)`,
        [
          tenantId,
          DEFAULT_GREETING,
          DEFAULT_FOLLOWUP_30M,
          DEFAULT_FOLLOWUP_24H_TEMPLATE,
          DEFAULT_TAKEOVER_COOLDOWN_MINUTES
        ]
      );
      await client.query("UPDATE tenant_configs SET metadata = $2::jsonb WHERE tenant_id = $1", [
        tenantId,
        JSON.stringify({ telegramBotToken: input.telegramBotToken })
      ]);

      await client.query(
        `INSERT INTO subscriptions (
            id, tenant_id, provider, status, trial_ends_at
          ) VALUES ($1, $2, 'POLAR', 'TRIALING', $3)`,
        [randomUUID(), tenantId, trialEndsAt]
      );

      const tenantResult = await client.query("SELECT * FROM tenants WHERE id = $1", [tenantId]);
      return mapTenant(tenantResult.rows[0] as Record<string, unknown>);
    });

    return { tenant, tenantApiKey };
  }

  public async getTenantById(tenantId: string): Promise<Tenant | null> {
    const result = await this.db.query("SELECT * FROM tenants WHERE id = $1", [tenantId]);
    return result.rowCount ? mapTenant(result.rows[0] as Record<string, unknown>) : null;
  }

  public async getTenantByWhatsappPhoneNumberId(phoneNumberId: string): Promise<Tenant | null> {
    const result = await this.db.query("SELECT * FROM tenants WHERE whatsapp_phone_number_id = $1", [
      phoneNumberId
    ]);
    return result.rowCount ? mapTenant(result.rows[0] as Record<string, unknown>) : null;
  }

  public async verifyTenantApiKey(tenantId: string, plaintextApiKey: string): Promise<boolean> {
    const tenant = await this.getTenantById(tenantId);
    if (!tenant) {
      return false;
    }
    const parts = splitHash(tenant.tenantApiKeyHash);
    const computed = hashTenantApiKey(plaintextApiKey, parts.salt);
    const expectedBuffer = Buffer.from(tenant.tenantApiKeyHash);
    const actualBuffer = Buffer.from(computed);
    if (expectedBuffer.length !== actualBuffer.length) {
      return false;
    }
    return crypto.timingSafeEqual(expectedBuffer, actualBuffer);
  }

  public async upsertTenantConfig(input: UpsertTenantConfigInput): Promise<TenantConfig> {
    const existing = await this.getTenantConfig(input.tenantId);
    const mergedMetadata = {
      ...(existing.metadata ?? {}),
      ...(input.metadata ?? {})
    };
    if (input.telegramBotToken) {
      mergedMetadata.telegramBotToken = input.telegramBotToken;
    }

    const merged = {
      autoReplyEnabled: input.autoReplyEnabled ?? existing.autoReplyEnabled,
      greetingTemplate: input.greetingTemplate ?? existing.greetingTemplate,
      followup30mTemplate: input.followup30mTemplate ?? existing.followup30mTemplate,
      followup24hTemplateName: input.followup24hTemplateName ?? existing.followup24hTemplateName,
      takeoverCooldownMinutes: input.takeoverCooldownMinutes ?? existing.takeoverCooldownMinutes,
      metadata: mergedMetadata
    };

    const configResult = await this.db.query(
      `INSERT INTO tenant_configs (
         tenant_id,
         auto_reply_enabled,
         greeting_template,
         followup_30m_template,
         followup_24h_template_name,
         takeover_cooldown_minutes,
         metadata
       ) VALUES ($1, $2, $3, $4, $5, $6, $7::jsonb)
       ON CONFLICT (tenant_id)
       DO UPDATE SET
         auto_reply_enabled = EXCLUDED.auto_reply_enabled,
         greeting_template = EXCLUDED.greeting_template,
         followup_30m_template = EXCLUDED.followup_30m_template,
         followup_24h_template_name = EXCLUDED.followup_24h_template_name,
         takeover_cooldown_minutes = EXCLUDED.takeover_cooldown_minutes,
         metadata = EXCLUDED.metadata,
         updated_at = NOW()
       RETURNING *`,
      [
        input.tenantId,
        merged.autoReplyEnabled,
        merged.greetingTemplate,
        merged.followup30mTemplate,
        merged.followup24hTemplateName,
        merged.takeoverCooldownMinutes,
        JSON.stringify(merged.metadata)
      ]
    );

    if (input.ownerChatId || input.ownerName || input.ownerEmail) {
      await this.upsertPrimaryOwner({
        tenantId: input.tenantId,
        name: input.ownerName ?? "Owner",
        phone: input.ownerChatId ?? "",
        email: input.ownerEmail
      });
    }

    return mapTenantConfig(configResult.rows[0] as Record<string, unknown>);
  }

  private async upsertPrimaryOwner(input: {
    tenantId: string;
    name: string;
    phone: string;
    email?: string;
  }): Promise<void> {
    if (!input.phone) {
      return;
    }
    await this.db.query(
      `INSERT INTO owner_contacts (id, tenant_id, name, phone, email, is_primary)
       VALUES ($1, $2, $3, $4, $5, true)
       ON CONFLICT (tenant_id, phone)
       DO UPDATE SET
         name = EXCLUDED.name,
         email = EXCLUDED.email,
         is_primary = true`,
      [randomUUID(), input.tenantId, input.name, input.phone.trim(), input.email ?? null]
    );
  }

  public async getTenantConfig(tenantId: string): Promise<TenantConfig> {
    const result = await this.db.query("SELECT * FROM tenant_configs WHERE tenant_id = $1", [tenantId]);
    if (!result.rowCount) {
      throw new Error(`Tenant config not found for tenant ${tenantId}`);
    }
    return mapTenantConfig(result.rows[0] as Record<string, unknown>);
  }

  public async listPrimaryOwners(tenantId: string): Promise<OwnerContact[]> {
    const result = await this.db.query(
      "SELECT * FROM owner_contacts WHERE tenant_id = $1 AND is_primary = true ORDER BY created_at ASC",
      [tenantId]
    );
    return result.rows.map((row) => mapOwner(row as Record<string, unknown>));
  }

  public async findLeadByPhone(tenantId: string, phone: string): Promise<Lead | null> {
    const result = await this.db.query(
      "SELECT * FROM leads WHERE tenant_id = $1 AND customer_phone = $2 LIMIT 1",
      [tenantId, normalizePhone(phone)]
    );
    return result.rowCount ? mapLead(result.rows[0] as Record<string, unknown>) : null;
  }

  public async findOrCreateLead(tenantId: string, phone: string): Promise<Lead> {
    const normalizedPhone = normalizePhone(phone);
    const existing = await this.findLeadByPhone(tenantId, normalizedPhone);
    if (existing) {
      return existing;
    }

    const result = await this.db.query(
      `INSERT INTO leads (
         id,
         tenant_id,
         customer_phone,
         status,
         last_inbound_at,
         updated_at
       ) VALUES ($1, $2, $3, 'NEW', NOW(), NOW())
       RETURNING *`,
      [randomUUID(), tenantId, normalizedPhone]
    );
    return mapLead(result.rows[0] as Record<string, unknown>);
  }

  public async updateLead(
    leadId: string,
    patch: Partial<Omit<Lead, "id" | "tenantId" | "createdAt">>
  ): Promise<Lead> {
    const sets: string[] = [];
    const params: unknown[] = [];
    let i = 1;

    const append = (column: string, value: unknown): void => {
      sets.push(`${column} = $${i++}`);
      params.push(value);
    };

    if (patch.customerName !== undefined) {
      append("customer_name", patch.customerName ?? null);
    }
    if (patch.requirement !== undefined) {
      append("requirement", patch.requirement ?? null);
    }
    if (patch.status !== undefined) {
      append("status", patch.status);
    }
    if (patch.botPausedUntil !== undefined) {
      append("bot_paused_until", patch.botPausedUntil ?? null);
    }
    if (patch.lastInboundAt !== undefined) {
      append("last_inbound_at", patch.lastInboundAt ?? null);
    }
    if (patch.lastOutboundAt !== undefined) {
      append("last_outbound_at", patch.lastOutboundAt ?? null);
    }

    append("updated_at", new Date());
    params.push(leadId);

    const query = `UPDATE leads SET ${sets.join(", ")} WHERE id = $${i} RETURNING *`;
    const result = await this.db.query(query, params);
    if (!result.rowCount) {
      throw new Error(`Lead not found for update: ${leadId}`);
    }
    return mapLead(result.rows[0] as Record<string, unknown>);
  }

  public async getLeadById(tenantId: string, leadId: string): Promise<Lead | null> {
    const result = await this.db.query("SELECT * FROM leads WHERE id = $1 AND tenant_id = $2", [leadId, tenantId]);
    return result.rowCount ? mapLead(result.rows[0] as Record<string, unknown>) : null;
  }

  public async getConversationForLead(tenantId: string, leadId: string): Promise<Conversation> {
    const existing = await this.db.query(
      "SELECT * FROM conversations WHERE tenant_id = $1 AND lead_id = $2 LIMIT 1",
      [tenantId, leadId]
    );
    if (existing.rowCount) {
      return mapConversation(existing.rows[0] as Record<string, unknown>);
    }
    const inserted = await this.db.query(
      `INSERT INTO conversations (id, tenant_id, lead_id, state, last_message_at)
       VALUES ($1, $2, $3, 'NEW', NOW())
       RETURNING *`,
      [randomUUID(), tenantId, leadId]
    );
    return mapConversation(inserted.rows[0] as Record<string, unknown>);
  }

  public async updateConversationState(conversationId: string, state: ConversationState): Promise<Conversation> {
    const result = await this.db.query(
      `UPDATE conversations
       SET state = $1, last_message_at = NOW(), updated_at = NOW()
       WHERE id = $2
       RETURNING *`,
      [state, conversationId]
    );
    if (!result.rowCount) {
      throw new Error(`Conversation not found: ${conversationId}`);
    }
    return mapConversation(result.rows[0] as Record<string, unknown>);
  }

  public async addInboundMessage(input: InboundMessageInput): Promise<void> {
    await this.db.query(
      `INSERT INTO messages (
         id,
         tenant_id,
         lead_id,
         direction,
         message_type,
         body,
         external_message_id,
         idempotency_key
       ) VALUES ($1, $2, $3, 'INBOUND', 'TEXT', $4, $5, $6)
       ON CONFLICT (idempotency_key) DO NOTHING`,
      [randomUUID(), input.tenantId, input.leadId, input.body, input.externalMessageId ?? null, input.idempotencyKey]
    );

    await this.db.query(
      "UPDATE leads SET last_inbound_at = NOW(), updated_at = NOW() WHERE id = $1 AND tenant_id = $2",
      [input.leadId, input.tenantId]
    );
  }

  public async addOutboundMessage(input: OutboundMessageInput): Promise<void> {
    await this.db.query(
      `INSERT INTO messages (
         id,
         tenant_id,
         lead_id,
         direction,
         message_type,
         body,
         external_message_id,
         idempotency_key
       ) VALUES ($1, $2, $3, 'OUTBOUND', $4, $5, $6, $7)
       ON CONFLICT (idempotency_key) DO NOTHING`,
      [
        randomUUID(),
        input.tenantId,
        input.leadId,
        input.messageType,
        input.body,
        input.externalMessageId ?? null,
        input.idempotencyKey
      ]
    );

    await this.db.query(
      "UPDATE leads SET last_outbound_at = NOW(), updated_at = NOW() WHERE id = $1 AND tenant_id = $2",
      [input.leadId, input.tenantId]
    );
  }

  public async scheduleFollowupJobs(tenantId: string, leadId: string, now = new Date()): Promise<void> {
    const followup30RunAt = new Date(now.getTime() + 30 * 60 * 1000);
    const followup24RunAt = new Date(now.getTime() + 24 * 60 * 60 * 1000);
    await this.db.query(
      `INSERT INTO followup_jobs (id, tenant_id, lead_id, job_type, run_at, status, attempt_count, idempotency_key)
       VALUES
         ($1, $2, $3, 'FOLLOWUP_30M', $4, 'PENDING', 0, $5),
         ($6, $2, $3, 'FOLLOWUP_24H', $7, 'PENDING', 0, $8)
       ON CONFLICT (tenant_id, lead_id, job_type) DO NOTHING`,
      [
        randomUUID(),
        tenantId,
        leadId,
        followup30RunAt,
        `${tenantId}:${leadId}:FOLLOWUP_30M`,
        randomUUID(),
        followup24RunAt,
        `${tenantId}:${leadId}:FOLLOWUP_24H`
      ]
    );
  }

  public async claimDueFollowupJobs(limit: number): Promise<FollowupJob[]> {
    return this.db.transaction(async (client: PoolClient) => {
      const result = await client.query(
        `UPDATE followup_jobs
         SET status = 'PROCESSING',
             locked_at = NOW(),
             attempt_count = attempt_count + 1,
             updated_at = NOW()
         WHERE id IN (
           SELECT id
           FROM followup_jobs
           WHERE status IN ('PENDING', 'FAILED')
             AND run_at <= NOW()
           ORDER BY run_at ASC
           LIMIT $1
           FOR UPDATE SKIP LOCKED
         )
         RETURNING *`,
        [limit]
      );
      return result.rows.map((row) => mapFollowupJob(row as Record<string, unknown>));
    });
  }

  public async markFollowupJobSuccess(jobId: string): Promise<void> {
    await this.db.query(
      `UPDATE followup_jobs
       SET status = 'SENT', last_error = NULL, updated_at = NOW()
       WHERE id = $1`,
      [jobId]
    );
  }

  public async markFollowupJobSkipped(jobId: string): Promise<void> {
    await this.db.query(
      `UPDATE followup_jobs
       SET status = 'SKIPPED', last_error = NULL, updated_at = NOW()
       WHERE id = $1`,
      [jobId]
    );
  }

  public async markFollowupJobFailure(jobId: string, error: string, retryAt?: Date): Promise<void> {
    await this.db.query(
      `UPDATE followup_jobs
       SET status = 'FAILED',
           last_error = $2,
           run_at = COALESCE($3, run_at),
           updated_at = NOW()
       WHERE id = $1`,
      [jobId, error.slice(0, 2000), retryAt ?? null]
    );
  }

  public async markFollowupJobDead(jobId: string, error: string): Promise<void> {
    await this.db.query(
      `UPDATE followup_jobs
       SET status = 'DEAD',
           last_error = $2,
           updated_at = NOW()
       WHERE id = $1`,
      [jobId, error.slice(0, 2000)]
    );
  }

  public async markLeadTakeover(tenantId: string, leadId: string, pausedUntil: Date): Promise<void> {
    await this.db.query(
      `UPDATE leads
       SET status = 'OWNER_TAKEOVER',
           bot_paused_until = $1,
           updated_at = NOW()
       WHERE id = $2 AND tenant_id = $3`,
      [pausedUntil, leadId, tenantId]
    );

    await this.db.query(
      `UPDATE conversations
       SET state = 'OWNER_TAKEOVER',
           updated_at = NOW()
       WHERE lead_id = $1 AND tenant_id = $2`,
      [leadId, tenantId]
    );
  }

  public async recordNotification(input: NotificationRecordInput): Promise<void> {
    await this.db.query(
      `INSERT INTO notifications (id, tenant_id, lead_id, channel, status, payload, error)
       VALUES ($1, $2, $3, $4, $5, $6::jsonb, $7)`,
      [
        randomUUID(),
        input.tenantId,
        input.leadId ?? null,
        input.channel,
        input.status,
        JSON.stringify(input.payload),
        input.error ?? null
      ]
    );
  }

  public async recordAuditEvent(input: AuditEventInput): Promise<void> {
    await this.db.query(
      `INSERT INTO audit_events (id, tenant_id, lead_id, actor, action, metadata)
       VALUES ($1, $2, $3, $4, $5, $6::jsonb)`,
      [
        randomUUID(),
        input.tenantId,
        input.leadId ?? null,
        input.actor,
        input.action,
        JSON.stringify(input.metadata ?? {})
      ]
    );
  }

  public async hasProcessedEvent(eventId: string, source: string): Promise<boolean> {
    const result = await this.db.query(
      "SELECT 1 FROM processed_events WHERE event_id = $1 AND source = $2 LIMIT 1",
      [eventId, source]
    );
    return Boolean(result.rowCount);
  }

  public async markProcessedEvent(eventId: string, source: string): Promise<void> {
    await this.db.query(
      `INSERT INTO processed_events (event_id, source)
       VALUES ($1, $2)
       ON CONFLICT (event_id, source) DO NOTHING`,
      [eventId, source]
    );
  }

  public async upsertSubscription(input: SubscriptionUpsertInput): Promise<void> {
    await this.db.query(
      `INSERT INTO subscriptions (
         id,
         tenant_id,
         provider,
         customer_id,
         subscription_id,
         status,
         plan_code,
         current_period_end,
         trial_ends_at
       ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
       ON CONFLICT (tenant_id)
       DO UPDATE SET
         provider = EXCLUDED.provider,
         customer_id = EXCLUDED.customer_id,
         subscription_id = EXCLUDED.subscription_id,
         status = EXCLUDED.status,
         plan_code = EXCLUDED.plan_code,
         current_period_end = EXCLUDED.current_period_end,
         trial_ends_at = EXCLUDED.trial_ends_at,
         updated_at = NOW()`,
      [
        randomUUID(),
        input.tenantId,
        input.provider,
        input.customerId ?? null,
        input.subscriptionId ?? null,
        input.status,
        input.planCode ?? null,
        input.currentPeriodEnd ?? null,
        input.trialEndsAt ?? null
      ]
    );
  }

  public async isAutomationAllowed(tenantId: string, now = new Date()): Promise<boolean> {
    const result = await this.db.query(
      `SELECT
         t.trial_ends_at AS tenant_trial_ends_at,
         c.auto_reply_enabled,
         s.status AS subscription_status,
         s.trial_ends_at AS subscription_trial_ends_at
       FROM tenants t
       JOIN tenant_configs c ON c.tenant_id = t.id
       LEFT JOIN subscriptions s ON s.tenant_id = t.id
       WHERE t.id = $1`,
      [tenantId]
    );

    if (!result.rowCount) {
      return false;
    }

    const row = result.rows[0] as Record<string, unknown>;
    const autoReplyEnabled = Boolean(row.auto_reply_enabled);
    if (!autoReplyEnabled) {
      return false;
    }

    const status = String(row.subscription_status ?? "TRIALING");
    const trialEndRaw = row.subscription_trial_ends_at ?? row.tenant_trial_ends_at;
    const trialEnd = trialEndRaw ? new Date(String(trialEndRaw)) : null;

    if (status === "ACTIVE") {
      return true;
    }
    if (status === "TRIALING" && trialEnd && trialEnd >= now) {
      return true;
    }

    return false;
  }
}
