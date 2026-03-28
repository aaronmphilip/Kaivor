const WORKER_BASE_URL = "https://bharatclaw-telegram.bharatclaw.workers.dev";

let activeTenantId = "";
let latestPayload = null;
let statusTimer = null;
let statusAttempts = 0;
const MAX_STATUS_ATTEMPTS = 45;

function esc(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

function stopStatusPolling() {
  if (statusTimer) {
    window.clearInterval(statusTimer);
    statusTimer = null;
  }
}

function setResult(html) {
  const resultEl = document.getElementById("result");
  if (!resultEl) return;
  resultEl.innerHTML = html;
  resultEl.style.display = "block";
}

function pairedActions(payload) {
  return `
    <div class="result-actions">
      <a class="btn btn-primary" href="${esc(payload.leadEntryUrl || "#")}" target="_blank" rel="noreferrer">Open Lead Link</a>
      <a class="btn btn-secondary" href="${esc(payload.ownerConsoleUrl || payload.workspaceUrl || "#")}" target="_blank" rel="noreferrer">Open Owner Console</a>
    </div>
  `;
}

function renderPendingState(payload, statusText = "Waiting for pairing. Open @bharatclawbot and send the pairing code.") {
  return `
    <div class="result-stack">
      <span class="pill">Start ready</span>
      <strong class="result-title">Your BharatClaw start is ready</strong>
      <p class="result-copy">Pair the owner chat once and BharatClaw will activate on the shared Telegram bot.</p>
      <div class="pair-box">
        <span>Pairing code</span>
        <strong>${esc(payload.pairingCode || payload.ownerPairCode || "")}</strong>
      </div>
      <div class="result-actions">
        <a class="btn btn-primary" href="${esc(payload.ownerConnectUrl || payload.ownerConnectBotUrl || "https://t.me/bharatclawbot")}" target="_blank" rel="noreferrer">Pair in @bharatclawbot</a>
        <button id="copy-pair-code" class="btn btn-secondary" type="button" data-pair-code="${esc(payload.pairingCode || payload.ownerPairCode || "")}">Copy Pairing Code</button>
      </div>
      <p class="hint">If the deep link does not auto-send the code, copy it and paste it into <code>@bharatclawbot</code>.</p>
      <div class="status-box">
        <strong>Pairing status</strong>
        <span id="owner-status-msg">${esc(statusText)}</span>
      </div>
      <div class="result-actions">
        <button id="check-owner-status" class="btn btn-secondary" type="button" data-tenant-id="${esc(payload.tenantId || "")}">Check Connection</button>
      </div>
    </div>
  `;
}

function renderPairedState(payload) {
  return `
    <div class="result-stack">
      <span class="pill pill-success">Paired</span>
      <strong class="result-title">BharatClaw is live</strong>
      <p class="result-copy">Your owner chat is connected. Share the lead link with customers and BharatClaw will handle replies, capture, and follow-up.</p>
      ${pairedActions(payload)}
      <div class="status-box">
        <strong>What happens now</strong>
        <span>Customers who open the lead link will enter your BharatClaw lead flow automatically.</span>
      </div>
    </div>
  `;
}

async function copyPairCode(code) {
  if (!code) return false;
  try {
    await navigator.clipboard.writeText(code);
    return true;
  } catch {
    return false;
  }
}

function attachResultHandlers() {
  const copyBtn = document.getElementById("copy-pair-code");
  if (copyBtn) {
    copyBtn.addEventListener("click", async () => {
      const code = copyBtn.getAttribute("data-pair-code") || "";
      const copied = await copyPairCode(code);
      const statusEl = document.getElementById("owner-status-msg");
      if (statusEl) {
        statusEl.textContent = copied
          ? "Pairing code copied. Paste it into @bharatclawbot if needed."
          : "Copy failed. Please copy the pairing code manually and send it in @bharatclawbot.";
      }
    });
  }

  const checkBtn = document.getElementById("check-owner-status");
  if (checkBtn) {
    checkBtn.addEventListener("click", () => {
      const tenantId = checkBtn.getAttribute("data-tenant-id") || "";
      if (tenantId) {
        checkOwnerStatus(tenantId, true);
      }
    });
  }
}

async function checkOwnerStatus(tenantId, manual = false) {
  if (!tenantId) return;
  const statusEl = document.getElementById("owner-status-msg");
  if (statusEl && manual) {
    statusEl.textContent = "Checking connection...";
  }

  try {
    const response = await fetch(`${WORKER_BASE_URL}/public/start/${encodeURIComponent(tenantId)}/status`);
    const data = await response.json().catch(() => ({}));
    if (!response.ok) {
      throw new Error(data?.detail || data?.error || "Unable to check status");
    }

    if (data.ownerConnected) {
      stopStatusPolling();
      latestPayload = {
        ...(latestPayload || {}),
        ...data,
        tenantId,
        ownerConsoleUrl: data.ownerConsoleUrl || latestPayload?.ownerConsoleUrl || latestPayload?.workspaceUrl,
        leadEntryUrl: data.leadEntryUrl || latestPayload?.leadEntryUrl
      };
      setResult(renderPairedState(latestPayload));
      return;
    }

    if (statusEl) {
      statusEl.textContent = "Not paired yet. Open @bharatclawbot and send the pairing code to activate BharatClaw.";
    }
  } catch (error) {
    if (statusEl) {
      statusEl.textContent = error instanceof Error ? error.message : "Unable to check pairing right now.";
    }
  }
}

function startStatusPolling(tenantId) {
  activeTenantId = tenantId;
  statusAttempts = 0;
  stopStatusPolling();
  statusTimer = window.setInterval(() => {
    statusAttempts += 1;
    if (statusAttempts > MAX_STATUS_ATTEMPTS) {
      stopStatusPolling();
      return;
    }
    checkOwnerStatus(tenantId, false);
  }, 4000);
}

async function submitTrial(event) {
  event.preventDefault();
  const form = event.currentTarget;
  const errorEl = document.getElementById("error");
  const submitBtn = form.querySelector('button[type="submit"]');
  errorEl.textContent = "";
  stopStatusPolling();

  const formData = new FormData(form);
  const payload = {
    ownerName: String(formData.get("ownerName") || "").trim(),
    businessName: String(formData.get("businessName") || "").trim(),
    ownerEmail: String(formData.get("ownerEmail") || "").trim() || undefined
  };

  if (!payload.ownerName || !payload.businessName) {
    errorEl.textContent = "Please fill your name and business name.";
    return;
  }

  if (submitBtn) {
    submitBtn.disabled = true;
    submitBtn.textContent = "Starting...";
  }

  try {
    const response = await fetch(`${WORKER_BASE_URL}/public/start`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(payload)
    });
    const data = await response.json().catch(() => ({}));

    if (!response.ok) {
      const missingFields = data?.missing
        ? Object.entries(data.missing)
            .filter(([, missing]) => Boolean(missing))
            .map(([key]) => key)
        : [];
      const message = missingFields.length
        ? `Missing: ${missingFields.join(", ")}`
        : data?.detail
          ? `${data?.error || "Unable to start BharatClaw"}: ${data.detail}`
          : data?.error || "Unable to start BharatClaw";
      throw new Error(message);
    }

    latestPayload = data;
    setResult(data.ownerConnected ? renderPairedState(data) : renderPendingState(data));
    attachResultHandlers();
    form.reset();

    if (!data.ownerConnected && data.tenantId) {
      startStatusPolling(String(data.tenantId));
      checkOwnerStatus(String(data.tenantId), false);
    }
  } catch (error) {
    const rawMessage = error instanceof Error ? error.message : "Something went wrong. Try again.";
    if (rawMessage === "Shared bot is not configured on server") {
      errorEl.textContent = "Server is missing shared bot setup. Add OWNER_CONNECT_BOT_TOKEN and OWNER_CONNECT_BOT_SECRET to the Worker.";
    } else {
      errorEl.textContent = rawMessage;
    }
  } finally {
    if (submitBtn) {
      submitBtn.disabled = false;
      submitBtn.textContent = "Start";
    }
  }
}

document.getElementById("trial-form")?.addEventListener("submit", submitTrial);
