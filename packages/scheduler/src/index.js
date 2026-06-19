import { buildFollowup30m } from "../../reply-engine/src/index.js";
const DEFAULT_FOLLOWUP_30M_EN = "Hey, just checking in. Are you still looking for this? Reply and we will help.";
const DEFAULT_FOLLOWUP_30M_HI = "Hey, quick check. Kya aapko abhi bhi help chahiye? Reply kar dijiye.";
const FOLLOWUP_24H_EN = "Hey, quick follow-up. Are you still looking for this? Reply and we will help.";
const FOLLOWUP_24H_HI = "Namaste, ek quick follow-up. Kya aapko abhi bhi help chahiye? Reply kar dijiye.";
export class FollowupScheduler {
    deps;
    constructor(deps) {
        this.deps = deps;
    }
    async run(limit = 20) {
        const jobs = await this.deps.repository.claimDueFollowupJobs(limit);
        for (const job of jobs) {
            await this.processJob(job).catch(async (error) => {
                const message = error instanceof Error ? error.message : "Unknown follow-up error";
                if (job.attemptCount >= this.deps.maxRetries) {
                    await this.deps.repository.markFollowupJobDead(job.id, message);
                    return;
                }
                const retryAt = new Date(Date.now() + 10 * 60 * 1000);
                await this.deps.repository.markFollowupJobFailure(job.id, message, retryAt);
            });
        }
    }
    async processJob(job) {
        const tenant = await this.deps.repository.getTenantById(job.tenantId);
        if (!tenant) {
            await this.deps.repository.markFollowupJobDead(job.id, "Tenant not found");
            return;
        }
        const lead = await this.deps.repository.getLeadById(job.tenantId, job.leadId);
        if (!lead) {
            await this.deps.repository.markFollowupJobDead(job.id, "Lead not found");
            return;
        }
        if (lead.botPausedUntil && lead.botPausedUntil > new Date()) {
            await this.deps.repository.markFollowupJobSkipped(job.id);
            return;
        }
        if (lead.lastInboundAt && lead.lastInboundAt > job.createdAt) {
            await this.deps.repository.markFollowupJobSkipped(job.id);
            return;
        }
        const config = await this.deps.repository.getTenantConfig(job.tenantId);
        const botToken = String(config.metadata.telegramBotToken ?? "");
        const language = lead.preferredLanguage === "hi" ? "hi" : "en";
        if (!botToken) {
            throw new Error("Missing telegramBotToken in tenant config metadata");
        }
        let body = "";
        let messageType = "TEXT";
        let externalMessageId = "";
        if (job.jobType === "FOLLOWUP_30M") {
            const configured30m = buildFollowup30m(config);
            body =
                language === "hi" && configured30m === DEFAULT_FOLLOWUP_30M_EN
                    ? DEFAULT_FOLLOWUP_30M_HI
                    : configured30m;
            externalMessageId = await this.deps.telegramClient.sendMessage({
                botToken,
                chatId: lead.customerPhone,
                text: body
            });
            messageType = "TEXT";
        }
        else {
            body = language === "hi" ? FOLLOWUP_24H_HI : FOLLOWUP_24H_EN;
            externalMessageId = await this.deps.telegramClient.sendMessage({
                botToken,
                chatId: lead.customerPhone,
                text: body
            });
            messageType = "TEXT";
        }
        await this.deps.repository.addOutboundMessage({
            tenantId: job.tenantId,
            leadId: job.leadId,
            body,
            messageType,
            externalMessageId,
            idempotencyKey: `${job.idempotencyKey}:attempt:${job.attemptCount}`
        });
        await this.deps.repository.markFollowupJobSuccess(job.id);
    }
}
