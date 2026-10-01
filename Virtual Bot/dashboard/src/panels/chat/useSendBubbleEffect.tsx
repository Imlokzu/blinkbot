import { useCallback, useEffect, useLayoutEffect, useRef, type RefObject } from 'react';
import { useMediaQuery } from '@/hooks/useMediaQuery';
import { useSendBubblePreference } from '@/hooks/useSendBubblePreference';
import styles from './SendBubble.module.css';

type Departure = {
  text: string;
  source: DOMRect;
  created: number;
  cancel: Set<() => void>;
};
// Scope departures to their composer, so another thread cannot consume them.
const departures = new WeakMap<Element, Departure>();

export function useSendBubbleEffect(root: RefObject<HTMLDivElement | null>) {
  const [enabled] = useSendBubblePreference();
  const reduced = useMediaQuery('(prefers-reduced-motion: reduce)');
  const cancel = useRef(new Set<() => void>());
  const departureScope = useRef<Element | null>(null);
  const clear = useCallback(() => {
    // React detaches the composer ref before running an unmount cleanup.
    const scope = departureScope.current;
    if (scope && departures.get(scope)?.cancel === cancel.current) departures.delete(scope);
    departureScope.current = null;
    cancel.current.forEach((stop) => stop());
    cancel.current.clear();
  }, []);
  useEffect(() => clear, [clear]);
  useEffect(() => { if (!enabled || reduced) clear(); }, [enabled, reduced, clear]);

  const launch = useCallback((text: string) => {
    if (!enabled || !text.trim() || window.matchMedia('(prefers-reduced-motion: reduce)').matches) return;
    const button = root.current?.querySelector<HTMLButtonElement>('.prompt-bar__send');
    const scope = root.current?.closest('.chat-conversation');
    if (!button || !scope) return;
    departureScope.current = scope;
    departures.set(scope, { text: text.trim(), source: button.getBoundingClientRect(),
      created: performance.now(), cancel: cancel.current });
  }, [enabled, root]);
  return { launch };
}

/** The flight uses the rendered message itself, including line breaks and attachments. */
export function useSentMessageFlight(root: RefObject<HTMLDivElement | null>, id: string, text: string) {
  // Keep the departure through StrictMode's setup/cleanup/setup replay.
  const departure = useRef<{ id: string; value: Departure } | null>(null);
  useLayoutEffect(() => {
    const target = root.current;
    const scope = target?.closest('.chat-conversation');
    if (!target?.isConnected || !scope) return;
    if (departure.current && (departure.current.id !== id || departure.current.value.text !== text.trim())) {
      departure.current = null;
    }
    const pending = departures.get(scope);
    if (!departure.current && pending?.text === text.trim() && performance.now() - pending.created < 800) {
      departure.current = { id, value: pending };
      departures.delete(scope);
    }
    const from = departure.current?.value;
    if (!from) return;

    const overlay = document.createElement('div');
    overlay.className = styles.overlay;
    overlay.setAttribute('aria-hidden', 'true');
    overlay.inert = true;
    const clone = target.cloneNode(true) as HTMLDivElement;
    clone.classList.remove('chat-bubble-in');
    clone.classList.add(styles.bubble);
    clone.removeAttribute('data-send-state');
    clone.removeAttribute('data-user-message');
    clone.removeAttribute('id');
    clone.querySelectorAll('[id]').forEach((node) => node.removeAttribute('id'));
    clone.dataset.sendBubble = id;
    overlay.appendChild(clone);
    target.dataset.sendState = 'flying';
    document.body.appendChild(overlay);

    let frame = 0;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let reveal: ReturnType<typeof setTimeout> | undefined;
    let stopped = false;
    const stop = (replay = false) => {
      if (!replay) departure.current = null;
      if (stopped) return;
      stopped = true;
      cancelAnimationFrame(frame);
      clearTimeout(timer);
      clearTimeout(reveal);
      clone.removeEventListener('animationend', finish);
      clone.removeEventListener('animationcancel', finish);
      overlay.remove();
      target.dataset.sendState = 'landed';
      from.cancel.delete(stop);
    };
    const finish = (event: AnimationEvent) => {
      if (event.target === clone && !event.pseudoElement) stop();
    };
    from.cancel.add(stop);
    clone.addEventListener('animationend', finish);
    clone.addEventListener('animationcancel', finish);

    // Follow the real destination while assistant-ui scrolls to the new message.
    const position = () => {
      if (stopped || !target.isConnected) return stop();
      const rect = target.getBoundingClientRect();
      clone.style.left = `${rect.left}px`;
      clone.style.top = `${rect.top}px`;
      clone.style.width = `${rect.width}px`;
      clone.style.height = `${rect.height}px`;
      clone.style.setProperty('--send-x', `${from.source.x + from.source.width / 2 - rect.x - rect.width / 2}px`);
      clone.style.setProperty('--send-y', `${from.source.y + from.source.height / 2 - rect.y - rect.height / 2}px`);
      frame = requestAnimationFrame(position);
    };
    position();
    // The readable message remains as its travelling copy dissolves in place.
    reveal = setTimeout(() => { target.dataset.sendState = 'landed'; }, 550);
    timer = setTimeout(() => stop(), 1000);
    return () => stop(true);
  }, [root, id, text]);
}
