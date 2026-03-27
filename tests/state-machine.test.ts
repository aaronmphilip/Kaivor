import { describe, expect, it } from "vitest";
import { computeTransition } from "../packages/state-machine/src/index.js";

describe("computeTransition", () => {
  it("moves NEW to AWAITING_NAME and asks language", () => {
    const result = computeTransition("NEW", "hi");
    expect(result.nextState).toBe("AWAITING_NAME");
    expect(result.nextLeadStatus).toBe("IN_PROGRESS");
    expect(result.replyKey).toBe("ASK_LANGUAGE");
  });

  it("captures language from AWAITING_NAME when language is not set", () => {
    const result = computeTransition("AWAITING_NAME", "English");
    expect(result.nextState).toBe("AWAITING_NAME");
    expect(result.preferredLanguage).toBe("en");
    expect(result.replyKey).toBe("ASK_NAME");
  });

  it("captures name from AWAITING_NAME when language is already selected", () => {
    const result = computeTransition("AWAITING_NAME", "Rahul Sharma", "en");
    expect(result.nextState).toBe("AWAITING_REQUIREMENT");
    expect(result.customerName).toBe("Rahul Sharma");
    expect(result.replyKey).toBe("ASK_REQUIREMENT");
  });

  it("captures requirement and triggers followup", () => {
    const result = computeTransition("AWAITING_REQUIREMENT", "Need AC repair today", "en");
    expect(result.nextState).toBe("FOLLOWUP_PENDING");
    expect(result.requirement).toContain("AC repair");
    expect(result.shouldScheduleFollowups).toBe(true);
    expect(result.shouldNotifyOwner).toBe(true);
    expect(result.replyKey).toBe("CONFIRM_CAPTURE");
  });

  it("handles /start as a hard restart", () => {
    const result = computeTransition("FOLLOWUP_PENDING", "/start", "hi");
    expect(result.nextState).toBe("AWAITING_NAME");
    expect(result.replyKey).toBe("ASK_LANGUAGE");
    expect(result.preferredLanguage).toBeNull();
    expect(result.clearLeadProfile).toBe(true);
  });

  it("re-asks requirement on low-signal messages", () => {
    const result = computeTransition("AWAITING_REQUIREMENT", "ok", "en");
    expect(result.nextState).toBe("AWAITING_REQUIREMENT");
    expect(result.replyKey).toBe("REASK_REQUIREMENT");
    expect(result.shouldScheduleFollowups).toBe(false);
  });

  it("allows language switch while awaiting requirement", () => {
    const result = computeTransition("AWAITING_REQUIREMENT", "Hindi", "en");
    expect(result.nextState).toBe("AWAITING_REQUIREMENT");
    expect(result.preferredLanguage).toBe("hi");
    expect(result.replyKey).toBe("REASK_REQUIREMENT");
  });
});
