import { describe, expect, it } from "vitest";
import { computeTransition } from "../packages/state-machine/src/index.js";

describe("computeTransition", () => {
  it("moves NEW to AWAITING_NAME", () => {
    const result = computeTransition("NEW", "hi");
    expect(result.nextState).toBe("AWAITING_NAME");
    expect(result.nextLeadStatus).toBe("IN_PROGRESS");
  });

  it("captures name from AWAITING_NAME", () => {
    const result = computeTransition("AWAITING_NAME", "Rahul Sharma");
    expect(result.nextState).toBe("AWAITING_REQUIREMENT");
    expect(result.customerName).toBe("Rahul Sharma");
  });

  it("captures requirement and triggers followup", () => {
    const result = computeTransition("AWAITING_REQUIREMENT", "Need AC repair today");
    expect(result.nextState).toBe("FOLLOWUP_PENDING");
    expect(result.requirement).toContain("AC repair");
    expect(result.shouldScheduleFollowups).toBe(true);
    expect(result.shouldNotifyOwner).toBe(true);
  });
});
