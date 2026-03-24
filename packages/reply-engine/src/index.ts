import type { ConversationState, TenantConfig } from "../../storage/src/types.js";

interface ReplyInput {
  previousState: ConversationState;
  customerName?: string;
  config: TenantConfig;
}

function clip(text: string, max = 320): string {
  return text.trim().slice(0, max);
}

export function buildAutoReply(input: ReplyInput): string {
  const { previousState, customerName, config } = input;

  if (previousState === "NEW") {
    return clip(config.greetingTemplate);
  }

  if (previousState === "AWAITING_NAME") {
    const safeName = customerName ? customerName.split(" ")[0] : "dost";
    return clip(`Great ${safeName}. Aapko kya requirement hai? Short me bata do.`);
  }

  if (previousState === "AWAITING_REQUIREMENT") {
    return clip("Perfect. Details mil gaye. Owner jaldi contact karega.");
  }

  return clip("Thanks. Humne message receive kar liya hai.");
}

export function buildFollowup30m(config: TenantConfig): string {
  return clip(config.followup30mTemplate);
}
