const WORKER_BASE_URL = "https://bharatclaw-telegram.bharatclaw.workers.dev";
const TOKEN_KEY = "bharatclaw_token";

let currentUser = null;
let rememberedTenants = [];
let selectedTenant = null;

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

function metricCard(label, value) {
  return `
    <article class="metric-card">
      <span>${esc(label)}</span>
      <strong>${esc(value)}</strong>
    </article>
  `;
}

function actionButton(label, href, variant = "primary") {
  if (!href) return "";
  return `<a class="btn btn-${variant}" href="${esc(href)}" target="_blank" rel="noreferrer">${esc(label)}</a>`;
}

function detailRow(label, value) {
  return `
    <div class="detail-row">
      <span>${esc(label)}</span>
      <strong>${esc(value || "-")}</strong>
    </div>
  `;
}

function renderLead(lead) {
  return `
    <article class="lead-card">
      <div class="lead-card-head">
        <strong>${esc(lead.name || "Unknown lead")}</strong>
        <span>${esc(lead.status || "New")}</span>
      </div>
      <p>${esc(lead.requirement || "Requirement not captured yet")}</p>
      <div class="lead-meta">
        <span>Chat: ${esc(lead.phone || "-")}</span>
        <span>Language: ${esc(lead.language || "English")}</span>
        <span>Updated: ${esc(lead.updatedAt || "-")}</span>
      </div>
    </article>
  `;
}

function parseOwnerConsoleUrl(url) {
  try {
    const parsed = new URL(url);
    return {
      tenantId: parsed.searchParams.get("tenantId") || "",
      token: parsed.searchParams.get("token") || ""
    };
  } catch {
    return { tenantId: "", token: "" };
  }
}

function renderAccountState() {
  const el = document.getElementById("workspace-account");
  if (!el) return;

  if (!currentUser) {
    el.innerHTML = `
      <strong>Direct access mode.</strong>
      <span>Open a direct owner console link, or sign in to load remembered BharatClaw setups.</span>
      <a class="btn btn-secondary" href="/auth">Sign In</a>
    `;
    return;
  }

  el.innerHTML = `
    <strong>Signed in as ${esc(currentUser.email)}</strong>
    <span>${rememberedTenants.length} remembered setup(s).</span>
    <button id="workspace-logout" class="btn btn-secondary" type="button">Log Out</button>
  `;
  document.getElementById("workspace-logout")?.addEventListener("click", async () => {
    try {
      await api("/public/auth/logout", { body: {} });
    } catch {
      // ignore and clear local token anyway
    }
    clearToken();
    window.location.href = "/auth";
  });
}

function renderTenantSwitcher() {
  const el = document.getElementById("tenant-switcher");
  if (!el) return;

  if (!currentUser || !rememberedTenants.length) {
    el.innerHTML = "";
    return;
  }

  el.innerHTML = `
    <div class="section-headline">
      <div>
        <p class="eyebrow">Remembered Setups</p>
        <h2>Choose a setup</h2>
      </div>
    </div>
    <div class="cards">
      ${rememberedTenants
        .map(
          (tenant) => `
            <article class="card remembered-card ${selectedTenant?.tenantId === tenant.tenantId ? "selected-card" : ""}">
              <h3>${esc(tenant.businessName || "BharatClaw Setup")}</h3>
              <p>${tenant.ownerConnected ? "Owner paired and live." : "Owner not paired yet."}</p>
              <div class="remembered-meta">
                <span>Status: ${esc(tenant.ownerPairStatus || "PENDING")}</span>
                <span>Pair code: ${esc(tenant.pairingCode || tenant.ownerPairCode || "-")}</span>
              </div>
              <div class="result-actions">
                <button class="btn btn-primary select-tenant-btn" type="button" data-tenant-id="${esc(tenant.tenantId)}">Open</button>
                ${tenant.ownerConnected ? "" : `<a class="btn btn-secondary" href="${esc(tenant.ownerConnectUrl || tenant.ownerConnectBotUrl || "https://t.me/bharatclawbot")}" target="_blank" rel="noreferrer">Pair Now</a>`}
                <button class="btn btn-secondary disconnect-tenant-btn" type="button" data-tenant-id="${esc(tenant.tenantId)}">Disconnect</button>
              </div>
            </article>
          `
        )
        .join("")}
    </div>
  `;

  el.querySelectorAll(".select-tenant-btn").forEach((button) => {
    button.addEventListener("click", () => {
      const tenantId = button.getAttribute("data-tenant-id") || "";
      const tenant = rememberedTenants.find((item) => item.tenantId === tenantId);
      if (tenant) {
        loadRememberedTenant(tenant);
      }
    });
  });

  el.querySelectorAll(".disconnect-tenant-btn").forEach((button) => {
    button.addEventListener("click", () => handleDisconnect(button.getAttribute("data-tenant-id") || ""));
  });
}

function renderWorkspace(data) {
  const errorEl = document.getElementById("workspace-error");
  const titleEl = document.getElementById("workspace-name");
  const metricsEl = document.getElementById("workspace-metrics");
  const setupEl = document.getElementById("workspace-setup");
  const leadsEl = document.getElementById("workspace-leads");
  const actionsEl = document.getElementById("workspace-actions");

  if (errorEl) errorEl.textContent = "";
  titleEl.textContent = data.businessName || selectedTenant?.businessName || "BharatClaw Owner Console";

  metricsEl.innerHTML = [
    metricCard("Total Leads", data?.stats?.totalLeads ?? 0),
    metricCard("Open Leads", data?.stats?.openLeads ?? 0),
    metricCard("Follow-up Pending", data?.stats?.followupPending ?? 0)
  ].join("");

  setupEl.innerHTML = [
    detailRow("Owner Connected", data.ownerConnected ? "Yes" : "No"),
    detailRow("Owner Name", data?.owner?.name || selectedTenant?.ownerName || "-"),
    detailRow("Owner Chat", data?.owner?.phone || "-"),
    detailRow("Owner Paired At", data.pairedAt || selectedTenant?.pairedAt || "-"),
    detailRow("Pairing Code", selectedTenant?.pairingCode || selectedTenant?.ownerPairCode || "-"),
    detailRow("Lead Link", data.leadEntryUrl || "-")
  ].join("");

  actionsEl.innerHTML = [
    actionButton("Open Lead Link", data.leadEntryUrl, "primary"),
    !data.ownerConnected && selectedTenant?.ownerConnectUrl ? actionButton("Pair Now", selectedTenant.ownerConnectUrl, "secondary") : "",
    getToken() && selectedTenant?.tenantId
      ? `<button id="workspace-disconnect" class="btn btn-secondary" type="button">Disconnect Pairing</button>`
      : ""
  ].join("");

  document.getElementById("workspace-disconnect")?.addEventListener("click", () => handleDisconnect(selectedTenant?.tenantId || ""));

  const leads = Array.isArray(data.leads) ? data.leads : [];
  leadsEl.innerHTML = leads.length
    ? leads.map(renderLead).join("")
    : `<div class="empty-state">No leads yet. Share your lead link and BharatClaw will start capturing them here.</div>`;
}

async function loadWorkspaceData(tenantId, token) {
  const errorEl = document.getElementById("workspace-error");
  const titleEl = document.getElementById("workspace-name");
  if (!tenantId || !token) {
    if (errorEl) errorEl.textContent = "Missing owner console access. Open a direct console link or sign in first.";
    titleEl.textContent = "Owner console unavailable";
    return;
  }

  try {
    const response = await fetch(
      `${WORKER_BASE_URL}/public/workspaces/${encodeURIComponent(tenantId)}?token=${encodeURIComponent(token)}`
    );
    const data = await response.json().catch(() => ({}));
    if (!response.ok) {
      throw new Error(data?.detail || data?.error || "Unable to load owner console");
    }
    renderWorkspace(data);
  } catch (error) {
    if (errorEl) {
      errorEl.textContent = error instanceof Error ? error.message : "Unable to load owner console.";
    }
    titleEl.textContent = "Owner console unavailable";
  }
}

async function loadRememberedTenant(tenant) {
  selectedTenant = tenant;
  renderTenantSwitcher();
  const parsed = parseOwnerConsoleUrl(tenant.ownerConsoleUrl || tenant.workspaceUrl || "");
  await loadWorkspaceData(parsed.tenantId, parsed.token);
}

async function handleDisconnect(tenantId) {
  if (!tenantId) return;
  const errorEl = document.getElementById("workspace-error");
  try {
    const data = await api(`/public/auth/tenants/${encodeURIComponent(tenantId)}/disconnect-owner`, { body: {} });
    const refreshed = await api("/public/auth/tenants");
    currentUser = refreshed.user || currentUser;
    rememberedTenants = Array.isArray(refreshed.tenants) ? refreshed.tenants : [];
    selectedTenant = rememberedTenants.find((tenant) => tenant.tenantId === tenantId) || data?.tenant || null;
    renderAccountState();
    renderTenantSwitcher();
    if (selectedTenant) {
      await loadRememberedTenant(selectedTenant);
      if (errorEl) {
        errorEl.textContent = "Pairing disconnected. Use the fresh pairing code to reconnect the owner chat.";
      }
    }
  } catch (error) {
    if (errorEl) {
      errorEl.textContent = error instanceof Error ? error.message : "Unable to disconnect pairing.";
    }
  }
}

async function refreshAuthState() {
  const token = getToken();
  if (!token) {
    currentUser = null;
    rememberedTenants = [];
    renderAccountState();
    renderTenantSwitcher();
    return false;
  }

  try {
    const data = await api("/public/auth/me");
    currentUser = data.user || null;
    rememberedTenants = Array.isArray(data.tenants) ? data.tenants : [];
    renderAccountState();
    renderTenantSwitcher();
    return true;
  } catch {
    clearToken();
    currentUser = null;
    rememberedTenants = [];
    renderAccountState();
    renderTenantSwitcher();
    return false;
  }
}

async function boot() {
  const params = new URLSearchParams(window.location.search);
  const tenantId = params.get("tenantId");
  const token = params.get("token");

  const hasAuth = await refreshAuthState();

  if (tenantId && token) {
    await loadWorkspaceData(tenantId, token);
    return;
  }

  if (hasAuth && rememberedTenants.length) {
    await loadRememberedTenant(rememberedTenants[0]);
    return;
  }

  const titleEl = document.getElementById("workspace-name");
  const errorEl = document.getElementById("workspace-error");
  titleEl.textContent = "Owner console unavailable";
  errorEl.textContent = hasAuth
    ? "No remembered setup found yet. Create one from Start with BharatClaw."
    : "Sign in or open a direct owner console link to load BharatClaw.";
}

boot();
