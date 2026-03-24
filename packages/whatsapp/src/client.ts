import type { WhatsAppSendTemplateInput, WhatsAppSendTextInput } from "./types.js";

interface WhatsAppClientConfig {
  accessToken: string;
  apiVersion: string;
}

interface GraphSendResponse {
  messages?: Array<{ id: string }>;
  error?: {
    message: string;
    code: number;
    type: string;
  };
}

function normalizePhone(phone: string): string {
  return phone.replace(/\D/g, "");
}

export class WhatsAppClient {
  constructor(private readonly config: WhatsAppClientConfig) {}

  public async sendText(input: WhatsAppSendTextInput): Promise<string> {
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

  public async sendTemplate(input: WhatsAppSendTemplateInput): Promise<string> {
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

  private async send(phoneNumberId: string, payload: Record<string, unknown>): Promise<GraphSendResponse> {
    const url = `https://graph.facebook.com/${this.config.apiVersion}/${phoneNumberId}/messages`;
    const response = await fetch(url, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${this.config.accessToken}`,
        "Content-Type": "application/json"
      },
      body: JSON.stringify(payload)
    });

    const data = (await response.json()) as GraphSendResponse;
    if (!response.ok || data.error) {
      const reason = data.error?.message ?? `WhatsApp API request failed (${response.status})`;
      throw new Error(reason);
    }
    return data;
  }
}
