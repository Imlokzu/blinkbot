import "@fontsource-variable/ibm-plex-sans/wght.css";
import "@fontsource/ibm-plex-mono/400.css";
import "@fontsource/ibm-plex-mono/500.css";
import "@fontsource/cormorant-garamond/500-italic.css";
import "./styles/base.css";
import "./styles/landing.css";

import { gsap } from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import { ScrollSmoother } from "gsap/ScrollSmoother";
import { SplitText } from "gsap/SplitText";
import { LANG_KEY, applyStatic, detectLang, getLang, setCurrentLang } from "./i18n.js";
import { loadShots } from "./shots.js";
import { initReveals, resplit, unsplit } from "./reveal.js";
import { initHero } from "./hero.js";
import { initTour } from "./tour.js";
import { initDevice } from "./device.js";
import { initCards, initFinal, initMagnetic, initMarquee, initNav, initScrollLinks, initTerminal } from "./extras.js";

gsap.registerPlugin(ScrollTrigger, ScrollSmoother, SplitText);

// Handy from the console while working on the motion; never shipped.
if (import.meta.env.DEV) Object.assign(window, { gsap, ScrollTrigger });

const root = document.documentElement;
const reduced = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
const finePointer = window.matchMedia("(pointer: fine)").matches;

function storedLang() {
  try {
    return localStorage.getItem(LANG_KEY);
  } catch {
    return null;
  }
}

function applyLanguage(lang) {
  setCurrentLang(lang);
  root.lang = lang;
  root.dataset.lang = lang;
  applyStatic(document, lang);
  loadShots(lang);
  for (const button of document.querySelectorAll("[data-set-lang]")) {
    button.setAttribute("aria-pressed", String(button.dataset.setLang === lang));
  }
}

// Language first: the hero must not make its entrance in the wrong one.
applyLanguage(detectLang(window.location.search, storedLang(), navigator.languages || [navigator.language]));

const smoother = reduced
  ? null
  : ScrollSmoother.create({
      wrapper: "#smooth-wrapper",
      content: "#smooth-content",
      smooth: 1.05,
      smoothTouch: false,
      effects: false,
    });

// Line splitting and every trigger position measure text, so they wait for
// the real fonts. fonts.ready alone is not enough: it can resolve before the
// stylesheet has asked for any font at all. Asking for them by name does
// wait — but never longer than a moment, a missing font must not hold the page.
const FONTS = ['600 1em "IBM Plex Sans Variable"', 'italic 500 1em "Cormorant Garamond"', '500 1em "IBM Plex Mono"'];
const fontsLoaded = Promise.race([
  Promise.all(FONTS.map((font) => document.fonts.load(font))).then(() => document.fonts.ready),
  new Promise((resolve) => setTimeout(resolve, 2500)),
]);

// Anything that still changes the page height later (a late font, an image
// without reserved space) moves every trigger below it: measure again once
// the height settles.
function remeasureOnResize() {
  const main = document.querySelector("main");
  let measured = main.offsetHeight;
  const settle = gsap.delayedCall(0.3, () => {
    ScrollTrigger.refresh();
    measured = main.offsetHeight;
  }).pause();
  new ResizeObserver(() => {
    if (Math.abs(main.offsetHeight - measured) > 2) settle.restart(true);
  }).observe(main);
}

fontsLoaded.then(() => {
  const { intro } = initHero({ reduced, finePointer });
  initReveals({ reduced });
  initTour({ reduced });
  const device = initDevice({ reduced, finePointer });
  initCards({ finePointer });
  initMarquee({ reduced });
  initTerminal({ reduced });
  initFinal({ reduced });
  initNav();
  if (finePointer && !reduced) initMagnetic();

  // "See it in action" lands where the dashboard has fully risen.
  const revealPoint = () => {
    const pin = ScrollTrigger.getAll().find((st) => st.pin === document.querySelector("[data-hero]"));
    return pin ? pin.start + (pin.end - pin.start) * 0.62 : document.querySelector("[data-window]").offsetTop;
  };
  initScrollLinks({ smoother, revealPoint });

  for (const button of document.querySelectorAll("[data-set-lang]")) {
    button.addEventListener("click", () => {
      const lang = button.dataset.setLang;
      if (lang === getLang()) return;
      try {
        localStorage.setItem(LANG_KEY, lang);
      } catch {
        // private mode: the choice lasts for this visit only
      }
      const url = new URL(window.location.href);
      url.searchParams.delete("lang");
      window.history.replaceState(null, "", url);

      intro?.progress(1);
      const content = document.querySelector("#smooth-content");
      gsap.to(content, {
        opacity: 0,
        duration: reduced ? 0 : 0.2,
        onComplete: () => {
          unsplit();
          applyLanguage(lang);
          device.refreshLabels();
          resplit({ reduced });
          ScrollTrigger.refresh();
          gsap.to(content, { opacity: 1, duration: reduced ? 0 : 0.35 });
        },
      });
    });
  }

  ScrollTrigger.refresh();
  remeasureOnResize();
});
