function cleanText(input, max = 160) {
    return input.trim().replace(/\s+/g, " ").slice(0, max);
}
function inferLanguage(input, fallback) {
    const text = cleanText(input, 80).toLowerCase();
    if (!text)
        return fallback ?? null;
    if (/[^\u0000-\u007f]/.test(text))
        return "hi";
    if (["hindi", "hin", "hi language"].includes(text))
        return "hi";
    if (["english", "eng", "en"].includes(text))
        return "en";
    if (/\b(mujhe|mujko|chahiye|karna|karo|hai|nahi|haan|kal|aaj|abhi|jaldi|kitna|kaise|kahan)\b/.test(text))
        return "hi";
    return fallback ?? "en";
}
function parseExplicitLanguage(input) {
    const text = cleanText(input, 40).toLowerCase().replace(/^\//, "");
    if (["hindi", "hin", "hi", "2", "hi language"].includes(text))
        return "hi";
    if (["english", "eng", "en", "1"].includes(text))
        return "en";
    return null;
}
function isRestartCommand(input) {
    const text = cleanText(input, 60).toLowerCase();
    return /^\/(start|restart)\b/.test(text);
}
function looksLikeMissingName(input) {
    const text = cleanText(input, 80).toLowerCase();
    return !text || ["hi", "hello", "hey", "yo", "start", "ok", "okay"].includes(text);
}
function looksLikeNonRequirement(input) {
    const text = cleanText(input, 160).toLowerCase();
    if (!text)
        return true;
    if (text.length < 4)
        return true;
    return ["hi", "hello", "hey", "ok", "okay", "yes", "no", "thanks", "thank you"].includes(text);
}
export function computeTransition(currentState, inboundText, preferredLanguage) {
    const text = cleanText(inboundText);
    const workflowMode = arguments.length > 3 && arguments[3] !== undefined ? arguments[3] : "lead_capture";
    const explicitLanguage = parseExplicitLanguage(text);
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
            preferredLanguage: null,
            customerName: "",
            requirement: "",
            workflowMode,
            workflowDetails: "",
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
            workflowMode,
            shouldNotifyOwner: false,
            shouldScheduleFollowups: false,
            shouldReply: true
        };
    }
    if (currentState === "AWAITING_NAME") {
        if (explicitLanguage) {
            return {
                nextState: "AWAITING_NAME",
                nextLeadStatus: "IN_PROGRESS",
                replyKey: "ASK_NAME",
                preferredLanguage: explicitLanguage,
                workflowMode,
                shouldNotifyOwner: false,
                shouldScheduleFollowups: false,
                shouldReply: true
            };
        }
        if (looksLikeMissingName(text)) {
            return {
                nextState: "AWAITING_NAME",
                nextLeadStatus: "IN_PROGRESS",
                replyKey: "ASK_NAME",
                preferredLanguage: inferLanguage(text, preferredLanguage),
                workflowMode,
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
            workflowMode,
            shouldNotifyOwner: false,
            shouldScheduleFollowups: false,
            shouldReply: true
        };
    }
    if (currentState === "AWAITING_REQUIREMENT") {
        if (explicitLanguage) {
            return {
                nextState: "AWAITING_REQUIREMENT",
                nextLeadStatus: "IN_PROGRESS",
                replyKey: "REASK_REQUIREMENT",
                preferredLanguage: explicitLanguage,
                workflowMode,
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
                preferredLanguage: inferLanguage(text, preferredLanguage),
                workflowMode,
                shouldNotifyOwner: false,
                shouldScheduleFollowups: false,
                shouldReply: true
            };
        }
        if (workflowMode === "quote_request") {
            return {
                nextState: "AWAITING_WORKFLOW_DETAILS",
                nextLeadStatus: "IN_PROGRESS",
                replyKey: "ASK_QUOTE_DETAILS",
                preferredLanguage: inferLanguage(text, preferredLanguage),
                requirement: text,
                workflowMode,
                shouldNotifyOwner: false,
                shouldScheduleFollowups: false,
                shouldReply: true
            };
        }
        if (workflowMode === "appointment_request") {
            return {
                nextState: "AWAITING_WORKFLOW_DETAILS",
                nextLeadStatus: "IN_PROGRESS",
                replyKey: "ASK_APPOINTMENT_DETAILS",
                preferredLanguage: inferLanguage(text, preferredLanguage),
                requirement: text,
                workflowMode,
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
            workflowMode,
            shouldNotifyOwner: true,
            shouldScheduleFollowups: true,
            shouldReply: true
        };
    }
    if (currentState === "AWAITING_WORKFLOW_DETAILS") {
        if (looksLikeNonRequirement(text)) {
            return {
                nextState: "AWAITING_WORKFLOW_DETAILS",
                nextLeadStatus: "IN_PROGRESS",
                replyKey: workflowMode === "appointment_request" ? "ASK_APPOINTMENT_DETAILS" : "ASK_QUOTE_DETAILS",
                preferredLanguage: inferLanguage(text, preferredLanguage),
                workflowMode,
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
            workflowMode,
            workflowDetails: text,
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
        workflowMode,
        shouldNotifyOwner: false,
        shouldScheduleFollowups: false,
        shouldReply: true
    };
}
