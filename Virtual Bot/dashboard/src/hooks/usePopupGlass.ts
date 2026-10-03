import { useEffect } from 'react';
import '@/vendor/hyalite/hyalite.js';
import type { HyaliteOptions } from '@/vendor/hyalite/hyalite.js';
import { applyPopupGlassTargets, POPUP_SURFACE_SELECTOR, popupGlassKind } from './popupGlassPreferences';
import { usePopupGlassTargets } from './usePopupGlassPreference';
import './popup-glass-motion.css';

/*
 * Popups keep their solid plate. Only categories selected in Appearance
 * carry a lens; unsupported SVG filters still use the existing CSS blur.
 *
 * Radix positions a menu by transforming an outer wrapper. A lens inside
 * that wrapper cannot see the page, so the card stays dark. The lens goes
 * on the wrapper. Menus with no transformed parent (the command palette,
 * the prompt bar) carry the lens themselves.
 */
const lens: HyaliteOptions = {
  bevel: 10,
  thickness: 46,
  slope: 1.4,
  shape: 'squircle',
  blur: 6,
  dispersion: 0.4,
  shade: 0.34,
  rim: 2.2,
  edgeW: 6,
  sat: 0.95,
  edge: 1.05,
  light: -16,
  smooth: 0,
  settle: 80,
};

const motionAttribute = 'data-popup-glass-motion';
const positionProperty = '--popup-glass-position';
type InlineStyle = { value: string; priority: string };
type MotionStyles = { attribute: string | null; position: InlineStyle; origin: InlineStyle };
type ArmedSurface = { radius: string; priority: string; wrapper: boolean; shell: HTMLElement; motion: MotionStyles | null };

const inlineStyle = (target: HTMLElement, property: string): InlineStyle => ({
  value: target.style.getPropertyValue(property),
  priority: target.style.getPropertyPriority(property),
});
const restoreStyle = (target: HTMLElement, property: string, previous: InlineStyle) => {
  if (previous.value) target.style.setProperty(property, previous.value, previous.priority);
  else target.style.removeProperty(property);
};

function releaseMotion(target: HTMLElement, previous: ArmedSurface) {
  if (!previous.motion) return;
  const { attribute, position, origin } = previous.motion;
  if (attribute === null) target.removeAttribute(motionAttribute);
  else target.setAttribute(motionAttribute, attribute);
  restoreStyle(target, positionProperty, position);
  restoreStyle(target, 'transform-origin', origin);
  previous.motion = null;
}

function syncMotion(target: HTMLElement, previous: ArmedSurface) {
  const shell = previous.shell;
  const state = shell.getAttribute('data-state');
  if (!previous.wrapper || shell.parentElement !== target || !shell.matches('.u-pop') || (state !== 'open' && state !== 'closed')) {
    releaseMotion(target, previous);
    return;
  }
  previous.motion ??= {
    attribute: target.getAttribute(motionAttribute),
    position: inlineStyle(target, positionProperty),
    origin: inlineStyle(target, 'transform-origin'),
  };
  // Radix owns the inline translation. Compose motion after it so scaling
  // never scales the page coordinates or pulls the popup off its trigger.
  const position = target.style.transform && target.style.transform !== 'none'
    ? target.style.transform : 'translate(0px, 0px)';
  if (target.style.getPropertyValue(positionProperty) !== position) target.style.setProperty(positionProperty, position);
  const origin = getComputedStyle(shell).transformOrigin;
  if (target.style.transformOrigin !== origin) target.style.transformOrigin = origin;
  if (target.getAttribute(motionAttribute) !== state) target.setAttribute(motionAttribute, state);
}

export function usePopupGlass() {
  const targets = usePopupGlassTargets();
  useEffect(() => {
    applyPopupGlassTargets(document.documentElement, targets);
    const reducedTransparency = window.matchMedia('(prefers-reduced-transparency: reduce)');
    let watcher: { stop(): void } | null = null;
    let observer: MutationObserver | null = null;
    const armed = new Map<HTMLElement, ArmedSurface>();
    const addedShellClasses = new Set<HTMLElement>();

    const release = (target: HTMLElement) => {
      const previous = armed.get(target);
      if (!previous) return;
      releaseMotion(target, previous);
      target.classList.remove('popup-lens');
      if (previous.wrapper) {
        if (previous.radius) target.style.setProperty('border-radius', previous.radius, previous.priority);
        else target.style.removeProperty('border-radius');
      }
      armed.delete(target);
    };

    const syncSurfaces = () => {
      const desired = new Map<HTMLElement, HTMLElement>();
      const shellClasses = new Set<HTMLElement>();
      document.body.querySelectorAll<HTMLElement>(POPUP_SURFACE_SELECTOR).forEach(shell => {
        const kind = popupGlassKind(shell);
        if (!kind || !targets.includes(kind)) return;
        const target = shell.closest<HTMLElement>('[data-radix-popper-content-wrapper]') ?? shell;
        if (!desired.has(target)) desired.set(target, shell);
        if (shell.matches('.prompt-bar__menu, [data-popup-root]')) shellClasses.add(shell);
      });
      // A nested content shell and its Radix wrapper describe one visual surface.
      // Nested selected surfaces share the outer lens instead of stacking filters.
      for (const target of desired.keys()) {
        for (let ancestor = target.parentElement; ancestor; ancestor = ancestor.parentElement) {
          if (desired.has(ancestor)) { desired.delete(target); break; }
        }
      }
      for (const target of armed.keys()) if (!desired.has(target)) release(target);
      for (const shell of addedShellClasses) {
        if (!shellClasses.has(shell)) { shell.classList.remove('popup-shell'); addedShellClasses.delete(shell); }
      }
      for (const shell of shellClasses) {
        if (!shell.classList.contains('popup-shell')) { shell.classList.add('popup-shell'); addedShellClasses.add(shell); }
      }
      for (const [target, shell] of desired) {
        if (!armed.has(target)) {
          const wrapper = target !== shell;
          armed.set(target, {
            radius: target.style.getPropertyValue('border-radius'),
            priority: target.style.getPropertyPriority('border-radius'),
            wrapper,
            shell,
            motion: null,
          });
          target.classList.add('popup-lens');
        }
        armed.get(target)!.shell = shell;
        syncMotion(target, armed.get(target)!);
        if (target !== shell) {
          const radius = getComputedStyle(shell).borderRadius;
          if (radius && target.style.borderRadius !== radius) {
            target.style.borderRadius = radius;
            // Hyalite observes size, so a radius-only change needs a rebuilt displacement map.
            window.Hyalite?.refresh(target);
          }
        }
      }
    };

    const stop = () => {
      observer?.disconnect();
      observer = null;
      watcher?.stop();
      watcher = null;
      for (const target of armed.keys()) release(target);
      for (const shell of addedShellClasses) shell.classList.remove('popup-shell');
      addedShellClasses.clear();
    };

    const start = () => {
      stop();
      if (!targets.length || reducedTransparency.matches) return;
      syncSurfaces();
      const relevantNode = (node: Node): boolean => node instanceof Element && (
        node.matches(`${POPUP_SURFACE_SELECTOR}, .popup-lens`) || Boolean(node.querySelector(POPUP_SURFACE_SELECTOR))
      );
      observer = new MutationObserver((records) => {
        const relevant = records.some(record => record.type === 'attributes'
          ? relevantNode(record.target) || armed.has(record.target as HTMLElement) || addedShellClasses.has(record.target as HTMLElement)
            || [...armed.values()].some(previous => previous.shell === record.target)
          : [...record.addedNodes, ...record.removedNodes].some(relevantNode));
        if (relevant) syncSurfaces();
      });
      observer.observe(document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ['class', 'data-state', 'style', 'data-popup-kind', 'data-kind', 'data-popup-root'] });
      const Hyalite = window.Hyalite;
      if (Hyalite?.supported()) watcher = Hyalite.watch(document.body, '.popup-lens', lens);
    };

    start();
    reducedTransparency.addEventListener('change', start);
    return () => {
      reducedTransparency.removeEventListener('change', start);
      stop();
    };
  }, [targets]);
}
