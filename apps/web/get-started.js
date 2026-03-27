const WORKER_BASE_URL = "https://bharatclaw-telegram.bharatclaw.workers.dev";

function esc(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

function renderOwnerConnect(payload) {
  if (payload.ownerConnected) {
    return `
      <p style="margin:10px 0 0;"><strong>Owner is already connected.</strong> Send <code>Hi</code> to your bot to test lead capture.</p>
    `;
  }
  const connectUrl = String(payload.ownerConnectBotUrl || "https://t.me/bharatclawbot");
  const pairCode = String(payload.ownerPairCode || "");
  const tenantId = String(payload.tenantId || "");
  return `
    <p style="margin:10px 0 6px;"><strong>Final step:</strong> verify and connect owner.</p>
    <p style="margin:0 0 8px;">
      <a class="btn btn-primary" href="${esc(connectUrl)}" target="_blank" rel="noreferrer">Open @bharatclawbot</a>
    </p>
    <p style="margin:0 0 4px;">Send this pairing code in the bot:</p>
    <p style="margin:0 0 8px; display:flex; gap:8px; align-items:center; flex-wrap:wrap;">
      <code id="owner-pair-code">${esc(pairCode)}</code>
      <button id="copy-pair-code" class="btn btn-secondary" type="button" data-pair-code="${esc(pairCode)}">Copy Code</button>
    </p>
    <p style="margin:0 0 8px;">After sending code, click check status:</p>
    <p style="margin:0;">
      <button id="check-owner-status" class="btn btn-secondary" type="button" data-tenant-id="${esc(tenantId)}">
        Check Owner Connection
      </button>
    </p>
    <p id="owner-status-msg" class="hint" style="margin-top:8px;"></p>
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

function renderSuccess(payload) {
  return `
    <strong>Workspace created and bot webhook configured.</strong>
    <p style="margin:8px 0 6px;">Tenant ID: <code>${esc(payload.tenantId)}</code></p>
    <p style="margin:0 0 6px;">Bot Username: <code>@${esc(payload.botUsername)}</code></p>
    <p style="margin:0 0 6px;">Webhook URL: <code>${esc(payload.webhookUrl)}</code></p>
    ${renderOwnerConnect(payload)}
  `;
}

async function checkOwnerStatus(tenantId) {
  const statusEl = document.getElementById("owner-status-msg");
  if (!statusEl) return;
  statusEl.textContent = "Checking...";
  try {
    const response = await fetch(`${WORKER_BASE_URL}/public/free-trial/${tenantId}/status`);
    const data = await response.json();
    if (!response.ok) {
      throw new Error(data?.error || "Unable to check status");
    }
    statusEl.textContent = data.ownerConnected
      ? "Owner connected. Alerts are active."
      : "Owner not connected yet. Open @bharatclawbot, send pairing code, then check again.";
  } catch (error) {
    statusEl.textContent = error instanceof Error ? error.message : "Failed to check status";
  }
}

async function submitTrial(event) {
  event.preventDefault();
  const form = event.currentTarget;
  const errorEl = document.getElementById("error");
  const resultEl = document.getElementById("result");
  const submitBtn = form.querySelector('button[type="submit"]');
  errorEl.textContent = "";
  resultEl.style.display = "none";
  resultEl.innerHTML = "";

  const formData = new FormData(form);
  const payload = {
    businessName: String(formData.get("businessName") || "").trim(),
    ownerName: String(formData.get("ownerName") || "").trim(),
    telegramBotToken: String(formData.get("telegramBotToken") || "").trim(),
    ownerEmail: String(formData.get("ownerEmail") || "").trim() || undefined
  };

  if (!payload.businessName || !payload.ownerName || !payload.telegramBotToken) {
    errorEl.textContent = "Please fill all required fields.";
    return;
  }

  if (submitBtn) {
    submitBtn.disabled = true;
    submitBtn.textContent = "Creating...";
  }

  try {
    const response = await fetch(`${WORKER_BASE_URL}/public/free-trial`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(payload)
    });
    const data = await response.json().catch(() => ({}));
    if (!response.ok) {
      throw new Error(data?.error || data?.detail || "Unable to create workspace");
    }
    resultEl.innerHTML = renderSuccess(data);
    resultEl.style.display = "block";
    form.reset();

    const checkBtn = document.getElementById("check-owner-status");
    if (checkBtn) {
      checkBtn.addEventListener("click", () => {
        const tenantId = checkBtn.getAttribute("data-tenant-id");
        if (tenantId) {
          checkOwnerStatus(tenantId);
        }
      });
    }

    const copyBtn = document.getElementById("copy-pair-code");
    if (copyBtn) {
      copyBtn.addEventListener("click", async () => {
        const code = copyBtn.getAttribute("data-pair-code") || "";
        const copied = await copyPairCode(code);
        const statusEl = document.getElementById("owner-status-msg");
        if (statusEl) {
          statusEl.textContent = copied
            ? "Code copied. Send it in @bharatclawbot."
            : "Copy failed. Manually copy the code and send it in @bharatclawbot.";
        }
      });
    }
  } catch (error) {
    errorEl.textContent = error instanceof Error ? error.message : "Something went wrong. Try again.";
  } finally {
    if (submitBtn) {
      submitBtn.disabled = false;
      submitBtn.textContent = "Create Free Workspace";
    }
  }
}

document.getElementById("trial-form")?.addEventListener("submit", submitTrial);
