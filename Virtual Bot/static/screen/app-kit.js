/* ============================================================
   App kit v1 — the runtime half (the look is app-kit.css)

   A plain script, not a module, so an app includes it with one tag and
   it runs before the app's own code:

     <script src="/static/screen/app-kit.js"></script>

   What it does:
     - takes the screen's live style from the parent's "botSkin" message
       — resolved colour tokens, font, theme, interface style (material |
       deep), language and the gesture insets — and puts them on <html>
       (CSS variables, data-theme, data-ui, lang), so the kit's parts and
       the app's own CSS match the screen exactly, skins included;
     - until that message arrives, guesses the same from the screen's
       saved settings (same origin), so the app does not flash in the
       wrong theme;
     - keeps the fill of every <input type="range" class="kit-slider">
       (--pct) as it is dragged; after setting a value from code, call
       BotApp.paint(el);
     - gives apps a small API: BotApp.lang / .theme / .ui,
       BotApp.onChange(fn) (called with {lang, theme, ui} on every
       change), BotApp.close() (leave the app).

   Public API: add to it, never rename; installed apps are copies.
   ============================================================ */

(function () {
  "use strict";

  var root = document.documentElement;
  var listeners = [];

  function read(key) {
    try { return window.localStorage.getItem(key); } catch (e) { return null; }
  }

  var api = {
    version: 1,
    lang: read("botScreenLang") === "en" ? "en" : "uk",
    theme: read("botScreenTheme") === "light" ? "light" : "dark",
    ui: read("botScreenUiStyle") === "deep" || read("botScreenUiStyle") === "oneui" ? "deep" : "material",
    onChange: function (fn) {
      if (typeof fn === "function") listeners.push(fn);
    },
    close: function () {
      try { window.parent.postMessage({ type: "closeStoreApp" }, "*"); } catch (e) { /* standalone */ }
    },
    paint: function (el) {
      if (el) { paint(el); return; }
      var all = document.querySelectorAll('input[type="range"].kit-slider');
      for (var i = 0; i < all.length; i++) paint(all[i]);
    },
  };

  function paint(el) {
    var min = Number(el.min || 0);
    var max = Number(el.max || 100);
    var pct = ((Number(el.value) - min) / Math.max(1, max - min)) * 100;
    el.style.setProperty("--pct", pct + "%");
  }

  function apply() {
    root.dataset.theme = api.theme;
    root.dataset.ui = api.ui;
    root.lang = api.lang;
  }

  apply();

  document.addEventListener("input", function (e) {
    var el = e.target;
    if (el && el.type === "range" && el.classList && el.classList.contains("kit-slider")) paint(el);
  }, true);
  document.addEventListener("DOMContentLoaded", function () { api.paint(); });

  // Only the parent may restyle the app. The origin is not checked: a
  // sandboxed (shared) app sees "null", and nothing here is a secret.
  window.addEventListener("message", function (e) {
    var d = e.data;
    if (e.source !== window.parent || !d || d.type !== "botSkin") return;
    // tokens: the screen's resolved values (v1); vars: a skin's overrides
    // only, sent by screens older than the kit.
    var tokens = d.tokens || d.vars || {};
    for (var k in tokens) {
      if (Object.prototype.hasOwnProperty.call(tokens, k) && /^--[a-z0-9-]+$/.test(k)) {
        root.style.setProperty(k, String(tokens[k]));
      }
    }
    if (d.insets) {
      if (d.insets.side != null) root.style.setProperty("--kit-inset-side", Number(d.insets.side) + "px");
      if (d.insets.bottom != null) root.style.setProperty("--kit-inset-bottom", Number(d.insets.bottom) + "px");
    }
    api.theme = d.theme === "light" ? "light" : "dark";
    if (d.ui === "deep" || d.ui === "material") api.ui = d.ui;
    if (d.lang === "uk" || d.lang === "en") api.lang = d.lang;
    apply();
    api.paint();
    for (var i = 0; i < listeners.length; i++) {
      try { listeners[i]({ lang: api.lang, theme: api.theme, ui: api.ui }); } catch (err) { /* one app bug must not stop the rest */ }
    }
  });

  window.BotApp = api;
})();
