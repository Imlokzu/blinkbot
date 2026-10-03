import { useCallback, useLayoutEffect, useRef, useState } from 'react';
import './disclosure-motion.css';

export type DisclosurePhase = 'opening' | 'open' | 'closing' | 'closed';

/** Explicit height avoids grid-track jumps when a nested log has been scrolled. */
export function useDisclosureMotion(initialOpen = false) {
  const ref = useRef<HTMLDivElement>(null);
  const [open, setOpen] = useState(initialOpen);
  const [phase, setPhase] = useState<DisclosurePhase>(initialOpen ? 'open' : 'closed');
  const desired = useRef(open);
  desired.current = open;
  const state = useRef<{
    node: HTMLDivElement | null;
    animation: Animation | null;
    observer: ResizeObserver | null;
    open: boolean;
    generation: number;
    target: number;
  }>({ node: null, animation: null, observer: null, open: initialOpen, generation: 0, target: 0 });

  const naturalHeight = useCallback((node: HTMLDivElement) => {
    const content = node.firstElementChild;
    if (!content) return 0;
    const css = getComputedStyle(content);
    return content.getBoundingClientRect().height
      + (parseFloat(css.marginTop) || 0) + (parseFloat(css.marginBottom) || 0);
  }, []);

  const animate = useCallback(() => {
    const current = state.current;
    const node = current.node;
    if (!node) return;
    // Capture the in-flight height before cancelling, so reversal never snaps.
    const start = node.getBoundingClientRect().height;
    const target = desired.current ? naturalHeight(node) : 0;
    const version = ++current.generation;
    current.animation?.cancel();
    current.animation = null;
    current.open = desired.current;
    current.target = target;
    node.scrollTop = 0;
    node.style.height = `${start}px`;
    const finish = () => {
      if (version !== state.current.generation || state.current.node !== node) return;
      node.style.height = desired.current ? 'auto' : '0px';
      node.scrollTop = 0;
      current.animation?.cancel();
      current.animation = null;
      setPhase(desired.current ? 'open' : 'closed');
    };
    if (Math.abs(start - target) < .5 || window.matchMedia('(prefers-reduced-motion: reduce)').matches
      || typeof node.animate !== 'function') {
      finish();
      return;
    }
    setPhase(desired.current ? 'opening' : 'closing');
    const animation = node.animate([{ height: `${start}px` }, { height: `${target}px` }], {
      duration: desired.current ? 220 : 240,
      easing: 'cubic-bezier(0.22, 1, 0.36, 1)',
      fill: 'both',
    });
    current.animation = animation;
    void animation.finished.then(finish).catch(() => { /* A reversal owns the next animation. */ });
  }, [naturalHeight]);

  useLayoutEffect(() => {
    const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)');
    const settle = () => {
      // Native preference changes also stop an animation that is already running.
      if (reducedMotion.matches && state.current.animation) animate();
    };
    reducedMotion.addEventListener('change', settle);
    return () => reducedMotion.removeEventListener('change', settle);
  }, [animate]);

  const release = useCallback(() => {
    const current = state.current;
    current.generation++;
    current.animation?.cancel();
    current.observer?.disconnect();
    if (current.node) {
      current.node.style.removeProperty('height');
      delete current.node.dataset.disclosureMotion;
    }
    current.node = null;
    current.animation = null;
    current.observer = null;
  }, []);

  // A closed disclosure may acquire its DOM only when results arrive later.
  useLayoutEffect(() => {
    const node = ref.current;
    const current = state.current;
    if (node !== current.node) {
      release();
      if (!node) return;
      current.node = node;
      current.open = desired.current;
      node.dataset.disclosureMotion = '';
      node.style.height = desired.current ? 'auto' : '0px';
      setPhase(desired.current ? 'open' : 'closed');
      if (typeof ResizeObserver !== 'undefined' && node.firstElementChild) {
        current.observer = new ResizeObserver(() => {
          if (current.animation && desired.current && Math.abs(naturalHeight(node) - current.target) > .5) animate();
        });
        current.observer.observe(node.firstElementChild);
      }
    } else if (node && current.open !== open) animate();
  });
  useLayoutEffect(() => release, [release]);
  const toggle = useCallback(() => setOpen(value => !value), []);
  return { ref, open, phase, setOpen, toggle };
}
