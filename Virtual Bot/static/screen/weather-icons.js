/* ============================================================
   Weather pictures and skies for the weather tile

   The tile used to be grey text on grey boxes, which is the one screen
   where the picture IS the information: "is it sunny" should be answered
   from across the room. So, as in One UI's weather, the whole tile takes
   the colour of the sky (day, night, overcast, rain, snow, storm), and
   the conditions are drawn, not written: a sun, a moon, clouds, drops,
   flakes, a bolt — in colour, from the same kit at every size (the big
   picture, the hourly strip, the days).

   Pure strings, no DOM: node renders and checks every picture
   (tests/test_screen_js.py).
   ============================================================ */

/* WMO weather code → what to draw. */
export function wxKind(code) {
  // Number(null) is 0, "clear sky": a missing code must not draw a sun
  if (code === null || code === undefined || code === "") return "cloudy";
  const c = Number(code);
  if (!Number.isFinite(c)) return "cloudy";
  if (c === 0) return "clear";
  if (c === 1) return "mostly";
  if (c === 2) return "partly";
  if (c === 3) return "cloudy";
  if (c === 45 || c === 48) return "fog";
  if (c >= 51 && c <= 55) return "drizzle";
  if (c === 56 || c === 57 || c === 66 || c === 67) return "sleet";
  if (c === 65 || c === 82) return "heavy";
  if ((c >= 61 && c <= 63) || c === 80 || c === 81) return "rain";
  if ((c >= 71 && c <= 77) || c === 85 || c === 86) return "snow";
  if (c >= 95) return "storm";
  return "cloudy";
}

export const KINDS = ["clear", "mostly", "partly", "cloudy", "fog", "drizzle", "rain", "heavy", "sleet", "snow", "storm"];

/* The sky behind the tile: [top, bottom], and a darker one for the night:
   a daytime grey at midnight reads as "the screen is wrong". */
const SKY = {
  clear: ["#3fa2ff", "#155fd6"],
  mostly: ["#4b9df0", "#1d5fc4"],
  partly: ["#5a92d6", "#2b5aa6"],
  cloudy: ["#71829a", "#3c485b"],
  fog: ["#8b95a3", "#566070"],
  drizzle: ["#5c7390", "#2e3d52"],
  rain: ["#4f6582", "#243246"],
  heavy: ["#435672", "#1b2536"],
  sleet: ["#6a7f9b", "#34445c"],
  snow: ["#8fa6c6", "#50698c"],
  storm: ["#4a4570", "#1c1934"],
};
const NIGHT_SKY = {
  clear: ["#1d2b5c", "#080e26"],
  mostly: ["#22305e", "#0b122c"],
  partly: ["#27345c", "#0e152e"],
  cloudy: ["#3c4658", "#1a202c"],
  fog: ["#4a525f", "#232830"],
  drizzle: ["#34435a", "#161e2b"],
  rain: ["#2e3c52", "#131a26"],
  heavy: ["#28344a", "#0f1520"],
  sleet: ["#3a4a62", "#18202e"],
  snow: ["#4a5c7a", "#1f2a3d"],
  storm: ["#2e2a4a", "#100e1f"],
};

export function wxSky(kind, night) {
  return (night && NIGHT_SKY[kind]) || SKY[kind] || SKY.cloudy;
}

/* ------------------------------------------------------------ drawing
   viewBox 64×64. Each piece takes an id prefix for its gradients, since
   SVG ids are page-global and the strip draws a dozen of these. */

function f(n) {
  return String(Math.round(n * 100) / 100);
}

function sun(id, cx, cy, r) {
  let rays = "";
  for (let i = 0; i < 8; i++) {
    const a = (i * Math.PI) / 4;
    rays += `M${f(cx + (r + 4) * Math.cos(a))} ${f(cy + (r + 4) * Math.sin(a))}` +
      `L${f(cx + (r + 8.5) * Math.cos(a))} ${f(cy + (r + 8.5) * Math.sin(a))}`;
  }
  return `<defs><radialGradient id="${id}s" cx="0.4" cy="0.35" r="0.75">` +
    `<stop offset="0" stop-color="#fff3a0"/><stop offset="0.55" stop-color="#ffd23f"/><stop offset="1" stop-color="#ff9d1c"/>` +
    `</radialGradient></defs>` +
    `<path d="${rays}" stroke="#ffc933" stroke-width="3.4" stroke-linecap="round"/>` +
    `<circle cx="${cx}" cy="${cy}" r="${r}" fill="url(#${id}s)"/>`;
}

function moon(id, cx, cy, r) {
  // Disc minus a bite, computed so the horns meet the disc exactly
  const bx = cx + r * 0.55;
  const by = cy - r * 0.45;
  const br = r * 0.85;
  const dx = bx - cx;
  const dy = by - cy;
  const d = Math.hypot(dx, dy);
  const a = (d * d + r * r - br * br) / (2 * d);
  const h = Math.sqrt(Math.max(0, r * r - a * a));
  const mx = cx + (a * dx) / d;
  const my = cy + (a * dy) / d;
  const p1 = [mx + (h * dy) / d, my - (h * dx) / d];
  const p2 = [mx - (h * dy) / d, my + (h * dx) / d];
  return `<defs><linearGradient id="${id}m" x1="0" y1="0" x2="1" y2="1">` +
    `<stop offset="0" stop-color="#fffbe0"/><stop offset="1" stop-color="#f1d77a"/></linearGradient></defs>` +
    `<path d="M${f(p1[0])} ${f(p1[1])}A${r} ${r} 0 1 0 ${f(p2[0])} ${f(p2[1])}A${f(br)} ${f(br)} 0 0 1 ${f(p1[0])} ${f(p1[1])}Z" fill="url(#${id}m)"/>` +
    `<circle cx="${f(cx + r * 1.25)}" cy="${f(cy - r * 1.05)}" r="1.4" fill="#fffbe0"/>` +
    `<circle cx="${f(cx + r * 1.6)}" cy="${f(cy - r * 0.2)}" r="1" fill="#fffbe0" fill-opacity="0.8"/>`;
}

// A cloud 50×34 with its bottom-left at (0, 0) in its own coordinates
const CLOUD = "M10 0h28a10 10 0 0 0 1.5-19.9A14 14 0 0 0 12.6-17.6 9 9 0 0 0 10 0z";

function cloud(id, x, y, scale, tone = "front") {
  const [top, bottom] = tone === "front"
    ? ["#ffffff", "#dde5ef"]
    : tone === "dark" ? ["#9aa6b8", "#6c788b"] : ["#c9d3e0", "#a4b0c1"];
  const gid = `${id}c${tone}`;
  return `<defs><linearGradient id="${gid}" x1="0" y1="0" x2="0" y2="1">` +
    `<stop offset="0" stop-color="${top}"/><stop offset="1" stop-color="${bottom}"/></linearGradient></defs>` +
    `<path transform="translate(${f(x)} ${f(y)}) scale(${scale})" d="${CLOUD}" fill="url(#${gid})"/>`;
}

function drops(xs, y, len, color = "#5ac8fa") {
  const d = xs.map((x) => `M${x} ${y}l-2.2 ${len}`).join("");
  return `<path d="${d}" stroke="${color}" stroke-width="3" stroke-linecap="round"/>`;
}

function flakes(points) {
  return points.map(([x, y]) =>
    `<g stroke="#ffffff" stroke-width="2" stroke-linecap="round">` +
    `<path d="M${x} ${y - 3.2}v6.4M${f(x - 2.8)} ${f(y - 1.6)}l5.6 3.2M${f(x - 2.8)} ${f(y + 1.6)}l5.6-3.2"/></g>`).join("");
}

function bolt(x, y) {
  return `<path transform="translate(${x} ${y})" d="M6 0L0 10h5l-2.5 9L11 7H6l3-7z" fill="#ffd60a" stroke="#ff9f0a" stroke-width="0.8" stroke-linejoin="round"/>`;
}

const PIECES = {
  clear: (id, night) => (night ? moon(id, 32, 32, 15) : sun(id, 32, 32, 13)),
  mostly: (id, night) => (night ? moon(id, 27, 26, 13) : sun(id, 26, 25, 11.5)) + cloud(id, 26, 54, 0.62),
  partly: (id, night) => (night ? moon(id, 25, 23, 12) : sun(id, 25, 24, 10)) + cloud(id, 12, 55, 0.95),
  cloudy: (id) => cloud(id, 22, 38, 0.72, "back") + cloud(id, 6, 52, 0.95),
  fog: (id) => cloud(id, 7, 38, 0.95) +
    `<path d="M10 45h36M16 51h36M12 57h30" stroke="#e6ecf3" stroke-width="3.2" stroke-linecap="round" stroke-opacity="0.9"/>`,
  drizzle: (id) => cloud(id, 7, 40, 0.95) + drops([22, 32, 42], 46, 5),
  rain: (id) => cloud(id, 7, 40, 0.95, "back") + drops([20, 29, 38, 47], 46, 8),
  heavy: (id) => cloud(id, 7, 40, 0.95, "dark") + drops([17, 25, 33, 41, 49], 45, 11, "#3fb4f0"),
  sleet: (id) => cloud(id, 7, 40, 0.95, "back") + drops([20, 38], 46, 8) + flakes([[29, 52], [47, 52]]),
  snow: (id) => cloud(id, 7, 40, 0.95) + flakes([[20, 49], [32, 55], [44, 49]]),
  storm: (id) => cloud(id, 7, 40, 0.95, "dark") + bolt(26, 40) + drops([17, 47], 46, 7),
};

let seq = 0;

/**
 * A weather picture as an SVG string.
 * @param {string} kind   one of KINDS (from wxKind)
 * @param {object} o      {night, size, cls}
 */
export function wxIconSvg(kind, o = {}) {
  const piece = PIECES[kind] || PIECES.cloudy;
  const id = "wx" + (++seq);
  const size = o.size ? ` width="${o.size}" height="${o.size}"` : "";
  return `<svg class="${o.cls || "wx-icon"}" viewBox="0 0 64 64"${size} aria-hidden="true" focusable="false" xmlns="http://www.w3.org/2000/svg">` +
    piece(id, !!o.night) + `</svg>`;
}

/* A wind arrow: the way the wind blows TO (meteorology gives FROM). */
export function windArrowSvg(fromDeg) {
  const to = (Number(fromDeg) + 180) % 360;
  return `<svg class="wx-wind" viewBox="0 0 16 16" aria-hidden="true" xmlns="http://www.w3.org/2000/svg">` +
    `<path transform="rotate(${Number.isFinite(to) ? f(to) : 0} 8 8)" d="M8 2l4.5 11L8 10.5 3.5 13z" fill="currentColor"/></svg>`;
}
