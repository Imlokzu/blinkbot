import { useCallback, useEffect, useRef, useState, type CSSProperties, type RefObject } from 'react';
import { createPortal } from 'react-dom';
import { ArrowUp } from 'lucide-react';
import { useMediaQuery } from '@/hooks/useMediaQuery';
import { useSendBubblePreference } from '@/hooks/useSendBubblePreference';
import styles from './SendBubble.module.css';

type Flight = { id: number; style: CSSProperties };

export function useSendBubbleEffect(root: RefObject<HTMLDivElement | null>) {
  const [enabled] = useSendBubblePreference();
  const reduced = useMediaQuery('(prefers-reduced-motion: reduce)');
  const [flights, setFlights] = useState<Flight[]>([]);
  const serial = useRef(0);
  const timers = useRef(new Map<number, ReturnType<typeof setTimeout>>());

  const clear = useCallback(() => {
    timers.current.forEach(clearTimeout);
    timers.current.clear();
  }, []);
  useEffect(() => clear, [clear]);
  useEffect(() => {
    if (!enabled || reduced) {
      clear();
      setFlights([]);
    }
  }, [enabled, reduced, clear]);

  const remove = useCallback((id: number) => {
    clearTimeout(timers.current.get(id));
    timers.current.delete(id);
    setFlights((current) => current.filter((flight) => flight.id !== id));
  }, []);

  const launch = useCallback(() => {
    if (!enabled || window.matchMedia('(prefers-reduced-motion: reduce)').matches) return;
    const button = root.current?.querySelector<HTMLButtonElement>('.prompt-bar__send');
    if (!button) return;
    // Read before onSend morphs the real button into Stop or clears the draft.
    const rect = button.getBoundingClientRect();
    if (!rect.width || !rect.height || rect.top < 16) return;
    const paint = getComputedStyle(button);
    const id = ++serial.current;
    const size = Math.min(rect.width, rect.height);
    const style = {
      left: rect.left + (rect.width - size) / 2,
      top: rect.top + (rect.height - size) / 2,
      width: size,
      height: size,
      // Use the armed palette even if its colour transition is still starting.
      backgroundColor: paint.getPropertyValue('--pb-ink').trim() || paint.backgroundColor,
      color: paint.getPropertyValue('--pb-bg').trim() || paint.color,
      '--send-rise': `${-Math.min(144, rect.top - 12)}px`,
    } as CSSProperties;
    setFlights((current) => [...current, { id, style }]);
    // Animation events can be suppressed in a hidden tab; still release it.
    timers.current.set(id, setTimeout(() => remove(id), 1000));
  }, [enabled, root, remove]);

  const overlay = flights.length ? createPortal(
    <div className={styles.overlay} aria-hidden="true">
      {flights.map((flight) => (
        <span key={flight.id} data-send-bubble="" className={styles.bubble} style={flight.style}
          onAnimationEnd={() => remove(flight.id)}>
          <ArrowUp size={16} strokeWidth={2.5} />
        </span>
      ))}
    </div>, document.body,
  ) : null;

  return { launch, overlay };
}
