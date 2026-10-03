/*
 * The device: the bot's screen in a desk-sized body.
 *
 * The face is live — the same crab engine as on the real screen — and the
 * mood chips drive it. The other views are captures of the real screen
 * (clock, weather, app drawer). While nobody touches the controls the screen
 * cycles through its views on its own, the way the real one rotates tiles;
 * the first tap hands control to the visitor for good.
 */

import { gsap } from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import { mountCrab } from "./crab.js";
import { t } from "./i18n.js";

// A 3x5 pixel font for the clock in the face's corner, like the real screen's.
const DIGITS = {
  0: "111101101101111",
  1: "010110010010111",
  2: "111001111100111",
  3: "111001111001111",
  4: "101101111001001",
  5: "111100111001111",
  6: "111100111101111",
  7: "111001001001001",
  8: "111101111101111",
  9: "111101111001111",
};

function drawClock(canvas) {
  const ctx = canvas.getContext("2d");
  const now = new Date();
  const text = `${String(now.getHours()).padStart(2, "0")}:${String(now.getMinutes()).padStart(2, "0")}`;
  ctx.clearRect(0, 0, canvas.width, canvas.height);
  ctx.fillStyle = getComputedStyle(canvas).getPropertyValue("--crab").trim() || "#d98263";
  let x = 0;
  for (const ch of text) {
    if (ch === ":") {
      ctx.fillRect(x, 1, 1, 1);
      ctx.fillRect(x, 3, 1, 1);
      x += 2;
      continue;
    }
    [...DIGITS[ch]].forEach((bit, i) => {
      if (bit === "1") ctx.fillRect(x + (i % 3), Math.floor(i / 3), 1, 1);
    });
    x += 4;
  }
}

const CYCLE = [
  ["face", "happy"],
  ["clock"],
  ["weather"],
  ["apps"],
  ["face", "love"],
];

export function initDevice({ reduced, finePointer }) {
  const section = document.querySelector("[data-device-section]");
  if (!section) return { refreshLabels() {} };
  const bot = section.querySelector("[data-bot]");
  const body = bot.querySelector(".bot__body");
  const moodLabel = section.querySelector("[data-bot-mood]");
  const views = new Map([...section.querySelectorAll("[data-bot-view]")].map((el) => [el.dataset.botView, el]));
  const viewButtons = [...section.querySelectorAll("[data-show-view]")];
  const moodButtons = [...section.querySelectorAll("[data-mood]")];
  const crab = mountCrab(section.querySelector('[data-crab="device"]'), { scale: 10, still: reduced });

  let current = "face";
  let mood = "idle";
  let auto = null;
  let handedOver = false;

  const showLabel = () => {
    moodLabel.textContent = mood === "idle" ? t("device.faceLabel") : t(`device.mood.${mood}`);
  };

  const setMood = (next) => {
    mood = next;
    crab.setEmotion(next);
    moodButtons.forEach((b) => b.classList.toggle("is-active", b.dataset.mood === next));
    showLabel();
  };

  const show = (name) => {
    if (name === current) return;
    const from = views.get(current);
    const to = views.get(name);
    current = name;
    viewButtons.forEach((b) => b.classList.toggle("is-active", b.dataset.showView === name));
    if (reduced) {
      from.classList.remove("is-active");
      to.classList.add("is-active");
      return;
    }
    to.classList.add("is-active");
    gsap.fromTo(to, { xPercent: 12, opacity: 0 }, { xPercent: 0, opacity: 1, duration: 0.6, ease: "expo.out" });
    gsap.to(from, {
      xPercent: -12,
      opacity: 0,
      duration: 0.45,
      ease: "power2.in",
      onComplete: () => {
        from.classList.remove("is-active");
        gsap.set(from, { clearProps: "all" });
      },
    });
  };

  const stopAuto = () => {
    handedOver = true;
    auto?.kill();
    auto = null;
  };

  let step = 0;
  const tick = () => {
    const [view, nextMood] = CYCLE[step % CYCLE.length];
    step += 1;
    if (nextMood) setMood(nextMood);
    show(view);
    auto = gsap.delayedCall(view === "face" ? 3.4 : 2.6, tick);
  };

  viewButtons.forEach((button) =>
    button.addEventListener("click", () => {
      stopAuto();
      show(button.dataset.showView);
    }),
  );
  moodButtons.forEach((button) =>
    button.addEventListener("click", () => {
      stopAuto();
      show("face");
      setMood(button.dataset.mood === mood ? "idle" : button.dataset.mood);
    }),
  );

  if (!reduced) {
    ScrollTrigger.create({
      trigger: bot,
      start: "top 75%",
      end: "bottom 15%",
      onToggle: (self) => {
        if (handedOver) return;
        if (self.isActive && !auto) auto = gsap.delayedCall(1.2, tick);
        else if (!self.isActive) {
          auto?.kill();
          auto = null;
        }
      },
    });
  }

  // The body tilts a little toward the pointer, like picking it up.
  if (finePointer && !reduced) {
    const rx = gsap.quickTo(body, "rotateX", { duration: 0.8, ease: "power3.out" });
    const ry = gsap.quickTo(body, "rotateY", { duration: 0.8, ease: "power3.out" });
    bot.addEventListener("pointermove", (event) => {
      const box = bot.getBoundingClientRect();
      ry(((event.clientX - box.left) / box.width - 0.5) * 12);
      rx(-((event.clientY - box.top) / box.height - 0.5) * 9);
    });
    bot.addEventListener("pointerleave", () => {
      rx(0);
      ry(0);
    });
  }

  // Counting up the numbers under the device.
  if (!reduced) {
    for (const dt of section.querySelectorAll("[data-count]")) {
      const target = Number(dt.dataset.count);
      const counter = { value: 0 };
      gsap.to(counter, {
        value: target,
        duration: 1.6,
        ease: "power3.out",
        scrollTrigger: { trigger: dt, start: "top 92%", once: true },
        onUpdate: () => {
          dt.textContent = String(Math.round(counter.value));
        },
      });
    }
  }

  const clock = section.querySelector("[data-pixel-clock]");
  if (clock) {
    drawClock(clock);
    window.setInterval(() => drawClock(clock), 15000);
  }

  showLabel();
  return { refreshLabels: showLabel };
}
