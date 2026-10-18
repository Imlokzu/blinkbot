import { useCallback, useEffect, useId, useRef, useState } from 'react';
import * as Slider from '@radix-ui/react-slider';
import { t } from '@/locales/chat';
import { thinkingLabel, type useBrainChoice } from './useBrainChoice';

/** Preview locally while dragging; only release or a keyboard step saves. */
export function EffortOptions({ brain }: { brain: ReturnType<typeof useBrainChoice> }) {
  const hintId = useId();
  const [preview, setPreview] = useState<number | null>(null);
  const [epoch, setEpoch] = useState(0);
  const gesture = useRef<{ target: Element; pointerId: number; identity: string } | null>(null);
  // Automatic clears the setting; explicit off remains a different stop.
  const levels = ['', ...new Set(brain.levels.filter(Boolean))];
  const levelKey = JSON.stringify(levels);
  const identity = JSON.stringify([brain.current, brain.thinking, levelKey]);
  const current = levels.indexOf(brain.thinking);
  const supported = current !== -1;
  const index = Math.min(preview ?? Math.max(0, current), levels.length - 1);
  const disabled = brain.loading || brain.saving || brain.unavailable;
  const labelFor = (level: string) => level ? thinkingLabel(level) : t('composer.asConfigured');
  const label = labelFor(preview === null ? brain.thinking : levels[index]);

  const cancelGesture = useCallback(() => {
    const active = gesture.current;
    gesture.current = null;
    if (active?.target.hasPointerCapture(active.pointerId)) active.target.releasePointerCapture(active.pointerId);
    setPreview(null);
    // Radix caches track coordinates until normal release; cancellation must reset them too.
    setEpoch(value => value + 1);
  }, []);

  // A drag belongs to the model and settings it started with, never a newer selection.
  useEffect(() => {
    if (gesture.current) cancelGesture();
    else setPreview(null);
  }, [brain.current, brain.thinking, brain.saving, brain.loading, brain.unavailable, levelKey, cancelGesture]);
  useEffect(() => {
    const blur = () => { if (gesture.current) cancelGesture(); };
    window.addEventListener('blur', blur);
    return () => window.removeEventListener('blur', blur);
  }, [cancelGesture]);

  const commit = ([next]: number[]) => {
    setPreview(null);
    if (!disabled && next >= 0 && next < levels.length) void brain.pickThinking(levels[next]);
  };

  return (
    <section className="brain-picker-pane effort-picker-pane" aria-label={t('composer.effort')} aria-busy={brain.saving}>
      <div className="effort-slider-heading">
        <h2 className="u-label">{t('composer.effort')}</h2>
        <span className="effort-slider-value" title={label}>{label}</span>
      </div>
      <p id={hintId} className="sr-only">{t('composer.effortHintShort')}</p>
      <div className="effort-slider-body">
        <Slider.Root key={epoch} className="effort-slider" min={0} max={Math.max(1, levels.length - 1)} step={1}
          value={[index]} disabled={disabled || levels.length < 2}
          onValueChange={([next]) => { if (!disabled) setPreview(next); }}
          onValueCommit={commit}
          onPointerDownCapture={event => {
            if (disabled || levels.length < 2) { event.preventDefault(); return; }
            gesture.current = { target: event.target as Element, pointerId: event.pointerId, identity };
          }}
          onPointerUpCapture={event => {
            if (gesture.current && gesture.current.identity !== identity) {
              event.preventDefault();
              cancelGesture();
            } else gesture.current = null;
          }}
          onPointerCancel={cancelGesture}
          onLostPointerCapture={() => { if (gesture.current) cancelGesture(); }}>
          <Slider.Track className="effort-slider-track">
            <Slider.Range className="effort-slider-range" />
            <span className="effort-slider-ticks" aria-hidden="true">
              {levels.map((level, position) => <i key={level} data-filled={position <= index ? '' : undefined} />)}
            </span>
          </Slider.Track>
          <Slider.Thumb className="effort-slider-thumb" aria-label={t('composer.chooseEffort')}
            aria-describedby={hintId} aria-valuetext={label} aria-disabled={disabled || levels.length < 2}
            tabIndex={brain.loading || brain.unavailable || levels.length < 2 ? -1 : 0}
            data-level={preview === null ? brain.thinking : levels[index]} />
        </Slider.Root>
        <div className="effort-slider-endpoints" aria-hidden="true">
          <span>{labelFor(levels[0])}</span><span>{labelFor(levels.at(-1)!)}</span>
        </div>
        {!supported ? <div className="effort-slider-feedback" role="status">
          <p>{t('composer.effortUnsupported')}</p>
          <button type="button" disabled={disabled} onClick={() => { void brain.pickThinking(''); }}>
            {t('composer.asConfigured')}
          </button>
        </div> : null}
        {!brain.loading && !brain.levels.length ? <p className="brain-picker-note">{t('composer.effortUnavailable')}</p> : null}
      </div>
    </section>
  );
}
