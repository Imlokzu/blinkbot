/*
 * The desk device cycles through real clock, weather and app-drawer captures.
 * The first interaction hands control to the visitor for the rest of the visit.
 */

import { gsap } from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";

const CYCLE = ["clock", "weather", "apps"];

export function initDevice({ reduced, finePointer }) {
  const section = document.querySelector("[data-device-section]");
  if (!section) return;
  const bot = section.querySelector("[data-bot]");
  const body = bot.querySelector(".bot__body");
  const views = new Map([...section.querySelectorAll("[data-bot-view]")].map((el) => [el.dataset.botView, el]));
  const viewButtons = [...section.querySelectorAll("[data-show-view]")];

  let current = "clock";
  let auto = null;
  let handedOver = false;

  const press = (buttons, isOn) =>
    buttons.forEach((b) => {
      b.classList.toggle("is-active", isOn(b));
      b.setAttribute("aria-pressed", String(isOn(b)));
    });

  const show = (name) => {
    if (name === current) return;
    const from = views.get(current);
    const to = views.get(name);
    current = name;
    press(viewButtons, (b) => b.dataset.showView === name);
    if (reduced) {
      from.classList.remove("is-active");
      to.classList.add("is-active");
      return;
    }
    // A tap during a transition must not let the older tween's cleanup
    // hide the view that is now on screen.
    gsap.killTweensOf([from, to]);
    to.classList.add("is-active");
    gsap.fromTo(to, { xPercent: 12, opacity: 0 }, { xPercent: 0, opacity: 1, duration: 0.6, ease: "expo.out" });
    gsap.to(from, {
      xPercent: -12,
      opacity: 0,
      duration: 0.45,
      ease: "power2.in",
      onComplete: () => {
        if (views.get(current) === from) return;
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

  let step = 1;
  const tick = () => {
    const view = CYCLE[step % CYCLE.length];
    step += 1;
    show(view);
    auto = gsap.delayedCall(2.6, tick);
  };

  viewButtons.forEach((button) =>
    button.addEventListener("click", () => {
      stopAuto();
      show(button.dataset.showView);
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
      dt.textContent = "0"; // so the count starts from what is already shown
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

}
