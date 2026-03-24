import type { ConversationState, LeadStatus } from "../../storage/src/types.js";

export interface TransitionResult {
  nextState: ConversationState;
  nextLeadStatus: LeadStatus;
  customerName?: string;
  requirement?: string;
  shouldNotifyOwner: boolean;
  shouldScheduleFollowups: boolean;
  shouldReply: boolean;
}

function cleanText(input: string, max = 120): string {
  return input.trim().replace(/\s+/g, " ").slice(0, max);
}

export function computeTransition(currentState: ConversationState, inboundText: string): TransitionResult {
  const text = cleanText(inboundText);

  if (currentState === "OWNER_TAKEOVER") {
    return {
      nextState: "OWNER_TAKEOVER",
      nextLeadStatus: "OWNER_TAKEOVER",
      shouldNotifyOwner: false,
      shouldScheduleFollowups: false,
      shouldReply: false
    };
  }

  if (currentState === "NEW") {
    return {
      nextState: "AWAITING_NAME",
      nextLeadStatus: "IN_PROGRESS",
      shouldNotifyOwner: false,
      shouldScheduleFollowups: false,
      shouldReply: true
    };
  }

  if (currentState === "AWAITING_NAME") {
    return {
      nextState: "AWAITING_REQUIREMENT",
      nextLeadStatus: "IN_PROGRESS",
      customerName: text,
      shouldNotifyOwner: false,
      shouldScheduleFollowups: false,
      shouldReply: true
    };
  }

  if (currentState === "AWAITING_REQUIREMENT") {
    return {
      nextState: "FOLLOWUP_PENDING",
      nextLeadStatus: "FOLLOWUP_PENDING",
      requirement: text,
      shouldNotifyOwner: true,
      shouldScheduleFollowups: true,
      shouldReply: true
    };
  }

  return {
    nextState: "FOLLOWUP_PENDING",
    nextLeadStatus: "FOLLOWUP_PENDING",
    shouldNotifyOwner: false,
    shouldScheduleFollowups: false,
    shouldReply: true
  };
}
