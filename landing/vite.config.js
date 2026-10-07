import { defineConfig } from "vite";
import { prerenderI18n } from "./scripts/prerender-i18n.js";
import { t, DEFAULT_LANG } from "./src/i18n.js";

export default defineConfig({
  plugins: [ prerenderI18n((key) => t(key, DEFAULT_LANG))],
  server: {
    port: 5199,
    strictPort: true,
  },
  build: {
    target: "es2022",
    assetsInlineLimit: 0,
  },
});
