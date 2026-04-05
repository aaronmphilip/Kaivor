(function bootstrapSite() {
  const fallbackWorkerBase = "https://bharatclaw-telegram.bharatclaw.workers.dev";
  const configuredWorkerBase =
    document.querySelector('meta[name="bharatclaw-worker-base"]')?.getAttribute("content")?.trim() || "";
  const useCurrentOrigin = /workers\.dev$/i.test(window.location.hostname);
  const prefersReducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;

  window.BharatClawSite = Object.freeze({
    workerBaseUrl: useCurrentOrigin ? window.location.origin : configuredWorkerBase || fallbackWorkerBase
  });

  if (!prefersReducedMotion) {
    document.body?.classList.add("motion-safe");
  }

  const activePage = document.body?.dataset.page || "";
  document.querySelectorAll("[data-nav]").forEach((link) => {
    if (link.getAttribute("data-nav") === activePage) {
      link.classList.add("nav-link-active");
    }
  });

  initRevealMotion(prefersReducedMotion);
  initAgentTheater(prefersReducedMotion);
})();

function initRevealMotion(prefersReducedMotion) {
  const nodes = Array.from(document.querySelectorAll("[data-reveal]"));
  if (!nodes.length) return;

  nodes.forEach((node, index) => {
    node.style.setProperty("--reveal-delay", `${Math.min(index * 70, 280)}ms`);
  });

  if (prefersReducedMotion || !("IntersectionObserver" in window)) {
    nodes.forEach((node) => node.classList.add("is-visible"));
    return;
  }

  const observer = new IntersectionObserver(
    (entries) => {
      entries.forEach((entry) => {
        if (!entry.isIntersecting) return;
        entry.target.classList.add("is-visible");
        observer.unobserve(entry.target);
      });
    },
    {
      threshold: 0.16,
      rootMargin: "0px 0px -8% 0px"
    }
  );

  nodes.forEach((node) => observer.observe(node));
}

function initAgentTheater(prefersReducedMotion) {
  const root = document.querySelector("[data-agent-theater]");
  if (!root) return;

  const bubbles = {
    bot1: root.querySelector('[data-agent-bubble="bot-1"]'),
    user1: root.querySelector('[data-agent-bubble="user-1"]'),
    bot2: root.querySelector('[data-agent-bubble="bot-2"]'),
    user2: root.querySelector('[data-agent-bubble="user-2"]'),
    bot3: root.querySelector('[data-agent-bubble="bot-3"]')
  };
  const sceneLabel = root.querySelector("[data-agent-scene-label]");
  const status = root.querySelector("[data-agent-status]");
  const intent = root.querySelector("[data-agent-intent]");
  const temperature = root.querySelector("[data-agent-temperature]");
  const automation = root.querySelector("[data-agent-automation]");
  const ownerSummary = root.querySelector("[data-agent-owner-summary]");
  const prompt = root.querySelector("[data-agent-prompt]");
  const command = root.querySelector("[data-agent-command]");
  const triggers = Array.from(root.querySelectorAll("[data-agent-trigger]"));

  const scenes = [
    {
      label: "Gym Trial Rescue",
      status: "AI copilot is qualifying a same-day fitness lead.",
      bubbles: {
        bot1: "Hey, thanks for messaging PrimeForm Fitness. I can help with that. First, what should I call you?",
        user1: "Rahul",
        bot2: "Perfect, Rahul. What are you looking for today?",
        user2: "Need a gym trial this evening if possible",
        bot3: "We can help with that. I have marked this as a same-day trial request and the owner has the brief already."
      },
      intent: "Same-day trial",
      temperature: "Hot",
      automation: "Capture, qualify, alert owner, and keep the lead warm before the visit.",
      ownerSummary: "Rahul wants a same-day gym trial and is showing visit intent. Owner should confirm the slot and greet personally.",
      prompt: "Owner view: hot lead, local intent, likely to convert on a fast handoff.",
      command: "#ai 919900110022"
    },
    {
      label: "Home Service Intake",
      status: "AI copilot is turning a vague service message into owner-ready context.",
      bubbles: {
        bot1: "Thanks for reaching out to SwiftCool Repair. What should I call you?",
        user1: "Neha",
        bot2: "Thanks, Neha. Tell me what you need help with.",
        user2: "AC not cooling and I need someone tomorrow morning",
        bot3: "Got it. I have tagged this as an urgent cooling issue and queued the owner with the summary."
      },
      intent: "Urgent service request",
      temperature: "Hot",
      automation: "Classify urgency, preserve the service brief, and prompt owner takeover for scheduling confidence.",
      ownerSummary: "Neha has a time-sensitive AC repair request for tomorrow morning. This is operationally urgent and worth a direct owner reply.",
      prompt: "Owner view: urgent request with timing pressure. Respond with availability, not a generic follow-up.",
      command: "#reply 919900220033 We can check tomorrow morning between 9 and 11 AM. Should I lock that in?"
    },
    {
      label: "Clinic Consultation Flow",
      status: "AI copilot is keeping a consultation lead warm while preparing the handoff.",
      bubbles: {
        bot1: "Welcome to Arogya Skin Clinic. I can help get your request to the right person. What is your name?",
        user1: "Ananya",
        bot2: "Thanks, Ananya. What would you like help with today?",
        user2: "Need consultation for acne treatment and pricing details",
        bot3: "Thanks, Ananya. I have shared your consultation request with the clinic team and flagged pricing sensitivity for a careful follow-up."
      },
      intent: "Consultation with pricing sensitivity",
      temperature: "Warm",
      automation: "Collect the need, flag trust-sensitive intent, and prepare a thoughtful owner draft.",
      ownerSummary: "Ananya is interested, but pricing trust matters here. A measured human reply will outperform a hard-sell chatbot tone.",
      prompt: "Owner view: trust-sensitive lead. Give reassurance, consultation clarity, and next-step options.",
      command: "#takeover 919900330044"
    }
  ];

  let activeIndex = 0;
  let timerId = null;

  function renderScene(index) {
    const scene = scenes[index];
    if (!scene) return;
    activeIndex = index;

    if (sceneLabel) sceneLabel.textContent = scene.label;
    if (status) status.textContent = scene.status;
    if (intent) intent.textContent = scene.intent;
    if (temperature) temperature.textContent = scene.temperature;
    if (automation) automation.textContent = scene.automation;
    if (ownerSummary) ownerSummary.textContent = scene.ownerSummary;
    if (prompt) prompt.textContent = scene.prompt;
    if (command) command.textContent = scene.command;

    Object.entries(scene.bubbles).forEach(([key, value]) => {
      if (bubbles[key]) bubbles[key].textContent = value;
    });

    triggers.forEach((trigger, triggerIndex) => {
      const isActive = triggerIndex === index;
      trigger.classList.toggle("is-active", isActive);
      trigger.setAttribute("aria-pressed", isActive ? "true" : "false");
    });
  }

  function stopRotation() {
    if (timerId) {
      window.clearInterval(timerId);
      timerId = null;
    }
  }

  function startRotation() {
    stopRotation();
    if (prefersReducedMotion) return;
    timerId = window.setInterval(() => {
      renderScene((activeIndex + 1) % scenes.length);
    }, 4800);
  }

  triggers.forEach((trigger, triggerIndex) => {
    trigger.addEventListener("click", () => {
      renderScene(triggerIndex);
      startRotation();
    });
  });

  root.addEventListener("mouseenter", stopRotation);
  root.addEventListener("mouseleave", startRotation);

  renderScene(0);
  startRotation();
}
