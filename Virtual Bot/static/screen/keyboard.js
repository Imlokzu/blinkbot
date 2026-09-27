/* ============================================================
   On-screen keyboard for the device screen

   The screen was voice-only on purpose: a keyboard on 2.4" is torture.
   But voice cannot do everything — a search query in the YouTube app, a
   city name, a word recognition keeps mishearing — and the 5" panel the
   bot is getting has room for keys. So: a keyboard, but a small one.

   Two halves:
     - the layouts and applyKey() are pure data and logic, no DOM, so node
       can check them (tests/test_screen_js.py), the same way as wake.js;
     - ScreenKeyboard draws it inside the stage and wires the pointer.

   Sizing is for a resistive panel at 320×240: 12 keys across the Ukrainian
   top row still leave each key ~24 px wide, the platform minimum
   (docs/SCREEN-PLATFORM.md, "Кнопки ≥ 24px").
   ============================================================ */

// Special keys. Everything else in a layout is a literal character.
export const K = {
  SHIFT: "{shift}",
  BACK: "{back}",
  SPACE: "{space}",
  ENTER: "{enter}",
  SYM: "{sym}",
  ABC: "{abc}",
  LANG: "{lang}",
};

// Rows per layout. The Ukrainian apostrophe is U+02BC (ʼ), the letter the
// rest of the project uses, and it gets a key of its own: it is in half of
// everyday words (памʼять, пʼять) and hiding it in the symbols layer would
// make those words the hardest to type.
export const LAYOUTS = {
  uk: [
    ["й", "ц", "у", "к", "е", "н", "г", "ш", "щ", "з", "х", "ї"],
    ["ф", "і", "в", "а", "п", "р", "о", "л", "д", "ж", "є"],
    [K.SHIFT, "я", "ч", "с", "м", "и", "т", "ь", "б", "ю", K.BACK],
    [K.SYM, K.LANG, "ʼ", K.SPACE, ".", K.ENTER],
  ],
  en: [
    ["q", "w", "e", "r", "t", "y", "u", "i", "o", "p"],
    ["a", "s", "d", "f", "g", "h", "j", "k", "l"],
    [K.SHIFT, "z", "x", "c", "v", "b", "n", "m", K.BACK],
    [K.SYM, K.LANG, ",", K.SPACE, ".", K.ENTER],
  ],
  sym: [
    ["1", "2", "3", "4", "5", "6", "7", "8", "9", "0"],
    ["-", "/", ":", ";", "(", ")", "?", "!", "\"", "@"],
    ["%", "+", "=", "_", "&", "*", "#", "ґ", "'", K.BACK],
    [K.ABC, K.LANG, ",", K.SPACE, ".", K.ENTER],
  ],
};

// Long press on a key types its neighbour: the letters that did not fit.
export const ALTERNATES = {
  "г": "ґ",
  ".": ",",
  "ʼ": "'",
  ",": "?",
};

export const LANG_ORDER = ["uk", "en"];

/**
 * Fresh keyboard state.
 * @param {object} o  {text, lang, autocap}
 */
export function initialState(o = {}) {
  const lang = LANG_ORDER.includes(o.lang) ? o.lang : "uk";
  const state = {
    text: String(o.text || ""),
    lang,
    layer: lang,          // "uk" | "en" | "sym"
    shift: false,         // one-shot capital
    caps: false,          // shift held twice
    autocap: o.autocap !== false,
    done: false,
  };
  state.shift = wantsCapital(state);
  return state;
}

/* A sentence starts: at the very beginning, or after . ! ? and a space. */
function wantsCapital(state) {
  if (!state.autocap) return false;
  const text = state.text;
  return !text.trim() || /[.!?…]\s+$/.test(text);
}

function isLetter(ch) {
  return /^\p{L}$/u.test(ch) && ch.toLowerCase() !== ch.toUpperCase();
}

/**
 * One key press → the next state. Never mutates the input.
 * @param {object} state
 * @param {string} key   a character or one of K
 */
export function applyKey(state, key) {
  const s = { ...state, done: false };
  switch (key) {
    case K.BACK:
      // Drop one user-visible character, not half a surrogate pair
      s.text = Array.from(s.text).slice(0, -1).join("");
      s.shift = s.caps || wantsCapital(s);
      return s;
    case K.SHIFT:
      // tap: next letter capital; tap again while armed: caps lock; again: off
      if (s.caps) { s.caps = false; s.shift = false; }
      else if (s.shift) { s.caps = true; }
      else { s.shift = true; }
      return s;
    case K.SPACE:
      // A double space after a word becomes ". " — the phone habit, and
      // the fastest way to end a sentence with one thumb.
      if (/\S $/.test(s.text) && !/[.!?,…] $/.test(s.text)) {
        s.text = s.text.slice(0, -1) + ". ";
      } else {
        s.text += " ";
      }
      s.shift = s.caps || wantsCapital(s);
      return s;
    case K.ENTER:
      s.done = true;
      return s;
    case K.SYM:
      s.layer = "sym";
      return s;
    case K.ABC:
      s.layer = s.lang;
      s.shift = s.caps || wantsCapital(s);
      return s;
    case K.LANG: {
      const next = LANG_ORDER[(LANG_ORDER.indexOf(s.lang) + 1) % LANG_ORDER.length];
      s.lang = next;
      s.layer = next;
      return s;
    }
    default: {
      let ch = String(key || "");
      if (!ch) return s;
      if ((s.shift || s.caps) && isLetter(ch)) ch = ch.toUpperCase();
      s.text += ch;
      if (s.layer === "sym" && ch === " ") s.layer = s.lang;
      s.shift = s.caps || wantsCapital(s);
      return s;
    }
  }
}

/** What a key shows: letters follow shift, special keys get a label key. */
export function keyCap(state, key) {
  if (key.startsWith("{")) return null;
  return (state.shift || state.caps) && isLetter(key) ? key.toUpperCase() : key;
}

/* ------------------------------------------------------------------ DOM */

const LONG_PRESS_MS = 450;
const REPEAT_DELAY_MS = 420;
const REPEAT_EVERY_MS = 70;

/**
 * The keyboard itself. One instance per page; open() binds it to whoever
 * asked (the chat, a settings field, an input inside a store app).
 *
 * @param {HTMLElement} root   an empty container inside the stage
 * @param {object} deps        {t: i18n lookup, icon: name → element}
 */
export class ScreenKeyboard {
  constructor(root, deps) {
    this.root = root;
    this.t = deps.t;
    this.icon = deps.icon;
    this.state = null;
    this.opts = null;
    this.root.classList.add("osk", "hidden");
    this.root.addEventListener("pointerdown", (e) => this._down(e));
    this.root.addEventListener("pointerup", (e) => this._up(e));
    this.root.addEventListener("pointercancel", () => this._release());
    this.root.addEventListener("pointerleave", () => this._release());
    // The stage swipes tiles and closes layers on taps; none of that may
    // happen through the keyboard.
    for (const type of ["click", "pointermove", "touchstart", "touchmove"]) {
      this.root.addEventListener(type, (e) => e.stopPropagation());
    }
  }

  get isOpen() {
    return !this.root.classList.contains("hidden");
  }

  /**
   * @param {object} opts {value, placeholder, lang, autocap, enter: "send"|"done"|"search",
   *                       onInput(text), onDone(text), onClose(cancelled)}
   */
  open(opts = {}) {
    if (this.isOpen) this.close(true);
    this.opts = opts;
    this.state = initialState({ text: opts.value, lang: opts.lang, autocap: opts.autocap });
    this.root.classList.remove("hidden");
    this._render();
  }

  close(cancelled) {
    if (!this.isOpen) return;
    this._release();
    const opts = this.opts;
    this.root.classList.add("hidden");
    this.root.innerHTML = "";
    this.opts = null;
    if (opts && typeof opts.onClose === "function") opts.onClose(!!cancelled);
  }

  _press(key) {
    const before = this.state;
    this.state = applyKey(before, key);
    if (this.state.text !== before.text && this.opts && this.opts.onInput) this.opts.onInput(this.state.text);
    if (this.state.done) {
      const text = this.state.text;
      const onDone = this.opts && this.opts.onDone;
      this.close(false);
      if (typeof onDone === "function") onDone(text);
      return;
    }
    this._render();
  }

  _down(e) {
    const btn = e.target.closest("[data-key]");
    if (!btn) return;
    e.preventDefault();
    e.stopPropagation();
    if (btn.dataset.key === "{close}") return;           // acts on release
    this._release();
    this.held = { btn, key: btn.dataset.key, fired: false };
    btn.classList.add("down");
    const key = this.held.key;
    if (key === K.BACK) {
      // Backspace repeats while held, like any keyboard
      this._press(K.BACK);
      this.held.fired = true;
      this.held.timer = setTimeout(() => {
        this.held.repeat = setInterval(() => this._press(K.BACK), REPEAT_EVERY_MS);
      }, REPEAT_DELAY_MS);
    } else if (ALTERNATES[key]) {
      this.held.timer = setTimeout(() => {
        this.held.fired = true;
        this._press(ALTERNATES[key]);
      }, LONG_PRESS_MS);
    }
  }

  _up(e) {
    const btn = e.target.closest("[data-key]");
    if (btn && btn.dataset.key === "{close}") {
      e.stopPropagation();
      this.close(true);
      return;
    }
    const held = this.held;
    this._release();
    if (held && !held.fired) this._press(held.key);
  }

  _release() {
    const held = this.held;
    this.held = null;
    if (!held) return;
    clearTimeout(held.timer);
    clearInterval(held.repeat);
    if (held.btn) held.btn.classList.remove("down");
  }

  _render() {
    const s = this.state;
    const t = this.t;
    this.root.innerHTML = "";
    this.root.dataset.layer = s.layer;

    // What is being typed, always visible: the field it goes to may well
    // be hidden under the keyboard on a screen this small.
    const bar = document.createElement("div");
    bar.className = "osk-bar";
    const text = document.createElement("div");
    text.className = "osk-text" + (s.text ? "" : " empty");
    text.textContent = s.text || (this.opts && this.opts.placeholder) || "";
    const caret = document.createElement("span");
    caret.className = "osk-caret";
    if (s.text) text.appendChild(caret);
    else text.prepend(caret);
    bar.appendChild(text);
    const close = document.createElement("button");
    close.type = "button";
    close.className = "osk-close";
    close.dataset.key = "{close}";
    close.setAttribute("aria-label", t("kb.close"));
    close.textContent = "✕";
    bar.appendChild(close);
    this.root.appendChild(bar);
    // Keep the end of a long line in view
    requestAnimationFrame(() => { text.scrollLeft = text.scrollWidth; });

    for (const row of LAYOUTS[s.layer]) {
      const line = document.createElement("div");
      line.className = "osk-row";
      for (const key of row) {
        const btn = document.createElement("button");
        btn.type = "button";
        btn.className = "osk-key";
        btn.dataset.key = key;
        const cap = keyCap(s, key);
        if (cap !== null) {
          btn.textContent = cap;
          if (ALTERNATES[key]) btn.dataset.alt = ALTERNATES[key];
        } else {
          btn.classList.add("osk-fn", "osk-" + key.slice(1, -1));
          if (key === K.SPACE) btn.textContent = t("kb.lang." + s.lang);
          else if (key === K.ENTER) btn.textContent = t("kb.enter." + ((this.opts && this.opts.enter) || "done"));
          else if (key === K.SYM) btn.textContent = "123";
          else if (key === K.ABC) btn.textContent = s.lang === "en" ? "abc" : "абв";
          else if (key === K.LANG) btn.appendChild(this.icon("globe"));
          else if (key === K.BACK) btn.appendChild(this.icon("backspace"));
          else if (key === K.SHIFT) {
            btn.appendChild(this.icon("shift"));
            btn.classList.toggle("on", s.shift || s.caps);
            btn.classList.toggle("caps", s.caps);
          }
          btn.setAttribute("aria-label", t("kb.key." + key.slice(1, -1)));
        }
        line.appendChild(btn);
      }
      this.root.appendChild(line);
    }
  }
}
