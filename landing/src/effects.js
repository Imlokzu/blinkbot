/*
 * Vanilla ports of three ObsidianUI components (MIT, https://www.obsidianui.dev):
 * Flip Text, Text reel and Draggable Marquee. The originals are React + GSAP;
 * this page has no framework, so the behavior is rebuilt on
 * requestAnimationFrame and pointer events with the same timing ideas.
 */

// Node imports this module in tests, where matchMedia does not exist.
const reducedMotion = typeof matchMedia === "function"
  ? matchMedia("(prefers-reduced-motion: reduce)")
  : { matches: true, addEventListener() {} };

/** Keeps a value inside [min, max) by wrapping around, like gsap.utils.wrap. */
export function wrap(min, max, value) {
  const range = max - min;
  return ((((value - min) % range) + range) % range) + min;
}

/**
 * Flip Text's stagger: delays follow a quarter sine across the word, so the
 * flip starts quickly and eases toward the last letter.
 */
export function flipDelay(index, total, duration) {
  if (total <= 1) return 0;
  return Math.sin((index / total) * (Math.PI / 2)) * duration * 0.25;
}

function splitGraphemes(text) {
  if (!("Segmenter" in Intl)) return Array.from(text);
  const segmenter = new Intl.Segmenter(undefined, { granularity: "grapheme" });
  return Array.from(segmenter.segment(text), ({ segment }) => segment);
}

/**
 * Splits every [data-flip-line] inside [data-flip] into letters. The letters
 * flip once when the page loads and again whenever the pointer enters.
 * Returns a refresh function to call after the text changes language.
 */
export function initFlipText(duration = 1.1) {
  const blocks = [...document.querySelectorAll("[data-flip]")];

  const timers = new Map();

  function play(block) {
    if (reducedMotion.matches) return;
    block.classList.remove("is-flipping");
    void block.offsetWidth; // Restart the CSS animation.
    block.classList.add("is-flipping");
    clearTimeout(timers.get(block));
    // The last letter starts at a quarter of the duration and runs one duration.
    timers.set(block, setTimeout(() => block.classList.remove("is-flipping"), (duration * 1.25 + 0.1) * 1000));
  }

  for (const block of blocks) {
    block.addEventListener("pointerenter", (event) => {
      if (event.pointerType === "mouse" && !block.classList.contains("is-flipping")) play(block);
    });
  }

  return function refreshFlipText() {
    for (const block of blocks) {
      const lines = [...block.querySelectorAll("[data-flip-line]")];
      if (block.matches("h1, h2")) {
        block.setAttribute("aria-label", lines.map((line) => line.textContent).join(" "));
      }
      const chars = lines.flatMap((line) => splitGraphemes(line.textContent));
      const total = chars.filter((char) => !/^\s$/u.test(char)).length;
      let index = 0;
      for (const line of lines) {
        const content = document.createDocumentFragment();
        for (const char of splitGraphemes(line.textContent)) {
          if (/^\s$/u.test(char)) {
            content.append(char);
            continue;
          }
          const span = document.createElement("span");
          span.dataset.flipChar = "";
          span.textContent = char;
          span.style.setProperty("--flip-delay", `${flipDelay(index++, total, duration).toFixed(3)}s`);
          span.style.setProperty("--flip-duration", `${duration}s`);
          content.append(span);
        }
        line.setAttribute("aria-hidden", "true");
        line.replaceChildren(content);
      }
      play(block);
    }
  };
}

/**
 * Text reel: a vertical loop of phrases that drifts slowly and speeds up in
 * the direction the page scrolls. Returns a refresh function that rebuilds
 * the duplicated copies after a language change.
 */
export function initTextReel() {
  const viewport = document.querySelector("[data-reel]");
  const track = viewport.querySelector("[data-reel-track]");
  const BASE = 0.45;
  const MAX = 10;
  let distance = 0;
  let y = 0;
  let velocity = BASE;
  let target = BASE;
  let direction = 1;
  let frame = 0;
  let last = 0;
  let visible = false;
  let settle = 0;
  let lastScroll = scrollY;

  function build() {
    const items = [...track.querySelectorAll(":scope > li:not([data-reel-clone])")];
    track.querySelectorAll("[data-reel-clone]").forEach((clone) => clone.remove());
    for (const item of items) {
      const clone = item.cloneNode(true);
      clone.dataset.reelClone = "";
      clone.removeAttribute("data-i18n");
      clone.setAttribute("aria-hidden", "true");
      track.append(clone);
    }
    // One full set measures the loop; the clones fill the gap behind it.
    distance = items.reduce((sum, item) => sum + item.offsetHeight, 0);
  }

  function tick(now) {
    frame = requestAnimationFrame(tick);
    const delta = Math.min(64, now - (last || now));
    last = now;
    if (!distance) return;
    velocity += (target - velocity) * 0.14;
    y = wrap(-distance, 0, y - velocity * (delta / (1000 / 60)));
    track.style.transform = `translate3d(0, ${y.toFixed(2)}px, 0)`;
  }

  function sync() {
    const play = visible && !reducedMotion.matches && !document.hidden;
    viewport.classList.toggle("is-moving", !reducedMotion.matches);
    if (play && !frame) {
      last = 0;
      frame = requestAnimationFrame(tick);
    } else if (!play && frame) {
      cancelAnimationFrame(frame);
      frame = 0;
    }
    if (reducedMotion.matches) track.style.transform = "";
  }

  window.addEventListener("scroll", () => {
    const delta = scrollY - lastScroll;
    lastScroll = scrollY;
    if (!visible || !delta) return;
    direction = delta > 0 ? 1 : -1;
    target = direction * Math.min(MAX, BASE + Math.abs(delta) ** 1.2 * 0.08);
    clearTimeout(settle);
    settle = setTimeout(() => { target = direction * BASE; }, 120);
  }, { passive: true });

  if ("IntersectionObserver" in window) {
    new IntersectionObserver(([entry]) => {
      visible = entry.isIntersecting;
      sync();
    }).observe(viewport);
  }
  if ("ResizeObserver" in window) new ResizeObserver(build).observe(viewport);
  document.addEventListener("visibilitychange", sync);
  reducedMotion.addEventListener("change", sync);

  return function refreshReel() {
    build();
    sync();
  };
}

/**
 * Draggable Marquee: the screenshot strip drifts left on its own, can be
 * dragged with momentum, and loops by cloning the cards once. Arrow keys
 * move it when it has focus. Without JavaScript it is a native scroll row.
 */
export function initMarquee() {
  const marquee = document.querySelector("[data-marquee]");
  const track = marquee.querySelector("[data-marquee-track]");
  const SPEED = 0.5;
  let width = 0;
  let x = 0;
  let velocity = -SPEED;
  let dragging = false;
  let startX = 0;
  let startOffset = 0;
  let lastX = 0;
  let lastTime = 0;
  let frame = 0;
  let previous = 0;
  let visible = false;
  let hovered = false;
  let moved = false;

  function build() {
    track.querySelectorAll("[data-marquee-clone]").forEach((clone) => clone.remove());
    const items = [...track.children];
    for (const item of items) {
      const clone = item.cloneNode(true);
      clone.dataset.marqueeClone = "";
      clone.setAttribute("aria-hidden", "true");
      clone.querySelectorAll("[data-i18n], [data-i18n-attr]").forEach((element) => {
        element.removeAttribute("data-i18n");
        element.removeAttribute("data-i18n-attr");
      });
      clone.querySelectorAll("img").forEach((image) => { image.alt = ""; });
      track.append(clone);
    }
    measure();
  }

  function measure() {
    frames = [...track.querySelectorAll(".shot--wide .shot__frame")].map((frame) => ({ frame, image: frame.querySelector("img") }));
    const first = track.firstElementChild;
    const firstClone = track.querySelector("[data-marquee-clone]");
    width = firstClone && first ? firstClone.offsetLeft - first.offsetLeft : 0;
  }

  let frames = [];

  function paint() {
    track.style.transform = `translate3d(${x.toFixed(2)}px, 0, 0)`;
    if (reducedMotion.matches) return;
    // From ObsidianUI's Parallax Slider: each picture shifts against the motion
    // inside its frame, depending on how far its card is from the screen center.
    const center = innerWidth / 2;
    for (const { frame, image } of frames) {
      const box = frame.getBoundingClientRect();
      if (box.right < 0 || box.left > innerWidth) continue;
      const offset = (box.left + box.width / 2 - center) / innerWidth;
      image.style.transform = `translate3d(${(offset * -14).toFixed(2)}%, 0, 0) scale(1.18)`;
    }
  }

  function tick(now) {
    frame = requestAnimationFrame(tick);
    const delta = Math.min(64, now - (previous || now)) / (1000 / 60);
    previous = now;
    if (!width || dragging) return;
    const cruise = reducedMotion.matches || hovered ? 0 : -SPEED;
    velocity += (cruise - velocity) * 0.04;
    x = wrap(-width, 0, x + velocity * delta);
    paint();
  }

  function sync() {
    const play = visible && !document.hidden;
    if (play && !frame) {
      previous = 0;
      frame = requestAnimationFrame(tick);
    } else if (!play && frame) {
      cancelAnimationFrame(frame);
      frame = 0;
    }
  }

  marquee.classList.add("is-live");
  build();

  marquee.addEventListener("pointerdown", (event) => {
    if (event.button !== 0) return;
    dragging = true;
    moved = false;
    startX = lastX = event.clientX;
    startOffset = x;
    lastTime = performance.now();
    velocity = 0;
    marquee.setPointerCapture(event.pointerId);
    marquee.classList.add("is-dragging");
  });
  marquee.addEventListener("pointermove", (event) => {
    if (!dragging) return;
    const now = performance.now();
    if (Math.abs(event.clientX - startX) > 4) moved = true;
    x = wrap(-width, 0, startOffset + event.clientX - startX);
    velocity = ((event.clientX - lastX) / Math.max(1, now - lastTime)) * (1000 / 60);
    lastX = event.clientX;
    lastTime = now;
    paint();
  });
  const release = () => {
    if (!dragging) return;
    dragging = false;
    // Momentum: the release speed decays back to the cruise speed in tick().
    velocity = Math.max(-40, Math.min(40, velocity));
    marquee.classList.remove("is-dragging");
  };
  marquee.addEventListener("pointerup", release);
  marquee.addEventListener("pointercancel", release);
  marquee.addEventListener("click", (event) => {
    if (moved) event.preventDefault();
  }, true);
  marquee.addEventListener("pointerenter", (event) => { hovered = event.pointerType === "mouse"; });
  marquee.addEventListener("pointerleave", () => { hovered = false; });
  marquee.addEventListener("keydown", (event) => {
    if (event.key !== "ArrowLeft" && event.key !== "ArrowRight") return;
    event.preventDefault();
    velocity = event.key === "ArrowLeft" ? 14 : -14;
  });

  if ("IntersectionObserver" in window) {
    new IntersectionObserver(([entry]) => {
      visible = entry.isIntersecting;
      sync();
    }).observe(marquee);
  }
  if ("ResizeObserver" in window) new ResizeObserver(measure).observe(track);
  document.addEventListener("visibilitychange", sync);

  return function refreshMarquee() {
    build();
  };
}

/**
 * Click Spark: short lines burst from every click or tap. Drawn on one fixed
 * canvas that only runs while sparks are alive.
 */
export function initClickSpark({ count = 8, size = 10, radius = 18, duration = 420 } = {}) {
  const canvas = document.createElement("canvas");
  canvas.className = "click-spark";
  canvas.setAttribute("aria-hidden", "true");
  document.body.append(canvas);
  const context = canvas.getContext("2d");
  let sparks = [];
  let frame = 0;

  function resize() {
    const ratio = devicePixelRatio || 1;
    canvas.width = innerWidth * ratio;
    canvas.height = innerHeight * ratio;
    context.setTransform(ratio, 0, 0, ratio, 0, 0);
  }

  function draw(now) {
    context.clearRect(0, 0, innerWidth, innerHeight);
    context.strokeStyle = getComputedStyle(document.documentElement).getPropertyValue("--accent").trim() || "#e6532f";
    context.lineWidth = 2;
    context.lineCap = "round";
    sparks = sparks.filter((spark) => {
      const progress = (now - spark.start) / duration;
      if (progress >= 1) return false;
      const eased = progress * (2 - progress);
      const distance = eased * radius;
      const length = size * (1 - eased);
      const cos = Math.cos(spark.angle);
      const sin = Math.sin(spark.angle);
      context.beginPath();
      context.moveTo(spark.x + distance * cos, spark.y + distance * sin);
      context.lineTo(spark.x + (distance + length) * cos, spark.y + (distance + length) * sin);
      context.stroke();
      return true;
    });
    frame = sparks.length ? requestAnimationFrame(draw) : 0;
  }

  function burst(x, y) {
    if (reducedMotion.matches) return;
    const start = performance.now();
    for (let i = 0; i < count; i++) sparks.push({ x, y, angle: (2 * Math.PI * i) / count, start });
    if (!frame) frame = requestAnimationFrame(draw);
  }

  resize();
  window.addEventListener("resize", resize);
  document.addEventListener("pointerdown", (event) => burst(event.clientX, event.clientY), { passive: true });
}

/**
 * Smooth Scroll: Lenis eases wheel scrolling on mouse and trackpad devices.
 * Touch keeps native scrolling, and reduced motion turns it off entirely.
 * Anchor links scroll with an offset for the sticky navigation.
 */
export async function initSmoothScroll() {
  const fine = matchMedia("(hover: hover) and (pointer: fine)");
  let lenis = null;
  let frame = 0;

  async function sync() {
    const wanted = fine.matches && !reducedMotion.matches;
    if (wanted && !lenis) {
      const { default: Lenis } = await import("lenis");
      lenis = new Lenis({
        duration: 1.2,
        easing: (t) => Math.min(1, 1.001 - 2 ** (-10 * t)),
        smoothWheel: true,
        anchors: { offset: -64 },
      });
      const raf = (time) => {
        lenis?.raf(time);
        frame = requestAnimationFrame(raf);
      };
      frame = requestAnimationFrame(raf);
    } else if (!wanted && lenis) {
      cancelAnimationFrame(frame);
      lenis.destroy();
      lenis = null;
    }
  }

  fine.addEventListener("change", sync);
  reducedMotion.addEventListener("change", sync);
  await sync();
}
