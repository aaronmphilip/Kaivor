import type { WhatsAppInboundMessage } from "./types.js";

interface WebhookMessage {
  id?: string;
  from?: string;
  timestamp?: string;
  type?: string;
  text?: { body?: string };
}

interface WebhookChangeValue {
  metadata?: { phone_number_id?: string };
  messages?: WebhookMessage[];
}

interface WebhookPayload {
  entry?: Array<{
    changes?: Array<{
      value?: WebhookChangeValue;
    }>;
  }>;
}

function toTimestamp(raw?: string): number {
  if (!raw) {
    return Date.now();
  }
  const asNumber = Number(raw);
  if (Number.isNaN(asNumber)) {
    return Date.now();
  }
  return asNumber * 1000;
}

export function parseWhatsAppWebhook(payload: unknown): WhatsAppInboundMessage[] {
  const body = payload as WebhookPayload;
  const inbound: WhatsAppInboundMessage[] = [];

  for (const entry of body.entry ?? []) {
    for (const change of entry.changes ?? []) {
      const value = change.value;
      const phoneNumberId = value?.metadata?.phone_number_id;
      if (!phoneNumberId) {
        continue;
      }
      for (const message of value.messages ?? []) {
        if (!message.id || !message.from) {
          continue;
        }
        if (message.type !== "text" || !message.text?.body) {
          continue;
        }
        inbound.push({
          eventId: message.id,
          fromPhone: message.from,
          text: message.text.body.trim(),
          phoneNumberId,
          timestamp: toTimestamp(message.timestamp)
        });
      }
    }
  }

  return inbound;
}
