/* Chromatic Drift, adapted from the owner's canvas sketch. The field stays
 * behind the hero; colour-batched strokes and bounded resolution keep the
 * same fine trails affordable on phones. */

const HUES = [38, 12, 199, 340, 155];
const BACKGROUND = "#070605";
const FRAME_MS = 1000 / 30;

function flowField(seed) {
  const random = () => {
    seed = (seed + 0x6d2b79f5) | 0;
    let value = Math.imul(seed ^ (seed >>> 15), 1 | seed);
    value = (value + Math.imul(value ^ (value >>> 7), 61 | value)) ^ value;
    return ((value ^ (value >>> 14)) >>> 0) / 4294967296;
  };
  const values = Float32Array.from({ length: 256 }, random);
  const order = Array.from({ length: 256 }, (_, index) => index);
  for (let i = 255; i > 0; i--) {
    const j = Math.floor(random() * (i + 1));
    [order[i], order[j]] = [order[j], order[i]];
  }
  const perm = Uint8Array.from({ length: 512 }, (_, i) => order[i & 255]);
  const fade = (v) => v * v * v * (v * (v * 6 - 15) + 10);
  const noise = (x, y) => {
    const floorX = Math.floor(x), floorY = Math.floor(y);
    const xi = floorX & 255, yi = floorY & 255;
    const u = fade(x - floorX), v = fade(y - floorY);
    const aa = values[(perm[xi] + perm[yi]) & 255];
    const ba = values[(perm[xi + 1] + perm[yi]) & 255];
    const ab = values[(perm[xi] + perm[yi + 1]) & 255];
    const bb = values[(perm[xi + 1] + perm[yi + 1]) & 255];
    return (aa + u * (ba - aa)) * (1 - v) + (ab + u * (bb - ab)) * v;
  };
  return (x, y, time) => (
    noise(x * 0.0016 + time, y * 0.0016 - time * 0.7) * 2.6
    + noise(x * 0.004 - time * 0.5, y * 0.004 + time * 0.3) * 1.4
  ) * Math.PI * 2;
}

export function initDrift(hero) {
  const canvas = hero?.querySelector("[data-drift]");
  const toggle = hero?.querySelector("[data-drift-toggle]");
  const context = canvas?.getContext("2d", { alpha: false });
  if (!context || !toggle) return () => {};

  const host = canvas.parentElement;
  const motion = window.matchMedia("(prefers-reduced-motion: reduce)");
  const coarse = window.matchMedia("(pointer: coarse)");
  const angle = flowField(Math.floor(Math.random() * 65536));
  const groups = HUES.flatMap((hue) => [
    { colour: `hsla(${hue}, 72%, 56%, 0.42)`, wide: false, particles: [] },
    { colour: `hsla(${hue}, 72%, 62%, 0.2)`, wide: true, particles: [] },
  ]);
  let width = 0, height = 0, ratio = 0, frame = 0;
  let request = null, lastTime = 0;
  let visible = false, paused = false, pageHidden = false, contextLost = false, destroyed = false;

  function reset(particle) {
    particle.x = Math.random() * width;
    particle.y = Math.random() * height;
    particle.life = 220 + Math.floor(Math.random() * 420);
  }

  function draw() {
    frame++;
    context.globalCompositeOperation = "source-over";
    context.fillStyle = "rgba(7, 6, 5, 0.045)";
    context.fillRect(0, 0, width, height);
    context.globalCompositeOperation = "lighter";
    for (const group of groups) {
      context.strokeStyle = group.colour;
      context.lineWidth = group.wide ? 1.8 : 0.9;
      context.beginPath();
      for (const particle of group.particles) {
        const x = particle.x, y = particle.y;
        const direction = angle(x, y, frame * 0.0011);
        particle.x += Math.cos(direction) * particle.speed;
        particle.y += Math.sin(direction) * particle.speed;
        if (--particle.life <= 0 || particle.x < -8 || particle.x > width + 8
          || particle.y < -8 || particle.y > height + 8) {
          reset(particle);
          continue;
        }
        context.moveTo(x, y);
        context.lineTo(particle.x, particle.y);
      }
      context.stroke();
    }
  }

  function stop() {
    if (request !== null) window.cancelAnimationFrame(request);
    request = null;
    lastTime = 0;
  }

  function tick(time) {
    if (destroyed) return;
    if (time - lastTime >= FRAME_MS) {
      draw();
      lastTime = time - ((time - lastTime) % FRAME_MS);
    }
    request = window.requestAnimationFrame(tick);
  }

  function sync() {
    if (destroyed) return;
    toggle.hidden = motion.matches;
    toggle.setAttribute("aria-pressed", String(paused));
    if (paused || motion.matches || !visible || document.hidden || pageHidden || contextLost || !width || !height) {
      stop();
    } else if (request === null) {
      request = window.requestAnimationFrame(tick);
    }
  }

  function resize(force = false) {
    if (destroyed || contextLost) return;
    const box = host.getBoundingClientRect();
    const nextWidth = Math.round(box.width), nextHeight = Math.round(box.height);
    // Cap both pixel density and total backing pixels, including large monitors.
    const nextRatio = Math.min(window.devicePixelRatio || 1, 1.5,
      Math.sqrt(1800000 / Math.max(1, nextWidth * nextHeight)));
    if (!force && width === nextWidth && height === nextHeight && ratio === nextRatio) return;
    width = nextWidth;
    height = nextHeight;
    ratio = nextRatio;
    canvas.width = Math.round(width * ratio);
    canvas.height = Math.round(height * ratio);
    context.setTransform(ratio, 0, 0, ratio, 0, 0);
    context.globalCompositeOperation = "source-over";
    context.fillStyle = BACKGROUND;
    context.fillRect(0, 0, width, height);
    for (const group of groups) group.particles = [];
    const count = Math.min(coarse.matches ? 2600 : 11000, Math.round(width * height / 75));
    for (let i = 0; i < count; i++) {
      const particle = { speed: 0.55 + Math.random() * 1.3 };
      reset(particle);
      const group = Math.floor(Math.random() * HUES.length) * 2 + (Math.random() < 0.06 ? 1 : 0);
      groups[group].particles.push(particle);
    }
    // Paint a complete still immediately, including when motion is disabled.
    for (let i = 0; i < 20; i++) draw();
    sync();
  }

  const onToggle = () => { paused = !paused; sync(); };
  const onResize = () => resize();
  const onMotion = () => { resize(true); sync(); };
  const onPageHide = () => { pageHidden = true; sync(); };
  const onPageShow = () => { pageHidden = false; sync(); };
  const onContextLost = (event) => { event.preventDefault(); contextLost = true; sync(); };
  const onContextRestored = () => { contextLost = false; resize(true); };
  const intersection = new IntersectionObserver(([entry]) => { visible = entry.isIntersecting; sync(); });
  const observer = new ResizeObserver(onResize);

  toggle.addEventListener("click", onToggle);
  motion.addEventListener("change", onMotion);
  document.addEventListener("visibilitychange", sync);
  window.addEventListener("resize", onResize);
  window.addEventListener("pagehide", onPageHide);
  window.addEventListener("pageshow", onPageShow);
  canvas.addEventListener("contextlost", onContextLost);
  canvas.addEventListener("contextrestored", onContextRestored);
  resize();
  intersection.observe(host);
  observer.observe(host);

  return () => {
    // Observer notifications queued before disconnect must not restart the field.
    destroyed = true;
    stop();
    intersection.disconnect();
    observer.disconnect();
    toggle.hidden = true;
    toggle.removeEventListener("click", onToggle);
    motion.removeEventListener("change", onMotion);
    document.removeEventListener("visibilitychange", sync);
    window.removeEventListener("resize", onResize);
    window.removeEventListener("pagehide", onPageHide);
    window.removeEventListener("pageshow", onPageShow);
    canvas.removeEventListener("contextlost", onContextLost);
    canvas.removeEventListener("contextrestored", onContextRestored);
  };
}
