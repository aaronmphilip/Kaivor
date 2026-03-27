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

function cleanText(input: string, max = 120): string {
  return input.trim().replace(/\s+/g, " ").slice(0, max);
}

function parseLanguage(input: string): LeadLanguage | null {
  const normalized = cleanText(input, 40).toLowerCase().replace(/^\//, "");
  if (!normalized) {
    return null;
  }
  if (["english", "eng", "1", "en"].includes(normalized)) {
    return "en";
  }
  if (["hindi", "hind", "2", "हिंदी", "हिन्दी"].includes(normalized)) {
    return "hi";
  }
  return null;
}

function isRestartCommand(input: string): boolean {
  const text = cleanText(input, 40).toLowerCase();
  return /^\/(start|restart)\b/.test(text);
}

function looksLikeNonRequirement(input: string): boolean {
  const text = cleanText(input, 160).toLowerCase();
  if (!text) {
    return true;
  }
  if (text.length < 4) {
    return true;
  }
  if (/^[^a-z0-9\u0900-\u097f]+$/i.test(text)) {
    return true;
  }
  const smallTalk = new Set([
    "hi",
    "hello",
    "hey",
    "ok",
    "okay",
    "yes",
    "no",
    "thanks",
    "thank you",
    "hmm",
    "hmmm",
    "yo"
  ]);
  return smallTalk.has(text);
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
      replyKey: "ASK_LANGUAGE",
      preferredLanguage: null,
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
      replyKey: "ASK_LANGUAGE",
      shouldNotifyOwner: false,
      shouldScheduleFollowups: false,
      shouldReply: true
    };
  }

  if (currentState === "AWAITING_NAME") {
    const explicitLanguage = parseLanguage(text);

    if (!preferredLanguage) {
      if (!explicitLanguage) {
        return {
          nextState: "AWAITING_NAME",
          nextLeadStatus: "IN_PROGRESS",
          replyKey: "ASK_LANGUAGE_RETRY",
          shouldNotifyOwner: false,
          shouldScheduleFollowups: false,
          shouldReply: true
        };
      }
      return {
        nextState: "AWAITING_NAME",
        nextLeadStatus: "IN_PROGRESS",
        replyKey: "ASK_NAME",
        preferredLanguage: explicitLanguage,
        shouldNotifyOwner: false,
        shouldScheduleFollowups: false,
        shouldReply: true
      };
    }

    if (explicitLanguage) {
      return {
        nextState: "AWAITING_NAME",
        nextLeadStatus: "IN_PROGRESS",
        replyKey: "ASK_NAME",
        preferredLanguage: explicitLanguage,
        shouldNotifyOwner: false,
        shouldScheduleFollowups: false,
        shouldReply: true
      };
    }

    return {
      nextState: "AWAITING_REQUIREMENT",
      nextLeadStatus: "IN_PROGRESS",
      replyKey: "ASK_REQUIREMENT",
      customerName: text,
      shouldNotifyOwner: false,
      shouldScheduleFollowups: false,
      shouldReply: true
    };
  }

  if (currentState === "AWAITING_REQUIREMENT") {
    const explicitLanguage = parseLanguage(text);
    if (explicitLanguage) {
      return {
        nextState: "AWAITING_REQUIREMENT",
        nextLeadStatus: "IN_PROGRESS",
        replyKey: "REASK_REQUIREMENT",
        preferredLanguage: explicitLanguage,
        shouldNotifyOwner: false,
        shouldScheduleFollowups: false,
        shouldReply: true
      };
    }
    if (looksLikeNonRequirement(text)) {
      return {
        nextState: "AWAITING_REQUIREMENT",
        nextLeadStatus: "IN_PROGRESS",
        replyKey: "REASK_REQUIREMENT",
        shouldNotifyOwner: false,
        shouldScheduleFollowups: false,
        shouldReply: true
      };
    }
    return {
      nextState: "FOLLOWUP_PENDING",
      nextLeadStatus: "FOLLOWUP_PENDING",
      replyKey: "CONFIRM_CAPTURE",
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
    shouldNotifyOwner: false,
    shouldScheduleFollowups: false,
    shouldReply: true
  };
}
