const WORKER_BASE_URL = "https://bharatclaw-telegram.bharatclaw.workers.dev";

async function submitWaitlist(event) {
  event.preventDefault();
  const form = event.currentTarget;
  const errorEl = document.getElementById("waitlist-error");
  const resultEl = document.getElementById("waitlist-result");
  const submitBtn = form.querySelector('button[type="submit"]');

  errorEl.textContent = "";
  resultEl.style.display = "none";
  resultEl.textContent = "";

  const formData = new FormData(form);
  const payload = {
    name: String(formData.get("name") || "").trim(),
    businessName: String(formData.get("businessName") || "").trim(),
    email: String(formData.get("email") || "").trim() || undefined
  };

  if (!payload.name || !payload.businessName) {
    errorEl.textContent = "Please fill your name and business name.";
    return;
  }

  if (submitBtn) {
    submitBtn.disabled = true;
    submitBtn.textContent = "Joining...";
  }

  try {
    const response = await fetch(`${WORKER_BASE_URL}/public/waitlist`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(payload)
    });
    const data = await response.json().catch(() => ({}));
    if (!response.ok) {
      throw new Error(data?.detail || data?.error || "Unable to join waitlist");
    }

    resultEl.textContent = data?.message || "You are on the waitlist.";
    resultEl.style.display = "block";
    form.reset();
  } catch (error) {
    errorEl.textContent = error instanceof Error ? error.message : "Unable to join waitlist right now.";
  } finally {
    if (submitBtn) {
      submitBtn.disabled = false;
      submitBtn.textContent = "Join Waitlist";
    }
  }
}

document.getElementById("waitlist-form")?.addEventListener("submit", submitWaitlist);
