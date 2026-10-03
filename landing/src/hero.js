/*
 * The hero: an entrance on load, then the dashboard window rising out of it.
 *
 * On a wide screen the hero is pinned while the window, which starts tilted
 * back and peeking from below the copy, flattens and moves up into full view,
 * and the callouts pointing at real parts of the UI appear one by one. On a
 * phone the window simply sits under the copy and straightens as it scrolls
 * in: pinning there fights the collapsing address bar.
 */

import { gsap } from "gsap";
import { SplitText } from "gsap/SplitText";
import { mountCrab } from "./crab.js";

const CLICK_MOODS = ["love", "celebrating", "cool", "surprised", "happy"];

export function initHero({ reduced, finePointer }) {
  const hero = document.querySelector("[data-hero]");
  const content = hero.querySelector("[data-hero-content]");
  const stage = hero.querySelector("[data-hero-stage]");
  const win = hero.querySelector("[data-window]");
  const callouts = hero.querySelectorAll("[data-callout]");
  const crabButton = hero.querySelector("[data-hero-crab]");

  const crab = mountCrab(hero.querySelector('[data-crab="hero"]'), { scale: 8, still: reduced });

  // Poking the crab cycles through its happier moods, then it calms down.
  let moodIndex = 0;
  let calm = null;
  crabButton.addEventListener("click", () => {
    crab.setEmotion(CLICK_MOODS[moodIndex % CLICK_MOODS.length]);
    moodIndex += 1;
    calm?.kill();
    calm = gsap.delayedCall(2.6, () => crab.setEmotion("idle"));
  });
  const primary = hero.querySelector(".button--primary");
  primary.addEventListener("pointerenter", () => crab.setEmotion("happy"));
  primary.addEventListener("pointerleave", () => crab.setEmotion("idle"));

  // A warm light that drifts toward the pointer.
  if (finePointer && !reduced) {
    const glow = hero.querySelector(".hero__glow");
    const setX = gsap.quickTo(glow, "--gx", { duration: 1.2, ease: "power3.out" });
    const setY = gsap.quickTo(glow, "--gy", { duration: 1.2, ease: "power3.out" });
    gsap.set(glow, { "--gx": "50%", "--gy": "38%" });
    hero.addEventListener("pointermove", (event) => {
      const box = hero.getBoundingClientRect();
      setX(`${(((event.clientX - box.left) / box.width) * 100).toFixed(1)}%`);
      setY(`${(((event.clientY - box.top) / box.height) * 100).toFixed(1)}%`);
    });
  }

  if (reduced) {
    document.documentElement.classList.add("is-ready");
    return { crab, intro: null };
  }

  // ── Entrance ──
  const title = SplitText.create(hero.querySelectorAll(".hero__title [data-i18n]"), {
    type: "lines,chars",
    mask: "lines",
    linesClass: "split-line",
  });
  const items = hero.querySelectorAll("[data-hero-item]");

  gsap.set(title.chars, { yPercent: 120, rotate: 7 });
  gsap.set(items, { y: 26, opacity: 0 });
  gsap.set(crabButton, { y: 26, opacity: 0, scale: 0.8 });
  gsap.set(stage, { y: 160, opacity: 0 });
  gsap.set(callouts, { opacity: 0, scale: 0.85 });
  document.documentElement.classList.add("is-ready");

  const intro = gsap
    .timeline({ delay: 0.1, onComplete: () => title.revert() })
    .to(crabButton, { y: 0, opacity: 1, scale: 1, duration: 1.1, ease: "back.out(2.2)" }, 0)
    .to(title.chars, { yPercent: 0, rotate: 0, duration: 1.3, ease: "expo.out", stagger: 0.018 }, 0.08)
    .to(items, { y: 0, opacity: 1, duration: 1.1, ease: "expo.out", stagger: 0.08 }, 0.42)
    .to(stage, { y: 0, opacity: 1, duration: 1.8, ease: "expo.out" }, 0.55)
    .add(() => crab.setEmotion("greeting"), 0.6)
    .add(() => crab.emotion === "greeting" && crab.setEmotion("idle"), 3.2);

  // ── Scroll: the window rises and flattens ──
  const mm = gsap.matchMedia();

  mm.add("(min-width: 961px)", () => {
    // Where the window starts: just under the copy, but always showing a
    // good slice of itself, whatever the screen height.
    const peek = () => {
      const natural = win.offsetTop;
      const below = content.offsetTop + content.offsetHeight + 36;
      return Math.min(below, window.innerHeight - 170) - natural;
    };
    // fromTo, not to: on a resize the starting poses are measured again
    // (invalidateOnRefresh) instead of being re-read mid-scroll.
    const tl = gsap.timeline({
      scrollTrigger: {
        trigger: hero,
        start: "top top",
        end: "+=170%",
        pin: true,
        scrub: 0.9,
        anticipatePin: 1,
        invalidateOnRefresh: true,
      },
    });
    tl.fromTo(
      content,
      { y: 0, opacity: 1 },
      { y: () => -window.innerHeight * 0.38, opacity: 0, ease: "power2.in", duration: 0.34 },
      0,
    )
      .fromTo(
        win,
        { y: peek, rotateX: 24, scale: 0.9 },
        { y: 0, rotateX: 0, scale: 1, ease: "power2.inOut", duration: 0.55, immediateRender: true },
        0,
      )
      .fromTo("[data-hero-scroll]", { opacity: 1 }, { opacity: 0, duration: 0.08 }, 0)
      .fromTo(
        callouts,
        { opacity: 0, scale: 0.85 },
        { opacity: 1, scale: 1, ease: "back.out(2)", duration: 0.1, stagger: 0.09 },
        0.6,
      )
      .to({}, { duration: 0.18 });

    return () => gsap.set(win, { clearProps: "transform" });
  });

  mm.add("(max-width: 960px)", () => {
    gsap.fromTo(
      win,
      { rotateX: 20, scale: 0.94 },
      {
        rotateX: 0,
        scale: 1,
        ease: "none",
        scrollTrigger: { trigger: win, start: "top bottom", end: "top 35%", scrub: true },
      },
    );
  });

  return { crab, intro };
}
