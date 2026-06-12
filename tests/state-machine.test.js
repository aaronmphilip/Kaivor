import { describe, expect, it } from "vitest";
import { computeTransition } from "../packages/state-machine/src/premium.js";
describe("computeTransition", () => {
    it("moves NEW to AWAITING_NAME and asks for name", () => {
        const result = computeTransition("NEW", "hi");
        expect(result.nextState).toBe("AWAITING_NAME");
        expect(result.nextLeadStatus).toBe("IN_PROGRESS");
        expect(result.replyKey).toBe("ASK_NAME");
        expect(result.preferredLanguage).toBe("en");
    });
    it("captures name from AWAITING_NAME", () => {
        const result = computeTransition("AWAITING_NAME", "Rahul Sharma");
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
        expect(result.replyKey).toBe("ASK_NAME");
        expect(result.preferredLanguage).toBeNull();
        expect(result.clearLeadProfile).toBe(true);
    });
    it("re-asks requirement on low-signal messages", () => {
        const result = computeTransition("AWAITING_REQUIREMENT", "ok", "en");
        expect(result.nextState).toBe("AWAITING_REQUIREMENT");
        expect(result.replyKey).toBe("REASK_REQUIREMENT");
        expect(result.shouldScheduleFollowups).toBe(false);
    });
    it("infers hindi while awaiting requirement", () => {
        const result = computeTransition("AWAITING_REQUIREMENT", "Mujhe salon booking chahiye", "en");
        expect(result.nextState).toBe("FOLLOWUP_PENDING");
        expect(result.preferredLanguage).toBe("hi");
        expect(result.replyKey).toBe("CONFIRM_CAPTURE");
    });
    it("adds a quote-details step for quote workflows", () => {
        const requirement = computeTransition("AWAITING_REQUIREMENT", "Need office painting quote", "en", "quote_request");
        expect(requirement.nextState).toBe("AWAITING_WORKFLOW_DETAILS");
        expect(requirement.replyKey).toBe("ASK_QUOTE_DETAILS");
        expect(requirement.requirement).toContain("painting quote");
        expect(requirement.shouldNotifyOwner).toBe(false);
        const details = computeTransition("AWAITING_WORKFLOW_DETAILS", "2500 sq ft in Gurgaon next week budget 1.5 lakh", "en", "quote_request");
        expect(details.nextState).toBe("FOLLOWUP_PENDING");
        expect(details.replyKey).toBe("CONFIRM_CAPTURE");
        expect(details.workflowDetails).toContain("Gurgaon");
        expect(details.shouldNotifyOwner).toBe(true);
    });
    it("adds an appointment-details step for booking workflows", () => {
        const requirement = computeTransition("AWAITING_REQUIREMENT", "Need a dentist appointment", "en", "appointment_request");
        expect(requirement.nextState).toBe("AWAITING_WORKFLOW_DETAILS");
        expect(requirement.replyKey).toBe("ASK_APPOINTMENT_DETAILS");
        expect(requirement.shouldScheduleFollowups).toBe(false);
        const details = computeTransition("AWAITING_WORKFLOW_DETAILS", "Tomorrow after 5 pm, urgent pain", "en", "appointment_request");
        expect(details.nextState).toBe("FOLLOWUP_PENDING");
        expect(details.workflowDetails).toContain("Tomorrow");
        expect(details.shouldScheduleFollowups).toBe(true);
    });
});
