/*
 * The bot's pixel crab on the landing page.
 *
 * The engine is Virtual Bot/static/crab.js itself (vite.config.js turns that
 * classic script into a module), so the crab here walks, blinks and emotes
 * exactly like the one on the device screen. This file only adds what a long
 * marketing page needs and a single always-visible face does not: three crabs
 * would otherwise each run a requestAnimationFrame loop while scrolled far
 * out of view, so a crab off screen is paused.
 *
 * The engine has no pause API, so this reaches into its loop handle
 * (_raf/_tick/_lastTs) — the same fields its own visibilitychange handler
 * uses. If crab.js ever renames them, pausing stops working but nothing
 * breaks: the crab just keeps animating.
 */

import { PixelCrab } from "bot-crab";

const crabs = new Set();

function pause(crab) {
  if (crab._raf !== null) cancelAnimationFrame(crab._raf);
  crab._raf = null;
  crab._lastTs = null;
}

function resume(crab) {
  if (crab._raf === null && !document.hidden) crab._raf = requestAnimationFrame(crab._tick);
}

const observer =
  "IntersectionObserver" in window
    ? new IntersectionObserver(
        (entries) => {
          for (const entry of entries) {
            const record = [...crabs].find((c) => c.canvas === entry.target);
            if (!record) continue;
            record.visible = entry.isIntersecting;
            if (record.still) continue;
            if (record.visible) resume(record.crab);
            else pause(record.crab);
          }
        },
        { rootMargin: "120px" },
      )
    : null;

// The engine resumes itself when the tab comes back; a crab that is still
// off screen (or held still) should not. Each engine registers its own
// listener in its constructor, after this one, so the check waits a tick
// until all of them have run and then takes back what they restarted.
document.addEventListener("visibilitychange", () => {
  if (document.hidden) return;
  setTimeout(() => {
    for (const record of crabs) {
      if (record.still || !record.visible) pause(record.crab);
    }
  }, 0);
});

/**
 * Start a crab on a canvas. `still` draws a few frames and stops, for
 * visitors who asked for reduced motion.
 */
export function mountCrab(canvas, { scale = 8, emotion = "idle", still = false } = {}) {
  const crab = new PixelCrab(canvas, null, null, { scale });
  const record = { canvas, crab, still, visible: true };
  crabs.add(record);
  crab.setEmotion(emotion);

  if (still) {
    pause(crab);
    for (let i = 0; i < 8; i += 1) crab._update(1 / 60);
  } else {
    observer?.observe(canvas);
  }

  return {
    setEmotion(next) {
      crab.setEmotion(next);
      if (still) for (let i = 0; i < 8; i += 1) crab._update(1 / 60);
    },
    get emotion() {
      return crab.emotion;
    },
  };
}
