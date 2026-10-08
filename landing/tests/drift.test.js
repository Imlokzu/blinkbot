import { test } from "node:test";
import assert from "node:assert/strict";
import { initDrift } from "../src/drift.js";

// Exercise scheduling without a browser: a duplicate RAF loop would consume
// battery even when the field looks correct in a screenshot.
function fixture(t, { reduced = false, supported = true } = {}) {
  const attributes = new Map();
  const toggle = Object.assign(new EventTarget(), {
    hidden: true,
    setAttribute: (key, value) => attributes.set(key, value),
  });
  let strokes = 0;
  const context = {
    setTransform() {}, fillRect() {}, beginPath() {}, moveTo() {}, lineTo() {},
    stroke() { strokes++; },
  };
  const box = { width: 390, height: 844 };
  const canvas = Object.assign(new EventTarget(), {
    parentElement: { getBoundingClientRect: () => box },
    getContext: () => supported ? context : null,
  });
  const hero = { querySelector: (selector) => selector === "[data-drift]" ? canvas : toggle };
  const media = Object.assign(new EventTarget(), { matches: reduced });
  const pending = new Map();
  let nextId = 0, intersect, resized, disconnected = 0;
  const win = Object.assign(new EventTarget(), {
    devicePixelRatio: 3,
    matchMedia: (query) => query.includes("reduced-motion") ? media : { matches: true },
    requestAnimationFrame: (callback) => { pending.set(++nextId, callback); return nextId; },
    cancelAnimationFrame: (id) => pending.delete(id),
  });
  const doc = Object.assign(new EventTarget(), { hidden: false });
  const globals = {
    window: win, document: doc,
    IntersectionObserver: class {
      constructor(callback) { intersect = callback; }
      observe() {}
      disconnect() { disconnected++; }
    },
    ResizeObserver: class {
      constructor(callback) { resized = callback; }
      observe() {}
      disconnect() { disconnected++; }
    },
  };
  for (const [key, value] of Object.entries(globals)) {
    const previous = Object.getOwnPropertyDescriptor(globalThis, key);
    Object.defineProperty(globalThis, key, { configurable: true, value });
    t.after(() => {
      if (previous) Object.defineProperty(globalThis, key, previous);
      else delete globalThis[key];
    });
  }
  const destroy = initDrift(hero);
  return {
    canvas, toggle, attributes, pending, win, doc, media, box, destroy,
    strokes: () => strokes,
    disconnected: () => disconnected,
    visible: (value) => intersect([{ isIntersecting: value }]),
    resize: () => resized(),
    frame: (time) => {
      for (const [id, callback] of [...pending]) { pending.delete(id); callback(time); }
    },
  };
}

test("the field pauses offscreen, in hidden tabs and on page navigation", (t) => {
  const f = fixture(t);
  assert.equal(f.pending.size, 0);
  f.visible(true);
  f.frame(50);
  f.visible(true);
  f.win.dispatchEvent(new Event("resize"));
  assert.equal(f.pending.size, 1, "repeated events must not create multiple loops");
  f.visible(false);
  assert.equal(f.pending.size, 0);
  f.visible(true);
  f.doc.hidden = true;
  f.doc.dispatchEvent(new Event("visibilitychange"));
  assert.equal(f.pending.size, 0);
  f.doc.hidden = false;
  f.doc.dispatchEvent(new Event("visibilitychange"));
  assert.equal(f.pending.size, 1);
  f.win.dispatchEvent(new Event("pagehide"));
  assert.equal(f.pending.size, 0);
  f.win.dispatchEvent(new Event("pageshow"));
  assert.equal(f.pending.size, 1, "back/forward cache restores one loop");
  f.destroy();
  assert.equal(f.pending.size, 0);
  assert.equal(f.disconnected(), 2);
  const strokesAfterDestroy = f.strokes();
  f.visible(true);
  f.box.width += 20;
  f.resize();
  assert.equal(f.strokes(), strokesAfterDestroy, "queued observers cannot redraw after cleanup");
  assert.equal(f.toggle.hidden, true);
  f.toggle.dispatchEvent(new Event("click"));
  f.win.dispatchEvent(new Event("pageshow"));
  assert.equal(f.pending.size, 0, "destroy removes lifecycle handlers");
});

test("reduced motion paints a still and respects a manually paused field", (t) => {
  const f = fixture(t, { reduced: true });
  f.visible(true);
  assert.ok(f.strokes() > 0, "a static illustration is still drawn");
  assert.equal(f.pending.size, 0);
  assert.equal(f.toggle.hidden, true);
  f.media.matches = false;
  f.media.dispatchEvent(new Event("change"));
  assert.equal(f.pending.size, 1);
  f.toggle.dispatchEvent(new Event("click"));
  assert.equal(f.attributes.get("aria-pressed"), "true");
  assert.equal(f.pending.size, 0);
  for (const value of [true, false]) {
    f.media.matches = value;
    f.media.dispatchEvent(new Event("change"));
    assert.equal(f.pending.size, 0, "motion changes retain the user's pause choice");
  }
  f.toggle.dispatchEvent(new Event("click"));
  assert.equal(f.pending.size, 1);
  f.destroy();
});

test("resizing bounds backing pixels and context recovery does not duplicate work", (t) => {
  const f = fixture(t);
  f.visible(true);
  Object.assign(f.box, { width: 3840, height: 2160 });
  f.resize();
  assert.ok(f.canvas.width * f.canvas.height <= 1802000, "pixel budget includes rounding");
  const strokes = f.strokes();
  f.resize();
  assert.equal(f.strokes(), strokes, "unchanged layout does not repaint the still");
  f.canvas.dispatchEvent(new Event("contextlost", { cancelable: true }));
  assert.equal(f.pending.size, 0);
  f.canvas.dispatchEvent(new Event("contextrestored"));
  assert.equal(f.pending.size, 1);
  f.destroy();
});

test("without Canvas support the landing keeps its fallback and hides the control", (t) => {
  const f = fixture(t, { supported: false });
  assert.equal(f.pending.size, 0);
  assert.equal(f.toggle.hidden, true);
  f.destroy();
});
