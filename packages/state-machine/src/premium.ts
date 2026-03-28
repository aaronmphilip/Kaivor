import type { ConversationState, LeadLanguage, LeadStatus } from "../../storage/src/types.js";

export type ReplyKey =
  | "ASK_LANGUAGE"
  | "ASK_LANGUAGE_RETRY"
  | "ASK_NAME"
  | "ASK_REQUIREMENT"
  | "REASK_REQUIREMENT"
  | "CONFIRM_CAPTURE"
  | "ACK";

export interface TransitionResult {
  nextState: ConversationState;
  nextLeadStatus: LeadStatus;
  replyKey: ReplyKey;
  preferredLanguage?: LeadLanguage | null;
  customerName?: string;
  requirement?: string;
  clearLeadProfile?: boolean;
  shouldNotifyOwner: boolean;
  shouldScheduleFollowups: boolean;
  shouldReply: boolean;
}

function cleanText(input: string, max = 160): string {
  return input.trim().replace(/\s+/g, " ").slice(0, max);
}

function inferLanguage(input: string, fallback?: LeadLanguage | null): LeadLanguage | null {
  const text = cleanText(input, 80).toLowerCase();
  if (!text) return fallback ?? null;
  if (/[^\u0000-\u007f]/.test(text)) return "hi";
  if (["hindi", "hin", "hi language"].includes(text)) return "hi";
  if (["english", "eng", "en"].includes(text)) return "en";
  return fallback ?? "en";
}

function isRestartCommand(input: string): boolean {
  const text = cleanText(input, 60).toLowerCase();
  return /^\/(start|restart)\b/.test(text);
}

function looksLikeMissingName(input: string): boolean {
  const text = cleanText(input, 80).toLowerCase();
  return !text || ["hi", "hello", "hey", "yo", "start", "ok", "okay"].includes(text);
}

function looksLikeNonRequirement(input: string): boolean {
  const text = cleanText(input, 160).toLowerCase();
  if (!text) return true;
  if (text.length < 4) return true;
  return ["hi", "hello", "hey", "ok", "okay", "yes", "no", "thanks", "thank you"].includes(text);
}

export function computeTransition(
  currentState: ConversationState,
  inboundText: string,
  preferredLanguage?: LeadLanguage | null
): TransitionResult {
  const text = cleanText(inboundText);

  if (currentState === "OWNER_TAKEOVER") {
    return {
      nextState: "OWNER_TAKEOVER",
      nextLeadStatus: "OWNER_TAKEOVER",
      replyKey: "ACK",
      shouldNotifyOwner: false,
      shouldScheduleFollowups: false,
      shouldReply: false
    };
  }

  if (isRestartCommand(text)) {
    return {
      nextState: "AWAITING_NAME",
      nextLeadStatus: "IN_PROGRESS",
      replyKey: "ASK_NAME",
      preferredLanguage: inferLanguage(text, preferredLanguage),
      customerName: "",
      requirement: "",
      clearLeadProfile: true,
      shouldNotifyOwner: false,
      shouldScheduleFollowups: false,
      shouldReply: true
    };
  }

  if (currentState === "NEW") {
    return {
      nextState: "AWAITING_NAME",
      nextLeadStatus: "IN_PROGRESS",
      replyKey: "ASK_NAME",
      preferredLanguage: inferLanguage(text, preferredLanguage),
      shouldNotifyOwner: false,
      shouldScheduleFollowups: false,
      shouldReply: true
    };
  }

  if (currentState === "AWAITING_NAME") {
    if (looksLikeMissingName(text)) {
      return {
        nextState: "AWAITING_NAME",
        nextLeadStatus: "IN_PROGRESS",
        replyKey: "ASK_NAME",
        preferredLanguage: inferLanguage(text, preferredLanguage),
        shouldNotifyOwner: false,
        shouldScheduleFollowups: false,
        shouldReply: true
      };
    }

    return {
      nextState: "AWAITING_REQUIREMENT",
      nextLeadStatus: "IN_PROGRESS",
      replyKey: "ASK_REQUIREMENT",
      preferredLanguage: inferLanguage(text, preferredLanguage),
      customerName: text,
      shouldNotifyOwner: false,
      shouldScheduleFollowups: false,
      shouldReply: true
    };
  }

  if (currentState === "AWAITING_REQUIREMENT") {
    if (looksLikeNonRequirement(text)) {
      return {
        nextState: "AWAITING_REQUIREMENT",
        nextLeadStatus: "IN_PROGRESS",
        replyKey: "REASK_REQUIREMENT",
        preferredLanguage: inferLanguage(text, preferredLanguage),
        shouldNotifyOwner: false,
        shouldScheduleFollowups: false,
        shouldReply: true
      };
    }

    return {
      nextState: "FOLLOWUP_PENDING",
      nextLeadStatus: "FOLLOWUP_PENDING",
      replyKey: "CONFIRM_CAPTURE",
      preferredLanguage: inferLanguage(text, preferredLanguage),
      requirement: text,
      shouldNotifyOwner: true,
      shouldScheduleFollowups: true,
      shouldReply: true
    };
  }

  return {
    nextState: "FOLLOWUP_PENDING",
    nextLeadStatus: "FOLLOWUP_PENDING",
    replyKey: "ACK",
    preferredLanguage: inferLanguage(text, preferredLanguage),
    shouldNotifyOwner: false,
    shouldScheduleFollowups: false,
    shouldReply: true
  };
}
