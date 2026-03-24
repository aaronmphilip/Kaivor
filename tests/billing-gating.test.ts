import crypto from "crypto";
import { describe, expect, it } from "vitest";
import { createApiApp } from "../apps/api/src/app.js";
import { createFakeServices } from "./helpers.js";

function sign(body: unknown, secret: string): string {
  const raw = JSON.stringify(body);
  const hash = crypto.createHmac("sha256", secret).update(raw).digest("hex");
  return `sha256=${hash}`;
}

describe("billing gate", () => {
  it("blocks auto replies when subscription is inactive", async () => {
    const services = createFakeServices();
    const tenant = services.repository.firstTenant();
    services.repository.subscription.set(tenant.id, {
      status: "INACTIVE",
      trialEndsAt: new Date(Date.now() - 3600_000)
    });

    const app = createApiApp(services);
    const payload = {
      entry: [
        {
          changes: [
            {
              value: {
                metadata: { phone_number_id: tenant.whatsappPhoneNumberId },
                messages: [
                  {
                    id: "wamid.billing.blocked",
                    from: "919111112222",
                    timestamp: String(Math.floor(Date.now() / 1000)),
                    type: "text",
                    text: { body: "Need info" }
                  }
                ]
              }
            }
          ]
        }
      ]
    };

    const response = await app.inject({
      method: "POST",
      url: "/webhooks/whatsapp",
      headers: {
        "x-hub-signature-256": sign(payload, services.config.whatsappAppSecret)
      },
      payload
    });

    expect(response.statusCode).toBe(200);
    expect(services.sentText.length).toBe(0);
    expect(services.repository.auditEvents.some((event) => event.action === "AUTOMATION_BLOCKED_BILLING")).toBe(
      true
    );
    await app.close();
  });
});
