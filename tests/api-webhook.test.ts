import crypto from "crypto";
import { afterEach, describe, expect, it } from "vitest";
import { createApiApp } from "../apps/api/src/app.js";
import { createFakeServices } from "./helpers.js";

function sign(body: unknown, secret: string): string {
  const raw = JSON.stringify(body);
  const hash = crypto.createHmac("sha256", secret).update(raw).digest("hex");
  return `sha256=${hash}`;
}

function buildInboundPayload(args: {
  phoneNumberId: string;
  eventId: string;
  fromPhone: string;
  text: string;
}): Record<string, unknown> {
  return {
    entry: [
      {
        changes: [
          {
            value: {
              metadata: {
                phone_number_id: args.phoneNumberId
              },
              messages: [
                {
                  id: args.eventId,
                  from: args.fromPhone,
                  timestamp: String(Math.floor(Date.now() / 1000)),
                  type: "text",
                  text: {
                    body: args.text
                  }
                }
              ]
            }
          }
        ]
      }
    ]
  };
}

describe("API webhook routes", () => {
  const apps: Array<ReturnType<typeof createApiApp>> = [];

  afterEach(async () => {
    for (const app of apps) {
      await app.close();
    }
    apps.length = 0;
  });

  it("verifies WhatsApp webhook challenge", async () => {
    const services = createFakeServices();
    const app = createApiApp(services);
    apps.push(app);

    const response = await app.inject({
      method: "GET",
      url: `/webhooks/whatsapp?hub.mode=subscribe&hub.verify_token=${services.config.whatsappWebhookVerifyToken}&hub.challenge=abc123`
    });

    expect(response.statusCode).toBe(200);
    expect(response.body).toBe("abc123");
  });

  it("rejects invalid WhatsApp signature", async () => {
    const services = createFakeServices();
    const app = createApiApp(services);
    apps.push(app);

    const payload = buildInboundPayload({
      phoneNumberId: services.repository.firstTenant().whatsappPhoneNumberId,
      eventId: "wamid.invalid",
      fromPhone: "919999991111",
      text: "hello"
    });

    const response = await app.inject({
      method: "POST",
      url: "/webhooks/whatsapp",
      headers: {
        "x-hub-signature-256": "sha256=deadbeef"
      },
      payload
    });

    expect(response.statusCode).toBe(401);
  });

  it("captures lead details and schedules followups", async () => {
    const services = createFakeServices();
    const app = createApiApp(services);
    apps.push(app);
    const tenant = services.repository.firstTenant();

    const payload1 = buildInboundPayload({
      phoneNumberId: tenant.whatsappPhoneNumberId,
      eventId: "wamid.1",
      fromPhone: "919888887777",
      text: "Hi"
    });
    const payload2 = buildInboundPayload({
      phoneNumberId: tenant.whatsappPhoneNumberId,
      eventId: "wamid.2",
      fromPhone: "919888887777",
      text: "Aman"
    });
    const payload3 = buildInboundPayload({
      phoneNumberId: tenant.whatsappPhoneNumberId,
      eventId: "wamid.3",
      fromPhone: "919888887777",
      text: "Need washing machine repair"
    });

    await app.inject({
      method: "POST",
      url: "/webhooks/whatsapp",
      headers: { "x-hub-signature-256": sign(payload1, services.config.whatsappAppSecret) },
      payload: payload1
    });
    await app.inject({
      method: "POST",
      url: "/webhooks/whatsapp",
      headers: { "x-hub-signature-256": sign(payload2, services.config.whatsappAppSecret) },
      payload: payload2
    });
    await app.inject({
      method: "POST",
      url: "/webhooks/whatsapp",
      headers: { "x-hub-signature-256": sign(payload3, services.config.whatsappAppSecret) },
      payload: payload3
    });

    const lead = [...services.repository.leads.values()][0];
    expect(lead.customerName).toBe("Aman");
    expect(lead.requirement).toContain("repair");
    expect(lead.status).toBe("FOLLOWUP_PENDING");
    expect(services.repository.followupJobs.size).toBe(2);
    expect(services.sentText.length).toBe(3);
  });

  it("does not process duplicate event id twice", async () => {
    const services = createFakeServices();
    const app = createApiApp(services);
    apps.push(app);
    const tenant = services.repository.firstTenant();
    const payload = buildInboundPayload({
      phoneNumberId: tenant.whatsappPhoneNumberId,
      eventId: "wamid.dup.1",
      fromPhone: "919777776666",
      text: "Hello"
    });

    await app.inject({
      method: "POST",
      url: "/webhooks/whatsapp",
      headers: { "x-hub-signature-256": sign(payload, services.config.whatsappAppSecret) },
      payload
    });
    await app.inject({
      method: "POST",
      url: "/webhooks/whatsapp",
      headers: { "x-hub-signature-256": sign(payload, services.config.whatsappAppSecret) },
      payload
    });

    expect(services.sentText.length).toBe(1);
  });
});
