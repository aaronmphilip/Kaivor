import nodemailer from "nodemailer";
export class NotificationService {
    deps;
    mailer;
    constructor(deps) {
        this.deps = deps;
        if (deps.smtp) {
            this.mailer = nodemailer.createTransport({
                host: deps.smtp.host,
                port: deps.smtp.port,
                secure: false,
                auth: {
                    user: deps.smtp.user,
                    pass: deps.smtp.pass
                }
            });
        }
    }
    async notifyOwnerLeadCaptured(tenant, lead) {
        const owners = await this.deps.repository.listPrimaryOwners(tenant.id);
        const tenantConfig = await this.deps.repository.getTenantConfig(tenant.id);
        const botToken = String(tenantConfig.metadata.telegramBotToken ?? "");
        const summary = [
            "New Kaivor lead captured.",
            `Name: ${lead.customerName ?? "Not provided"}`,
            `Chat: ${lead.customerPhone}`,
            `Requirement: ${lead.requirement ?? "Not provided"}`,
            `Language: ${lead.preferredLanguage === "hi" ? "Hindi" : "English"}`
        ].join("\n");
        for (const owner of owners) {
            let telegramDelivered = false;
            try {
                if (!botToken) {
                    throw new Error("Missing telegramBotToken in tenant config metadata");
                }
                const messageId = await this.deps.telegramClient.sendMessage({
                    botToken,
                    chatId: owner.phone,
                    text: summary
                });
                telegramDelivered = true;
                await this.deps.repository.recordNotification({
                    tenantId: tenant.id,
                    leadId: lead.id,
                    channel: "TELEGRAM_OWNER_ALERT",
                    status: "SENT",
                    payload: {
                        ownerChatId: owner.phone,
                        telegramMessageId: messageId
                    }
                });
            }
            catch (error) {
                await this.deps.repository.recordNotification({
                    tenantId: tenant.id,
                    leadId: lead.id,
                    channel: "TELEGRAM_OWNER_ALERT",
                    status: "FAILED",
                    payload: {
                        ownerChatId: owner.phone
                    },
                    error: error instanceof Error ? error.message : "Unknown Telegram notification error"
                });
            }
            if (telegramDelivered || !owner.email || !this.mailer) {
                continue;
            }
            try {
                await this.mailer.sendMail({
                    from: this.deps.smtp?.from,
                    to: owner.email,
                    subject: "Kaivor: New Telegram lead",
                    text: summary
                });
                await this.deps.repository.recordNotification({
                    tenantId: tenant.id,
                    leadId: lead.id,
                    channel: "EMAIL_OWNER_ALERT",
                    status: "SENT",
                    payload: { ownerEmail: owner.email }
                });
            }
            catch (error) {
                await this.deps.repository.recordNotification({
                    tenantId: tenant.id,
                    leadId: lead.id,
                    channel: "EMAIL_OWNER_ALERT",
                    status: "FAILED",
                    payload: { ownerEmail: owner.email },
                    error: error instanceof Error ? error.message : "Unknown email notification error"
                });
            }
        }
    }
}
