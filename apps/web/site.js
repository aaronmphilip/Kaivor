(function bootstrapSite() {
  const fallbackWorkerBase = "https://bharatclaw-telegram.bharatclaw.workers.dev";
  const configuredWorkerBase =
    document.querySelector('meta[name="bharatclaw-worker-base"]')?.getAttribute("content")?.trim() || "";
  const useCurrentOrigin = /workers\.dev$/i.test(window.location.hostname);

  window.BharatClawSite = Object.freeze({
    workerBaseUrl: useCurrentOrigin ? window.location.origin : configuredWorkerBase || fallbackWorkerBase
  });

  const activePage = document.body?.dataset.page || "";
  document.querySelectorAll("[data-nav]").forEach((link) => {
    if (link.getAttribute("data-nav") === activePage) {
      link.classList.add("nav-link-active");
    }
  });
})();
