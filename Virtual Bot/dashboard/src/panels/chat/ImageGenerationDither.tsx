// Dither-field treatment adapted from beUI. See licenses/beui-MIT.txt.
// https://beui.dev/components/agents/image-generation
import { useEffect, useRef } from 'react';

export function ImageGenerationDither({ reduced }: { reduced: boolean }) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  useEffect(() => {
    const canvas = canvasRef.current;
    const context = canvas?.getContext('2d');
    if (!canvas || !context) return;
    let frame = 0;
    let width = 0;
    let height = 0;
    let color = '';
    let previous = 0;
    let visible = true;
    let disposed = false;
    const pointer = { x: 0, y: 0, targetX: 0, targetY: 0, inside: false };
    const finePointer = window.matchMedia('(hover: hover) and (pointer: fine)').matches;

    const resize = () => {
      const rect = canvas.getBoundingClientRect();
      width = rect.width;
      height = rect.height;
      const dpr = Math.min(window.devicePixelRatio || 1, 2);
      canvas.width = Math.round(width * dpr);
      canvas.height = Math.round(height * dpr);
      context.setTransform(dpr, 0, 0, dpr, 0, 0);
      color = getComputedStyle(canvas).color;
      pointer.x = pointer.targetX = width / 2;
      pointer.y = pointer.targetY = height / 2;
      if (reduced) draw(0);
    };
    const draw = (time: number) => {
      context.clearRect(0, 0, width, height);
      if (!pointer.inside) {
        pointer.targetX = width / 2 + (reduced ? 0 : Math.sin(time / 1700) * width * 0.12);
        pointer.targetY = height / 2 + (reduced ? 0 : Math.cos(time / 2100) * height * 0.1);
      }
      const follow = reduced ? 1 : pointer.inside ? 0.16 : 0.07;
      pointer.x += (pointer.targetX - pointer.x) * follow;
      pointer.y += (pointer.targetY - pointer.y) * follow;
      const radius = Math.min(width, height) * 0.38;
      const columns = Math.ceil(width / 10) + 1;
      const rows = Math.ceil(height / 10) + 1;
      const offsetX = (width - (columns - 1) * 10) / 2;
      const offsetY = (height - (rows - 1) * 10) / 2;
      context.fillStyle = color;
      for (let row = 0; row < rows; row++) {
        for (let column = 0; column < columns; column++) {
          const anchorX = offsetX + column * 10;
          const anchorY = offsetY + row * 10;
          const deltaX = anchorX - pointer.x;
          const deltaY = anchorY - pointer.y;
          const distance = Math.hypot(deltaX, deltaY);
          const proximity = radius ? Math.max(0, 1 - distance / radius) : 0;
          const influence = proximity * proximity * (3 - 2 * proximity);
          const displacement = influence * influence * 9;
          context.globalAlpha = 0.13 + influence * 0.68;
          context.beginPath();
          context.arc(anchorX + (distance ? deltaX / distance : 0) * displacement,
            anchorY + (distance ? deltaY / distance : 0) * displacement,
            0.65 + influence * 0.85, 0, Math.PI * 2);
          context.fill();
        }
      }
      context.globalAlpha = 1;
    };
    const tick = (time: number) => {
      frame = 0;
      if (disposed || reduced || document.hidden || !visible) return;
      if (time - previous >= 32) { draw(time); previous = time; }
      frame = requestAnimationFrame(tick);
    };
    const resume = () => {
      cancelAnimationFrame(frame);
      frame = 0;
      if (!disposed && !reduced && !document.hidden && visible) frame = requestAnimationFrame(tick);
    };
    const move = (event: PointerEvent) => {
      if (reduced || !finePointer) return;
      const rect = canvas.getBoundingClientRect();
      pointer.inside = true;
      pointer.targetX = event.clientX - rect.left;
      pointer.targetY = event.clientY - rect.top;
    };
    const leave = () => { pointer.inside = false; };
    const theme = new MutationObserver(() => { color = getComputedStyle(canvas).color; if (reduced) draw(0); });
    theme.observe(document.documentElement, { attributes: true, attributeFilter: ['class', 'data-theme', 'data-accent', 'style'] });
    const size = new ResizeObserver(resize);
    const intersection = new IntersectionObserver(([entry]) => { visible = entry.isIntersecting; resume(); });
    resize();
    draw(0);
    size.observe(canvas);
    intersection.observe(canvas);
    document.addEventListener('visibilitychange', resume);
    canvas.addEventListener('pointermove', move, { passive: true });
    canvas.addEventListener('pointerleave', leave);
    resume();
    return () => {
      disposed = true;
      cancelAnimationFrame(frame);
      size.disconnect(); intersection.disconnect(); theme.disconnect();
      document.removeEventListener('visibilitychange', resume);
      canvas.removeEventListener('pointermove', move);
      canvas.removeEventListener('pointerleave', leave);
    };
  }, [reduced]);
  return <canvas ref={canvasRef} className="image-generation-dither" aria-hidden="true" />;
}
