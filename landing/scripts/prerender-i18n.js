/*
 * Build-time fill of the HTML from the locale catalogue.
 *
 * The rule in this repo is that index.html holds keys, never visible text.
 * Left at that, the page would paint empty headings until the script runs,
 * and crawlers would see nothing. So the default language is written into
 * the markup here, at build (and dev) time, from the very same catalogue the
 * browser uses afterwards to switch languages.
 *
 * It relies on one authoring convention, which tests/i18n.test.js enforces:
 * an element carrying data-i18n is written empty, `<tag data-i18n="k"></tag>`,
 * so filling it is a plain substitution rather than an HTML parse.
 */

const TEXT = /(<([a-z][a-z0-9-]*)\b[^>]*?\sdata-i18n="([^"]+)"[^>]*>)(<\/\2>)/g;
const ATTRS = /<[a-z][a-z0-9-]*\b[^>]*?\sdata-i18n-attr="([^"]+)"[^>]*>/g;

export function escapeHtml(text) {
  return String(text)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

/** Returns the HTML with text and attributes filled; throws on an unknown key. */
export function prerender(html, t) {
  const missing = new Set();
  const lookup = (key) => {
    const value = t(key);
    if (value === key) missing.add(key);
    return escapeHtml(value);
  };

  let out = html.replace(TEXT, (_all, open, _tag, key, close) => `${open}${lookup(key)}${close}`);

  out = out.replace(ATTRS, (tag, spec) => {
    let filled = tag;
    for (const pair of spec.split(";")) {
      const [attr, key] = pair.split(":").map((s) => s.trim());
      if (!attr || !key) continue;
      const value = lookup(key);
      const existing = new RegExp(`\\s${attr}="[^"]*"`);
      filled = existing.test(filled)
        ? filled.replace(existing, ` ${attr}="${value}"`)
        : filled.replace(/\s*\/?>$/, (end) => ` ${attr}="${value}"${end}`);
    }
    return filled;
  });

  if (missing.size) {
    throw new Error(`Unknown i18n keys in index.html: ${[...missing].join(", ")}`);
  }
  return out;
}

/** Vite plugin wrapper. */
export function prerenderI18n(t) {
  return {
    name: "landing-prerender-i18n",
    transformIndexHtml: {
      order: "pre",
      handler: (html) => prerender(html, t),
    },
  };
}
