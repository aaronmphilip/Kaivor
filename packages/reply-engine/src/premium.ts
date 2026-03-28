import type { LeadLanguage, TenantConfig } from "../../storage/src/types.js";
import type { TransitionResult } from "../../state-machine/src/premium.js";

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

function buildGreeting(businessName: string, language: LeadLanguage): string {
  if (language === "hi") {
    return `Namaste. ${businessName} se connect karne ke liye thanks. Main aapki help karta hoon, bas do quick details chahiye.`;
  }
  return `Hey, thanks for reaching out to ${businessName}. I will help you with this, just a couple quick details first.`;
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
    return clip(`${greeting}\nChoose your language:\n1. English\n2. Hindi`);
  }

  if (transition.replyKey === "ASK_LANGUAGE_RETRY") {
    return clip("Please choose one option to continue:\n1. English\n2. Hindi");
  }

  if (transition.replyKey === "ASK_NAME") {
    return language === "hi"
      ? clip(`${buildGreeting(businessName, language)}\n\nAapka naam kya hai?`)
      : clip(`${buildGreeting(businessName, language)}\n\nCan I get your name?`);
  }

  if (transition.replyKey === "ASK_REQUIREMENT") {
    if (language === "hi") {
      return safeFirstName
        ? clip(`Got it, ${safeFirstName}. Aapko aaj kis cheez mein help chahiye?`)
        : clip("Got it. Aapko aaj kis cheez mein help chahiye?");
    }
    return safeFirstName
      ? clip(`Got it, ${safeFirstName}. What are you looking for today?`)
      : clip("Got it. What are you looking for today?");
  }

  if (transition.replyKey === "REASK_REQUIREMENT") {
    return language === "hi"
      ? clip("Thoda aur clear bata do. Example: AC repair today, salon booking, gym trial.")
      : clip("Tell me a bit more clearly in one line. Example: AC repair today, salon booking, gym trial.");
  }

  if (transition.replyKey === "CONFIRM_CAPTURE") {
    return language === "hi"
      ? clip("Perfect. Details mil gayi. Owner aapse jaldi contact karega.")
      : clip("Perfect. I have your details. The owner will reach out shortly.");
  }

  return language === "hi"
    ? clip("Thanks. Message mil gaya. Zarurat padne par hum yahin reply karenge.")
    : clip("Thanks. We have your message and will reply here shortly.");
}
