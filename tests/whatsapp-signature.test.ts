import crypto from "crypto";
import { describe, expect, it } from "vitest";
import { verifyWhatsAppSignature } from "../packages/whatsapp/src/signature.js";

describe("verifyWhatsAppSignature", () => {
  it("accepts valid signature", () => {
    const body = JSON.stringify({ hello: "world" });
    const secret = "app-secret";
    const hash = crypto.createHmac("sha256", secret).update(body).digest("hex");
    const valid = verifyWhatsAppSignature({
      appSecret: secret,
      signatureHeader: `sha256=${hash}`,
      rawBody: body
    });
    expect(valid).toBe(true);
  });

  it("rejects wrong signature", () => {
    const valid = verifyWhatsAppSignature({
      appSecret: "app-secret",
      signatureHeader: "sha256=deadbeef",
      rawBody: JSON.stringify({ hello: "world" })
    });
    expect(valid).toBe(false);
  });
});
