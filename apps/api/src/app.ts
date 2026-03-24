import Fastify from "fastify";
import { z } from "zod";
import { PolarBillingService } from "../../../packages/billing/src/index.js";
import type { AppConfig } from "../../../packages/config/src/index.js";
import { NotificationService } from "../../../packages/notifications/src/index.js";
import { buildAutoReply } from "../../../packages/reply-engine/src/index.js";
import { computeTransition } from "../../../packages/state-machine/src/index.js";
import type { LeadRepository } from "../../../packages/storage/src/index.js";
import { parseWhatsAppWebhook, verifyWhatsAppSignature, WhatsAppClient } from "../../../packages/whatsapp/src/index.js";

declare module "fastify" {
  interface FastifyRequest {
    rawBody?: string;
  }
}

export interface ApiServices {
  config: AppConfig;
  repository: LeadRepository;
  whatsappClient: WhatsAppClient;
  notificationService: NotificationService;
  billingService: PolarBillingService;
}

const createTenantSchema = z.object({
  name: z.string().min(2),
  slug: z.string().min(2),
  whatsappPhoneNumberId: z.string().min(5),
  ownerName: z.string().min(2),
  ownerPhone: z.string().min(8),
  ownerEmail: z.string().email().optional(),
  trialDays: z.number().int().min(1).max(30).optional()
});

const updateConfigSchema = z.object({
  autoReplyEnabled: z.boolean().optional(),
  greetingTemplate: z.string().min(5).max(320).optional(),
  followup30mTemplate: z.string().min(5).max(320).optional(),
  followup24hTemplateName: z.string().min(2).max(100).optional(),
  takeoverCooldownMinutes: z.number().int().min(5).max(1440).optional(),
  metadata: z.record(z.unknown()).optional(),
  ownerName: z.string().min(2).optional(),
  ownerPhone: z.string().min(8).optional(),
  ownerEmail: z.string().email().optional()
});

const takeoverSchema = z.object({
  tenantId: z.string().uuid(),
  command: z.string().min(8)
});

function parseTakeoverCommand(command: string): string | null {
  const match = /^#takeover\s+([+\d][\d\s-]{6,20})$/i.exec(command.trim());
  if (!match) {
    return null;
  }
  return match[1].replace(/\D/g, "");
}

function hasMasterAccess(inputKey: string | undefined, config: AppConfig): boolean {
  return inputKey === config.masterApiKey;
}

export function createApiApp(services: ApiServices) {
  const app = Fastify({ logger: true });
  const { config, repository, whatsappClient, notificationService, billingService } = services;

  app.addContentTypeParser(
    "application/json",
    { parseAs: "string" },
    (request, body: string, done: (error: Error | null, parsed?: unknown) => void) => {
      request.rawBody = body;
      try {
        done(null, JSON.parse(body));
      } catch {
        done(new Error("Invalid JSON body"));
      }
    }
  );

  app.get("/health", async () => ({ ok: true, service: "bharatclaw-api" }));

  app.get("/webhooks/whatsapp", async (request, reply) => {
    const query = request.query as Record<string, string | undefined>;
    const mode = query["hub.mode"];
    const verifyToken = query["hub.verify_token"];
    const challenge = query["hub.challenge"];

    if (mode === "subscribe" && verifyToken === config.whatsappWebhookVerifyToken) {
      return reply.code(200).send(challenge);
    }
    return reply.code(403).send({ error: "Invalid verify token" });
  });

  app.post("/webhooks/whatsapp", async (request, reply) => {
    const signature = request.headers["x-hub-signature-256"] as string | undefined;
    const rawBody = request.rawBody ?? JSON.stringify(request.body ?? {});

    const signatureValid = verifyWhatsAppSignature({
      appSecret: config.whatsappAppSecret,
      signatureHeader: signature,
      rawBody
    });
    if (!signatureValid) {
      return reply.code(401).send({ error: "Invalid WhatsApp signature" });
    }

    const inboundMessages = parseWhatsAppWebhook(request.body);
    for (const inbound of inboundMessages) {
      const alreadyProcessed = await repository.hasProcessedEvent(inbound.eventId, "WHATSAPP");
      if (alreadyProcessed) {
        continue;
      }

      const tenant = await repository.getTenantByWhatsappPhoneNumberId(inbound.phoneNumberId);
      if (!tenant) {
        continue;
      }

      const automationAllowed = await repository.isAutomationAllowed(tenant.id);
      if (!automationAllowed) {
        await repository.recordAuditEvent({
          tenantId: tenant.id,
          actor: "SYSTEM",
          action: "AUTOMATION_BLOCKED_BILLING",
          metadata: { eventId: inbound.eventId }
        });
        await repository.markProcessedEvent(inbound.eventId, "WHATSAPP");
        continue;
      }

      const lead = await repository.findOrCreateLead(tenant.id, inbound.fromPhone);
      const conversation = await repository.getConversationForLead(tenant.id, lead.id);

      await repository.addInboundMessage({
        tenantId: tenant.id,
        leadId: lead.id,
        body: inbound.text,
        externalMessageId: inbound.eventId,
        idempotencyKey: `inbound:${inbound.eventId}`
      });

      if (lead.botPausedUntil && lead.botPausedUntil > new Date()) {
        await repository.markProcessedEvent(inbound.eventId, "WHATSAPP");
        continue;
      }

      const transition = computeTransition(conversation.state, inbound.text);
      const updatedLead = await repository.updateLead(lead.id, {
        customerName: transition.customerName ?? lead.customerName,
        requirement: transition.requirement ?? lead.requirement,
        status: transition.nextLeadStatus
      });

      await repository.updateConversationState(conversation.id, transition.nextState);

      if (transition.shouldReply) {
        const replyBody = buildAutoReply({
          previousState: conversation.state,
          customerName: transition.customerName,
          config: await repository.getTenantConfig(tenant.id)
        });
        const externalMessageId = await whatsappClient.sendText({
          phoneNumberId: tenant.whatsappPhoneNumberId,
          to: lead.customerPhone,
          body: replyBody
        });
        await repository.addOutboundMessage({
          tenantId: tenant.id,
          leadId: lead.id,
          body: replyBody,
          messageType: "TEXT",
          externalMessageId,
          idempotencyKey: `outbound:reply:${inbound.eventId}`
        });
      }

      if (transition.shouldScheduleFollowups) {
        await repository.scheduleFollowupJobs(tenant.id, lead.id);
      }

      if (transition.shouldNotifyOwner) {
        await notificationService.notifyOwnerLeadCaptured(tenant, updatedLead);
      }

      await repository.markProcessedEvent(inbound.eventId, "WHATSAPP");
    }

    return reply.code(200).send({ ok: true });
  });

  app.post("/webhooks/polar", async (request, reply) => {
    const rawBody = request.rawBody ?? JSON.stringify(request.body ?? {});
    const signature = request.headers["x-polar-signature"] as string | undefined;
    const valid = billingService.verifyWebhookSignature(signature, rawBody);

    if (!valid) {
      return reply.code(401).send({ error: "Invalid Polar webhook signature" });
    }

    const result = await billingService.handleWebhook(request.body, repository);
    return reply.code(200).send({ ok: true, updated: result.updated, tenantId: result.tenantId });
  });

  app.post("/admin/tenants", async (request, reply) => {
    const masterApiKey = request.headers["x-master-api-key"] as string | undefined;
    if (!hasMasterAccess(masterApiKey, config)) {
      return reply.code(401).send({ error: "Unauthorized" });
    }

    const payload = createTenantSchema.parse(request.body);
    const created = await repository.createTenant(payload);
    let checkoutUrl: string | undefined;
    if (payload.ownerEmail) {
      checkoutUrl = await billingService
        .createCheckoutUrl({ tenantId: created.tenant.id, customerEmail: payload.ownerEmail })
        .catch(() => undefined);
    }

    return reply.code(201).send({
      tenantId: created.tenant.id,
      tenantApiKey: created.tenantApiKey,
      trialEndsAt: created.tenant.trialEndsAt.toISOString(),
      checkoutUrl
    });
  });

  app.post("/admin/tenants/:tenantId/config", async (request, reply) => {
    const { tenantId } = request.params as { tenantId: string };
    const masterApiKey = request.headers["x-master-api-key"] as string | undefined;
    const tenantApiKey = request.headers["x-tenant-api-key"] as string | undefined;

    const isMaster = hasMasterAccess(masterApiKey, config);
    const isTenantKeyValid = tenantApiKey ? await repository.verifyTenantApiKey(tenantId, tenantApiKey) : false;

    if (!isMaster && !isTenantKeyValid) {
      return reply.code(401).send({ error: "Unauthorized" });
    }

    const payload = updateConfigSchema.parse(request.body);
    const updated = await repository.upsertTenantConfig({ tenantId, ...payload });
    return reply.code(200).send({ config: updated });
  });

  app.post("/internal/takeover", async (request, reply) => {
    const masterApiKey = request.headers["x-master-api-key"] as string | undefined;
    const tenantApiKey = request.headers["x-tenant-api-key"] as string | undefined;
    const body = takeoverSchema.parse(request.body);

    const isMaster = hasMasterAccess(masterApiKey, config);
    const isTenantKeyValid = tenantApiKey
      ? await repository.verifyTenantApiKey(body.tenantId, tenantApiKey)
      : false;

    if (!isMaster && !isTenantKeyValid) {
      return reply.code(401).send({ error: "Unauthorized" });
    }

    const customerPhone = parseTakeoverCommand(body.command);
    if (!customerPhone) {
      return reply.code(400).send({ error: "Command must be '#takeover <phone>'" });
    }

    const lead = await repository.findLeadByPhone(body.tenantId, customerPhone);
    if (!lead) {
      return reply.code(404).send({ error: "Lead not found for provided phone number" });
    }

    const tenantConfig = await repository.getTenantConfig(body.tenantId);
    const pausedUntil = new Date(Date.now() + tenantConfig.takeoverCooldownMinutes * 60 * 1000);
    await repository.markLeadTakeover(body.tenantId, lead.id, pausedUntil);
    await repository.recordAuditEvent({
      tenantId: body.tenantId,
      leadId: lead.id,
      actor: "OWNER",
      action: "MANUAL_TAKEOVER_STARTED",
      metadata: {
        command: body.command,
        pausedUntil: pausedUntil.toISOString()
      }
    });

    return reply.code(200).send({
      ok: true,
      leadId: lead.id,
      pausedUntil: pausedUntil.toISOString()
    });
  });

  return app;
}
