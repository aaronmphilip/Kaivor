import crypto from "crypto";
function parsePolarHeaderSignature(signatureHeader) {
    if (!signatureHeader) {
        return null;
    }
    const [prefix, value] = signatureHeader.split("=");
    if (prefix !== "sha256" || !value) {
        return null;
    }
    return value;
}
function mapPolarStatus(status) {
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
    config;
    constructor(config) {
        this.config = config;
    }
    verifyWebhookSignature(signatureHeader, rawBody) {
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
    async createCheckoutUrl(input) {
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
        const payload = (await response.json());
        if (!response.ok) {
            throw new Error(payload.error ?? "Could not create Polar checkout session");
        }
        return payload.url ?? payload.checkout_url ?? "";
    }
    async handleWebhook(payload, repository) {
        const body = payload;
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
