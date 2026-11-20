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

export function initTourStage() {
  // On wide screens the screenshots move into one sticky stage that follows
  // the step being read. Narrow screens and no-JS keep the inline images.
  const media = matchMedia("(min-width: 980px)");
  const layout = document.querySelector("[data-tour]");
  const stage = layout.querySelector("[data-tour-stage]");
  const screen = stage.querySelector("[data-tour-screen]");
  const ticks = stage.querySelectorAll(".tour__ticks li");
  const steps = [...layout.querySelectorAll("[data-tour-step]")];
  const frames = steps.map((step) => {
    const image = step.querySelector("img").cloneNode();
    image.removeAttribute("data-i18n-attr");
    image.alt = "";
    image.sizes = step.dataset.shape === "phone" ? "300px" : "760px";
    image.loading = "eager";
    screen.append(image);
    return image;
  });
  let active = -1;

  function activate(index) {
    if (index === active || index < 0) return;
    active = index;
    steps.forEach((step, i) => step.classList.toggle("is-active", i === index));
    frames.forEach((frame, i) => frame.classList.toggle("is-active", i === index));
    ticks.forEach((tick, i) => tick.classList.toggle("is-active", i === index));
    stage.dataset.shape = steps[index].dataset.shape;
  }

  function sync() {
    layout.classList.toggle("has-stage", media.matches);
    stage.hidden = !media.matches;
  }

  if ("IntersectionObserver" in window) {
    // A thin band across the middle of the viewport decides the current step.
    const observer = new IntersectionObserver((entries) => {
      for (const entry of entries) {
        if (entry.isIntersecting) activate(steps.indexOf(entry.target));
      }
    }, { rootMargin: "-45% 0px -45% 0px" });
    for (const step of steps) observer.observe(step);
  }
  activate(0);
  media.addEventListener("change", sync);
  sync();
}
