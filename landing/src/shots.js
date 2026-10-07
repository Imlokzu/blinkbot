/*
 * Product captures are always English, independent of the marketing copy.
 * Desktop captures close the optional right panel before taking the picture.
 * Full and half widths let each viewport load the appropriate size.
 */

// Width in pixels of each full-size capture: the dashboard at 2x, the phone
// at 3x, the 320x240 device screen at 4x.
const FULL_WIDTH = {
  chat: 2880,
  memory: 2880,
  welcome: 2880,
  mobile: 1170,
  "screen-clock": 1280,
  "screen-weather": 1280,
  "screen-apps": 1280,
};

// How wide each slot renders, so the browser can pick the smaller file.
const SLOT_SIZES = [
  [".window", "(max-width: 960px) 92vw, 1180px"],
  [".device-frame", "(max-width: 960px) 92vw, 720px"],
  [".tour__inline--phone", "320px"],
  [".tour__inline", "92vw"],
  [".bot__view", "(max-width: 960px) 80vw, 460px"],
];

const BASE = `${import.meta.env.BASE_URL}shots/en/`;

export function loadShots(root = document) {
  for (const img of root.querySelectorAll("img[data-shot]")) {
    const name = img.dataset.shot;
    const full = FULL_WIDTH[name];
    if (!full) continue;
    const slot = SLOT_SIZES.find(([selector]) => img.matches(selector) || img.closest(selector));
    img.sizes = slot ? slot[1] : "100vw";
    img.srcset = `${BASE}${name}-half.webp ${full / 2}w, ${BASE}${name}.webp ${full}w`;
    img.src = `${BASE}${name}.webp`;
  }
}
