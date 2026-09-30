/* ============================================================
   App icons for the watch-style drawer

   Drawn from scratch for the honeycomb: a full coloured disc with one
   bold white glyph, the way a watch face shows its apps. The older sets
   (pixel-ui.js, icons.js) were outline glyphs meant to sit inside a
   neutral ring; blown up to a 48 px bubble they read as thin scribbles,
   and two dozen grey rings next to each other all looked alike. Here the
   colour carries recognition and the glyph only has to be legible at a
   glance, even shrunk to a third at the edge of the fisheye.

   Rules every glyph follows:
     - viewBox 48×48, the disc fills it; the glyph stays inside r ≈ 15
       around the centre, so it survives the fisheye shrink;
     - white (W) for the shape, the disc's own dark tone (D) for cut-outs,
       at most one accent colour for the detail that makes it that app;
     - no text except where the text IS the app (2048), no emoji.

   Pure strings, no DOM, so node can render and check every icon
   (tests/test_screen_js.py); appIconEl() is the only DOM helper.
   ============================================================ */

const W = "#fff";

/* A round-joined stroke, the common case in these glyphs. */
function line(d, color, width) {
  return `<path d="${d}" fill="none" stroke="${color}" stroke-width="${width}" stroke-linecap="round" stroke-linejoin="round"/>`;
}

function fmt(n) {
  return String(Math.round(n * 100) / 100);
}

/* Gear outline: teeth are trapezoids between the root and tip radius. */
function gearPath(cx, cy, teeth, tip, root) {
  const step = (Math.PI * 2) / teeth;
  const pt = (r, a) => `${fmt(cx + r * Math.cos(a))} ${fmt(cy + r * Math.sin(a))}`;
  let d = "";
  for (let i = 0; i < teeth; i++) {
    const a = i * step - Math.PI / 2;
    d += (i ? "L" : "M") + pt(root, a - step * 0.32);
    d += "L" + pt(tip, a - step * 0.17);
    d += "L" + pt(tip, a + step * 0.17);
    d += "L" + pt(root, a + step * 0.32);
  }
  return d + "Z";
}

/* Rays around a point, for the sun glyphs. */
function rays(cx, cy, count, from, to, color, width, skip = () => false) {
  let d = "";
  for (let i = 0; i < count; i++) {
    const a = (i * Math.PI * 2) / count - Math.PI / 2;
    if (skip(a)) continue;
    d += `M${fmt(cx + from * Math.cos(a))} ${fmt(cy + from * Math.sin(a))}` +
      `L${fmt(cx + to * Math.cos(a))} ${fmt(cy + to * Math.sin(a))}`;
  }
  return line(d, color, width);
}

/* A crescent: disc (cx, cy, r) with a bite of disc (bx, by, br) taken out. */
function crescent(cx, cy, r, bx, by, br) {
  const dx = bx - cx;
  const dy = by - cy;
  const d = Math.hypot(dx, dy);
  const a = (d * d + r * r - br * br) / (2 * d);
  const h = Math.sqrt(Math.max(0, r * r - a * a));
  const mx = cx + (a * dx) / d;
  const my = cy + (a * dy) / d;
  const p1 = [mx + (h * dy) / d, my - (h * dx) / d];
  const p2 = [mx - (h * dy) / d, my + (h * dx) / d];
  return `M${fmt(p1[0])} ${fmt(p1[1])}A${r} ${r} 0 1 0 ${fmt(p2[0])} ${fmt(p2[1])}` +
    `A${br} ${br} 0 0 1 ${fmt(p1[0])} ${fmt(p1[1])}Z`;
}

/* A pixel picture from rows of "X" — for the pixel-paint app, drawn as
   pixels on purpose. */
function pixels(rows, x0, y0, cell, color, accent = {}) {
  let out = "";
  rows.forEach((row, y) => {
    Array.from(row).forEach((ch, x) => {
      if (ch === " ") return;
      const fill = accent[ch] || color;
      out += `<rect x="${fmt(x0 + x * cell)}" y="${fmt(y0 + y * cell)}" width="${fmt(cell - 0.5)}" height="${fmt(cell - 0.5)}" rx="0.6" fill="${fill}"/>`;
    });
  });
  return out;
}

/* ------------------------------------------------------------ designs
   Each design: bg — the disc gradient, top to bottom; d — the cut-out
   colour (defaults to the bottom of the gradient); glyph(D) — the SVG. */

export const DESIGNS = {
  checklist: {
    bg: ["#8793d8", "#616bb0"],
    glyph: (D) =>
      `<rect x="13" y="12" width="22" height="26" rx="4" fill="${W}"/>` +
      `<rect x="19" y="9" width="10" height="6" rx="2" fill="${D}"/>` +
      line("M17 21l2 2 3-4M17 30l2 2 3-4M26 21h5M26 30h5", D, 2.4),
  },
  convert: {
    bg: ["#62b7c5", "#3a8997"],
    glyph: () => line("M12 17h23l-5-5M35 17l-5 5M36 31H13l5-5M13 31l5 5", W, 3.4),
  },
  face: {
    bg: ["#5eead4", "#0f9f94"],
    glyph: (D) =>
      `<rect x="12" y="17.5" width="24" height="19" rx="7" fill="${W}"/>` +
      line("M24 17.5v-4", W, 3) +
      `<circle cx="24" cy="11.5" r="2.6" fill="${W}"/>` +
      `<circle cx="19" cy="26" r="2.6" fill="${D}"/><circle cx="29" cy="26" r="2.6" fill="${D}"/>` +
      line("M20.8 31.5h6.4", D, 2.4),
  },
  clock: {
    bg: ["#3a3a3c", "#141416"],
    glyph: (D) =>
      `<circle cx="24" cy="24" r="14" fill="${W}"/>` +
      line("M24 12.8v2.4M24 32.8v2.4M12.8 24h2.4M32.8 24h2.4", D, 2) +
      line("M24 24v-7.5", D, 3) +
      line("M24 24l5.6 3.4", "#ff9f0a", 3) +
      `<circle cx="24" cy="24" r="2" fill="${D}"/>`,
  },
  mic: {
    bg: ["#bf5af2", "#8638b8"],
    glyph: () =>
      `<rect x="18.5" y="9" width="11" height="19" rx="5.5" fill="${W}"/>` +
      line("M13.5 23.5a10.5 10.5 0 0 0 21 0", W, 3) +
      line("M24 34v4.5M19 38.5h10", W, 3),
  },
  hourglass: {
    bg: ["#ff9f0a", "#e06c00"],
    glyph: (D) =>
      line("M15 10.5h18M15 37.5h18", W, 3) +
      `<path d="M17 11.5c0 6 3.5 9 7 12.5-3.5 3.5-7 6.5-7 12.5h14c0-6-3.5-9-7-12.5 3.5-3.5 7-6.5 7-12.5z" fill="${W}"/>` +
      `<path d="M20.2 15.5h7.6c-.8 2-2 3.3-3.8 4.8-1.8-1.5-3-2.8-3.8-4.8z" fill="${D}"/>` +
      `<path d="M20 34.2c.8-2.8 2.2-4.4 4-5.8 1.8 1.4 3.2 3 4 5.8z" fill="${D}"/>`,
  },
  weather: {
    bg: ["#4aa8ff", "#1c5fd6"],
    glyph: () =>
      rays(19.5, 19.5, 8, 9.6, 12.4, "#ffd60a", 2.6, (a) => a > 0 && a < Math.PI * 0.75) +
      `<circle cx="19.5" cy="19.5" r="6.8" fill="#ffd60a"/>` +
      `<path d="M17.5 37h16.5a6.3 6.3 0 0 0 .7-12.6 8.4 8.4 0 0 0-16.2 2.1A5.3 5.3 0 0 0 17.5 37z" fill="${W}"/>`,
  },
  sun: {
    bg: ["#ffb340", "#ff7a00"],
    glyph: () =>
      rays(24, 24, 8, 10.5, 14, W, 3) +
      `<circle cx="24" cy="24" r="7" fill="${W}"/>`,
  },
  bubble: {
    bg: ["#34c759", "#1c9a43"],
    glyph: (D) =>
      `<path d="M11.5 16A5.5 5.5 0 0 1 17 10.5h14A5.5 5.5 0 0 1 36.5 16v9A5.5 5.5 0 0 1 31 30.5h-8.5l-7.5 6.5v-6.6A5.5 5.5 0 0 1 11.5 25z" fill="${W}"/>` +
      `<circle cx="18.2" cy="20.5" r="2.1" fill="${D}"/><circle cx="24" cy="20.5" r="2.1" fill="${D}"/><circle cx="29.8" cy="20.5" r="2.1" fill="${D}"/>`,
  },
  gauge: {
    bg: ["#ff6482", "#d8315b"],
    glyph: () =>
      line("M11 30a13 13 0 0 1 26 0", W, 4) +
      line("M24 30l6.5-8.5", "#ffd60a", 3.2) +
      `<circle cx="24" cy="30" r="3.2" fill="${W}"/>`,
  },
  sliders: {
    bg: ["#8e9aaf", "#586178"],
    glyph: (D) =>
      `<rect x="11" y="11.5" width="26" height="10.5" rx="5.25" fill="${W}"/>` +
      `<circle cx="31.4" cy="16.75" r="3.3" fill="${D}"/>` +
      `<rect x="12.2" y="26.7" width="23.6" height="9.6" rx="4.8" fill="none" stroke="${W}" stroke-width="2.4"/>` +
      `<circle cx="18" cy="31.5" r="2.9" fill="${W}"/>`,
  },
  camera: {
    bg: ["#6e6e73", "#3a3a3c"],
    glyph: (D) =>
      `<path d="M19 14.5l1.6-2.5h6.8l1.6 2.5H34a3.5 3.5 0 0 1 3.5 3.5v13.5a3.5 3.5 0 0 1-3.5 3.5H14a3.5 3.5 0 0 1-3.5-3.5V18a3.5 3.5 0 0 1 3.5-3.5z" fill="${W}"/>` +
      `<circle cx="24" cy="24.8" r="6.6" fill="${D}"/>` +
      `<circle cx="24" cy="24.8" r="3.2" fill="#64d2ff"/>` +
      `<circle cx="32.6" cy="18.6" r="1.4" fill="${D}"/>`,
  },
  server: {
    bg: ["#64d2ff", "#1a8fd6"],
    glyph: (D) =>
      `<rect x="12" y="11" width="24" height="11" rx="3" fill="${W}"/>` +
      `<rect x="12" y="26" width="24" height="11" rx="3" fill="${W}"/>` +
      `<circle cx="17" cy="16.5" r="1.9" fill="#30d158"/><circle cx="17" cy="31.5" r="1.9" fill="#30d158"/>` +
      line("M23 16.5h8M23 31.5h8", D, 2.2),
  },
  monitor: {
    bg: ["#48484a", "#1c1c1e"],
    glyph: (D) =>
      `<rect x="10" y="11" width="28" height="19.5" rx="3" fill="${W}"/>` +
      `<rect x="13" y="14" width="22" height="13.5" rx="1.3" fill="${D}"/>` +
      line("M17 24v-3M21 24v-6M25 24v-2M29 24v-4.5", "#64d2ff", 2) +
      line("M19.5 37h9M24 30.5V37", W, 3),
  },
  gear: {
    bg: ["#aeaeb2", "#6e6e73"],
    glyph: (D) =>
      `<path d="${gearPath(24, 24, 9, 14.2, 10.6)}" fill="${W}"/>` +
      `<circle cx="24" cy="24" r="4.6" fill="${D}"/>`,
  },
  notebook: {
    bg: ["#ffd60a", "#f2b300"],
    d: "#946800",
    glyph: (D) =>
      `<path d="M14.5 12.5A2.5 2.5 0 0 1 17 10h16.5v28H17a2.5 2.5 0 0 1-2.5-2.5z" fill="${W}"/>` +
      line("M14.5 35.2a2.5 2.5 0 0 1 2.5-2.7h16.5", D, 1.6) +
      line("M19.5 17h6.5M19.5 22h9M19.5 27h7", D, 2.2) +
      `<path d="M28 10v8.6l2.3-1.7 2.3 1.7V10z" fill="#ff453a"/>`,
  },
  history: {
    bg: ["#30b0c7", "#157a8c"],
    glyph: () =>
      line("M12 24A12 12 0 1 1 15.5 32.5", W, 3.2) +
      line("M8.4 20.8L12 24.6l3.8-3.4", W, 3.2) +
      line("M24 17v7.5l5 3", W, 3),
  },
  bag: {
    bg: ["#0a84ff", "#0058d6"],
    glyph: (D) =>
      line("M19 21v-5a5 5 0 0 1 10 0v5", W, 3) +
      `<path d="M14.5 19h19l-1.5 16.2a3 3 0 0 1-3 2.8H19a3 3 0 0 1-3-2.8z" fill="${W}"/>` +
      `<circle cx="19" cy="23.5" r="1.7" fill="${D}"/><circle cx="29" cy="23.5" r="1.7" fill="${D}"/>`,
  },
  petals: {
    bg: ["#2f7d78", "#123f3c"],
    glyph: () => {
      let out = "";
      for (let i = 0; i < 6; i++) {
        const a = (i * Math.PI) / 3 - Math.PI / 2;
        out += `<circle cx="${fmt(24 + 6.8 * Math.cos(a))}" cy="${fmt(24 + 6.8 * Math.sin(a))}" r="7" fill="#b8fff0" fill-opacity="0.55"/>`;
      }
      return out;
    },
  },
  calc: {
    bg: ["#3a3a3c", "#141416"],
    glyph: () =>
      line("M13 17h8M17 13v8M27 17h8", W, 3) +
      line("M14.2 28.2l5.6 5.6M19.8 28.2l-5.6 5.6M27 28.8h8M27 33.4h8", "#ff9f0a", 3),
  },
  chip: {
    bg: ["#1f8f6a", "#0e5641"],
    glyph: (D) =>
      line("M19 10.5v4M24 10.5v4M29 10.5v4M19 33.5v4M24 33.5v4M29 33.5v4" +
        "M10.5 19h4M10.5 24h4M10.5 29h4M33.5 19h4M33.5 24h4M33.5 29h4", W, 2.4) +
      `<rect x="15" y="15" width="18" height="18" rx="3.2" fill="${W}"/>` +
      `<rect x="19.5" y="19.5" width="9" height="9" rx="1.6" fill="${D}"/>`,
  },
  dice: {
    bg: ["#ff375f", "#c2143f"],
    glyph: (D) =>
      `<g transform="rotate(-12 24 24)"><rect x="12.5" y="12.5" width="23" height="23" rx="5.5" fill="${W}"/>` +
      [[18.2, 18.2], [29.8, 18.2], [24, 24], [18.2, 29.8], [29.8, 29.8]]
        .map(([x, y]) => `<circle cx="${x}" cy="${y}" r="2.3" fill="${D}"/>`).join("") +
      "</g>",
  },
  crab: {
    bg: ["#8bd8ff", "#3a9be8"],
    glyph: () => {
      const C = "#ff5b3a";
      return line("M15 31l-3.6 3M16.8 33.6l-2.4 3.6M33 31l3.6 3M31.2 33.6l2.4 3.6", C, 2.2) +
        line("M16.5 25l-3.2-5.2M31.5 25l3.2-5.2M21 22v-3.6M27 22v-3.6", C, 2.4) +
        `<circle cx="12.6" cy="17.4" r="3.7" fill="${C}"/><circle cx="35.4" cy="17.4" r="3.7" fill="${C}"/>` +
        `<ellipse cx="24" cy="28.5" rx="10.5" ry="7" fill="${C}"/>` +
        `<circle cx="21" cy="17" r="2.5" fill="${W}"/><circle cx="27" cy="17" r="2.5" fill="${W}"/>` +
        `<circle cx="21.5" cy="17.3" r="1.15" fill="#1c1c1e"/><circle cx="27.5" cy="17.3" r="1.15" fill="#1c1c1e"/>`;
    },
  },
  tile2048: {
    bg: ["#edc22e", "#d19c0c"],
    d: "#776e65",
    glyph: (D) =>
      `<rect x="10.5" y="15" width="27" height="18" rx="4" fill="${W}"/>` +
      `<text x="24" y="27.6" text-anchor="middle" font-family="system-ui,-apple-system,'Segoe UI',Roboto,sans-serif" font-weight="800" font-size="9.6" letter-spacing="-0.3" fill="${D}">2048</text>`,
  },
  metronome: {
    bg: ["#b08a5a", "#7a5a33"],
    glyph: (D) =>
      `<path d="M19.4 11h9.2L35 37H13z" fill="${W}"/>` +
      line("M16.8 32h14.4", D, 2) +
      line("M24 32L31.5 13.5", "#ffd60a", 2.4) +
      `<circle cx="28.9" cy="19.9" r="2.5" fill="#ffd60a"/>`,
  },
  pixelheart: {
    bg: ["#ff6ac1", "#d63a9a"],
    glyph: () =>
      pixels([" XX XX ", "XoXXXXX", "XXXXXXX", " XXXXX ", "  XXX  ", "   X   "],
        11.4, 13.4, 3.6, W, { o: "#ffd60a" }),
  },
  tomato: {
    bg: ["#ff5e4d", "#d6281b"],
    glyph: (D) =>
      `<circle cx="24" cy="26.5" r="11.5" fill="${W}"/>` +
      line("M24 26.5v-5M24 26.5l3.8 2.2", D, 2.4) +
      line("M24 16v-4", "#34c759", 2.4) +
      `<path d="M24 14.5l-6 -1.5 3.4 3.4-4.4 1.8 5.4.4 1.6 2.6 1.6-2.6 5.4-.4-4.4-1.8 3.4-3.4z" fill="#34c759"/>`,
  },
  bolt: {
    bg: ["#ffd60a", "#ff8a00"],
    glyph: () => `<path d="M27.5 9L14 27.5h9.2L21 39l13-18.5h-9.2z" fill="${W}"/>`,
  },
  snake: {
    bg: ["#4cd964", "#1f8f3c"],
    glyph: (D) =>
      line("M33 16.5H20a4.5 4.5 0 0 0 0 9h8a4.5 4.5 0 0 1 0 9H13.5", W, 5) +
      `<circle cx="34" cy="16.5" r="3.8" fill="${W}"/><circle cx="35.2" cy="15.4" r="1.2" fill="${D}"/>` +
      `<circle cx="14.5" cy="16" r="2.9" fill="#ff453a"/>`,
  },
  stopwatch: {
    bg: ["#ff7a45", "#e0481b"],
    glyph: () =>
      `<circle cx="24" cy="26.5" r="11.5" fill="none" stroke="${W}" stroke-width="3.2"/>` +
      line("M20.5 10.5h7M24 10.5v4.5M33.6 15.6l2.2-2.2", W, 3) +
      line("M24 26.5v-7", "#ffd60a", 3) +
      `<circle cx="24" cy="26.5" r="2.1" fill="${W}"/>`,
  },
  tictactoe: {
    bg: ["#5e5ce6", "#3634a3"],
    glyph: () =>
      line("M20 12v24M28 12v24M12 20h24M12 28h24", W, 2.4) +
      line("M21.8 21.8l4.4 4.4M26.2 21.8l-4.4 4.4M13.8 29.8l4.4 4.4M18.2 29.8l-4.4 4.4", "#ff9f0a", 2.6) +
      `<circle cx="32" cy="16" r="2.5" fill="none" stroke="#64d2ff" stroke-width="2.4"/>`,
  },
  checkers: {
    bg: ["#c44b37", "#6b1f14"],
    glyph: (D) =>
      `<circle cx="24" cy="24" r="14" fill="${W}"/>` +
      `<circle cx="24" cy="24" r="10.2" fill="none" stroke="${D}" stroke-width="2.2"/>` +
      `<circle cx="24" cy="24" r="6.6" fill="none" stroke="${D}" stroke-width="2.2"/>` +
      `<path d="M19 25h10l-1-5.5-2.5 2.5-1.5-3.5-1.5 3.5-2.5-2.5z" fill="#ffd60a"/>`,
  },
  globe: {
    bg: ["#32ade6", "#1668b8"],
    glyph: () =>
      `<circle cx="24" cy="24" r="13" fill="none" stroke="${W}" stroke-width="2.8"/>` +
      `<ellipse cx="24" cy="24" rx="5.5" ry="13" fill="none" stroke="${W}" stroke-width="2.4"/>` +
      line("M11 24h26M13.4 17.5h21.2M13.4 30.5h21.2", W, 2.2),
  },
  play: {
    bg: ["#ff3b30", "#c8102e"],
    glyph: (D) =>
      `<rect x="10" y="14" width="28" height="20" rx="6.5" fill="${W}"/>` +
      `<path d="M21 19.2v9.6l8.3-4.8z" fill="${D}"/>`,
  },
  note: {
    bg: ["#ff2d55", "#b3003c"],
    glyph: () =>
      `<circle cx="24" cy="24" r="13" fill="none" stroke="${W}" stroke-width="2.6"/>` +
      line("M21.8 29.8V18.6l7.4-1.8v9.6", W, 2.6) +
      `<circle cx="19.6" cy="29.8" r="2.6" fill="${W}"/><circle cx="27" cy="26.4" r="2.6" fill="${W}"/>`,
  },
  headphones: {
    bg: ["#ff2d55", "#b3003c"],
    glyph: () =>
      line("M12.5 29v-4a11.5 11.5 0 0 1 23 0v4", W, 3) +
      `<rect x="10.5" y="26" width="7" height="11" rx="3" fill="${W}"/>` +
      `<rect x="30.5" y="26" width="7" height="11" rx="3" fill="${W}"/>`,
  },
  moon: {
    bg: ["#2c2c2e", "#000000"],
    glyph: () =>
      `<path d="${crescent(23, 25, 11.5, 29.5, 19.5, 9.5)}" fill="${W}"/>` +
      `<circle cx="32" cy="13" r="1.4" fill="${W}"/><circle cx="36" cy="21" r="1" fill="${W}"/>`,
  },
  sunset: {
    bg: ["#ff9f45", "#ff2d6f"],
    glyph: () =>
      rays(24, 29, 7, 13, 16, W, 2.6, (a) => a > -0.1 || a < -Math.PI + 0.1) +
      `<path d="M13.5 29a10.5 10.5 0 0 1 21 0z" fill="${W}"/>` +
      line("M10.5 32.5h27M15 37h18", W, 2.6),
  },
  terminal: {
    bg: ["#1c1c1e", "#000000"],
    glyph: () => line("M14 17.5l6.5 6.5-6.5 6.5M24 31h10", "#30d158", 3.2),
  },
  power: {
    bg: ["#48484a", "#1c1c1e"],
    glyph: () => line("M24 11v11M17 15.5a11 11 0 1 0 14 0", W, 3.4),
  },
  leaf: {
    bg: ["#34c759", "#1c7a3a"],
    glyph: (D) =>
      `<path d="M35 12.5C21.5 12 13 19 13.5 30.5c0 1.8.3 3.5.8 5 11.2.5 20.4-6.5 20.7-23z" fill="${W}"/>` +
      line("M15.5 34C20.5 27 25.5 22 31 17.5", D, 2.2),
  },
  pencil: {
    bg: ["#ff9f0a", "#e06c00"],
    glyph: (D) =>
      `<g transform="rotate(45 24 24)"><rect x="20" y="8" width="8" height="24" rx="1.6" fill="${W}"/>` +
      `<path d="M20 32h8l-4 7.5z" fill="${W}"/>` + line("M20 13h8", D, 2) +
      `<path d="M22.6 36.9h2.8L24 39.5z" fill="${D}"/></g>`,
  },
  target: {
    bg: ["#ff453a", "#b3261e"],
    glyph: () =>
      `<circle cx="24" cy="24" r="12.5" fill="none" stroke="${W}" stroke-width="3"/>` +
      `<circle cx="24" cy="24" r="6.8" fill="none" stroke="${W}" stroke-width="3"/>` +
      `<circle cx="24" cy="24" r="2.4" fill="${W}"/>`,
  },
  gamepad: {
    bg: ["#5e5ce6", "#3634a3"],
    glyph: (D) =>
      `<path d="M16 16.5h16a7 7 0 0 1 6.8 5.4l1.5 7.2a4.6 4.6 0 0 1-7.7 4.1L29.5 30h-11l-3.1 3.2a4.6 4.6 0 0 1-7.7-4.1l1.5-7.2A7 7 0 0 1 16 16.5z" fill="${W}"/>` +
      line("M16.5 21.2v5M14 23.7h5", D, 2.2) +
      `<circle cx="30" cy="22.2" r="1.7" fill="${D}"/><circle cx="33.2" cy="25.4" r="1.7" fill="${D}"/>`,
  },
  puzzle: {
    bg: ["#30d158", "#1a8a3c"],
    glyph: () =>
      `<path d="M13 16h6a3.5 3.5 0 1 1 7 0h6v6a3.5 3.5 0 1 1 0 7v6h-6a3.5 3.5 0 1 0-7 0h-6z" fill="${W}"/>`,
  },
  grid: {
    bg: ["#ff9f0a", "#e06c00"],
    glyph: () =>
      [[12, 12], [25, 12], [12, 25], [25, 25]]
        .map(([x, y]) => `<rect x="${x}" y="${y}" width="11" height="11" rx="3" fill="${W}"/>`).join(""),
  },
  letter: {
    bg: ["#8e8e93", "#48484a"],
    glyph: (D, label) =>
      `<text x="24" y="31" text-anchor="middle" font-family="system-ui,-apple-system,'Segoe UI',Roboto,sans-serif" font-weight="700" font-size="21" fill="${W}">${escapeXml(firstLetter(label))}</text>`,
  },
};

/* Which design an app gets. The app's own id wins, so an app keeps a
   picture of what it is (the crab game is a crab, not a smiley) even when
   its manifest only names a generic icon; the manifest's icon name comes
   next, and a letter disc is the last resort for a third-party package
   naming an icon nobody drew. */
const BY_ID = {
  // the screen's own screens and apps (screen.js SCREENS)
  face: "face", clock: "clock", chat: "mic", timer: "hourglass", weather: "weather",
  say: "bubble", state: "gauge", quick: "sliders", camera: "camera", services: "server",
  panel: "monitor", settings: "gear", memory: "notebook", chats: "history", store: "bag",
  "daily-checklist": "checklist",
  "unit-converter": "convert",
  notices: "bubble",
  // built-in store packages (store/packages/*)
  breathe: "petals", calculator: "calc", "device-settings": "chip", dice: "dice",
  "flappy-crab": "crab", "game-2048": "tile2048", metronome: "metronome",
  "pixel-paint": "pixelheart", pomodoro: "tomato", reaction: "bolt", snake: "snake",
  stopwatch: "stopwatch", "tic-tac-toe": "tictactoe", checkers: "checkers", "world-clock": "globe",
  youtube: "play", "yt-music": "note",
  "skin-amoled": "moon", "skin-sunset": "sunset", "skin-terminal": "terminal",
};

const BY_ICON = {
  face: "face", clock: "clock", mic: "mic", timer: "hourglass", sun: "sun",
  bubble: "bubble", gauge: "gauge", sliders: "sliders", camera: "camera", server: "server",
  monitor: "monitor", settings: "gear", gear: "gear", memory: "notebook", history: "history",
  store: "bag", music: "note", headphones: "headphones", youtube: "play", pencil: "pencil",
  leaf: "leaf", calc: "calc", dice: "dice", grid: "grid", target: "target", bolt: "bolt",
  gamepad: "gamepad", puzzle: "puzzle", globe: "globe", moon: "moon", power: "power",
};

/**
 * The design key for an app.
 * @param {object} app  {id, pkg, icon} — id "app:snake" and pkg "snake" both work
 */
export function appIconKey(app = {}) {
  const pkg = app.pkg || (typeof app.id === "string" && app.id.startsWith("app:") ? app.id.slice(4) : "");
  if (pkg && BY_ID[pkg]) return BY_ID[pkg];
  if (!pkg && app.id && BY_ID[app.id]) return BY_ID[app.id];
  if (app.icon && BY_ICON[app.icon]) return BY_ICON[app.icon];
  return "letter";
}

function escapeXml(s) {
  return String(s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&apos;" }[c]));
}

function firstLetter(label) {
  const ch = Array.from(String(label || "").trim())[0] || "?";
  return ch.toUpperCase();
}

/* ------------------------------------------------------------ themed
   The "Material You" interface style draws the same glyphs as Pixel's themed
   icons: one tonal disc in the screen's colour and the glyph in a darker
   (light theme) or lighter (dark theme) tone of the same colour. It is
   the same drawing re-inked, so a new design gets its themed look for
   free: white → the glyph tone, the disc's own dark cut-outs → the disc
   tone, any accent → a middle tone that still separates from both. */

function hexRgb(hex) {
  let h = String(hex || "").replace("#", "");
  if (h.length === 3) h = h.split("").map((c) => c + c).join("");
  const n = parseInt(h, 16);
  return Number.isFinite(n) && h.length === 6 ? [(n >> 16) & 255, (n >> 8) & 255, n & 255] : [128, 128, 128];
}

/** a → b by t (0…1), as #rrggbb. */
export function mixHex(a, b, t) {
  const x = hexRgb(a);
  const y = hexRgb(b);
  return "#" + x.map((v, i) => Math.round(v + (y[i] - v) * t).toString(16).padStart(2, "0")).join("");
}

function luminance(hex) {
  const [r, g, b] = hexRgb(hex);
  return (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255;
}

/** The three tones of a themed icon for a colour and a theme. */
export function themedColors(tint, theme = "dark") {
  if (theme === "light") {
    return { bg: mixHex(tint, "#ffffff", 0.8), fg: mixHex(tint, "#000000", 0.55), mid: mixHex(tint, "#ffffff", 0.2) };
  }
  return { bg: mixHex(tint, "#141518", 0.74), fg: mixHex(tint, "#ffffff", 0.66), mid: mixHex(tint, "#ffffff", 0.18) };
}

const CUT = "__cut__";

function reink(glyph, tones) {
  return glyph
    .split(CUT).join(tones.bg)
    .replace(/(fill|stroke)="(#[0-9a-fA-F]{3,6})"/g, (all, attr, color) => {
      const c = color.toLowerCase();
      if (c === "#fff" || c === "#ffffff") return `${attr}="${tones.fg}"`;
      if (luminance(c) < 0.16) return `${attr}="${tones.bg}"`;
      return `${attr}="${tones.mid}"`;
    });
}

let gradientSeq = 0;

/**
 * One icon as an SVG string.
 * @param {string} key    a DESIGNS key (from appIconKey)
 * @param {object} opts   {label} — for the letter fallback;
 *                        {themed: {bg, fg, mid}} — tonal ink (themedColors)
 */
export function appIconSvg(key, opts = {}) {
  const design = DESIGNS[key] || DESIGNS.letter;
  if (opts.themed) {
    const tones = opts.themed;
    return `<svg class="app-glyph themed" viewBox="0 0 48 48" aria-hidden="true" focusable="false" xmlns="http://www.w3.org/2000/svg">` +
      `<circle cx="24" cy="24" r="24" fill="${tones.bg}"/>` +
      reink(design.glyph(CUT, opts.label), tones) +
      `</svg>`;
  }
  const [top, bottom] = design.bg;
  const dark = design.d || bottom;
  // Every icon on the page needs its own gradient id: SVG ids are global,
  // and a shared one would paint all discs in the first icon's colours.
  const id = "aig" + (++gradientSeq);
  return `<svg class="app-glyph" viewBox="0 0 48 48" aria-hidden="true" focusable="false" xmlns="http://www.w3.org/2000/svg">` +
    `<defs><linearGradient id="${id}" x1="0" y1="0" x2="0" y2="1">` +
    `<stop offset="0" stop-color="${top}"/><stop offset="1" stop-color="${bottom}"/></linearGradient></defs>` +
    `<circle cx="24" cy="24" r="24" fill="url(#${id})"/>` +
    design.glyph(dark, opts.label) +
    `</svg>`;
}

/** The icon as an element, for the drawer and the store.
 *  @param {object} opts  {themed} as in appIconSvg */
export function appIconEl(app = {}, opts = {}) {
  const tpl = document.createElement("template");
  tpl.innerHTML = appIconSvg(appIconKey(app), { label: app.label, themed: opts.themed });
  const svg = tpl.content.firstElementChild;
  svg.dataset.design = appIconKey(app);
  return svg;
}
