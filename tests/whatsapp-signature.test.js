import { describe, expect, it } from "vitest";
import { parseTelegramWebhook, verifyTelegramSecret } from "../packages/telegram/src/index.js";
describe("telegram webhook helpers", () => {
    it("verifies secret token", () => {
        expect(verifyTelegramSecret("abc", "abc")).toBe(true);
        expect(verifyTelegramSecret("wrong", "abc")).toBe(false);
    });
    it("parses inbound text update", () => {
        const parsed = parseTelegramWebhook({
            update_id: 42,
            message: {
                date: 123,
                chat: { id: 91999 },
                from: { id: 91999 },
                text: "hello"
            }
        });
        expect(parsed.length).toBe(1);
        expect(parsed[0].eventId).toBe("telegram:42");
        expect(parsed[0].chatId).toBe("91999");
        expect(parsed[0].text).toBe("hello");
    });
});
