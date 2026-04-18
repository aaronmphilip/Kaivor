export class TelegramClient {
    async sendMessage(input) {
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
        const payload = (await response.json());
        if (!response.ok || !payload.ok) {
            throw new Error(payload.description ?? `Telegram API failed (${response.status})`);
        }
        return String(payload.result?.message_id ?? "unknown");
    }
}
