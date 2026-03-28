const WORKER_BASE_URL = "https://bharatclaw-telegram.bharatclaw.workers.dev";
const TOKEN_KEY = "bharatclaw_token";

function getToken() {
  return window.localStorage.getItem(TOKEN_KEY) || "";
}

function setToken(token) {
  if (token) {
    window.localStorage.setItem(TOKEN_KEY, token);
  }
}

function clearToken() {
  window.localStorage.removeItem(TOKEN_KEY);
}

async function api(path, payload, token) {
  const headers = {};
  if (payload) {
    headers["content-type"] = "application/json";
  }
  if (token) {
    headers.authorization = `Bearer ${token}`;
  }
  const response = await fetch(`${WORKER_BASE_URL}${path}`, {
    method: payload ? "POST" : "GET",
    headers,
    body: payload ? JSON.stringify(payload) : undefined
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) {
    throw new Error(data?.detail || data?.error || "Request failed");
  }
  return data;
}

function setSessionState(html) {
  const el = document.getElementById("auth-session-state");
  if (el) {
    el.innerHTML = html || "";
  }
}

async function refreshSessionState() {
  const token = getToken();
  if (!token) {
    setSessionState(`
      <strong>Not signed in.</strong>
      <span>Create an account or sign in, then BharatClaw will remember your setups and pairing state.</span>
    `);
    return;
  }

  try {
    const data = await api("/public/auth/me", null, token);
    setSessionState(`
      <strong>Signed in as ${data.user.email}</strong>
      <span>${Array.isArray(data.tenants) ? data.tenants.length : 0} remembered setup(s).</span>
      <button id="logout-btn" class="btn btn-secondary" type="button">Log Out</button>
    `);
    document.getElementById("logout-btn")?.addEventListener("click", async () => {
      try {
        await api("/public/auth/logout", {}, token);
      } catch {
        // ignore logout API failure and clear local state anyway
      }
      clearToken();
      refreshSessionState();
    });
  } catch {
    clearToken();
    setSessionState(`
      <strong>Session expired.</strong>
      <span>Please sign in again.</span>
    `);
  }
}

async function handleAuthSubmit(event, path, errorId) {
  event.preventDefault();
  const form = event.currentTarget;
  const errorEl = document.getElementById(errorId);
  const submitBtn = form.querySelector('button[type="submit"]');
  errorEl.textContent = "";

  const payload = Object.fromEntries(new FormData(form).entries());

  if (submitBtn) {
    submitBtn.disabled = true;
    submitBtn.textContent = "Working...";
  }

  try {
    const data = await api(path, payload, "");
    if (data.token) {
      setToken(data.token);
    }
    window.location.href = "/start";
  } catch (error) {
    errorEl.textContent = error instanceof Error ? error.message : "Something went wrong.";
  } finally {
    if (submitBtn) {
      submitBtn.disabled = false;
      submitBtn.textContent = path.includes("signup") ? "Create Account" : "Sign In";
    }
  }
}

document.getElementById("signup-form")?.addEventListener("submit", (event) => handleAuthSubmit(event, "/public/auth/signup", "signup-error"));
document.getElementById("login-form")?.addEventListener("submit", (event) => handleAuthSubmit(event, "/public/auth/login", "login-error"));

refreshSessionState();
