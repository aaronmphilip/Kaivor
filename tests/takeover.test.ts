import { describe, expect, it } from "vitest";
import { createApiApp } from "../apps/api/src/app.js";
import { createFakeServices } from "./helpers.js";

describe("manual takeover", () => {
  it("pauses bot for lead after #takeover command", async () => {
    const services = createFakeServices();
    const app = createApiApp(services);
    const tenant = services.repository.firstTenant();
    const lead = await services.repository.findOrCreateLead(tenant.id, "919123456789");
    await services.repository.getConversationForLead(tenant.id, lead.id);

    const takeoverResponse = await app.inject({
      method: "POST",
      url: "/internal/takeover",
      headers: {
        "x-master-api-key": services.config.masterApiKey
      },
      payload: {
        tenantId: tenant.id,
        command: "#takeover 919123456789"
      }
    });

    expect(takeoverResponse.statusCode).toBe(200);

    const pausedLead = await services.repository.getLeadById(tenant.id, lead.id);
    expect(pausedLead?.status).toBe("OWNER_TAKEOVER");
    expect(pausedLead?.botPausedUntil).toBeDefined();

    const webhookResponse = await app.inject({
      method: "POST",
      url: `/webhooks/telegram/${tenant.id}`,
      headers: { "x-telegram-bot-api-secret-token": services.config.telegramWebhookSecret },
      payload: {
        update_id: 9001,
        message: {
          date: Math.floor(Date.now() / 1000),
          chat: { id: "919123456789" },
          from: { id: "919123456789" },
          text: "Are you there?"
        }
      }
    });

    expect(webhookResponse.statusCode).toBe(200);
    expect(services.sentMessages.length).toBe(0);
    await app.close();
  });
});
