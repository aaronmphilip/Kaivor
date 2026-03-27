export type ConversationState =
  | "NEW"
  | "AWAITING_NAME"
  | "AWAITING_REQUIREMENT"
  | "CAPTURED"
  | "FOLLOWUP_PENDING"
  | "OWNER_TAKEOVER"
  | "CLOSED";

export type LeadStatus =
  | "NEW"
  | "IN_PROGRESS"
  | "CAPTURED"
  | "FOLLOWUP_PENDING"
  | "OWNER_TAKEOVER"
  | "CLOSED";

export type MessageDirection = "INBOUND" | "OUTBOUND";
export type MessageType = "TEXT" | "TEMPLATE" | "SYSTEM";
export type LeadLanguage = "en" | "hi";

export type FollowupJobType = "FOLLOWUP_30M" | "FOLLOWUP_24H";
export type FollowupJobStatus =
  | "PENDING"
  | "PROCESSING"
  | "SENT"
  | "FAILED"
  | "DEAD"
  | "SKIPPED";

export type SubscriptionStatus =
  | "TRIALING"
  | "ACTIVE"
  | "PAST_DUE"
  | "CANCELED"
  | "INACTIVE";

export interface Tenant {
  id: string;
  name: string;
  slug: string;
  whatsappPhoneNumberId: string;
  tenantApiKeyHash: string;
  trialEndsAt: Date;
  createdAt: Date;
}

export interface TenantConfig {
  tenantId: string;
  autoReplyEnabled: boolean;
  greetingTemplate: string;
  followup30mTemplate: string;
  followup24hTemplateName: string;
  takeoverCooldownMinutes: number;
  metadata: Record<string, unknown>;
  updatedAt: Date;
}

export interface OwnerContact {
  id: string;
  tenantId: string;
  name: string;
  phone: string;
  email?: string;
  isPrimary: boolean;
  createdAt: Date;
}

export interface Lead {
  id: string;
  tenantId: string;
  customerPhone: string;
  customerName?: string;
  preferredLanguage?: LeadLanguage | null;
  requirement?: string;
  status: LeadStatus;
  botPausedUntil?: Date;
  lastInboundAt?: Date;
  lastOutboundAt?: Date;
  createdAt: Date;
  updatedAt: Date;
}

export interface Conversation {
  id: string;
  tenantId: string;
  leadId: string;
  state: ConversationState;
  lastMessageAt: Date;
  createdAt: Date;
  updatedAt: Date;
}

export interface FollowupJob {
  id: string;
  tenantId: string;
  leadId: string;
  jobType: FollowupJobType;
  runAt: Date;
  status: FollowupJobStatus;
  attemptCount: number;
  lastError?: string;
  lockedAt?: Date;
  idempotencyKey: string;
  createdAt: Date;
  updatedAt: Date;
}

export interface Subscription {
  id: string;
  tenantId: string;
  provider: string;
  customerId?: string;
  subscriptionId?: string;
  status: SubscriptionStatus;
  planCode?: string;
  currentPeriodEnd?: Date;
  trialEndsAt?: Date;
  createdAt: Date;
  updatedAt: Date;
}

export interface CreateTenantInput {
  name: string;
  slug: string;
  ownerName: string;
  ownerChatId: string;
  ownerEmail?: string;
  telegramBotToken: string;
  trialDays?: number;
}

export interface UpsertTenantConfigInput {
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
}

export interface InboundMessageInput {
  tenantId: string;
  leadId: string;
  body: string;
  externalMessageId?: string;
  idempotencyKey: string;
}

export interface OutboundMessageInput {
  tenantId: string;
  leadId: string;
  body: string;
  messageType: MessageType;
  externalMessageId?: string;
  idempotencyKey: string;
}

export interface NotificationRecordInput {
  tenantId: string;
  leadId?: string;
  channel: string;
  status: string;
  payload: Record<string, unknown>;
  error?: string;
}

export interface AuditEventInput {
  tenantId: string;
  leadId?: string;
  actor: string;
  action: string;
  metadata?: Record<string, unknown>;
}

export interface SubscriptionUpsertInput {
  tenantId: string;
  status: SubscriptionStatus;
  provider: string;
  customerId?: string;
  subscriptionId?: string;
  planCode?: string;
  currentPeriodEnd?: Date;
  trialEndsAt?: Date;
}

export interface LeadRepository {
  createTenant(input: CreateTenantInput): Promise<{ tenant: Tenant; tenantApiKey: string }>;
  getTenantById(tenantId: string): Promise<Tenant | null>;
  getTenantByWhatsappPhoneNumberId(phoneNumberId: string): Promise<Tenant | null>;
  verifyTenantApiKey(tenantId: string, plaintextApiKey: string): Promise<boolean>;
  upsertTenantConfig(input: UpsertTenantConfigInput): Promise<TenantConfig>;
  getTenantConfig(tenantId: string): Promise<TenantConfig>;
  listPrimaryOwners(tenantId: string): Promise<OwnerContact[]>;
  findLeadByPhone(tenantId: string, phone: string): Promise<Lead | null>;
  findOrCreateLead(tenantId: string, phone: string): Promise<Lead>;
  updateLead(leadId: string, patch: Partial<Omit<Lead, "id" | "tenantId" | "createdAt">>): Promise<Lead>;
  getConversationForLead(tenantId: string, leadId: string): Promise<Conversation>;
  updateConversationState(conversationId: string, state: ConversationState): Promise<Conversation>;
  addInboundMessage(input: InboundMessageInput): Promise<void>;
  addOutboundMessage(input: OutboundMessageInput): Promise<void>;
  scheduleFollowupJobs(tenantId: string, leadId: string, now?: Date): Promise<void>;
  claimDueFollowupJobs(limit: number): Promise<FollowupJob[]>;
  markFollowupJobSuccess(jobId: string): Promise<void>;
  markFollowupJobSkipped(jobId: string): Promise<void>;
  markFollowupJobFailure(jobId: string, error: string, retryAt?: Date): Promise<void>;
  markFollowupJobDead(jobId: string, error: string): Promise<void>;
  markLeadTakeover(tenantId: string, leadId: string, pausedUntil: Date): Promise<void>;
  recordNotification(input: NotificationRecordInput): Promise<void>;
  recordAuditEvent(input: AuditEventInput): Promise<void>;
  hasProcessedEvent(eventId: string, source: string): Promise<boolean>;
  markProcessedEvent(eventId: string, source: string): Promise<void>;
  upsertSubscription(input: SubscriptionUpsertInput): Promise<void>;
  isAutomationAllowed(tenantId: string, now?: Date): Promise<boolean>;
  getLeadById(tenantId: string, leadId: string): Promise<Lead | null>;
}
