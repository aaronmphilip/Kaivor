import crypto from "crypto";
import type { LeadRepository, SubscriptionStatus } from "../../storage/src/index.js";

export interface PolarConfig {
  accessToken?: string;
  webhookSecret?: string;
  productId?: string;
  appBaseUrl: string;
}

interface PolarWebhookPayload {
  type?: string;
  data?: {
    id?: string;
    status?: string;
    customer_id?: string;
    current_period_end?: string;
    trial_ends_at?: string;
    metadata?: Record<string, string>;
    product_id?: string;
  };
}

function parsePolarHeaderSignature(signatureHeader?: string): string | null {
  if (!signatureHeader) {
    return null;
  }
  const [prefix, value] = signatureHeader.split("=");
  if (prefix !== "sha256" || !value) {
    return null;
  }
  return value;
}

function mapPolarStatus(status?: string): SubscriptionStatus {
  const normalized = (status ?? "").toLowerCase();
  if (normalized === "active") {
    return "ACTIVE";
  }
  if (normalized === "trialing") {
    return "TRIALING";
  }
  if (normalized === "past_due") {
    return "PAST_DUE";
  }
  if (normalized === "canceled") {
    return "CANCELED";
  }
  return "INACTIVE";
}

export class PolarBillingService {
  constructor(private readonly config: PolarConfig) {}

  public verifyWebhookSignature(signatureHeader: string | undefined, rawBody: string): boolean {
    if (!this.config.webhookSecret) {
      return false;
    }

    const parsed = parsePolarHeaderSignature(signatureHeader);
    if (!parsed) {
      return false;
    }

    const expected = crypto.createHmac("sha256", this.config.webhookSecret).update(rawBody).digest("hex");
    const expectedBuffer = Buffer.from(expected, "hex");
    const actualBuffer = Buffer.from(parsed, "hex");

    if (expectedBuffer.length !== actualBuffer.length) {
      return false;
    }

    return crypto.timingSafeEqual(expectedBuffer, actualBuffer);
  }

  public async createCheckoutUrl(input: { tenantId: string; customerEmail?: string }): Promise<string> {
    if (!this.config.accessToken || !this.config.productId) {
      throw new Error("Polar is not configured for checkout");
    }

    const response = await fetch("https://api.polar.sh/v1/checkouts/", {
      method: "POST",
      headers: {
        Authorization: `Bearer ${this.config.accessToken}`,
        "Content-Type": "application/json"
      },
      body: JSON.stringify({
        product_id: this.config.productId,
        success_url: `${this.config.appBaseUrl}/billing/success`,
        cancel_url: `${this.config.appBaseUrl}/billing/cancel`,
        customer_email: input.customerEmail,
        metadata: {
          tenantId: input.tenantId
        }
      })
    });

    const payload = (await response.json()) as { url?: string; checkout_url?: string; error?: string };
    if (!response.ok) {
      throw new Error(payload.error ?? "Could not create Polar checkout session");
    }
    return payload.url ?? payload.checkout_url ?? "";
  }

  public async handleWebhook(
    payload: unknown,
    repository: LeadRepository
  ): Promise<{ updated: boolean; tenantId?: string }> {
    const body = payload as PolarWebhookPayload;
    const tenantId = body.data?.metadata?.tenantId;
    if (!tenantId) {
      return { updated: false };
    }

    const status = mapPolarStatus(body.data?.status);
    await repository.upsertSubscription({
      tenantId,
      provider: "POLAR",
      status,
      customerId: body.data?.customer_id,
      subscriptionId: body.data?.id,
      planCode: body.data?.product_id,
      currentPeriodEnd: body.data?.current_period_end ? new Date(body.data.current_period_end) : undefined,
      trialEndsAt: body.data?.trial_ends_at ? new Date(body.data.trial_ends_at) : undefined
    });

    await repository.recordAuditEvent({
      tenantId,
      actor: "POLAR_WEBHOOK",
      action: `SUBSCRIPTION_${status}`,
      metadata: {
        eventType: body.type ?? "unknown"
      }
    });

    return { updated: true, tenantId };
  }
}
