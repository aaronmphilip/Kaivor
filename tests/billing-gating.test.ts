import { describe, expect, it } from "vitest";
import { createApiApp } from "../apps/api/src/app.js";
import { createFakeServices } from "./helpers.js";

describe("billing gate", () => {
  it("blocks auto replies when subscription is inactive", async () => {
    const services = createFakeServices();
    const tenant = services.repository.firstTenant();
    services.repository.subscription.set(tenant.id, {
      status: "INACTIVE",
      trialEndsAt: new Date(Date.now() - 3600_000)
    });

    const app = createApiApp(services);
    const response = await app.inject({
      method: "POST",
      url: `/webhooks/telegram/${tenant.id}`,
      headers: {
        "x-telegram-bot-api-secret-token": services.config.telegramWebhookSecret
      },
      payload: {
        update_id: 1001,
        message: {
          date: Math.floor(Date.now() / 1000),
          chat: { id: "919111112222" },
          from: { id: "919111112222" },
          text: "Need info"
        }
      }
    });

    expect(response.statusCode).toBe(200);
    expect(services.sentMessages.length).toBe(0);
    expect(services.repository.auditEvents.some((event) => event.action === "AUTOMATION_BLOCKED_BILLING")).toBe(
      true
    );
    await app.close();
  });
});
