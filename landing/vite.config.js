import { fileURLToPath } from "node:url";
import path from "node:path";
import { defineConfig } from "vite";
import { prerenderI18n } from "./scripts/prerender-i18n.js";
import { t, DEFAULT_LANG } from "./src/i18n.js";

const here = path.dirname(fileURLToPath(import.meta.url));
const repo = path.resolve(here, "..");

/*
 * The crab on this page is the bot's own: Virtual Bot/static/crab.js, the
 * same engine that draws the face on the device screen and in the dashboard.
 * That file is a classic script (the screen loads it with a <script> tag),
 * so it declares PixelCrab without exporting it. This plugin lets the
 * landing import it as a module by appending the export at build time —
 * one source of truth for the mascot instead of a copy that drifts.
 */
const CRAB_ID = "bot-crab";
const crabFile = path.join(repo, "Virtual Bot", "static", "crab.js");

function botCrab() {
  return {
    name: "landing-bot-crab",
    resolveId(id) {
      return id === CRAB_ID ? crabFile : null;
    },
    transform(code, id) {
      if (path.normalize(id) !== crabFile) return null;
      return { code: `${code}\nexport { PixelCrab };\n`, map: null };
    },
  };
}

export default defineConfig({
  plugins: [botCrab(), prerenderI18n((key) => t(key, DEFAULT_LANG))],
  server: {
    port: 5199,
    strictPort: true,
    fs: { allow: [here, path.join(repo, "Virtual Bot", "static")] },
  },
  build: {
    target: "es2022",
    assetsInlineLimit: 0,
  },
});
