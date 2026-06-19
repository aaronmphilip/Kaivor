import { afterEach, describe, expect, it } from "vitest";
import { createApiApp } from "../apps/api/src/app.js";
import { createFakeServices } from "./helpers.js";
function buildTelegramPayload(args) {
    return {
        update_id: args.updateId,
        message: {
            date: Math.floor(Date.now() / 1000),
            chat: { id: args.chatId },
            from: { id: args.chatId },
            text: args.text
        }
    };
}
describe("API telegram webhook routes", () => {
    const apps = [];
    afterEach(async () => {
        for (const app of apps) {
            await app.close();
        }
        apps.length = 0;
    });
    it("rejects invalid Telegram secret", async () => {
        const services = createFakeServices();
        const app = createApiApp(services);
        apps.push(app);
        const tenant = services.repository.firstTenant();
        const response = await app.inject({
            method: "POST",
            url: `/webhooks/telegram/${tenant.id}`,
            headers: {
                "x-telegram-bot-api-secret-token": "wrong-secret"
            },
            payload: buildTelegramPayload({
                updateId: 1,
                chatId: "919999991111",
                text: "hello"
            })
        });
        expect(response.statusCode).toBe(401);
    });
    it("captures lead details and schedules followups", async () => {
        const services = createFakeServices();
        const app = createApiApp(services);
        apps.push(app);
        const tenant = services.repository.firstTenant();
        await app.inject({
            method: "POST",
            url: `/webhooks/telegram/${tenant.id}`,
            headers: { "x-telegram-bot-api-secret-token": services.config.telegramWebhookSecret },
            payload: buildTelegramPayload({ updateId: 11, chatId: "919888887777", text: "Hi" })
        });
        await app.inject({
            method: "POST",
            url: `/webhooks/telegram/${tenant.id}`,
            headers: { "x-telegram-bot-api-secret-token": services.config.telegramWebhookSecret },
            payload: buildTelegramPayload({ updateId: 12, chatId: "919888887777", text: "English" })
        });
        await app.inject({
            method: "POST",
            url: `/webhooks/telegram/${tenant.id}`,
            headers: { "x-telegram-bot-api-secret-token": services.config.telegramWebhookSecret },
            payload: buildTelegramPayload({ updateId: 13, chatId: "919888887777", text: "Aman" })
        });
        await app.inject({
            method: "POST",
            url: `/webhooks/telegram/${tenant.id}`,
            headers: { "x-telegram-bot-api-secret-token": services.config.telegramWebhookSecret },
            payload: buildTelegramPayload({ updateId: 14, chatId: "919888887777", text: "Need washing machine repair" })
        });
        const lead = [...services.repository.leads.values()][0];
        expect(lead.customerName).toBe("Aman");
        expect(lead.requirement).toContain("repair");
        expect(lead.preferredLanguage).toBe("en");
        expect(lead.status).toBe("FOLLOWUP_PENDING");
        expect(services.repository.followupJobs.size).toBe(2);
        expect(services.sentMessages.length).toBe(4);
    });
    it("does not process duplicate event id twice", async () => {
        const services = createFakeServices();
        const app = createApiApp(services);
        apps.push(app);
        const tenant = services.repository.firstTenant();
        const payload = buildTelegramPayload({ updateId: 99, chatId: "919777776666", text: "Hello" });
        await app.inject({
            method: "POST",
            url: `/webhooks/telegram/${tenant.id}`,
            headers: { "x-telegram-bot-api-secret-token": services.config.telegramWebhookSecret },
            payload
        });
        await app.inject({
            method: "POST",
            url: `/webhooks/telegram/${tenant.id}`,
            headers: { "x-telegram-bot-api-secret-token": services.config.telegramWebhookSecret },
            payload
        });
        expect(services.sentMessages.length).toBe(1);
    });
    it("handles /start interruption by restarting flow", async () => {
        const services = createFakeServices();
        const app = createApiApp(services);
        apps.push(app);
        const tenant = services.repository.firstTenant();
        await app.inject({
            method: "POST",
            url: `/webhooks/telegram/${tenant.id}`,
            headers: { "x-telegram-bot-api-secret-token": services.config.telegramWebhookSecret },
            payload: buildTelegramPayload({ updateId: 201, chatId: "919555554444", text: "Hi" })
        });
        await app.inject({
            method: "POST",
            url: `/webhooks/telegram/${tenant.id}`,
            headers: { "x-telegram-bot-api-secret-token": services.config.telegramWebhookSecret },
            payload: buildTelegramPayload({ updateId: 202, chatId: "919555554444", text: "English" })
        });
        await app.inject({
            method: "POST",
            url: `/webhooks/telegram/${tenant.id}`,
            headers: { "x-telegram-bot-api-secret-token": services.config.telegramWebhookSecret },
            payload: buildTelegramPayload({ updateId: 203, chatId: "919555554444", text: "Riya" })
        });
        await app.inject({
            method: "POST",
            url: `/webhooks/telegram/${tenant.id}`,
            headers: { "x-telegram-bot-api-secret-token": services.config.telegramWebhookSecret },
            payload: buildTelegramPayload({ updateId: 204, chatId: "919555554444", text: "/start" })
        });
        const lead = [...services.repository.leads.values()][0];
        expect(lead.status).toBe("IN_PROGRESS");
        expect(lead.preferredLanguage).toBeNull();
        expect(lead.customerName).toBe("");
        expect(lead.requirement).toBe("");
        expect(services.sentMessages.length).toBe(4);
    });
});
