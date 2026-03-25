import type { TelegramInboundMessage } from "./types.js";

interface TelegramUpdate {
  update_id?: number;
  message?: {
    date?: number;
    text?: string;
    from?: { id?: number | string };
    chat?: { id?: number | string };
  };
}

export function verifyTelegramSecret(secretHeader: string | undefined, expectedSecret: string): boolean {
  if (!secretHeader || !expectedSecret) {
    return false;
  }
  return secretHeader === expectedSecret;
}

export function parseTelegramWebhook(payload: unknown): TelegramInboundMessage[] {
  const body = payload as TelegramUpdate;
  if (!body.update_id || !body.message?.text || !body.message?.chat?.id) {
    return [];
  }

  return [
    {
      eventId: `telegram:${body.update_id}`,
      chatId: String(body.message.chat.id),
      fromId: String(body.message.from?.id ?? body.message.chat.id),
      text: body.message.text.trim(),
      timestamp: (body.message.date ?? Math.floor(Date.now() / 1000)) * 1000
    }
  ];
}
