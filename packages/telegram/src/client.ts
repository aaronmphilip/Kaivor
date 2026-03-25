import type { TelegramSendMessageInput } from "./types.js";

export class TelegramClient {
  public async sendMessage(input: TelegramSendMessageInput): Promise<string> {
    const url = `https://api.telegram.org/bot${input.botToken}/sendMessage`;
    const response = await fetch(url, {
      method: "POST",
      headers: {
        "Content-Type": "application/json"
      },
      body: JSON.stringify({
        chat_id: input.chatId,
        text: input.text
      })
    });

    const payload = (await response.json()) as {
      ok: boolean;
      result?: { message_id?: number };
      description?: string;
    };
    if (!response.ok || !payload.ok) {
      throw new Error(payload.description ?? `Telegram API failed (${response.status})`);
    }
    return String(payload.result?.message_id ?? "unknown");
  }
}
