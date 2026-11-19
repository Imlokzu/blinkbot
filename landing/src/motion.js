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
  const media = matchMedia("(min-width: 735px) and (hover: hover) and (pointer: fine) and (prefers-reduced-motion: no-preference)");
  const list = document.querySelector("[data-preview-list]");
  const follower = document.querySelector("[data-preview-follower]");
  const frame = follower.querySelector(".tour__floating-frame");
  const rows = list.querySelectorAll("[data-preview-row]");
  const images = new Map();
  const target = { x: 0, y: 0 };
  const position = { x: 0, y: 0 };
  let active = null;
  let pointer = null;
  let animationFrame = 0;

  function paint(tilt = 0) {
    follower.style.transform = `translate3d(${position.x}px, ${position.y}px, 0) rotate(${tilt}deg)`;
  }

  function animate() {
    animationFrame = 0;
    const dx = target.x - position.x;
    const dy = target.y - position.y;
    if (Math.abs(dx) < 0.2 && Math.abs(dy) < 0.2) {
      Object.assign(position, target);
      paint();
      return;
    }
    position.x += dx * 0.14;
    position.y += dy * 0.14;
    paint(Math.max(-9, Math.min(9, dx * 0.045)).toFixed(2));
    animationFrame = requestAnimationFrame(animate);
  }

  function updateTarget() {
    // The preview trails the pointer while staying inside the available viewport.
    const gutter = 12;
    const halfWidth = frame.offsetWidth / 2 + gutter;
    const halfHeight = frame.offsetHeight / 2 + gutter;
    target.x = Math.min(innerWidth - halfWidth, Math.max(halfWidth, pointer.clientX));
    target.y = Math.min(innerHeight - halfHeight, Math.max(halfHeight, pointer.clientY));
  }

  function scheduleFollow() {
    updateTarget();
    if (!animationFrame) animationFrame = requestAnimationFrame(animate);
  }

  function showImage(image) {
    for (const item of images.values()) item.classList.toggle("is-active", item === image);
    list.classList.add("has-active-preview");
    follower.classList.add("is-visible");
  }

  function getImage(row) {
    if (images.has(row)) return images.get(row);
    const image = row.querySelector("img").cloneNode();
    image.removeAttribute("data-i18n-attr");
    image.alt = "";
    image.sizes = row.dataset.shape === "phone" ? "250px" : "420px";
    image.loading = "eager";
    image.addEventListener("load", () => {
      if (active === row) showImage(image);
    });
    images.set(row, image);
    frame.append(image);
    return image;
  }

  function show(row, event) {
    if (!media.matches || event.pointerType === "touch") return;
    const opening = !active;
    active?.classList.remove("is-active");
    active = row;
    pointer = event;
    row.classList.add("is-active");
    follower.dataset.shape = row.dataset.shape;
    if (opening) {
      updateTarget();
      Object.assign(position, target);
      paint();
    } else {
      scheduleFollow();
    }
    const image = getImage(row);
    if (image.complete && image.naturalWidth) {
      showImage(image);
    } else {
      list.classList.remove("has-active-preview");
      follower.classList.remove("is-visible");
    }
  }

  function hide() {
    cancelAnimationFrame(animationFrame);
    animationFrame = 0;
    active?.classList.remove("is-active");
    active = null;
    pointer = null;
    list.classList.remove("has-active-preview");
    follower.classList.remove("is-visible");
  }

  function syncMedia() {
    hide();
    list.classList.toggle("is-hoverable", media.matches);
    follower.hidden = !media.matches;
  }

  for (const row of rows) row.addEventListener("pointerenter", (event) => show(row, event));
  list.addEventListener("pointermove", (event) => {
    if (!media.matches || event.pointerType === "touch") return;
    const row = event.target.closest("[data-preview-row]");
    if (!row) {
      hide();
    } else if (row !== active) {
      // Scrolling can dismiss the preview without the cursor leaving this row.
      show(row, event);
    } else {
      pointer = event;
      scheduleFollow();
    }
  });
  if ("ResizeObserver" in window) {
    // The frame changes shape between desktop and phone captures.
    new ResizeObserver(() => {
      if (active) scheduleFollow();
    }).observe(frame);
  }
  list.addEventListener("pointerleave", hide);
  list.addEventListener("focusin", hide);
  window.addEventListener("scroll", hide, { passive: true });
  window.addEventListener("resize", hide);
  document.addEventListener("visibilitychange", hide);
  media.addEventListener("change", syncMedia);
  syncMedia();
}
