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
