/*
 * Text entrances.
 *
 * Headings rise line by line out of a mask; the statement lights up word by
 * word as it is scrolled through. Both are built with SplitText, and both
 * have to survive a language switch, which replaces the very text that was
 * split. So a heading is un-split again as soon as its entrance has played
 * (it is then plain text that re-wraps on resize and can be swapped freely),
 * and rebuild() re-splits only what has not been revealed yet.
 */

import { gsap } from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import { SplitText } from "gsap/SplitText";

const pending = new Map(); // element -> { split, tween }
const revealed = new WeakSet();
let statement = null; // { split, tween }

function revealHeading(el) {
  const target = el.querySelector("[data-i18n]") || el;
  const split = SplitText.create(target, { type: "lines", mask: "lines", linesClass: "split-line" });
  const tween = gsap.from(split.lines, {
    yPercent: 118,
    rotate: 2.5,
    duration: 1.25,
    ease: "expo.out",
    stagger: 0.09,
    scrollTrigger: { trigger: el, start: "top 86%", once: true },
    onComplete() {
      revealed.add(el);
      pending.delete(el);
      split.revert();
    },
  });
  pending.set(el, { split, tween });
}

function buildStatement() {
  const el = document.querySelector("[data-statement]");
  if (!el) return;
  const split = SplitText.create(el, { type: "words", wordsClass: "word" });
  const tween = gsap.to(split.words, {
    opacity: 1,
    ease: "none",
    stagger: 0.12,
    scrollTrigger: { trigger: el, start: "top 78%", end: "bottom 42%", scrub: 0.6 },
  });
  statement = { split, tween };
}

/** Plain-language copy is never hidden from someone who asked for less motion. */
export function initReveals({ reduced }) {
  if (reduced) return;
  for (const el of document.querySelectorAll("[data-split-lines]")) {
    if (el.closest("[data-hero]")) continue; // the hero has its own entrance
    revealHeading(el);
  }
  buildStatement();

  ScrollTrigger.batch("[data-reveal]", {
    start: "top 90%",
    once: true,
    onEnter: (batch) =>
      gsap.from(batch, { y: 34, opacity: 0, duration: 1.1, ease: "expo.out", stagger: 0.08, overwrite: true }),
  });
}

/** Undo every split that still exists, so new text can be written in. */
export function unsplit() {
  for (const { split, tween } of pending.values()) {
    tween.scrollTrigger?.kill();
    tween.kill();
    split.revert();
  }
  if (statement) {
    statement.tween.scrollTrigger?.kill();
    statement.tween.kill();
    statement.split.revert();
    statement = null;
  }
}

/** After a language switch: split again whatever has not made its entrance yet. */
export function resplit({ reduced }) {
  if (reduced) return;
  const waiting = [...pending.keys()];
  pending.clear();
  for (const el of waiting) {
    if (!revealed.has(el)) revealHeading(el);
  }
  buildStatement();
}
