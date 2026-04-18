function normalizePhone(phone) {
    return phone.replace(/\D/g, "");
}
export class WhatsAppClient {
    config;
    constructor(config) {
        this.config = config;
    }
    async sendText(input) {
        const response = await this.send(input.phoneNumberId, {
            messaging_product: "whatsapp",
            to: normalizePhone(input.to),
            type: "text",
            text: {
                body: input.body
            }
        });
        return response.messages?.[0]?.id ?? "unknown";
    }
    async sendTemplate(input) {
        const response = await this.send(input.phoneNumberId, {
            messaging_product: "whatsapp",
            to: normalizePhone(input.to),
            type: "template",
            template: {
                name: input.templateName,
                language: {
                    code: input.languageCode ?? "en"
                }
            }
        });
        return response.messages?.[0]?.id ?? "unknown";
    }
    async send(phoneNumberId, payload) {
        const url = `https://graph.facebook.com/${this.config.apiVersion}/${phoneNumberId}/messages`;
        const response = await fetch(url, {
            method: "POST",
            headers: {
                Authorization: `Bearer ${this.config.accessToken}`,
                "Content-Type": "application/json"
            },
            body: JSON.stringify(payload)
        });
        const data = (await response.json());
        if (!response.ok || data.error) {
            const reason = data.error?.message ?? `WhatsApp API request failed (${response.status})`;
            throw new Error(reason);
        }
        return data;
    }
}
