/*
 * The dashboard tour: text on the left scrolls by while the frame on the
 * right stays put and changes what it shows. The last step is the phone, so
 * the frame itself changes shape — the browser window narrows and rounds off
 * into a handset, and back again on the way up.
 *
 * Only on wide screens. On a phone each step simply carries its own picture.
 */

import { gsap } from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";

const BAR = 38;

export function initTour({ reduced }) {
  const section = document.querySelector("[data-tour]");
  if (!section) return;
  const body = section.querySelector(".tour__body");
  const stage = section.querySelector("[data-tour-stage]");
  const frame = section.querySelector("[data-device-frame]");
  const bar = frame.querySelector(".device-frame__bar");
  const notch = frame.querySelector(".device-frame__notch");
  const dots = frame.querySelector(".window__dots");
  const screen = frame.querySelector(".device-frame__screen");
  const steps = [...section.querySelectorAll("[data-tour-step]")];
  const progress = section.querySelector("[data-tour-progress]");

  const mm = gsap.matchMedia();
  mm.add("(min-width: 961px)", () => {
    let active = -1;
    let phone = false;

    // Sizes come from the stage, which is as wide as its column.
    const sizes = () => {
      const width = stage.clientWidth;
      const desktop = { width, height: (width / 1440) * 1000 + BAR };
      const tall = Math.min(window.innerHeight * 0.74, 700);
      const handset = { width: (tall * 390) / 844, height: tall };
      return { desktop, handset };
    };

    const shape = (toPhone, animate) => {
      phone = toPhone;
      const { desktop, handset } = sizes();
      const target = toPhone ? handset : desktop;
      const vars = { duration: animate && !reduced ? 1 : 0, ease: "expo.inOut" };
      gsap.to(frame, { width: target.width, height: target.height, borderRadius: toPhone ? 44 : 16, ...vars });
      gsap.to(bar, { height: toPhone ? 0 : BAR, borderBottomWidth: toPhone ? 0 : 1, ...vars });
      gsap.to(screen, { top: toPhone ? 0 : BAR, ...vars });
      gsap.to(dots, { opacity: toPhone ? 0 : 1, ...vars, duration: vars.duration * 0.4 });
      gsap.to(notch, { opacity: toPhone ? 1 : 0, ...vars, delay: toPhone ? vars.duration * 0.5 : 0, duration: vars.duration * 0.5 });
    };

    const show = (index) => {
      if (index === active) return;
      const previous = active;
      active = index;
      steps.forEach((step, i) => step.classList.toggle("is-active", i === index));
      const name = steps[index].dataset.tourStep;
      const next = screen.querySelector(`[data-tour-shot="${name}"]`);
      const prev = previous >= 0 ? screen.querySelector(`[data-tour-shot="${steps[previous].dataset.tourStep}"]`) : null;

      if ((name === "mobile") !== phone) shape(name === "mobile", previous >= 0);
      if (!prev || reduced) {
        screen.querySelectorAll("img").forEach((img) => img.classList.toggle("is-active", img === next));
        return;
      }
      // The new picture wipes in from below over the old one.
      const down = index > previous;
      gsap.killTweensOf([next, prev]);
      next.classList.add("is-active");
      gsap.set(next, { zIndex: 2 });
      gsap.set(prev, { zIndex: 1 });
      gsap.fromTo(
        next,
        { clipPath: down ? "inset(100% 0% 0% 0%)" : "inset(0% 0% 100% 0%)", scale: 1.06 },
        {
          clipPath: "inset(0% 0% 0% 0%)",
          scale: 1,
          duration: 1,
          ease: "expo.inOut",
          onComplete: () => {
            prev.classList.remove("is-active");
            gsap.set([prev, next], { clearProps: "zIndex,clipPath,scale" });
          },
        },
      );
      gsap.fromTo(prev, { scale: 1 }, { scale: 0.96, duration: 1, ease: "expo.inOut" });
    };

    shape(false, false);
    show(0);

    const pin = ScrollTrigger.create({
      trigger: body,
      start: "top top",
      end: "bottom bottom",
      pin: stage,
      pinSpacing: false,
      invalidateOnRefresh: true,
      onRefresh: () => shape(phone, false),
      onUpdate: (self) => gsap.set(progress, { scaleX: self.progress }),
    });

    steps.forEach((step, i) =>
      ScrollTrigger.create({
        trigger: step,
        start: "top 55%",
        end: "bottom 55%",
        onToggle: (self) => self.isActive && show(i),
      }),
    );

    return () => {
      pin.kill();
      gsap.set([frame, bar, screen, dots, notch], { clearProps: "all" });
      screen.querySelectorAll("img").forEach((img) => gsap.set(img, { clearProps: "all" }));
    };
  });
}
