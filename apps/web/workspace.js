const WORKER_BASE_URL = "https://bharatclaw-telegram.bharatclaw.workers.dev";

function esc(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
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

async function loadWorkspace() {
  const params = new URLSearchParams(window.location.search);
  const tenantId = params.get("tenantId");
  const token = params.get("token");
  const errorEl = document.getElementById("workspace-error");
  const titleEl = document.getElementById("workspace-name");
  const metricsEl = document.getElementById("workspace-metrics");
  const setupEl = document.getElementById("workspace-setup");
  const leadsEl = document.getElementById("workspace-leads");
  const actionsEl = document.getElementById("workspace-actions");

  if (!tenantId || !token) {
    errorEl.textContent = "Missing owner console access. Open the owner console link from BharatClaw setup.";
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

    titleEl.textContent = data.businessName || "BharatClaw Owner Console";
    metricsEl.innerHTML = [
      metricCard("Total Leads", data?.stats?.totalLeads ?? 0),
      metricCard("Open Leads", data?.stats?.openLeads ?? 0),
      metricCard("Follow-up Pending", data?.stats?.followupPending ?? 0)
    ].join("");

    setupEl.innerHTML = [
      detailRow("Owner Connected", data.ownerConnected ? "Yes" : "No"),
      detailRow("Owner Name", data?.owner?.name || "-"),
      detailRow("Owner Chat", data?.owner?.phone || "-"),
      detailRow("Owner Paired At", data.pairedAt || "-"),
      detailRow("Lead Link", data.leadEntryUrl || "-")
    ].join("");

    actionsEl.innerHTML = [
      actionButton("Open Lead Link", data.leadEntryUrl, "primary"),
      actionButton("Open Telegram Bot", "https://t.me/bharatclawbot", "secondary")
    ].join("");

    const leads = Array.isArray(data.leads) ? data.leads : [];
    leadsEl.innerHTML = leads.length
      ? leads.map(renderLead).join("")
      : `<div class="empty-state">No leads yet. Share your lead link and BharatClaw will start capturing them here.</div>`;
  } catch (error) {
    errorEl.textContent = error instanceof Error ? error.message : "Unable to load owner console.";
    titleEl.textContent = "Owner console unavailable";
  }
}

loadWorkspace();
