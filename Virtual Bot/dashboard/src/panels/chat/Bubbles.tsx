import { createContext, useCallback, useContext, useEffect, useId, useRef, useState, type CSSProperties, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { TextMessagePartProvider } from '@assistant-ui/react';
import { SmilePlus } from '../../vendor/solar-icons/compat.ts';
import { t } from '@/locales/chat';
import { cn } from '@/lib/cn';
import { Markdown } from './Markdown';
import { REPLY_ATTRIBUTE } from './SelectionActions';
export { ActivityLine } from './ActivityTree';

/*
 * The pieces of a messenger-style reply: short bubbles, the line that says
 * what the bot is doing right now, typing dots, and emoji reactions.
 *
 * Two motions carry the messenger feel. The typing dots shrink away inside
 * their own bubble (and a reply that replaces them crossfades out of those
 * dots). When the bot reacts, three beats follow: the text bubble rises and
 * slowly turns from a pill into a circle, the emoji appears, then that
 * circle travels faster and lands as the reaction. A reaction the person
 * picks flies from the button instead.
 *
 * Reactions are content, not icons, so emoji are allowed here even though
 * DESIGN.md bans them as interface icons.
 */

/** How long the typing bubble takes to collapse. The runtime holds a
 *  reaction-only draft at least this long, so the collapse is not cut off.
 *  Kept in step with `chat-typing-out` in base.css. */
export const TYPING_LEAVE_MS = 780;

/** Rise and pill-to-circle. Slow, and it finishes before anything travels. */
const TRANSFORM_MS = 1100;
/** The emoji shows in the finished circle, then the trip starts. */
const EMOJI_MS = 240;
/** The circle's trip to the message. Shorter than the transform on purpose. */
const TRAVEL_MS = 380;
/** Kept in step with the morph fades in base.css. */
const MORPH_MS = TRANSFORM_MS + EMOJI_MS + TRAVEL_MS;
/** Kept in step with `chat-emoji-flight` in base.css. */
const FLIGHT_MS = 1100;
/** Chip stays hidden until the circle lands. Matches `.chat-reaction-land`. */
export const LAND_DELAY_MS = MORPH_MS - 40;
/** A reaction picked from the button arrives with the shorter flight. */
export const FLIGHT_LAND_MS = 920;

export function typingLeaveMs(): number {
  if (typeof window === 'undefined') return TYPING_LEAVE_MS;
  return window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 1 : TYPING_LEAVE_MS;
}

const QUICK_REACTIONS = ['👍', '❤️', '😂', '😮', '😢', '🔥', '🙏', '👏'] as const;

export function TypingBubble({ leaving = false }: { leaving?: boolean }) {
  return (
    <div
      role="status"
      aria-label={leaving ? undefined : t('typing.aria')}
      aria-hidden={leaving || undefined}
      data-typing=""
      className={cn(
        'chat-bubble-in flex h-9 items-center gap-1.5 rounded-full bg-surface-2 px-4',
        leaving && 'chat-typing-out',
      )}
    >
      <span className="chat-dot size-2 rounded-full bg-ink-3" />
      <span className="chat-dot size-2 rounded-full bg-ink-3" />
      <span className="chat-dot size-2 rounded-full bg-ink-3" />
    </div>
  );
}

/*
 * A reaction travels as one fixed emoji, then the chip fades in where it
 * lands. Fixed, and portaled to the body, because the thread scrolls and a
 * transformed ancestor would otherwise trap it.
 */
type FlightSpec = {
  emoji: string;
  x: number; y: number;
  dx: number; dy: number;
  mx: number; my: number;
};

const FlightContext = createContext<(spec: FlightSpec) => boolean>(() => false);

export function EmojiFlights({ children }: { children: ReactNode }) {
  const [flights, setFlights] = useState<(FlightSpec & { id: number })[]>([]);
  const launch = useCallback((spec: FlightSpec) => {
    if (typeof window !== 'undefined' && window.matchMedia('(prefers-reduced-motion: reduce)').matches) return false;
    const id = flightSeq++;
    setFlights((current) => [...current, { ...spec, id }]);
    window.setTimeout(() => {
      setFlights((current) => current.filter((item) => item.id !== id));
    }, FLIGHT_MS + 20);
    return true;
  }, []);
  return (
    <FlightContext value={launch}>
      {children}
      {typeof document !== 'undefined' ? createPortal(
        flights.map((flight) => (
          <span
            key={flight.id}
            aria-hidden
            className="chat-emoji-flight"
            style={{
              '--x': flight.x,
              '--y': flight.y,
              '--dx': flight.dx,
              '--dy': flight.dy,
              '--mx': flight.mx,
              '--my': flight.my,
            } as CSSProperties}
          >{flight.emoji}</span>
        )),
        document.body,
      ) : null}
    </FlightContext>
  );
}

let flightSeq = 0;

export function useEmojiFlight() {
  return useContext(FlightContext);
}

function centerOf(rect: DOMRect) {
  return { x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 };
}

/** Where the chip sits: centered on the bubble's bottom edge, inset from a side. */
export function reactionTarget(bubble: DOMRect, align: 'start' | 'end') {
  return {
    x: align === 'start' ? bubble.left + 22 : bubble.right - 22,
    y: bubble.bottom,
  };
}

/** Bow the path sideways so a picked emoji arcs instead of sliding straight.
 *  `lift` is the text bubble that is leaving. It rises and slowly becomes a
 *  circle, the emoji appears, and only then does the circle travel — faster —
 *  onto the message. The bot mark stays where it is. */
export function flyEmoji(
  launch: (spec: FlightSpec) => boolean,
  emoji: string,
  from: DOMRect,
  to: { x: number; y: number },
  lift?: Element | null,
): boolean {
  if (typeof window !== 'undefined' && window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
    return false;
  }
  const send = (origin: DOMRect) => {
    const start = centerOf(origin);
    const dx = to.x - start.x;
    const dy = to.y - start.y;
    const len = Math.hypot(dx, dy) || 1;
    const bow = Math.max(64, Math.min(120, len * 0.62));
    return launch({
      emoji,
      x: start.x,
      y: start.y,
      dx,
      dy,
      mx: dx * 0.5 + (-dy / len) * bow,
      my: dy * 0.5 + (dx / len) * bow,
    });
  };
  if (!lift || !lift.isConnected) return send(from);
  morphBubble(lift, emoji, to);
  return true;
}

/** A fixed copy of the text bubble, so the real message (and the bot mark)
 *  stay put while the copy becomes the reaction. */
function morphBubble(bubble: Element, emoji: string, to: { x: number; y: number }) {
  const rect = bubble.getBoundingClientRect();
  if (rect.width < 1 || rect.height < 1) return;
  const ghost = bubble.cloneNode(true) as HTMLElement;
  ghost.classList.remove('chat-bubble-in', 'chat-bubble-open', 'chat-typing-out');
  ghost.classList.add('chat-morph');
  ghost.removeAttribute('data-typing');
  ghost.setAttribute('aria-hidden', 'true');
  const badge = document.createElement('span');
  badge.className = 'chat-morph-emoji';
  badge.textContent = emoji;
  ghost.appendChild(badge);
  const radius = getComputedStyle(bubble).borderRadius || '12px';
  ghost.style.animation = 'none';
  ghost.style.position = 'fixed';
  ghost.style.margin = '0';
  ghost.style.zIndex = '40';
  ghost.style.pointerEvents = 'none';
  ghost.style.overflow = 'hidden';
  ghost.style.padding = '0';
  document.body.appendChild(ghost);
  // The typing pill is on its way out; the copy is the thing that travels.
  if (bubble instanceof HTMLElement && bubble.hasAttribute('data-typing')) {
    bubble.style.visibility = 'hidden';
  }

  const width = rect.width;
  const height = rect.height;
  const circle = Math.round(Math.max(32, Math.min(height, 40)));
  const chip = 22;
  const startCx = rect.left + width / 2;
  const startCy = rect.top + height / 2;
  const risenCy = startCy - 26;
  const dx = to.x - startCx;
  const dy = to.y - risenCy;
  const len = Math.hypot(dx, dy) || 1;
  const bow = Math.min(22, len * 0.14);
  const cpx = startCx + dx * 0.5 + (-dy / len) * bow;
  const cpy = risenCy + dy * 0.5 + (dx / len) * bow;
  const along = (t: number) => {
    const u = 1 - t;
    return {
      x: u * u * startCx + 2 * u * t * cpx + t * t * to.x,
      y: u * u * risenCy + 2 * u * t * cpy + t * t * to.y,
    };
  };
  const box = (cx: number, cy: number, w: number, h: number, round: string) => ({
    left: `${cx - w / 2}px`,
    top: `${cy - h / 2}px`,
    width: `${w}px`,
    height: `${h}px`,
    borderRadius: round,
  });
  const size = (t: number) => circle + (chip - circle) * t;
  const at = (t: number) => {
    const p = along(t);
    const s = size(t);
    return box(p.x, p.y, s, s, '999px');
  };
  const formed = box(startCx, risenCy, circle, circle, '999px');
  const transformEnd = TRANSFORM_MS / MORPH_MS;
  const travelStart = (TRANSFORM_MS + EMOJI_MS) / MORPH_MS;

  ghost.animate([
    { ...box(startCx, startCy, width, height, radius), offset: 0, easing: 'cubic-bezier(0.22, 0.7, 0.2, 1)' },
    { ...formed, offset: transformEnd, easing: 'linear' },
    { ...formed, offset: travelStart, easing: 'cubic-bezier(0.45, 0.02, 0.2, 1)' },
    { ...at(0.55), offset: travelStart + (1 - travelStart) * 0.55 },
    { ...at(1), offset: 1 },
  ], { duration: MORPH_MS, fill: 'forwards' });
  window.setTimeout(() => ghost.remove(), MORPH_MS + 40);
}

/** The small emoji badge hanging off a bubble's lower edge. */
export function ReactionChip({ emoji, label, onClick, align, landing, delayMs = LAND_DELAY_MS }: {
  emoji: string; label: string; onClick?: () => void; align: 'start' | 'end';
  /** True while an emoji is still in flight towards this chip. */
  landing?: boolean;
  /** When that flight arrives. The text-bubble morph is the default. */
  delayMs?: number;
}) {
  const className = cn(
    'absolute -bottom-3 z-10 grid h-6 min-w-6 place-items-center rounded-full border border-line bg-surface px-1 text-[13px] leading-none shadow-raise',
    landing ? 'chat-reaction-land' : 'chat-reaction-in',
    align === 'start' ? 'left-2.5' : 'right-2.5',
  );
  if (!onClick) return <span role="img" aria-label={label} className={className} style={landing ? { animationDelay: `${delayMs}ms` } : undefined}>{emoji}</span>;
  return (
    <button type="button" aria-label={label} onClick={onClick}
      style={landing ? { animationDelay: `${delayMs}ms` } : undefined}
      className={cn(className, 'transition-transform hover:scale-110 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent')}>
      {emoji}
    </button>
  );
}

function ReactionPicker({ id, current, onPick, onClose, boundary }: {
  id: string;
  current?: string;
  onPick: (emoji: string | null, source?: HTMLElement) => void;
  onClose: () => void;
  /** Clicks inside it (the toggle button included) are not "outside". */
  boundary: React.RefObject<HTMLElement | null>;
}) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => { if (event.key === 'Escape') onClose(); };
    const onDown = (event: PointerEvent) => {
      if (boundary.current && !boundary.current.contains(event.target as Node)) onClose();
    };
    document.addEventListener('keydown', onKey);
    document.addEventListener('pointerdown', onDown);
    ref.current?.querySelector<HTMLButtonElement>('button')?.focus();
    return () => {
      document.removeEventListener('keydown', onKey);
      document.removeEventListener('pointerdown', onDown);
    };
  }, [onClose, boundary]);

  const onMenuKey = (event: React.KeyboardEvent<HTMLDivElement>) => {
    const buttons = [...(ref.current?.querySelectorAll<HTMLButtonElement>('button[role="menuitem"]') ?? [])];
    const index = buttons.indexOf(document.activeElement as HTMLButtonElement);
    if (index < 0 || !buttons.length) return;
    const columns = getComputedStyle(ref.current!).gridTemplateColumns.split(' ').length;
    const vertical = columns < buttons.length ? columns : 1;
    const delta = event.key === 'ArrowRight' ? 1 : event.key === 'ArrowLeft' ? -1
      : event.key === 'ArrowDown' ? vertical : event.key === 'ArrowUp' ? -vertical : 0;
    const next = event.key === 'Home' ? 0 : event.key === 'End' ? buttons.length - 1
      : delta ? (index + delta + buttons.length) % buttons.length : -1;
    if (next >= 0) {
      event.preventDefault();
      buttons[next].focus();
    } else if (event.key === 'Tab') onClose();
  };

  return (
    <div ref={ref} id={id} role="menu" aria-label={t('reaction.pick')} data-state="open" data-reaction-picker
      onKeyDown={onMenuKey}
      className="popup-shell u-pop absolute bottom-full left-0 z-20 mb-1.5 grid w-max max-w-[calc(100vw-32px)] grid-cols-4 gap-0.5 rounded-xl border border-line bg-surface p-1 shadow-pop min-[480px]:pointer-fine:grid-cols-8">
      <div className="popup-plate liquid-glass" aria-hidden="true" />
      {QUICK_REACTIONS.map((emoji) => (
        <button key={emoji} type="button" role="menuitem"
          aria-label={emoji === current ? t('reaction.remove', { emoji }) : emoji}
          onClick={(event) => { onPick(emoji === current ? null : emoji, event.currentTarget); onClose(); }}
          className={cn(
            'grid size-8 place-items-center rounded-full text-[17px] transition-transform hover:scale-115 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent motion-reduce:transition-none max-[759px]:size-11 pointer-coarse:size-11',
            emoji === current && 'bg-accent-soft',
          )}>
          {emoji}
        </button>
      ))}
    </div>
  );
}

export interface ReactionControlProps {
  current?: string;
  bubbleIndex: number;
  onReact: (emoji: string | null) => void;
  target: () => HTMLElement | null;
  onFlight?: (emoji: string | null) => void;
}

/** The reply action targets one real answer bubble without adding side buttons. */
export function ReactionControl({ current, bubbleIndex, onReact, target, onFlight }: ReactionControlProps) {
  const [picking, setPicking] = useState(false);
  const boundary = useRef<HTMLSpanElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const pickerId = useId();
  const launch = useEmojiFlight();
  const closePicker = useCallback(() => {
    setPicking(false);
    trigger.current?.focus({ preventScroll: true });
  }, []);
  const pick = (emoji: string | null, source?: HTMLElement) => {
    const bubble = target();
    const flying = Boolean(emoji && source && bubble
      && flyEmoji(launch, emoji, source.getBoundingClientRect(), reactionTarget(bubble.getBoundingClientRect(), 'start')));
    onFlight?.(flying ? emoji : null);
    onReact(emoji);
  };
  return <span ref={boundary} className="relative inline-flex shrink-0">
    <button ref={trigger} type="button" aria-label={t('reaction.add')} aria-haspopup="menu" aria-expanded={picking}
      aria-controls={picking ? pickerId : undefined} data-reaction-trigger data-reaction-bubble={bubbleIndex}
      onClick={() => setPicking(open => !open)}
      className="grid size-7 place-items-center rounded-sm text-ink-3 transition-colors hover:bg-surface-2 hover:text-ink focus-visible:text-ink focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent max-[759px]:size-11 pointer-coarse:size-11">
      <SmilePlus className="size-4" aria-hidden="true" />
    </button>
    {picking ? <ReactionPicker id={pickerId} current={current} onPick={pick} onClose={closePicker} boundary={boundary} /> : null}
  </span>;
}

/** One grey bubble; existing badges retain their original per-bubble address. */
export function BotBubble({ text, note, running, reaction, onReact, fromTyping, index, flyingReaction }: {
  text: string;
  /** Said while working rather than the answer — drawn a step quieter. */
  note?: boolean;
  running?: boolean;
  reaction?: string;
  /** Absent while the reply is still being written or cannot be addressed. */
  onReact?: (emoji: string | null) => void;
  /** This bubble took the place of the typing dots, so the dots fade inside it. */
  fromTyping?: boolean;
  index?: number;
  flyingReaction?: string | null;
}) {
  const [dots, setDots] = useState(fromTyping);
  const bubble = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!fromTyping) {
      setDots(false);
      return;
    }
    setDots(true);
    const id = window.setTimeout(() => setDots(false), typingLeaveMs());
    return () => window.clearTimeout(id);
  }, [fromTyping]);
  return (
    <div className={cn('group/bubble relative flex max-w-full items-center gap-1', reaction && 'mb-3')}>
      <div ref={bubble} data-assistant-bubble={index} {...{ [REPLY_ATTRIBUTE]: '' }}
        className={cn(
          'chat-bubble-in relative min-w-0 max-w-full rounded-lg bg-surface-2 px-3.5 py-2',
          fromTyping && 'chat-bubble-open',
          note && '[&_*]:text-ink-2',
        )}>
        {dots ? (
          <span aria-hidden data-typing="" className="chat-dots-leave pointer-events-none absolute inset-y-0 left-3.5 flex items-center gap-1.5">
            <span className="chat-dot size-2 rounded-full bg-ink-3" />
            <span className="chat-dot size-2 rounded-full bg-ink-3" />
            <span className="chat-dot size-2 rounded-full bg-ink-3" />
          </span>
        ) : null}
        <div className={fromTyping ? 'chat-text-in' : undefined}>
          <TextMessagePartProvider text={text} isRunning={running}>
            <Markdown />
          </TextMessagePartProvider>
        </div>
        {reaction ? (
          <ReactionChip key={reaction} emoji={reaction} align="start" landing={flyingReaction === reaction} delayMs={FLIGHT_LAND_MS}
            label={t('reaction.yours', { emoji: reaction })}
            onClick={onReact ? () => onReact(null) : undefined} />
        ) : null}
      </div>
    </div>
  );
}
