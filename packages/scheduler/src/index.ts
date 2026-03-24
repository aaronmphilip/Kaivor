import { buildFollowup30m } from "../../reply-engine/src/index.js";
import type { LeadRepository } from "../../storage/src/index.js";
import type { WhatsAppClient } from "../../whatsapp/src/index.js";

interface SchedulerDeps {
  repository: LeadRepository;
  whatsappClient: WhatsAppClient;
  maxRetries: number;
}

export class FollowupScheduler {
  constructor(private readonly deps: SchedulerDeps) {}

  public async run(limit = 20): Promise<void> {
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

  private async processJob(job: Awaited<ReturnType<LeadRepository["claimDueFollowupJobs"]>>[number]): Promise<void> {
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
    let body = "";
    let messageType: "TEXT" | "TEMPLATE" = "TEXT";
    let externalMessageId = "";

    if (job.jobType === "FOLLOWUP_30M") {
      body = buildFollowup30m(config);
      externalMessageId = await this.deps.whatsappClient.sendText({
        phoneNumberId: tenant.whatsappPhoneNumberId,
        to: lead.customerPhone,
        body
      });
      messageType = "TEXT";
    } else {
      body = `template:${config.followup24hTemplateName}`;
      externalMessageId = await this.deps.whatsappClient.sendTemplate({
        phoneNumberId: tenant.whatsappPhoneNumberId,
        to: lead.customerPhone,
        templateName: config.followup24hTemplateName
      });
      messageType = "TEMPLATE";
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
