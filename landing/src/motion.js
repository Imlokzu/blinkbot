export function initReveals() {
  if (!("IntersectionObserver" in window)) return;

  const observer = new IntersectionObserver((entries) => {
    for (const entry of entries) {
      if (!entry.isIntersecting) continue;
      entry.target.classList.add("is-in");
      observer.unobserve(entry.target);
    }
  }, { rootMargin: "0px 0px -10% 0px" });

  for (const element of document.querySelectorAll("[data-reveal]")) observer.observe(element);
  document.documentElement.classList.add("motion-ready");
}

export function initTourPreview() {
  const motion = matchMedia("(hover: hover) and (pointer: fine) and (prefers-reduced-motion: no-preference)");

  for (const card of document.querySelectorAll("[data-tour-card]")) {
    const preview = card.querySelector(".tour__preview");
    const reset = () => {
      preview.style.removeProperty("--preview-x");
      preview.style.removeProperty("--preview-y");
    };

    card.addEventListener("pointermove", (event) => {
      if (!motion.matches || event.pointerType === "touch") return;
      const { left, top, width, height } = preview.getBoundingClientRect();
      const clamp = (offset) => Math.max(-0.5, Math.min(0.5, offset));
      preview.style.setProperty("--preview-x", `${clamp((event.clientX - left) / width - 0.5) * 8}px`);
      preview.style.setProperty("--preview-y", `${clamp((event.clientY - top) / height - 0.5) * 8}px`);
    }, { passive: true });

    card.addEventListener("pointerleave", reset);
    card.addEventListener("pointercancel", reset);
    motion.addEventListener("change", reset);
  }
}
