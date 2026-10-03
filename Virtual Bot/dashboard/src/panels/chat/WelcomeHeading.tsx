import { useEffect, useState } from 'react';
import { AnimatePresence, motion, useReducedMotion } from 'motion/react';
import { t } from '@/locales/chat';
import './welcome-heading.css';

const HEADINGS = [
  'thread.prompt1',
  'thread.welcomeIdea',
  'thread.welcomePlan',
  'thread.welcomePlayful',
] as const;
const HOLD_MS = 2_000;

export function WelcomeHeading() {
  const [index, setIndex] = useState(0);
  const reducedMotion = useReducedMotion();

  useEffect(() => {
    if (reducedMotion) return;
    let timer: number | undefined;
    const stop = () => window.clearTimeout(timer);
    const schedule = () => {
      stop();
      if (!document.hidden) {
        timer = window.setTimeout(() => {
          if (!document.hidden) setIndex((current) => (current + 1) % HEADINGS.length);
        }, HOLD_MS);
      }
    };
    schedule();
    document.addEventListener('visibilitychange', schedule);
    return () => {
      stop();
      document.removeEventListener('visibilitychange', schedule);
    };
  }, [index, reducedMotion]);

  const current = reducedMotion ? 0 : index;
  return (
    <h2 className="welcome-heading" aria-label={t('thread.prompt1')}>
      {/* Reserve the tallest localized phrase so the composer never jumps. */}
      {HEADINGS.map((key) => (
        <span key={key} aria-hidden="true" className="welcome-heading__measure">{t(key)}</span>
      ))}
      {reducedMotion ? (
        <span aria-hidden="true" className="welcome-heading__phrase">{t('thread.prompt1')}</span>
      ) : (
        <AnimatePresence initial={false} mode="wait">
          <motion.span
            key={current}
            aria-hidden="true"
            className="welcome-heading__phrase"
            initial={{ rotateX: -65, opacity: 0 }}
            animate={{ rotateX: 0, opacity: 1 }}
            exit={{ rotateX: 65, opacity: 0 }}
            transition={{ type: 'spring', duration: .24, bounce: 0 }}
          >
            {t(HEADINGS[current])}
          </motion.span>
        </AnimatePresence>
      )}
    </h2>
  );
}
