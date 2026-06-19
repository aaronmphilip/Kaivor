(function () {
  const header = document.querySelector(".site-header");
  const revealNodes = document.querySelectorAll(
    ".hero-copy, .hero-visual, .outcome-strip article, .section-copy, .case-card, .system-visual, .calm-band, .setup-steps article, .download-band, .site-footer"
  );

  revealNodes.forEach((node) => node.classList.add("reveal"));

  const onScroll = () => {
    if (header) {
      header.classList.toggle("is-scrolled", window.scrollY > 12);
    }
  };

  window.addEventListener("scroll", onScroll, { passive: true });
  onScroll();

  if (!("IntersectionObserver" in window)) {
    revealNodes.forEach((node) => node.classList.add("is-visible"));
    return;
  }

  const observer = new IntersectionObserver(
    (entries) => {
      entries.forEach((entry) => {
        if (entry.isIntersecting) {
          entry.target.classList.add("is-visible");
          observer.unobserve(entry.target);
        }
      });
    },
    { threshold: 0.12, rootMargin: "0px 0px -40px 0px" }
  );

  revealNodes.forEach((node) => observer.observe(node));
})();