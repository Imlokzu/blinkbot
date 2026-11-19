const MIN_WEIGHT = 200;
const MAX_WEIGHT = 820;
const RADIUS = 280;
const IDLE_AFTER = 2200;
const clamp = (value) => Math.min(1, Math.max(0, value));

export function weightAtDistance(distance) {
  const nearby = clamp(1 - distance / RADIUS);
  return MIN_WEIGHT + (MAX_WEIGHT - MIN_WEIGHT) * nearby * nearby * (3 - 2 * nearby);
}

export function wordRevealProgress(top, height, viewportHeight) {
  return clamp((viewportHeight * 0.9 - top) / (height + viewportHeight * 0.4));
}

function splitGraphemes(text) {
  if (!("Segmenter" in Intl)) return Array.from(text);
  const segmenter = new Intl.Segmenter(undefined, { granularity: "grapheme" });
  return Array.from(segmenter.segment(text), ({ segment }) => segment);
}

export function createTextMotion() {
  const heading = document.querySelector("[data-motion-title]");
  const titleLines = [...heading.querySelectorAll("[data-motion-line]")];
  const paragraph = document.querySelector("[data-motion-words]");
  const preference = matchMedia("(prefers-reduced-motion: reduce)");
  let letters = [];
  let words = [];
  let titleVisible = false;
  let paragraphVisible = false;
  let headingFrame = 0;
  let wordsFrame = 0;
  let pointer = null;
  let lastMove = -Infinity;
  let sweepStart = 0;

  function animateHeading(now) {
    headingFrame = requestAnimationFrame(animateHeading);
    const bounds = heading.getBoundingClientRect();
    let x;
    let y;
    if (pointer && now - lastMove < IDLE_AFTER) {
      ({ x, y } = pointer);
    } else {
      const seconds = (now - sweepStart) / 1000;
      const sweep = (Math.sin(seconds * 0.8 - Math.PI / 2) + 1) / 2;
      x = bounds.left - 200 + sweep * (bounds.width + 400);
      y = bounds.top + bounds.height * (0.5 + 0.3 * Math.sin(seconds * 1.6));
    }

    // Read the positions before changing weights: Geist's glyph widths can change.
    const boxes = letters.map(({ element }) => element.getBoundingClientRect());
    boxes.forEach(({ left, top, width, height }, i) => {
      const letter = letters[i];
      const distance = Math.hypot(left + width / 2 - x, top + height / 2 - y);
      letter.weight += (weightAtDistance(distance) - letter.weight) * 0.14;
      const rounded = Math.round(letter.weight / 10) * 10;
      if (rounded === letter.applied) return;
      letter.element.style.fontWeight = rounded;
      letter.applied = rounded;
    });
  }

  function measureWords() {
    wordsFrame = 0;
    if (preference.matches || document.hidden) return;
    const { top, height } = paragraph.getBoundingClientRect();
    const lit = wordRevealProgress(top, height, innerHeight) * words.length;
    words.forEach((word, i) => word.style.setProperty("--lit", clamp(lit - i).toFixed(3)));
  }

  function scheduleWords() {
    if (paragraphVisible && !wordsFrame && !preference.matches && !document.hidden) {
      wordsFrame = requestAnimationFrame(measureWords);
    }
  }

  function syncHeading() {
    const shouldPlay = titleVisible && letters.length && !preference.matches && !document.hidden;
    if (shouldPlay && !headingFrame) {
      sweepStart = performance.now();
      headingFrame = requestAnimationFrame(animateHeading);
    } else if (!shouldPlay && headingFrame) {
      cancelAnimationFrame(headingFrame);
      headingFrame = 0;
    }
  }

  function syncMotion() {
    syncHeading();
    paragraph.classList.toggle("has-scroll-motion", !preference.matches);
    if (preference.matches) {
      for (const letter of letters) {
        letter.element.style.removeProperty("font-weight");
        letter.weight = MIN_WEIGHT;
        letter.applied = MIN_WEIGHT;
      }
    }
    scheduleWords();
  }

  if (!("IntersectionObserver" in window)) return () => {};

  const observer = new IntersectionObserver((entries) => {
    for (const entry of entries) {
      if (entry.target === heading) {
        titleVisible = entry.isIntersecting;
        syncHeading();
      } else {
        paragraphVisible = entry.isIntersecting;
        // Measure on exit too, so the final state survives a jump past the section.
        cancelAnimationFrame(wordsFrame);
        measureWords();
      }
    }
  });
  observer.observe(heading);
  observer.observe(paragraph);

  window.addEventListener("pointermove", (event) => {
    if (event.pointerType !== "mouse" || !titleVisible || preference.matches) return;
    pointer = { x: event.clientX, y: event.clientY };
    lastMove = performance.now();
  }, { passive: true });
  window.addEventListener("scroll", scheduleWords, { passive: true });
  window.addEventListener("resize", scheduleWords);
  document.addEventListener("visibilitychange", syncMotion);
  preference.addEventListener("change", syncMotion);

  return function refreshText() {
    const title = titleLines.map((line) => line.textContent).join(" ");
    heading.setAttribute("aria-label", title);
    letters = [];
    for (const line of titleLines) {
      const content = document.createDocumentFragment();
      for (const character of splitGraphemes(line.textContent)) {
        if (/^\s$/u.test(character)) {
          content.append(character);
        } else {
          const element = document.createElement("span");
          element.dataset.titleLetter = "";
          element.textContent = character;
          content.append(element);
          letters.push({ element, weight: MIN_WEIGHT, applied: MIN_WEIGHT });
        }
      }
      line.setAttribute("aria-hidden", "true");
      line.replaceChildren(content);
    }

    const content = document.createDocumentFragment();
    words = [];
    // A nonbreaking space from the Ukrainian catalogue stays inside a word group.
    for (const token of paragraph.textContent.split(/( +)/u)) {
      if (!token || token.startsWith(" ")) {
        content.append(token);
      } else {
        const word = document.createElement("span");
        word.dataset.motionWord = "";
        word.textContent = token;
        content.append(word);
        words.push(word);
      }
    }
    paragraph.replaceChildren(content);
    syncMotion();
  };
}
