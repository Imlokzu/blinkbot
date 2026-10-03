/*
 * The smaller moving parts: the navigation bar, the reading progress line,
 * magnetic buttons, the cards' pointer light, the marquee that speeds up
 * with the scroll, the terminal that types itself, and the final crab.
 */

import { gsap } from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import { mountCrab } from "./crab.js";
import { t } from "./i18n.js";

// Names, not words: they read the same in every language, so they are data.
const MARQUEE = [
  ["OpenClaw", "Whisper", "Piper", "Telegram", "Discord", "Raspberry Pi", "MCP"],
  ["FastAPI", "Excalidraw", "YouTube Music", "GSAP", "Markdown", "Python", "PWA"],
];

export function initNav() {
  const nav = document.querySelector("[data-nav]");
  ScrollTrigger.create({
    start: "top -12",
    end: "max",
    onToggle: (self) => nav.classList.toggle("is-scrolled", self.isActive),
  });
  const bar = document.querySelector(".progress__bar");
  ScrollTrigger.create({
    start: 0,
    end: "max",
    onUpdate: (self) => gsap.set(bar, { scaleX: self.progress }),
  });
}

export function initScrollLinks({ smoother, revealPoint }) {
  for (const link of document.querySelectorAll("[data-scroll-to]")) {
    link.addEventListener("click", (event) => {
      const selector = link.dataset.scrollTo;
      const target = selector === "reveal" ? null : document.querySelector(selector);
      if (selector !== "reveal" && !target) return;
      event.preventDefault();
      const y = selector === "reveal" ? revealPoint() : null;
      if (smoother) {
        smoother.scrollTo(y ?? target, true, "top top");
      } else if (y !== null) {
        window.scrollTo({ top: y, behavior: "smooth" });
      } else {
        target.scrollIntoView({ behavior: "smooth" });
      }
      if (selector.startsWith("#")) history.replaceState(null, "", selector === "#top" ? location.pathname + location.search : selector);
    });
  }
}

/** Buttons lean toward the pointer a little, then spring back. */
export function initMagnetic() {
  for (const el of document.querySelectorAll("[data-magnetic]")) {
    const x = gsap.quickTo(el, "x", { duration: 0.6, ease: "power3.out" });
    const y = gsap.quickTo(el, "y", { duration: 0.6, ease: "power3.out" });
    el.addEventListener("pointermove", (event) => {
      const box = el.getBoundingClientRect();
      x((event.clientX - box.left - box.width / 2) * 0.22);
      y((event.clientY - box.top - box.height / 2) * 0.3);
    });
    el.addEventListener("pointerleave", () => {
      gsap.to(el, { x: 0, y: 0, duration: 0.9, ease: "elastic.out(1, 0.35)" });
    });
  }
}

/*
 * The cards' little loops are CSS animations; off screen they would keep
 * ticking for nobody. Pause whatever is out of view.
 */
function pauseOffscreen(selector) {
  if (!("IntersectionObserver" in window)) return;
  const observer = new IntersectionObserver(
    (entries) => entries.forEach((entry) => entry.target.classList.toggle("is-paused", !entry.isIntersecting)),
    { rootMargin: "80px" },
  );
  document.querySelectorAll(selector).forEach((el) => observer.observe(el));
}

export function initCards({ finePointer }) {
  document.querySelectorAll(".viz-voice__bars i").forEach((bar, i) => bar.style.setProperty("--i", String(i)));
  pauseOffscreen("[data-card], [data-tiers], .hero__scroll");
  if (!finePointer) return;
  for (const card of document.querySelectorAll("[data-card]")) {
    card.addEventListener("pointermove", (event) => {
      const box = card.getBoundingClientRect();
      card.style.setProperty("--mx", `${event.clientX - box.left}px`);
      card.style.setProperty("--my", `${event.clientY - box.top}px`);
    });
  }
}

export function initMarquee({ reduced }) {
  const crab = '<svg viewBox="0 0 10 8" aria-hidden="true" shape-rendering="crispEdges"><use href="#crab-glyph"/></svg>';
  const rows = [...document.querySelectorAll("[data-marquee-row]")];
  rows.forEach((row, i) => {
    const track = `<div class="marquee__track">${MARQUEE[i].map((name) => `<span>${name}</span>${crab}`).join("")}</div>`;
    // Two identical tracks: when the first has slid fully away, the second
    // stands exactly where it started, so the loop has no seam.
    row.innerHTML = track + track;
  });
  if (reduced) return;

  const loops = rows.map((row) => {
    const direction = Number(row.dataset.marqueeRow);
    return direction > 0
      ? gsap.fromTo(row, { xPercent: 0 }, { xPercent: -50, ease: "none", duration: 42, repeat: -1 })
      : gsap.fromTo(row, { xPercent: -50 }, { xPercent: 0, ease: "none", duration: 48, repeat: -1 });
  });

  // Scrolling pushes the marquee: faster, and leaning into the motion.
  const skew = gsap.quickTo(rows, "skewX", { duration: 0.6, ease: "power3.out" });
  const st = ScrollTrigger.create({
    trigger: "[data-marquee]",
    start: "top bottom",
    end: "bottom top",
    onUpdate: (self) => {
      const velocity = self.getVelocity();
      const boost = 1 + Math.min(Math.abs(velocity) / 220, 7);
      loops.forEach((loop) => {
        loop.timeScale(boost);
        gsap.to(loop, { timeScale: 1, duration: 1.2, ease: "power2.out", overwrite: true });
      });
      skew(gsap.utils.clamp(-9, 9, velocity / -260));
    },
    onToggle: (self) => loops.forEach((loop) => (self.isActive ? loop.resume() : loop.pause())),
    onLeave: () => skew(0),
    onLeaveBack: () => skew(0),
  });
  if (!st.isActive) loops.forEach((loop) => loop.pause());
}

export function initTerminal({ reduced }) {
  const terminal = document.querySelector("[data-terminal]");
  if (!terminal) return;
  const lines = [...terminal.querySelectorAll("code[data-type]")];
  const full = lines.map((line) => line.textContent);

  if (!reduced) {
    lines.forEach((line) => {
      line.textContent = "";
    });
    ScrollTrigger.create({
      trigger: terminal,
      start: "top 75%",
      once: true,
      onEnter: () => {
        const tl = gsap.timeline({ delay: 0.3 });
        lines.forEach((line, i) => {
          const text = full[i];
          const state = { n: 0 };
          tl.add(() => line.classList.add("is-typing"));
          tl.to(state, {
            n: text.length,
            duration: Math.min(1.4, text.length * 0.028),
            ease: "none",
            onUpdate: () => {
              line.textContent = text.slice(0, Math.round(state.n));
            },
          });
          tl.add(() => line.classList.remove("is-typing"), "+=0.25");
        });
        tl.add(() => lines.at(-1).classList.add("is-typing"));
      },
    });
  }

  for (const button of terminal.querySelectorAll("[data-copy]")) {
    button.addEventListener("click", async () => {
      const label = button.querySelector("[data-i18n]");
      try {
        await navigator.clipboard.writeText(button.dataset.copy);
      } catch {
        return; // no clipboard access: the command is still on screen to select
      }
      button.classList.add("is-done");
      label.textContent = t("start.copied");
      gsap.delayedCall(1.8, () => {
        button.classList.remove("is-done");
        label.textContent = t("start.copy");
      });
    });
  }
}

export function initFinal({ reduced }) {
  const canvas = document.querySelector('[data-crab="final"]');
  if (!canvas) return;
  const crab = mountCrab(canvas, { scale: 8, still: reduced });
  const cta = canvas.closest(".final").querySelector(".button--primary");
  cta.addEventListener("pointerenter", () => crab.setEmotion("celebrating"));
  cta.addEventListener("pointerleave", () => crab.setEmotion("idle"));
}
