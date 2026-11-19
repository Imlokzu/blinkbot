import "@fontsource-variable/ibm-plex-sans/wght.css";
import "@fontsource/ibm-plex-mono/400.css";
import "./styles/base.css";
import "./styles/landing.css";

import { LANG_KEY, applyStatic, detectLang, setCurrentLang, t } from "./i18n.js";

const root = document.documentElement;
const languageGroup = document.querySelector(".lang");
const languageButtons = languageGroup.querySelectorAll("[data-set-lang]");

function savedLanguage() {
  try {
    return localStorage.getItem(LANG_KEY);
  } catch {
    return null;
  }
}

function applyLanguage(lang) {
  setCurrentLang(lang);
  root.lang = lang;
  applyStatic(document, lang);
  for (const button of languageButtons) {
    button.setAttribute("aria-pressed", String(button.dataset.setLang === lang));
  }
}

applyLanguage(detectLang(location.search, savedLanguage(), navigator.languages || [navigator.language]));
languageGroup.hidden = false;

for (const button of languageButtons) {
  button.addEventListener("click", () => {
    const lang = button.dataset.setLang;
    try {
      localStorage.setItem(LANG_KEY, lang);
    } catch {
      // The language still applies to this document if storage is blocked.
    }
    const url = new URL(location.href);
    url.searchParams.delete("lang");
    history.replaceState(history.state, "", url);
    applyLanguage(lang);
  });
}

const navToggle = document.querySelector("[data-nav-toggle]");
const navLinks = document.querySelector("#site-navigation");
const narrowNav = matchMedia("(max-width: 800px)");

function setMenu(open) {
  navToggle.hidden = !narrowNav.matches;
  navToggle.setAttribute("aria-expanded", String(open));
  navLinks.hidden = narrowNav.matches && !open;
}

setMenu(false);
narrowNav.addEventListener("change", () => {
  const menuHadFocus = navLinks.contains(document.activeElement);
  const toggleHadFocus = document.activeElement === navToggle;
  setMenu(false);
  if (narrowNav.matches && menuHadFocus) navToggle.focus();
  if (!narrowNav.matches && toggleHadFocus) navLinks.querySelector("a").focus();
});
navToggle.addEventListener("click", () => setMenu(navLinks.hidden));
document.addEventListener("keydown", (event) => {
  if (event.key !== "Escape" || navToggle.getAttribute("aria-expanded") !== "true") return;
  setMenu(false);
  navToggle.focus();
});
for (const link of navLinks.querySelectorAll("a")) {
  link.addEventListener("click", (event) => {
    if (!narrowNav.matches || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
    setMenu(false);
    document.querySelector(link.hash)?.focus({ preventScroll: true });
  });
}

const deviceControls = document.querySelector(".bot__views");
const viewButtons = deviceControls.querySelectorAll("[data-show-view]");
const views = document.querySelectorAll("[data-bot-view]");

function showDeviceView(name) {
  for (const button of viewButtons) {
    button.setAttribute("aria-pressed", String(button.dataset.showView === name));
  }
  for (const view of views) {
    view.hidden = view.dataset.botView !== name;
  }
}

deviceControls.hidden = false;
for (const button of viewButtons) {
  button.addEventListener("click", () => showDeviceView(button.dataset.showView));
}

const copyStatus = document.querySelector("[data-copy-status]");
let latestCopy = 0;
for (const button of document.querySelectorAll("[data-copy]")) {
  const label = button.querySelector("[data-i18n]");
  let resetLabel;
  button.hidden = false;

  button.addEventListener("click", async () => {
    const attempt = ++latestCopy;
    copyStatus.textContent = "";
    let copied;
    try {
      await navigator.clipboard.writeText(button.dataset.copy);
      copied = true;
    } catch {
      copied = false;
    }
    if (attempt !== latestCopy) return;

    clearTimeout(resetLabel);
    copyStatus.textContent = t(copied ? "start.copied" : "start.copyHelp");
    label.dataset.i18n = copied ? "start.copied" : "start.copyFailed";
    label.textContent = t(label.dataset.i18n);
    resetLabel = setTimeout(() => {
      label.dataset.i18n = "start.copy";
      label.textContent = t("start.copy");
    }, 2000);
  });
}
