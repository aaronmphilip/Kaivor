const WORKER_BASE_URL =
  window.BharatClawSite?.workerBaseUrl || "https://bharatclaw-telegram.bharatclaw.workers.dev";
const TOKEN_KEY = "bharatclaw_token";

let activeTenantId = "";
let latestPayload = null;
let statusTimer = null;
let statusAttempts = 0;
let currentUser = null;
let rememberedTenants = [];
const MAX_STATUS_ATTEMPTS = 45;

function esc(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

function getToken() {
  return window.localStorage.getItem(TOKEN_KEY) || "";
}

function clearToken() {
  window.localStorage.removeItem(TOKEN_KEY);
}

async function api(path, options = {}) {
  const token = getToken();
  const headers = {};
  if (options.body !== undefined) {
    headers["content-type"] = "application/json";
  }
  if (token) {
    headers.authorization = `Bearer ${token}`;
  }

  const response = await fetch(`${WORKER_BASE_URL}${path}`, {
    method: options.method || (options.body !== undefined ? "POST" : "GET"),
    headers,
    body: options.body !== undefined ? JSON.stringify(options.body) : undefined
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) {
    throw new Error(data?.detail || data?.error || "Request failed");
  }
  return data;
}

function focusItems(profile) {
  return Array.isArray(profile?.launchFocus) ? profile.launchFocus : [];
}

function renderProfileChips(payload) {
  const chips = [];
  const profile = payload?.profile || {};
  if (profile.industry) chips.push(profile.industry);
  if (profile.teamSize) chips.push(profile.teamSize);
  if (profile.channelMode) chips.push(profile.channelMode);
  focusItems(profile).forEach((item) => chips.push(item.label));

  if (!chips.length) return "";

  return `
    <div class="meta-tags">
      ${chips.map((chip) => `<span class="pill">${esc(chip)}</span>`).join("")}
    </div>
  `;
}

function renderFocusCard(payload) {
  const focus = focusItems(payload?.profile);
  if (!focus.length) return "";

  return `
    <div class="card">
      <h3>Configured focus</h3>
      <ul class="feature-list">
        ${focus
          .map((item) => `<li>${esc(item.label)} (${esc(item.availabilityLabel)}): ${esc(item.summary)}</li>`)
          .join("")}
      </ul>
    </div>
  `;
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
  resultEl.style.display = html ? "block" : "none";
}

function disconnectButton(payload, mode = "result") {
  if (!getToken() || !payload?.tenantId) return "";
  return `<button class="btn btn-secondary disconnect-pairing-btn" type="button" data-tenant-id="${esc(payload.tenantId)}" data-mode="${esc(mode)}">Disconnect Pairing</button>`;
}

function pairedActions(payload) {
  return `
    <div class="result-actions">
      <a class="btn btn-primary" href="${esc(payload.leadEntryUrl || "#")}" target="_blank" rel="noreferrer">Open Lead Link</a>
      <a class="btn btn-secondary" href="${esc(payload.ownerConsoleUrl || payload.workspaceUrl || "/workspace")}" target="_blank" rel="noreferrer">Open Owner Console</a>
      ${disconnectButton(payload)}
    </div>
  `;
}

function renderPendingState(payload, statusText = "Waiting for pairing. Open @bharatclawbot and send the pairing code.") {
  const badge = payload.existing ? "Existing workspace" : "Workspace ready";
  const businessName = payload.businessName || "Your BharatClaw workspace";
  return `
    <div class="result-stack">
      <span class="pill">${esc(badge)}</span>
      <strong class="result-title">${esc(businessName)} is ready to pair</strong>
      <p class="result-copy">${esc(payload.nextStep || "Pair the owner chat once and BharatClaw will activate on the shared Telegram bot.")}</p>
      ${renderProfileChips(payload)}
      <div class="pair-box">
        <span>Pairing code</span>
        <strong>${esc(payload.pairingCode || payload.ownerPairCode || "")}</strong>
      </div>
      <div class="result-actions">
        <a class="btn btn-primary" href="${esc(payload.ownerConnectUrl || payload.ownerConnectBotUrl || "https://t.me/bharatclawbot")}" target="_blank" rel="noreferrer">Pair in @bharatclawbot</a>
        <button id="copy-pair-code" class="btn btn-secondary" type="button" data-pair-code="${esc(payload.pairingCode || payload.ownerPairCode || "")}">Copy Pairing Code</button>
        ${disconnectButton(payload)}
      </div>
      <div class="status-box">
        <strong>Pairing status</strong>
        <span id="owner-status-msg">${esc(statusText)}</span>
      </div>
      <div class="result-actions">
        <button id="check-owner-status" class="btn btn-secondary" type="button" data-tenant-id="${esc(payload.tenantId || "")}">Check Connection</button>
      </div>
      ${renderFocusCard(payload)}
    </div>
  `;
}

function renderPairedState(payload) {
  const badge = payload.existing ? "Remembered" : "Paired";
  const businessName = payload.businessName || "BharatClaw workspace";
  return `
    <div class="result-stack">
      <span class="pill pill-success">${esc(badge)}</span>
      <strong class="result-title">${esc(businessName)} is live</strong>
      <p class="result-copy">Your owner chat is connected. Share the lead link and BharatClaw will handle capture, follow-up, and owner visibility.</p>
      ${renderProfileChips(payload)}
      ${pairedActions(payload)}
      <div class="status-box">
        <strong>What happens now</strong>
        <span>${esc(payload.nextStep || "Customers who open the lead link will enter your BharatClaw lead flow automatically.")}</span>
      </div>
      ${renderFocusCard(payload)}
    </div>
  `;
}

function renderRememberedStartCard(tenant) {
  const focus = focusItems(tenant.profile)
    .slice(0, 3)
    .map((item) => item.label)
    .join(", ");

  return `
    <article class="card remembered-card">
      <h3>${esc(tenant.businessName || "BharatClaw Workspace")}</h3>
      <p>${tenant.ownerConnected ? "Owner is paired and live." : "Owner not paired yet. Finish pairing to activate BharatClaw."}</p>
      ${renderProfileChips(tenant)}
      <div class="remembered-meta">
        <span>Status: ${esc(tenant.ownerPairStatus || (tenant.ownerConnected ? "PAIRED" : "PENDING"))}</span>
        <span>Pair code: ${esc(tenant.pairingCode || tenant.ownerPairCode || "-")}</span>
        ${focus ? `<span>Focus: ${esc(focus)}</span>` : ""}
      </div>
      <div class="result-actions">
        <a class="btn btn-primary" href="${esc(tenant.leadEntryUrl || "#")}" target="_blank" rel="noreferrer">Lead Link</a>
        <a class="btn btn-secondary" href="${esc(tenant.ownerConsoleUrl || "/workspace")}" target="_blank" rel="noreferrer">Owner Console</a>
        ${tenant.ownerConnected ? "" : `<a class="btn btn-secondary" href="${esc(tenant.ownerConnectUrl || tenant.ownerConnectBotUrl || "https://t.me/bharatclawbot")}" target="_blank" rel="noreferrer">Pair Now</a>`}
        ${disconnectButton(tenant, "remembered")}
      </div>
    </article>
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

function renderRememberedStarts() {
  const el = document.getElementById("remembered-starts");
  if (!el) return;

  if (!currentUser) {
    el.innerHTML = `
      <div class="empty-state">Sign in to see remembered BharatClaw workspaces, pairing state, and disconnect controls.</div>
    `;
    return;
  }

  if (!rememberedTenants.length) {
    el.innerHTML = `
      <div class="empty-state">No remembered workspaces yet for ${esc(currentUser.email)}. Create one from the launch form.</div>
    `;
    return;
  }

  el.innerHTML = `
    <div class="section-headline">
      <div>
        <p class="eyebrow">Remembered Workspaces</p>
        <h2>Your BharatClaw setups</h2>
      </div>
    </div>
    <div class="cards">
      ${rememberedTenants.map(renderRememberedStartCard).join("")}
    </div>
  `;

  el.querySelectorAll(".disconnect-pairing-btn").forEach((button) => {
    button.addEventListener("click", () =>
      handleDisconnect(button.getAttribute("data-tenant-id") || "", button.getAttribute("data-mode") || "remembered")
    );
  });
}

function renderAuthState() {
  const authStateEl = document.getElementById("auth-state");
  if (!authStateEl) return;

  if (!currentUser) {
    authStateEl.innerHTML = `
      <strong>Not signed in.</strong>
      <span>Sign in if you want BharatClaw to remember your launch profile, owner pairing, and console access.</span>
      <a class="btn btn-secondary" href="/auth">Sign In</a>
    `;
    return;
  }

  authStateEl.innerHTML = `
    <strong>Signed in as ${esc(currentUser.email)}</strong>
    <span>${rememberedTenants.length} remembered workspace(s).</span>
    <a class="btn btn-secondary" href="/workspace">Open Owner Console</a>
    <button id="logout-btn" class="btn btn-secondary" type="button">Log Out</button>
  `;

  document.getElementById("logout-btn")?.addEventListener("click", async () => {
    try {
      await api("/public/auth/logout", { body: {} });
    } catch {
      // ignore and clear local token anyway
    }
    clearToken();
    currentUser = null;
    rememberedTenants = [];
    renderAuthState();
    renderRememberedStarts();
  });
}

async function refreshAuthState() {
  const token = getToken();
  if (!token) {
    currentUser = null;
    rememberedTenants = [];
    renderAuthState();
    renderRememberedStarts();
    return;
  }

  try {
    const data = await api("/public/auth/me");
    currentUser = data.user || null;
    rememberedTenants = Array.isArray(data.tenants) ? data.tenants : [];
  } catch {
    clearToken();
    currentUser = null;
    rememberedTenants = [];
  }

  renderAuthState();
  renderRememberedStarts();
}

async function handleDisconnect(tenantId, mode = "remembered") {
  if (!tenantId) return;
  try {
    const data = await api(`/public/auth/tenants/${encodeURIComponent(tenantId)}/disconnect-owner`, {
      body: {}
    });
    await refreshAuthState();
    if (mode === "result" && data?.tenant) {
      latestPayload = { ...latestPayload, ...data.tenant, existing: true };
      setResult(renderPendingState(latestPayload, "Pairing disconnected. Use the fresh code to reconnect the owner chat."));
      attachResultHandlers();
      startStatusPolling(latestPayload.tenantId);
    }
  } catch (error) {
    const errorEl = document.getElementById("error");
    if (errorEl) {
      errorEl.textContent = error instanceof Error ? error.message : "Unable to disconnect pairing.";
    }
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

  document.querySelectorAll(".disconnect-pairing-btn").forEach((button) => {
    if (button.dataset.bound === "true") return;
    button.dataset.bound = "true";
    button.addEventListener("click", () =>
      handleDisconnect(button.getAttribute("data-tenant-id") || "", button.getAttribute("data-mode") || "result")
    );
  });
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
      attachResultHandlers();
      refreshAuthState();
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
    if (statusAttempts > MAX_STATUS_ATTEMPTS || activeTenantId !== tenantId) {
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
    ownerEmail: String(formData.get("ownerEmail") || "").trim() || undefined,
    industry: String(formData.get("industry") || "").trim() || undefined,
    teamSize: String(formData.get("teamSize") || "").trim() || undefined,
    launchFocusKeys: formData
      .getAll("launchFocusKeys")
      .map((item) => String(item || "").trim())
      .filter(Boolean)
  };

  if (!payload.ownerName || !payload.businessName) {
    errorEl.textContent = "Please fill your name and business name.";
    return;
  }

  if (submitBtn) {
    submitBtn.dataset.idleLabel = submitBtn.dataset.idleLabel || submitBtn.textContent || "Create Workspace";
    submitBtn.disabled = true;
    submitBtn.textContent = "Creating...";
  }

  try {
    const data = await api("/public/start", { body: payload });
    latestPayload = data;
    setResult(data.ownerConnected ? renderPairedState(data) : renderPendingState(data));
    attachResultHandlers();
    form.reset();

    const defaultFocus = form.querySelectorAll(
      'input[name="launchFocusKeys"][value="lead_capture"], input[name="launchFocusKeys"][value="followup_recovery"], input[name="launchFocusKeys"][value="owner_handoff"]'
    );
    defaultFocus.forEach((input) => {
      input.checked = true;
    });

    if (!data.ownerConnected && data.tenantId) {
      startStatusPolling(String(data.tenantId));
      checkOwnerStatus(String(data.tenantId), false);
    }

    await refreshAuthState();
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
      submitBtn.textContent = submitBtn.dataset.idleLabel || "Create Workspace";
    }
  }
}

document.getElementById("trial-form")?.addEventListener("submit", submitTrial);

refreshAuthState();
