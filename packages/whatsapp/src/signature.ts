import crypto from "crypto";

export function verifyWhatsAppSignature(input: {
  appSecret: string;
  signatureHeader?: string;
  rawBody: string;
}): boolean {
  const { appSecret, signatureHeader, rawBody } = input;
  if (!signatureHeader) {
    return false;
  }

  const [prefix, actualHex] = signatureHeader.split("=");
  if (prefix !== "sha256" || !actualHex) {
    return false;
  }

  const expectedHex = crypto.createHmac("sha256", appSecret).update(rawBody).digest("hex");
  const expectedBuffer = Buffer.from(expectedHex, "hex");
  const actualBuffer = Buffer.from(actualHex, "hex");

  if (expectedBuffer.length !== actualBuffer.length) {
    return false;
  }
  return crypto.timingSafeEqual(expectedBuffer, actualBuffer);
}
