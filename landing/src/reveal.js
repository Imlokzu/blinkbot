/*
 * Text entrances.
 *
 * Headings rise line by line out of a mask; the statement lights up word by
 * word as it is scrolled through. Both are built with SplitText, and both
 * have to survive a language switch, which replaces the very text that was
 * split. So a heading is un-split again as soon as its entrance has played
 * (it is then plain text that re-wraps on resize and can be swapped freely),
 * and resplit() splits again only what has not been revealed yet.
 */

import { gsap } from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import { SplitText } from "gsap/SplitText";
import { t } from "./i18n.js";

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

// The statement's key words (listed per language in statement.accent) are
// set in the serif, the way the hero sets its second line.
function markAccents(words) {
  const accents = new Set(t("statement.accent").toLowerCase().split("|"));
  for (const word of words) {
    const bare = word.textContent.toLowerCase().replace(/[.,!?;:—–«»"]/g, "");
    if (accents.has(bare)) word.classList.add("word--accent");
  }
}

function buildStatement({ scrub }) {
  const el = document.querySelector("[data-statement]");
  if (!el) return;
  const split = SplitText.create(el, { type: "words", wordsClass: "word" });
  markAccents(split.words);
  const tween = scrub
    ? gsap.to(split.words, {
        opacity: 1,
        ease: "none",
        stagger: 0.12,
        scrollTrigger: { trigger: el, start: "top 78%", end: "bottom 42%", scrub: 0.6 },
      })
    : gsap.set(split.words, { opacity: 1 });
  statement = { split, tween };
}

/** Plain-language copy is never hidden from someone who asked for less motion. */
export function initReveals({ reduced }) {
  buildStatement({ scrub: !reduced });
  if (reduced) return;
  for (const el of document.querySelectorAll("[data-split-lines]")) {
    if (el.closest("[data-hero]")) continue; // the hero has its own entrance
    revealHeading(el);
  }

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
  buildStatement({ scrub: !reduced });
  if (reduced) return;
  const waiting = [...pending.keys()];
  pending.clear();
  for (const el of waiting) {
    if (!revealed.has(el)) revealHeading(el);
  }
}
