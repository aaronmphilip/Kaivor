import nodemailer, { type Transporter } from "nodemailer";
import type { SmtpConfig } from "../../config/src/index.js";
import type { Lead, LeadRepository, Tenant } from "../../storage/src/index.js";
import type { WhatsAppClient } from "../../whatsapp/src/index.js";

interface NotificationServiceDeps {
  repository: LeadRepository;
  whatsappClient: WhatsAppClient;
  smtp?: SmtpConfig;
}

export class NotificationService {
  private readonly mailer?: Transporter;

  constructor(private readonly deps: NotificationServiceDeps) {
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

  public async notifyOwnerLeadCaptured(tenant: Tenant, lead: Lead): Promise<void> {
    const owners = await this.deps.repository.listPrimaryOwners(tenant.id);
    const summary = [
      "New BharatClaw lead captured.",
      `Name: ${lead.customerName ?? "Not provided"}`,
      `Phone: ${lead.customerPhone}`,
      `Requirement: ${lead.requirement ?? "Not provided"}`
    ].join("\n");

    for (const owner of owners) {
      let whatsappDelivered = false;
      try {
        const messageId = await this.deps.whatsappClient.sendText({
          phoneNumberId: tenant.whatsappPhoneNumberId,
          to: owner.phone,
          body: summary
        });
        whatsappDelivered = true;
        await this.deps.repository.recordNotification({
          tenantId: tenant.id,
          leadId: lead.id,
          channel: "WHATSAPP_OWNER_ALERT",
          status: "SENT",
          payload: {
            ownerPhone: owner.phone,
            whatsappMessageId: messageId
          }
        });
      } catch (error) {
        await this.deps.repository.recordNotification({
          tenantId: tenant.id,
          leadId: lead.id,
          channel: "WHATSAPP_OWNER_ALERT",
          status: "FAILED",
          payload: {
            ownerPhone: owner.phone
          },
          error: error instanceof Error ? error.message : "Unknown WhatsApp notification error"
        });
      }

      if (whatsappDelivered || !owner.email || !this.mailer) {
        continue;
      }

      try {
        await this.mailer.sendMail({
          from: this.deps.smtp?.from,
          to: owner.email,
          subject: "BharatClaw: New WhatsApp lead",
          text: summary
        });
        await this.deps.repository.recordNotification({
          tenantId: tenant.id,
          leadId: lead.id,
          channel: "EMAIL_OWNER_ALERT",
          status: "SENT",
          payload: { ownerEmail: owner.email }
        });
      } catch (error) {
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
