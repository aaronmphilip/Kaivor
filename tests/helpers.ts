import { randomUUID } from "crypto";
import type { PolarBillingService } from "../packages/billing/src/index.js";
import type { AppConfig } from "../packages/config/src/index.js";
import type { NotificationService } from "../packages/notifications/src/index.js";
import type {
  AuditEventInput,
  Conversation,
  ConversationState,
  FollowupJob,
  Lead,
  LeadRepository,
  NotificationRecordInput,
  OwnerContact,
  SubscriptionStatus,
  Tenant,
  TenantConfig
} from "../packages/storage/src/index.js";
import type { TelegramClient } from "../packages/telegram/src/index.js";

const defaultConfig: AppConfig = {
  nodeEnv: "test",
  port: 3000,
  databaseUrl: "postgres://test",
  masterApiKey: "master-test-key",
  appBaseUrl: "http://localhost:3000",
  telegramWebhookSecret: "telegram-secret-test",
  polarAccessToken: "polar-token",
  polarWebhookSecret: "polar-secret",
  polarProductId: "prod_123",
  polarMonthlyPriceInr: 1499,
  followupPollIntervalMs: 60000,
  followupMaxRetries: 3
};

const defaultTenantConfig: Omit<TenantConfig, "tenantId" | "updatedAt"> = {
  autoReplyEnabled: true,
  greetingTemplate: "Namaste! BharatClaw se bol raha hoon. Aapka naam bata do please?",
  followup30mTemplate: "Quick follow-up. Kya aapko abhi bhi help chahiye?",
  followup24hTemplateName: "lead_followup_24h",
  takeoverCooldownMinutes: 180,
  metadata: {}
};

export class InMemoryLeadRepository implements LeadRepository {
  public tenants = new Map<string, Tenant>();
  public tenantConfigs = new Map<string, TenantConfig>();
  public owners = new Map<string, OwnerContact[]>();
  public leads = new Map<string, Lead>();
  public conversations = new Map<string, Conversation>();
  public followupJobs = new Map<string, FollowupJob>();
  public processed = new Set<string>();
  public outboundMessages: Array<{ tenantId: string; leadId: string; body: string }> = [];
  public auditEvents: AuditEventInput[] = [];
  public notifications: NotificationRecordInput[] = [];
  public subscription = new Map<string, { status: SubscriptionStatus; trialEndsAt?: Date }>();

  constructor() {
    const tenantId = randomUUID();
    const tenant: Tenant = {
      id: tenantId,
      name: "Test Tenant",
      slug: "test-tenant",
      whatsappPhoneNumberId: "telegram:test-tenant",
      tenantApiKeyHash: "salt:hash",
      trialEndsAt: new Date(Date.now() + 3 * 24 * 60 * 60 * 1000),
      createdAt: new Date()
    };
    this.tenants.set(tenantId, tenant);
    this.tenantConfigs.set(tenantId, {
      tenantId,
      ...defaultTenantConfig,
      metadata: {
        telegramBotToken: "test-bot-token"
      },
      updatedAt: new Date()
    });
    this.owners.set(tenantId, [
      {
        id: randomUUID(),
        tenantId,
        name: "Owner",
        phone: "919999999999",
        email: "owner@example.com",
        isPrimary: true,
        createdAt: new Date()
      }
    ]);
    this.subscription.set(tenantId, { status: "ACTIVE" });
  }

  public firstTenant(): Tenant {
    return [...this.tenants.values()][0];
  }

  public async createTenant(input: {
    name: string;
    slug: string;
    ownerName: string;
    ownerChatId: string;
    ownerEmail?: string;
    telegramBotToken: string;
    trialDays?: number;
  }): Promise<{ tenant: Tenant; tenantApiKey: string }> {
    const tenantId = randomUUID();
    const trialEndsAt = new Date(Date.now() + (input.trialDays ?? 7) * 24 * 60 * 60 * 1000);
    const tenant: Tenant = {
      id: tenantId,
      name: input.name,
      slug: input.slug,
      whatsappPhoneNumberId: `telegram:${input.slug}`,
      tenantApiKeyHash: "fake",
      trialEndsAt,
      createdAt: new Date()
    };
    this.tenants.set(tenantId, tenant);
    this.tenantConfigs.set(tenantId, {
      tenantId,
      ...defaultTenantConfig,
      updatedAt: new Date()
    });
    this.owners.set(tenantId, [
      {
        id: randomUUID(),
        tenantId,
        name: input.ownerName,
        phone: input.ownerChatId,
        email: input.ownerEmail,
        isPrimary: true,
        createdAt: new Date()
      }
    ]);
    const config = this.tenantConfigs.get(tenantId)!;
    config.metadata = {
      ...(config.metadata ?? {}),
      telegramBotToken: input.telegramBotToken
    };
    this.tenantConfigs.set(tenantId, config);
    this.subscription.set(tenantId, { status: "TRIALING", trialEndsAt });
    return { tenant, tenantApiKey: "tenant-key-plain" };
  }

  public async getTenantById(tenantId: string): Promise<Tenant | null> {
    return this.tenants.get(tenantId) ?? null;
  }

  public async getTenantByWhatsappPhoneNumberId(phoneNumberId: string): Promise<Tenant | null> {
    for (const tenant of this.tenants.values()) {
      if (tenant.whatsappPhoneNumberId === phoneNumberId) {
        return tenant;
      }
    }
    return null;
  }

  public async verifyTenantApiKey(tenantId: string, plaintextApiKey: string): Promise<boolean> {
    return this.tenants.has(tenantId) && plaintextApiKey === "tenant-test-key";
  }

  public async upsertTenantConfig(input: {
    tenantId: string;
    autoReplyEnabled?: boolean;
    greetingTemplate?: string;
    followup30mTemplate?: string;
    followup24hTemplateName?: string;
    takeoverCooldownMinutes?: number;
    metadata?: Record<string, unknown>;
    ownerName?: string;
    ownerChatId?: string;
    ownerEmail?: string;
    telegramBotToken?: string;
  }): Promise<TenantConfig> {
    const existing = this.tenantConfigs.get(input.tenantId);
    if (!existing) {
      throw new Error("Tenant config missing");
    }
    const merged: TenantConfig = {
      ...existing,
      autoReplyEnabled: input.autoReplyEnabled ?? existing.autoReplyEnabled,
      greetingTemplate: input.greetingTemplate ?? existing.greetingTemplate,
      followup30mTemplate: input.followup30mTemplate ?? existing.followup30mTemplate,
      followup24hTemplateName: input.followup24hTemplateName ?? existing.followup24hTemplateName,
      takeoverCooldownMinutes: input.takeoverCooldownMinutes ?? existing.takeoverCooldownMinutes,
      metadata: {
        ...(existing.metadata ?? {}),
        ...(input.metadata ?? {}),
        ...(input.telegramBotToken ? { telegramBotToken: input.telegramBotToken } : {})
      },
      updatedAt: new Date()
    };
    this.tenantConfigs.set(input.tenantId, merged);
    return merged;
  }

  public async getTenantConfig(tenantId: string): Promise<TenantConfig> {
    const config = this.tenantConfigs.get(tenantId);
    if (!config) {
      throw new Error("Tenant config missing");
    }
    return config;
  }

  public async listPrimaryOwners(tenantId: string): Promise<OwnerContact[]> {
    return this.owners.get(tenantId) ?? [];
  }

  public async findLeadByPhone(tenantId: string, phone: string): Promise<Lead | null> {
    const normalized = phone.replace(/\D/g, "");
    for (const lead of this.leads.values()) {
      if (lead.tenantId === tenantId && lead.customerPhone === normalized) {
        return lead;
      }
    }
    return null;
  }

  public async findOrCreateLead(tenantId: string, phone: string): Promise<Lead> {
    const existing = await this.findLeadByPhone(tenantId, phone);
    if (existing) {
      return existing;
    }
    const lead: Lead = {
      id: randomUUID(),
      tenantId,
      customerPhone: phone.replace(/\D/g, ""),
      status: "NEW",
      createdAt: new Date(),
      updatedAt: new Date()
    };
    this.leads.set(lead.id, lead);
    return lead;
  }

  public async updateLead(
    leadId: string,
    patch: Partial<Omit<Lead, "id" | "tenantId" | "createdAt">>
  ): Promise<Lead> {
    const existing = this.leads.get(leadId);
    if (!existing) {
      throw new Error("Lead missing");
    }
    const updated: Lead = {
      ...existing,
      ...patch,
      updatedAt: new Date()
    };
    this.leads.set(leadId, updated);
    return updated;
  }

  public async getConversationForLead(tenantId: string, leadId: string): Promise<Conversation> {
    const key = `${tenantId}:${leadId}`;
    const existing = this.conversations.get(key);
    if (existing) {
      return existing;
    }
    const conversation: Conversation = {
      id: randomUUID(),
      tenantId,
      leadId,
      state: "NEW",
      lastMessageAt: new Date(),
      createdAt: new Date(),
      updatedAt: new Date()
    };
    this.conversations.set(key, conversation);
    return conversation;
  }

  public async updateConversationState(conversationId: string, state: ConversationState): Promise<Conversation> {
    for (const [key, conversation] of this.conversations.entries()) {
      if (conversation.id === conversationId) {
        const updated: Conversation = {
          ...conversation,
          state,
          updatedAt: new Date()
        };
        this.conversations.set(key, updated);
        return updated;
      }
    }
    throw new Error("Conversation missing");
  }

  public async addInboundMessage(input: {
    tenantId: string;
    leadId: string;
    body: string;
    externalMessageId?: string;
    idempotencyKey: string;
  }): Promise<void> {
    const lead = this.leads.get(input.leadId);
    if (lead) {
      lead.lastInboundAt = new Date();
      this.leads.set(input.leadId, lead);
    }
  }

  public async addOutboundMessage(input: {
    tenantId: string;
    leadId: string;
    body: string;
    messageType: "TEXT" | "TEMPLATE" | "SYSTEM";
    externalMessageId?: string;
    idempotencyKey: string;
  }): Promise<void> {
    this.outboundMessages.push({
      tenantId: input.tenantId,
      leadId: input.leadId,
      body: input.body
    });
  }

  public async scheduleFollowupJobs(tenantId: string, leadId: string, now = new Date()): Promise<void> {
    const job1: FollowupJob = {
      id: randomUUID(),
      tenantId,
      leadId,
      jobType: "FOLLOWUP_30M",
      runAt: new Date(now.getTime() + 30 * 60 * 1000),
      status: "PENDING",
      attemptCount: 0,
      idempotencyKey: `${tenantId}:${leadId}:FOLLOWUP_30M`,
      createdAt: new Date(),
      updatedAt: new Date()
    };
    const job2: FollowupJob = {
      id: randomUUID(),
      tenantId,
      leadId,
      jobType: "FOLLOWUP_24H",
      runAt: new Date(now.getTime() + 24 * 60 * 60 * 1000),
      status: "PENDING",
      attemptCount: 0,
      idempotencyKey: `${tenantId}:${leadId}:FOLLOWUP_24H`,
      createdAt: new Date(),
      updatedAt: new Date()
    };
    this.followupJobs.set(job1.id, job1);
    this.followupJobs.set(job2.id, job2);
  }

  public async claimDueFollowupJobs(limit: number): Promise<FollowupJob[]> {
    return [...this.followupJobs.values()].slice(0, limit);
  }

  public async markFollowupJobSuccess(jobId: string): Promise<void> {
    const job = this.followupJobs.get(jobId);
    if (job) {
      job.status = "SENT";
    }
  }

  public async markFollowupJobSkipped(jobId: string): Promise<void> {
    const job = this.followupJobs.get(jobId);
    if (job) {
      job.status = "SKIPPED";
    }
  }

  public async markFollowupJobFailure(jobId: string, error: string, retryAt?: Date): Promise<void> {
    const job = this.followupJobs.get(jobId);
    if (job) {
      job.status = "FAILED";
      job.lastError = error;
      if (retryAt) {
        job.runAt = retryAt;
      }
    }
  }

  public async markFollowupJobDead(jobId: string, error: string): Promise<void> {
    const job = this.followupJobs.get(jobId);
    if (job) {
      job.status = "DEAD";
      job.lastError = error;
    }
  }

  public async markLeadTakeover(tenantId: string, leadId: string, pausedUntil: Date): Promise<void> {
    const lead = this.leads.get(leadId);
    if (!lead) {
      return;
    }
    lead.botPausedUntil = pausedUntil;
    lead.status = "OWNER_TAKEOVER";
    this.leads.set(leadId, lead);

    const key = `${tenantId}:${leadId}`;
    const conversation = this.conversations.get(key);
    if (conversation) {
      conversation.state = "OWNER_TAKEOVER";
      this.conversations.set(key, conversation);
    }
  }

  public async recordNotification(input: NotificationRecordInput): Promise<void> {
    this.notifications.push(input);
  }

  public async recordAuditEvent(input: AuditEventInput): Promise<void> {
    this.auditEvents.push(input);
  }

  public async hasProcessedEvent(eventId: string, source: string): Promise<boolean> {
    return this.processed.has(`${source}:${eventId}`);
  }

  public async markProcessedEvent(eventId: string, source: string): Promise<void> {
    this.processed.add(`${source}:${eventId}`);
  }

  public async upsertSubscription(input: {
    tenantId: string;
    status: SubscriptionStatus;
    provider: string;
    customerId?: string;
    subscriptionId?: string;
    planCode?: string;
    currentPeriodEnd?: Date;
    trialEndsAt?: Date;
  }): Promise<void> {
    this.subscription.set(input.tenantId, {
      status: input.status,
      trialEndsAt: input.trialEndsAt
    });
  }

  public async isAutomationAllowed(tenantId: string, now = new Date()): Promise<boolean> {
    const config = this.tenantConfigs.get(tenantId);
    if (!config?.autoReplyEnabled) {
      return false;
    }
    const subscription = this.subscription.get(tenantId);
    if (!subscription) {
      return true;
    }
    if (subscription.status === "ACTIVE") {
      return true;
    }
    if (subscription.status === "TRIALING") {
      return subscription.trialEndsAt ? subscription.trialEndsAt >= now : true;
    }
    return false;
  }

  public async getLeadById(tenantId: string, leadId: string): Promise<Lead | null> {
    const lead = this.leads.get(leadId);
    if (!lead || lead.tenantId !== tenantId) {
      return null;
    }
    return lead;
  }
}

export function createFakeServices(overrides?: Partial<AppConfig>) {
  const repository = new InMemoryLeadRepository();
  const sentMessages: Array<{ chatId: string; text: string }> = [];

  const telegramClient = {
    sendMessage: async ({ chatId, text }: { botToken: string; chatId: string; text: string }) => {
      sentMessages.push({ chatId, text });
      return `tpl_${randomUUID()}`;
    }
  } as unknown as TelegramClient;

  const notificationService = {
    notifyOwnerLeadCaptured: async () => {}
  } as unknown as NotificationService;

  const billingService = {
    verifyWebhookSignature: () => true,
    handleWebhook: async () => ({ updated: true }),
    createCheckoutUrl: async () => "https://polar.sh/checkout/test"
  } as unknown as PolarBillingService;

  const config: AppConfig = {
    ...defaultConfig,
    ...overrides
  };

  return {
    config,
    repository,
    telegramClient,
    notificationService,
    billingService,
    sentMessages
  };
}
