import { randomUUID } from "crypto";
const defaultConfig = {
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
const defaultTenantConfig = {
    autoReplyEnabled: true,
    greetingTemplate: "Hey, thanks for reaching out. I will help you with this. Just a couple quick details first.",
    followup30mTemplate: "Hey, just checking in. Are you still looking for this?",
    followup24hTemplateName: "lead_followup_24h",
    takeoverCooldownMinutes: 180,
    metadata: {}
};
export class InMemoryLeadRepository {
    tenants = new Map();
    tenantConfigs = new Map();
    owners = new Map();
    leads = new Map();
    conversations = new Map();
    followupJobs = new Map();
    processed = new Set();
    outboundMessages = [];
    auditEvents = [];
    notifications = [];
    subscription = new Map();
    constructor() {
        const tenantId = randomUUID();
        const tenant = {
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
                telegramBotToken: "test-bot-token",
                businessName: "Test Tenant"
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
    firstTenant() {
        return [...this.tenants.values()][0];
    }
    async createTenant(input) {
        const tenantId = randomUUID();
        const trialEndsAt = new Date(Date.now() + (input.trialDays ?? 7) * 24 * 60 * 60 * 1000);
        const tenant = {
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
        const config = this.tenantConfigs.get(tenantId);
        config.metadata = {
            ...(config.metadata ?? {}),
            telegramBotToken: input.telegramBotToken,
            businessName: input.name
        };
        this.tenantConfigs.set(tenantId, config);
        this.subscription.set(tenantId, { status: "TRIALING", trialEndsAt });
        return { tenant, tenantApiKey: "tenant-key-plain" };
    }
    async getTenantById(tenantId) {
        return this.tenants.get(tenantId) ?? null;
    }
    async getTenantByWhatsappPhoneNumberId(phoneNumberId) {
        for (const tenant of this.tenants.values()) {
            if (tenant.whatsappPhoneNumberId === phoneNumberId) {
                return tenant;
            }
        }
        return null;
    }
    async verifyTenantApiKey(tenantId, plaintextApiKey) {
        return this.tenants.has(tenantId) && plaintextApiKey === "tenant-test-key";
    }
    async upsertTenantConfig(input) {
        const existing = this.tenantConfigs.get(input.tenantId);
        if (!existing) {
            throw new Error("Tenant config missing");
        }
        const merged = {
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
    async getTenantConfig(tenantId) {
        const config = this.tenantConfigs.get(tenantId);
        if (!config) {
            throw new Error("Tenant config missing");
        }
        return config;
    }
    async listPrimaryOwners(tenantId) {
        return this.owners.get(tenantId) ?? [];
    }
    async findLeadByPhone(tenantId, phone) {
        const normalized = phone.replace(/\D/g, "");
        for (const lead of this.leads.values()) {
            if (lead.tenantId === tenantId && lead.customerPhone === normalized) {
                return lead;
            }
        }
        return null;
    }
    async findOrCreateLead(tenantId, phone) {
        const existing = await this.findLeadByPhone(tenantId, phone);
        if (existing) {
            return existing;
        }
        const lead = {
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
    async updateLead(leadId, patch) {
        const existing = this.leads.get(leadId);
        if (!existing) {
            throw new Error("Lead missing");
        }
        const updated = {
            ...existing,
            ...patch,
            updatedAt: new Date()
        };
        this.leads.set(leadId, updated);
        return updated;
    }
    async getConversationForLead(tenantId, leadId) {
        const key = `${tenantId}:${leadId}`;
        const existing = this.conversations.get(key);
        if (existing) {
            return existing;
        }
        const conversation = {
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
    async updateConversationState(conversationId, state) {
        for (const [key, conversation] of this.conversations.entries()) {
            if (conversation.id === conversationId) {
                const updated = {
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
    async addInboundMessage(input) {
        const lead = this.leads.get(input.leadId);
        if (lead) {
            lead.lastInboundAt = new Date();
            this.leads.set(input.leadId, lead);
        }
    }
    async addOutboundMessage(input) {
        this.outboundMessages.push({
            tenantId: input.tenantId,
            leadId: input.leadId,
            body: input.body
        });
    }
    async scheduleFollowupJobs(tenantId, leadId, now = new Date()) {
        const job1 = {
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
        const job2 = {
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
    async claimDueFollowupJobs(limit) {
        return [...this.followupJobs.values()].slice(0, limit);
    }
    async markFollowupJobSuccess(jobId) {
        const job = this.followupJobs.get(jobId);
        if (job) {
            job.status = "SENT";
        }
    }
    async markFollowupJobSkipped(jobId) {
        const job = this.followupJobs.get(jobId);
        if (job) {
            job.status = "SKIPPED";
        }
    }
    async markFollowupJobFailure(jobId, error, retryAt) {
        const job = this.followupJobs.get(jobId);
        if (job) {
            job.status = "FAILED";
            job.lastError = error;
            if (retryAt) {
                job.runAt = retryAt;
            }
        }
    }
    async markFollowupJobDead(jobId, error) {
        const job = this.followupJobs.get(jobId);
        if (job) {
            job.status = "DEAD";
            job.lastError = error;
        }
    }
    async markLeadTakeover(tenantId, leadId, pausedUntil) {
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
    async recordNotification(input) {
        this.notifications.push(input);
    }
    async recordAuditEvent(input) {
        this.auditEvents.push(input);
    }
    async hasProcessedEvent(eventId, source) {
        return this.processed.has(`${source}:${eventId}`);
    }
    async markProcessedEvent(eventId, source) {
        this.processed.add(`${source}:${eventId}`);
    }
    async upsertSubscription(input) {
        this.subscription.set(input.tenantId, {
            status: input.status,
            trialEndsAt: input.trialEndsAt
        });
    }
    async isAutomationAllowed(tenantId, now = new Date()) {
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
    async getLeadById(tenantId, leadId) {
        const lead = this.leads.get(leadId);
        if (!lead || lead.tenantId !== tenantId) {
            return null;
        }
        return lead;
    }
}
export function createFakeServices(overrides) {
    const repository = new InMemoryLeadRepository();
    const sentMessages = [];
    const telegramClient = {
        sendMessage: async ({ chatId, text }) => {
            sentMessages.push({ chatId, text });
            return `tpl_${randomUUID()}`;
        }
    };
    const notificationService = {
        notifyOwnerLeadCaptured: async () => { }
    };
    const billingService = {
        verifyWebhookSignature: () => true,
        handleWebhook: async () => ({ updated: true }),
        createCheckoutUrl: async () => "https://polar.sh/checkout/test"
    };
    const config = {
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
