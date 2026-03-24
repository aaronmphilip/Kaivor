import crypto from "crypto";
import { describe, expect, it } from "vitest";
import { createApiApp } from "../apps/api/src/app.js";
import { createFakeServices } from "./helpers.js";

function sign(body: unknown, secret: string): string {
  const raw = JSON.stringify(body);
  const hash = crypto.createHmac("sha256", secret).update(raw).digest("hex");
  return `sha256=${hash}`;
}

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

    const inboundPayload = {
      entry: [
        {
          changes: [
            {
              value: {
                metadata: { phone_number_id: tenant.whatsappPhoneNumberId },
                messages: [
                  {
                    id: "wamid.takeover",
                    from: "919123456789",
                    timestamp: String(Math.floor(Date.now() / 1000)),
                    type: "text",
                    text: { body: "Are you there?" }
                  }
                ]
              }
            }
          ]
        }
      ]
    };

    const webhookResponse = await app.inject({
      method: "POST",
      url: "/webhooks/whatsapp",
      headers: { "x-hub-signature-256": sign(inboundPayload, services.config.whatsappAppSecret) },
      payload: inboundPayload
    });

    expect(webhookResponse.statusCode).toBe(200);
    expect(services.sentText.length).toBe(0);
    await app.close();
  });
});
