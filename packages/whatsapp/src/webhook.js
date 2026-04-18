function toTimestamp(raw) {
    if (!raw) {
        return Date.now();
    }
    const asNumber = Number(raw);
    if (Number.isNaN(asNumber)) {
        return Date.now();
    }
    return asNumber * 1000;
}
export function parseWhatsAppWebhook(payload) {
    const body = payload;
    const inbound = [];
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
