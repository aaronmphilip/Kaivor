import type { LeadLanguage, TenantConfig } from "../../storage/src/types.js";
import type { TransitionResult } from "../../state-machine/src/index.js";

interface ReplyInput {
  transition: TransitionResult;
  customerName?: string;
  language?: LeadLanguage;
  config: TenantConfig;
}

function clip(text: string, max = 320): string {
  return text.trim().slice(0, max);
}

function firstName(name?: string): string {
  return clip((name ?? "").split(" ")[0] || "", 40);
}

function getBusinessName(config: TenantConfig): string {
  const fromMetadata = String(config.metadata?.businessName ?? "").trim();
  return fromMetadata || "our team";
}

export function buildAutoReply(input: ReplyInput): string {
  const { transition, customerName, config } = input;
  const language: LeadLanguage = transition.preferredLanguage ?? input.language ?? "en";
  const safeFirstName = firstName(customerName);
  const businessName = getBusinessName(config);
  const greeting = clip(
    String(config.greetingTemplate || "").replaceAll("{{business_name}}", businessName) ||
      `Hey thanks for reaching out to ${businessName}. I will help you with this. Just a couple quick details first.`
  );

  if (transition.replyKey === "ASK_LANGUAGE") {
    return clip(`${greeting}\nChoose your language:\n1) English\n2) हिन्दी`);
  }

  if (transition.replyKey === "ASK_LANGUAGE_RETRY") {
    return clip("Please pick one option to continue:\n1) English\n2) हिन्दी");
  }

  if (transition.replyKey === "ASK_NAME") {
    return language === "hi"
      ? clip("Badhiya. Jaldi se batao, aapka naam kya hai?")
      : clip("Awesome. Let us keep this quick. What should I call you?");
  }

  if (transition.replyKey === "ASK_REQUIREMENT") {
    if (language === "hi") {
      return safeFirstName
        ? clip(`${safeFirstName}, badhiya. Aapko kis kaam me help chahiye?`)
        : clip("Badhiya. Aapko kis kaam me help chahiye?");
    }
    return safeFirstName
      ? clip(`Perfect ${safeFirstName}. What do you need help with today?`)
      : clip("Perfect. What do you need help with today?");
  }

  if (transition.replyKey === "REASK_REQUIREMENT") {
    return language === "hi"
      ? clip("Samjha. Aap apni exact requirement ek line me bata do. Example: AC repair, salon booking, gym trial.")
      : clip("Got you. Please share your exact requirement in one line. Example: AC repair, salon booking, gym trial.");
  }

  if (transition.replyKey === "CONFIRM_CAPTURE") {
    return language === "hi"
      ? clip("Shukriya. Aapki details save ho gayi hain. Owner jaldi contact karega.")
      : clip("Got it. Your details are saved. The owner will contact you shortly.");
  }

  return language === "hi"
    ? clip("Shukriya. Message mil gaya. Hum jaldi revert karenge.")
    : clip("Thanks. We got your message and will get back shortly.");
}

export function buildFollowup30m(config: TenantConfig): string {
  return clip(config.followup30mTemplate);
}
