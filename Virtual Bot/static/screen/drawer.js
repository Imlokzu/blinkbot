/* ============================================================
   The app drawer, watch style

   The old drawer was a phone grid: five columns of 42 px circles and a
   list you scroll. On 320×240 that meant tiny targets, labels cut to
   "Налашту…", and scrolling to find anything past the first three rows.
   A watch solves the same problem on an even smaller screen with the
   honeycomb: big round icons packed as hexagons, panned freely in two
   directions, and a fisheye that keeps the middle full size while the
   edges shrink — so you see the whole set at once and still hit the
   one you want with a thumb.

   Two views, as on a watch: the honeycomb, and a list (icon + name) for
   when names matter more than the picture. The choice is remembered.

   Gestures inside the drawer are its own: drag pans (with momentum and a
   snap onto the nearest icon), a tap opens, pulling past the top edge
   closes — the way a watch drawer lets go. Pointer events do not reach
   the stage, so a pan never turns into a carousel swipe.

   The layout maths (honeycomb, fisheye, listLens, rubber) is pure and
   exported, so node checks it (tests/test_screen_js.py).
   ============================================================ */

export const VIEWS = ["honeycomb", "list"];

// Hex pitch: centre-to-centre distance of neighbouring icons. The icon is
// 48 px, so 6 px of air between discs at full size.
export const ICON = 48;
export const PITCH = 54;
export const ROW = 40;           // list row pitch

/**
 * Hex cells for n icons, nearest to the centre first.
 * Filled as an ellipse as wide as the screen is: a round blob on a 4:3
 * screen would leave the sides empty and push icons off the top.
 * @returns {{x: number, y: number}[]}  centres, in px, (0, 0) = first icon
 */
export function honeycomb(n, pitch = PITCH, aspect = 4 / 3) {
  const cells = [];
  const reach = Math.ceil(Math.sqrt(n)) + 2;
  const rowH = pitch * Math.sqrt(3) / 2;
  for (let r = -reach; r <= reach; r++) {
    for (let q = -reach * 2; q <= reach * 2; q++) {
      const x = pitch * (q + r / 2);
      const y = rowH * r;
      const ex = x / aspect;
      // Angle from "east", clockwise: within a ring the second app sits
      // to the right of the first, the rest go round like clock hands.
      let angle = Math.atan2(y, x);
      if (angle < 0) angle += Math.PI * 2;
      cells.push({ x, y, d: Math.round(Math.hypot(ex, y) * 100) / 100, angle });
    }
  }
  cells.sort((a, b) => a.d - b.d || a.angle - b.angle);
  return cells.slice(0, n).map(({ x, y }) => ({ x: round(x), y: round(y) }));
}

function round(v) {
  return Math.round(v * 100) / 100;
}

/**
 * Where an icon at (x, y) from the view centre is drawn, and how big.
 * Inside the inner ellipse — full size, true place. Beyond it the icon
 * shrinks and is pulled in toward the centre, so the rim is a ring of
 * small icons hugging the big ones instead of a gap and a cut-off row.
 */
export function fisheye(x, y, w, h, o = {}) {
  const edge = o.edge ?? 20;
  const inner = o.inner ?? 0.46;
  const falloff = o.falloff ?? 0.85;
  const pull = o.pull ?? 0.8;
  const minScale = o.minScale ?? 0.3;
  const u = x / Math.max(1, w / 2 - edge);
  const v = y / Math.max(1, h / 2 - edge);
  const r = Math.hypot(u, v);
  if (r <= inner) return { x, y, scale: 1, opacity: 1 };
  const over = r - inner;
  const scale = Math.max(minScale, 1 - over * falloff);
  const f = (inner + over * pull) / r;
  const opacity = r > 1.45 ? Math.max(0, 1 - (r - 1.45) * 2.5) : 1;
  return { x: x * f, y: y * f, scale, opacity };
}

/** The list's lens: rows shrink and fade toward the top and bottom edge. */
export function listLens(y, h, o = {}) {
  const v = Math.abs(y) / Math.max(1, h / 2 - (o.edge ?? 18));
  const inner = o.inner ?? 0.6;
  if (v <= inner) return { x: 0, y, scale: 1, opacity: 1 };
  const over = v - inner;
  const scale = Math.max(0.72, 1 - over * 0.55);
  return { x: 0, y, scale, opacity: Math.max(0, 1 - over * 0.9) };
}

/** Past a bound the content follows the finger at half speed. */
export function rubber(v, min, max, give = 0.45) {
  if (v < min) return min - (min - v) * give;
  if (v > max) return max + (v - max) * give;
  return v;
}

/** Index of the item nearest to point p. */
export function nearest(points, p) {
  let best = -1;
  let bestD = Infinity;
  points.forEach((pt, i) => {
    const d = (pt.x - p.x) ** 2 + (pt.y - p.y) ** 2;
    if (d < bestD) { bestD = d; best = i; }
  });
  return best;
}

/* ------------------------------------------------------------------ DOM */

const VIEW_KEY = "botScreenDrawerView";
const TAP_SLOP = 7;           // stage px a tap may wander
const CLOSE_PULL = 30;        // stage px past the top edge that closes
const FRICTION = 0.94;        // velocity kept per 16 ms of momentum

function readView() {
  try {
    const v = localStorage.getItem(VIEW_KEY);
    return VIEWS.includes(v) ? v : "honeycomb";
  } catch (e) {
    return "honeycomb";
  }
}

function writeView(v) {
  try { localStorage.setItem(VIEW_KEY, v); } catch (e) { /* private mode */ }
}

/**
 * @param {HTMLElement} root  empty container that fills the drawer layer
 * @param {object} deps {t, iconEl(app) → element, icon(name) → element,
 *                       onLaunch(id), onClose(), onActivity()}
 */
export class WatchDrawer {
  constructor(root, deps) {
    this.root = root;
    this.deps = deps;
    this.view = readView();
    this.apps = [];
    this.points = [];
    this.pan = { x: 0, y: 0 };
    this.bounds = { minX: 0, maxX: 0, minY: 0, maxY: 0 };
    this.anim = 0;
    this.drag = null;

    root.classList.add("watch-drawer");
    root.innerHTML = "";
    this.field = el("div", "wd-field");
    this.name = el("div", "wd-name");
    this.viewBtn = el("button", "wd-btn wd-view");
    this.closeBtn = el("button", "wd-btn wd-close");
    this.viewBtn.type = this.closeBtn.type = "button";
    this.closeBtn.appendChild(deps.icon("close"));
    root.append(this.field, this.name, this.viewBtn, this.closeBtn);

    root.addEventListener("pointerdown", (e) => this._down(e));
    root.addEventListener("pointermove", (e) => this._move(e));
    root.addEventListener("pointerup", (e) => this._up(e));
    root.addEventListener("pointercancel", (e) => this._cancel(e));
    root.addEventListener("wheel", (e) => this._wheel(e), { passive: false });
    // Keyboard (desktop checks, accessibility): Enter/Space on a focused
    // icon is a click with detail 0; real taps are handled in _up.
    root.addEventListener("click", (e) => {
      e.stopPropagation();
      if (e.detail !== 0) return;
      const btn = e.target.closest("button");
      if (btn) this._activate(btn);
    });
    for (const type of ["touchstart", "touchmove"]) {
      root.addEventListener(type, (e) => e.stopPropagation(), { passive: true });
    }
    this._labelButtons();
  }

  /**
   * @param {object[]} apps  [{id, label, icon, pkg, on}] in drawer order
   */
  setApps(apps) {
    this.apps = apps.slice();
    this.field.innerHTML = "";
    this.buttons = this.apps.map((app) => {
      const btn = el("button", "wd-app");
      btn.type = "button";
      btn.dataset.id = app.id;
      btn.setAttribute("aria-label", app.label);
      btn.classList.toggle("on", !!app.on);
      const disc = el("span", "wd-disc");
      disc.appendChild(this.deps.iconEl(app));
      const label = el("span", "wd-label");
      label.textContent = app.label;
      btn.append(disc, label);
      this.field.appendChild(btn);
      return btn;
    });
    this._layout();
  }

  /** The drawer was just opened: centre on where the person is now. */
  show() {
    this._stop();
    const on = this.apps.findIndex((a) => a.on);
    const at = this.points[on >= 0 ? on : 0] || { x: 0, y: 0 };
    this.pan = this._startPan(at);
    this._paint();
    this.root.classList.remove("wd-enter");
    void this.root.offsetWidth;          // restart the zoom-in animation
    this.root.classList.add("wd-enter");
  }

  setView(view) {
    if (!VIEWS.includes(view) || view === this.view) return;
    const current = this._centredIndex();
    this.view = view;
    writeView(view);
    this._labelButtons();
    this._layout();
    this.pan = this._startPan(this.points[current] || { x: 0, y: 0 });
    this._paint();
  }

  _startPan(at) {
    if (this.view === "list") return { x: 0, y: this._clampY(at.y) };
    return {
      x: Math.min(this.bounds.maxX, Math.max(this.bounds.minX, at.x)),
      y: Math.min(this.bounds.maxY, Math.max(this.bounds.minY, at.y)),
    };
  }

  _labelButtons() {
    const t = this.deps.t;
    const next = this.view === "list" ? "honeycomb" : "list";
    this.viewBtn.innerHTML = "";
    this.viewBtn.appendChild(this.deps.icon(next === "list" ? "list" : "hex"));
    this.viewBtn.setAttribute("aria-label", t("apps.view." + next));
    this.closeBtn.setAttribute("aria-label", t("apps.close"));
    this.root.dataset.view = this.view;
  }

  _size() {
    return { w: this.root.clientWidth || 320, h: this.root.clientHeight || 240 };
  }

  _layout() {
    const n = this.apps.length;
    const { w, h } = this._size();
    this.points = this.view === "list"
      ? this.apps.map((_, i) => ({ x: 0, y: i * ROW }))
      : honeycomb(n, PITCH, Math.max(1, w / h));
    const xs = this.points.map((p) => p.x);
    const ys = this.points.map((p) => p.y);
    // The honeycomb pans only until its rim reaches the fisheye's edge
    // ring: an edge icon may not be dragged to the middle, or half the
    // screen would be empty — on a watch the rim icons just grow as you
    // come near, and that is enough to hit them.
    const inX = this.view === "list" ? 0 : Math.max(0, w / 2 - PITCH * 1.3);
    const inY = this.view === "list" ? 0 : Math.max(0, h / 2 - PITCH * 1.1);
    this.bounds = {
      minX: Math.min(0, Math.min(...xs) + inX), maxX: Math.max(0, Math.max(...xs) - inX),
      minY: Math.min(0, Math.min(...ys) + inY), maxY: Math.max(0, Math.max(...ys) - inY),
    };
  }

  /* In the list the first row sits at the top, not in the middle: nobody
     wants a half-empty screen above "Обличчя". */
  _clampY(y) {
    const { h } = this._size();
    const top = this.view === "list" ? this.bounds.minY + h / 2 - ROW / 2 - 32 : this.bounds.minY;
    const bottom = this.view === "list" ? Math.max(top, this.bounds.maxY - h / 2 + ROW / 2 + 10) : this.bounds.maxY;
    return Math.min(bottom, Math.max(top, y));
  }

  _yBounds() {
    const lo = this._clampY(-Infinity);
    const hi = this._clampY(Infinity);
    return [lo, hi];
  }

  _paint() {
    const { w, h } = this._size();
    const list = this.view === "list";
    this.points.forEach((p, i) => {
      const rx = p.x - this.pan.x;
      const ry = p.y - this.pan.y;
      const f = list ? listLens(ry, h) : fisheye(rx, ry, w, h);
      const btn = this.buttons[i];
      btn.style.transform = `translate3d(${round(f.x)}px, ${round(f.y)}px, 0) scale(${round(f.scale)})`;
      btn.style.opacity = f.opacity;
      btn.style.visibility = f.opacity <= 0.02 ? "hidden" : "";
      // Nearer the middle draws on top of the ring of shrunk icons
      btn.style.zIndex = String(Math.round(f.scale * 100));
    });
    const idx = this._centredIndex();
    const app = this.apps[idx];
    this.name.textContent = app && !list ? app.label : "";
    this.name.classList.toggle("hidden", !app || list);
  }

  _centredIndex() {
    return nearest(this.points, this.pan);
  }

  /* ---------------------------------------------------------- gestures */

  _scale() {
    const rect = this.root.getBoundingClientRect();
    return rect.width / (this.root.clientWidth || rect.width || 1) || 1;
  }

  _down(e) {
    // Mine, all of it: the stage must not start a swipe or a long press
    e.stopPropagation();
    if (this.drag) return;
    this._stop();
    this.deps.onActivity?.();
    const btn = e.target.closest("button");
    this.drag = {
      id: e.pointerId, x0: e.clientX, y0: e.clientY, k: this._scale(),
      pan0: { ...this.pan }, moved: false, btn, samples: [{ t: performance.now(), x: e.clientX, y: e.clientY }],
    };
    if (btn) btn.classList.add("pressed");
    try { this.root.setPointerCapture(e.pointerId); } catch (err) { /* not capturable */ }
  }

  _move(e) {
    const d = this.drag;
    if (!d || d.id !== e.pointerId) return;
    e.stopPropagation();
    const dx = (e.clientX - d.x0) / d.k;
    const dy = (e.clientY - d.y0) / d.k;
    if (!d.moved && Math.hypot(dx, dy) < TAP_SLOP) return;
    if (!d.moved) {
      d.moved = true;
      if (d.btn) d.btn.classList.remove("pressed");
    }
    const [lo, hi] = this._yBounds();
    const list = this.view === "list";
    this.pan = {
      x: list ? 0 : rubber(d.pan0.x - dx, this.bounds.minX, this.bounds.maxX),
      y: rubber(d.pan0.y - dy, lo, hi),
    };
    d.samples.push({ t: performance.now(), x: e.clientX, y: e.clientY });
    if (d.samples.length > 6) d.samples.shift();
    this._paint();
  }

  _up(e) {
    const d = this.drag;
    if (!d || d.id !== e.pointerId) return;
    e.stopPropagation();
    this.drag = null;
    this.deps.onActivity?.();
    if (d.btn) d.btn.classList.remove("pressed");
    if (!d.moved) {
      if (d.btn) this._activate(d.btn);
      return;
    }
    const [lo] = this._yBounds();
    if (this.pan.y < lo - CLOSE_PULL) {
      this.deps.onClose?.();
      return;
    }
    // Momentum from the last few moves
    const first = d.samples[0];
    const last = d.samples[d.samples.length - 1];
    const dt = Math.max(1, last.t - first.t);
    const recent = performance.now() - last.t < 80;
    const vx = recent ? -(last.x - first.x) / d.k / dt : 0;
    const vy = recent ? -(last.y - first.y) / d.k / dt : 0;
    this._fling(this.view === "list" ? 0 : vx, vy);
  }

  _cancel(e) {
    const d = this.drag;
    if (!d || d.id !== e.pointerId) return;
    this.drag = null;
    if (d.btn) d.btn.classList.remove("pressed");
    this._snap();
  }

  _wheel(e) {
    e.preventDefault();
    e.stopPropagation();
    this._stop();
    this.deps.onActivity?.();
    const [lo, hi] = this._yBounds();
    const list = this.view === "list";
    this.pan = {
      x: list ? 0 : Math.min(this.bounds.maxX, Math.max(this.bounds.minX, this.pan.x + e.deltaX * 0.5)),
      y: Math.min(hi, Math.max(lo, this.pan.y + e.deltaY * 0.5)),
    };
    this._paint();
    clearTimeout(this.wheelTimer);
    this.wheelTimer = setTimeout(() => this._snap(), 160);
  }

  _activate(btn) {
    if (btn === this.closeBtn) { this.deps.onClose?.(); return; }
    if (btn === this.viewBtn) { this.setView(this.view === "list" ? "honeycomb" : "list"); return; }
    const id = btn.dataset.id;
    if (id) this.deps.onLaunch?.(id);
  }

  /* --------------------------------------------------------- animation */

  _stop() {
    cancelAnimationFrame(this.anim);
    this.anim = 0;
    clearTimeout(this.wheelTimer);
  }

  _fling(vx, vy) {
    this._stop();
    let last = performance.now();
    const step = (now) => {
      const dt = Math.min(48, now - last);
      last = now;
      const [lo, hi] = this._yBounds();
      const outX = this.pan.x < this.bounds.minX || this.pan.x > this.bounds.maxX;
      const outY = this.pan.y < lo || this.pan.y > hi;
      // Out of bounds the momentum dies fast; the snap pulls it back
      const keep = Math.pow(FRICTION, dt / 16) * (outX || outY ? 0.6 : 1);
      vx *= keep;
      vy *= keep;
      this.pan = { x: this.pan.x + vx * dt, y: this.pan.y + vy * dt };
      this._paint();
      if (Math.hypot(vx, vy) < 0.05) { this.anim = 0; this._snap(); return; }
      this.anim = requestAnimationFrame(step);
    };
    this.anim = requestAnimationFrame(step);
  }

  /* Settle on the nearest icon (honeycomb) or row (list), inside bounds. */
  _snap() {
    this._stop();
    const [lo, hi] = this._yBounds();
    let target;
    if (this.view === "list") {
      target = { x: 0, y: Math.min(hi, Math.max(lo, Math.round(this.pan.y / ROW) * ROW)) };
    } else {
      const clamped = {
        x: Math.min(this.bounds.maxX, Math.max(this.bounds.minX, this.pan.x)),
        y: Math.min(this.bounds.maxY, Math.max(this.bounds.minY, this.pan.y)),
      };
      const at = this.points[nearest(this.points, clamped)] || clamped;
      target = {
        x: Math.min(this.bounds.maxX, Math.max(this.bounds.minX, at.x)),
        y: Math.min(this.bounds.maxY, Math.max(this.bounds.minY, at.y)),
      };
    }
    const step = () => {
      const dx = target.x - this.pan.x;
      const dy = target.y - this.pan.y;
      if (Math.hypot(dx, dy) < 0.4) {
        this.pan = { ...target };
        this._paint();
        this.anim = 0;
        return;
      }
      this.pan = { x: this.pan.x + dx * 0.22, y: this.pan.y + dy * 0.22 };
      this._paint();
      this.anim = requestAnimationFrame(step);
    };
    this.anim = requestAnimationFrame(step);
  }
}

function el(tag, cls) {
  const node = document.createElement(tag);
  node.className = cls;
  return node;
}
