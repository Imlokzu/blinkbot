/* ============================================================
   Gesture navigation for open apps, as on Android

   An app opened full screen used to have one way out: the "Назад ✕"
   button in its title bar — and a full-screen app (the YouTube player)
   has no title bar, and an app in an iframe swallows every swipe, so the
   stage never saw one. Android solved the same thing with edges:

     - a pill at the bottom; swipe UP from it → home (the carousel);
     - swipe in from the LEFT or RIGHT edge → back (the previous app, or
       the drawer the app was opened from).

   Thin strips along those edges lie above the app, iframe included, so
   the gesture works in every app. They are narrow on purpose (12 px of a
   320 px screen): an app's own controls stay reachable. The app follows
   the finger — it shrinks as you pull it up, an arrow grows at the edge —
   so the gesture is visible before it is done, and letting go early
   cancels it.

   classify() is pure and exported, so node checks the thresholds
   (tests/test_screen_js.py).
   ============================================================ */

export const EDGE = 12;          // side strips, stage px
export const BOTTOM = 16;        // bottom strip, stage px
export const HOME_PULL = 36;     // how far up counts as "home"
export const BACK_PULL = 30;     // how far in counts as "back"

/**
 * What a finished edge drag means.
 * @param {"bottom"|"left"|"right"} zone  where the finger went down
 * @param {number} dx  stage px, + is right
 * @param {number} dy  stage px, + is down
 * @param {number} ms  how long the drag took
 * @returns {"home"|"back"|null}
 */
export function classify(zone, dx, dy, ms = 300) {
  const quick = ms < 220;                       // a flick needs less distance
  if (zone === "bottom") {
    const up = -dy;
    if (up > Math.abs(dx) * 0.7 && (up >= HOME_PULL || (quick && up >= HOME_PULL / 2))) return "home";
    return null;
  }
  const inward = zone === "left" ? dx : -dx;
  if (inward > Math.abs(dy) && (inward >= BACK_PULL || (quick && inward >= BACK_PULL / 2))) return "back";
  return null;
}

/** 0…1: how far along the gesture is, for the visual feedback. */
export function progress(zone, dx, dy) {
  if (zone === "bottom") return Math.max(0, Math.min(1, -dy / (HOME_PULL * 2)));
  const inward = zone === "left" ? dx : -dx;
  return Math.max(0, Math.min(1, inward / (BACK_PULL * 1.6)));
}

/* ------------------------------------------------------------------ DOM */

/**
 * @param {HTMLElement} stage
 * @param {object} deps {target() → element that follows the finger (the
 *   app layer), onHome(), onBack(), onActivity(), scale() → stage scale}
 */
export class GestureNav {
  constructor(stage, deps) {
    this.deps = deps;
    this.root = document.createElement("div");
    this.root.className = "gnav";
    this.root.setAttribute("aria-hidden", "true");
    this.zones = {};
    for (const zone of ["left", "right", "bottom"]) {
      const strip = document.createElement("div");
      strip.className = "gnav-zone gnav-" + zone;
      strip.dataset.zone = zone;
      this.root.appendChild(strip);
      this.zones[zone] = strip;
    }
    this.pill = document.createElement("div");
    this.pill.className = "gnav-pill";
    this.zones.bottom.appendChild(this.pill);
    this.arrow = document.createElement("div");
    this.arrow.className = "gnav-arrow";
    this.arrow.innerHTML = '<svg viewBox="0 0 24 24"><path d="M14.5 6 8.5 12l6 6"/></svg>';
    this.root.appendChild(this.arrow);
    stage.appendChild(this.root);

    this.drag = null;
    this.root.addEventListener("pointerdown", (e) => this._down(e));
    this.root.addEventListener("pointermove", (e) => this._move(e));
    this.root.addEventListener("pointerup", (e) => this._up(e));
    this.root.addEventListener("pointercancel", (e) => this._up(e, true));
    for (const type of ["click", "touchstart", "touchmove"]) {
      this.root.addEventListener(type, (e) => e.stopPropagation(), { passive: true });
    }
  }

  /** Shown only while an app is open. `hint` nudges the pill once. */
  setOn(on, hint) {
    this.root.classList.toggle("on", !!on);
    if (!on) this._reset();
    if (on && hint) {
      this.pill.classList.remove("hint");
      void this.pill.offsetWidth;
      this.pill.classList.add("hint");
    }
  }

  _down(e) {
    const strip = e.target.closest(".gnav-zone");
    if (!strip || this.drag) return;
    e.stopPropagation();
    e.preventDefault();
    this.deps.onActivity?.();
    this.drag = { id: e.pointerId, zone: strip.dataset.zone, x0: e.clientX, y0: e.clientY, t0: performance.now(), k: this.deps.scale() };
    try { this.root.setPointerCapture(e.pointerId); } catch (err) { /* not capturable */ }
    const target = this.deps.target();
    if (target) target.classList.add("gnav-dragging");
  }

  _move(e) {
    const d = this.drag;
    if (!d || d.id !== e.pointerId) return;
    e.stopPropagation();
    const dx = (e.clientX - d.x0) / d.k;
    const dy = (e.clientY - d.y0) / d.k;
    const p = progress(d.zone, dx, dy);
    const target = this.deps.target();
    if (d.zone === "bottom") {
      // The app rises and shrinks under the finger, as the Android card does
      if (target) target.style.transform = `translate3d(0, ${Math.round(-p * 26)}px, 0) scale(${(1 - p * 0.16).toFixed(3)})`;
      this.pill.style.transform = `translate3d(0, ${Math.round(Math.max(-40, dy))}px, 0)`;
    } else {
      // An arrow grows out of the edge; the app gives a little
      const rect = this.root.getBoundingClientRect();
      const y = (e.clientY - rect.top) / d.k;
      this.arrow.className = "gnav-arrow show " + d.zone + (p >= 1 ? " ready" : "");
      this.arrow.style.top = Math.round(y) + "px";
      this.arrow.style.transform = `translate3d(${Math.round((d.zone === "left" ? 1 : -1) * p * 26)}px, -50%, 0) scale(${(0.5 + p * 0.5).toFixed(3)})`;
      if (target) target.style.transform = `translate3d(${Math.round((d.zone === "left" ? 1 : -1) * p * 8)}px, 0, 0)`;
    }
  }

  _up(e, cancelled) {
    const d = this.drag;
    if (!d || d.id !== e.pointerId) return;
    e.stopPropagation();
    this.drag = null;
    const dx = (e.clientX - d.x0) / d.k;
    const dy = (e.clientY - d.y0) / d.k;
    const action = cancelled ? null : classify(d.zone, dx, dy, performance.now() - d.t0);
    // Drop the inline transform and act in the same frame: the layer then
    // glides from where the finger left it — back to full size on a
    // cancel, on into its closing transition on home/back.
    this._reset();
    if (action === "home") this.deps.onHome?.();
    else if (action === "back") this.deps.onBack?.();
  }

  _reset() {
    const target = this.deps.target();
    if (target) {
      target.classList.remove("gnav-dragging");
      target.style.transform = "";
    }
    this.pill.style.transform = "";
    this.arrow.className = "gnav-arrow";
    this.arrow.style.transform = "";
  }
}
