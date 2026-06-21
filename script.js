(function () {
  const header = document.querySelector(".site-header");
  const revealNodes = document.querySelectorAll(
    ".hero-copy, .hero-device, .stat-card, .section-intro, .example-card, .bento-card, .flow-step, .principle-copy, .setup-card, .cta-band, .site-footer"
  );
  const staggerGrids = document.querySelectorAll(
    ".stats-strip, .examples-grid, .features-bento, .setup-grid, .flow-steps"
  );
  const magneticButtons = document.querySelectorAll(".btn-magnetic");

  revealNodes.forEach((node) => node.classList.add("reveal"));
  staggerGrids.forEach((grid) => grid.classList.add("reveal-stagger"));

  const onScroll = () => {
    if (header) {
      header.classList.toggle("is-scrolled", window.scrollY > 8);
    }
  };

  window.addEventListener("scroll", onScroll, { passive: true });
  onScroll();

  if ("IntersectionObserver" in window) {
    const observer = new IntersectionObserver(
      (entries) => {
        entries.forEach((entry) => {
          if (entry.isIntersecting) {
            entry.target.classList.add("is-visible");
            observer.unobserve(entry.target);
          }
        });
      },
      { threshold: 0.1, rootMargin: "0px 0px -32px 0px" }
    );

    [...revealNodes, ...staggerGrids].forEach((node) => observer.observe(node));
  } else {
    [...revealNodes, ...staggerGrids].forEach((node) =>
      node.classList.add("is-visible")
    );
  }

  magneticButtons.forEach((btn) => {
    btn.addEventListener("mousedown", () => btn.classList.add("is-pressed"));
    btn.addEventListener("mouseup", () => btn.classList.remove("is-pressed"));
    btn.addEventListener("mouseleave", () => btn.classList.remove("is-pressed"));

    btn.addEventListener("mousemove", (e) => {
      const rect = btn.getBoundingClientRect();
      const x = e.clientX - rect.left - rect.width / 2;
      const y = e.clientY - rect.top - rect.height / 2;
      btn.style.transform = `translate(${x * 0.08}px, ${y * 0.12}px)`;
    });

    btn.addEventListener("mouseleave", () => {
      btn.style.transform = "";
    });
  });

  document.querySelectorAll("[data-count]").forEach((el) => {
    const target = parseInt(el.dataset.count, 10);
    if (Number.isNaN(target) || target === 0) return;

    const animate = () => {
      const duration = 1200;
      const start = performance.now();

      const tick = (now) => {
        const progress = Math.min((now - start) / duration, 1);
        const eased = 1 - Math.pow(1 - progress, 3);
        el.textContent = Math.round(eased * target);
        if (progress < 1) requestAnimationFrame(tick);
      };

      requestAnimationFrame(tick);
    };

    if ("IntersectionObserver" in window) {
      const counterObserver = new IntersectionObserver(
        (entries) => {
          if (entries[0].isIntersecting) {
            animate();
            counterObserver.disconnect();
          }
        },
        { threshold: 0.5 }
      );
      counterObserver.observe(el);
    } else {
      animate();
    }
  });
})();