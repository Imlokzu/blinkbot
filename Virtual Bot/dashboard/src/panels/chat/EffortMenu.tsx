import { useCallback, useEffect, useId, useRef, useState, type CSSProperties, type PointerEvent, type Ref } from 'react';
import * as Slider from '@radix-ui/react-slider';
import { ChevronRight } from '../../vendor/solar-icons/compat.ts';
import { t } from '@/locales/chat';
import { t as pickerText } from '@/locales/modelPicker';
import { prepareLocalHaptics, tickLocalHaptics } from '@/lib/localHaptics';
import { thinkingLabel, type useBrainChoice } from './useBrainChoice';

/** Preview locally while dragging; only release or a keyboard step saves. */
export function EffortOptions({ brain, modelLabel, modelButtonRef, onChangeModel }: {
  brain: ReturnType<typeof useBrainChoice>;
  modelLabel: string;
  modelButtonRef: Ref<HTMLButtonElement>;
  onChangeModel: () => void;
}) {
  const hintId = useId();
  const [preview, setPreview] = useState<number | null>(null);
  const [pointerFraction, setPointerFraction] = useState<number | null>(null);
  const [epoch, setEpoch] = useState(0);
  const [dragging, setDragging] = useState(false);
  const [keyActive, setKeyActive] = useState(false);
  const keyTimer = useRef<number | undefined>(undefined);
  const trustedKey = useRef(false);
  const gesture = useRef<{
    target: Element; pointerId: number; identity: string;
    grabOffset: number; next: number; start: number; fromThumb: boolean; trusted: boolean;
  } | null>(null);
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

  useEffect(() => {
    prepareLocalHaptics();
    return () => window.clearTimeout(keyTimer.current);
  }, []);

  const stepFeedback = (trusted: boolean, keyboard = false) => {
    tickLocalHaptics(trusted);
    if (keyboard) {
      window.clearTimeout(keyTimer.current);
      setKeyActive(true);
      keyTimer.current = window.setTimeout(() => setKeyActive(false), 180);
    }
  };

  const cancelGesture = useCallback(() => {
    const active = gesture.current;
    gesture.current = null;
    setDragging(false);
    if (active?.target.hasPointerCapture(active.pointerId)) active.target.releasePointerCapture(active.pointerId);
    setPreview(null);
    setPointerFraction(null);
    // Radix caches track coordinates until normal release; cancellation must reset them too.
    setEpoch(value => value + 1);
  }, []);

  // A drag belongs to the model and settings it started with, never a newer selection.
  useEffect(() => {
    if (gesture.current) cancelGesture();
    else { setPreview(null); setPointerFraction(null); }
  }, [brain.current, brain.thinking, brain.saving, brain.loading, brain.unavailable, levelKey, cancelGesture]);
  useEffect(() => {
    const blur = () => { if (gesture.current) cancelGesture(); };
    window.addEventListener('blur', blur);
    return () => window.removeEventListener('blur', blur);
  }, [cancelGesture]);

  const commit = ([next]: number[]) => {
    setPreview(null);
    setPointerFraction(null);
    if (!disabled && next >= 0 && next < levels.length) void brain.pickThinking(levels[next]);
  };

  const previewPointer = (event: PointerEvent<HTMLElement>) => {
    const active = gesture.current;
    if (!active || active.pointerId !== event.pointerId) return;
    event.preventDefault();
    if (disabled || active.identity !== identity) { cancelGesture(); return; }
    const bounds = event.currentTarget.getBoundingClientRect();
    const fraction = Math.max(0, Math.min(1, (event.clientX - active.grabOffset - bounds.left) / Math.max(1, bounds.width)));
    const next = Math.round(fraction * (levels.length - 1));
    if (next !== active.next) stepFeedback(active.trusted);
    active.next = next;
    // Travel is continuous; the label and saved value still use the nearest stop.
    setPointerFraction(fraction);
    setPreview(active.next);
  };

  return (
    <section className="brain-picker-pane effort-picker-pane" aria-label={t('composer.effort')} aria-busy={brain.saving}
      data-dragging={dragging ? '' : undefined} data-interacting={dragging || keyActive ? '' : undefined}
      style={{ '--effort-fraction': pointerFraction ?? index / Math.max(1, levels.length - 1) } as CSSProperties}>
      <h2 className="sr-only">{t('composer.effort')}</h2>
      <div className="effort-slider-heading">
        <button ref={modelButtonRef} type="button" className="model-picker-change-model"
          aria-label={pickerText('changeModel', { model: modelLabel })} onClick={onChangeModel}>
          <span key={label} className="effort-slider-value" title={label}>{label}</span>
          <span className="effort-model-link">
            <span title={modelLabel}>{modelLabel}</span>
            <ChevronRight aria-hidden="true" className="effort-model-chevron" strokeWidth={1.75} />
          </span>
        </button>
      </div>
      <p id={hintId} className="sr-only">{t('composer.effortHintShort')}</p>
      <div className="effort-slider-body">
        <Slider.Root key={epoch} className="effort-slider" min={0} max={Math.max(1, levels.length - 1)} step={1}
          value={[index]} disabled={disabled || levels.length < 2}
          onKeyDownCapture={event => {
            trustedKey.current = event.isTrusted && ['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown', 'Home', 'End', 'PageUp', 'PageDown'].includes(event.key);
          }}
          onKeyUpCapture={() => { trustedKey.current = false; }}
          onBlurCapture={() => { trustedKey.current = false; }}
          onValueChange={([next]) => {
            if (!disabled) {
              if (next !== index) stepFeedback(trustedKey.current, true);
              trustedKey.current = false;
              setPreview(next);
            }
          }}
          onValueCommit={commit}
          onPointerDownCapture={event => {
            event.preventDefault();
            if (disabled || levels.length < 2 || gesture.current) return;
            const thumb = (event.target as Element).closest<HTMLElement>('[role=slider]');
            const bounds = thumb?.getBoundingClientRect();
            // The 44px pill keeps its grab offset; pressing the track remains absolute.
            gesture.current = {
              target: event.currentTarget, pointerId: event.pointerId, identity,
              grabOffset: bounds ? event.clientX - (bounds.left + bounds.width / 2) : 0,
              next: index, start: index, fromThumb: Boolean(thumb),
              trusted: event.isTrusted,
            };
            setDragging(true);
            event.currentTarget.setPointerCapture(event.pointerId);
            event.currentTarget.querySelector<HTMLElement>('[role=slider]')?.focus({ preventScroll: true });
            if (!thumb) previewPointer(event);
          }}
          onPointerMove={previewPointer}
          onPointerUpCapture={event => {
            const active = gesture.current;
            if (!active || active.pointerId !== event.pointerId) return;
            event.preventDefault();
            if (active.identity !== identity || disabled) { cancelGesture(); return; }
            gesture.current = null;
            setDragging(false);
            if (active.target.hasPointerCapture(active.pointerId)) active.target.releasePointerCapture(active.pointerId);
            // Merely grabbing the handle must not reset an unsupported saved level.
            if (!active.fromThumb || active.next !== active.start) commit([active.next]);
            else { setPreview(null); setPointerFraction(null); }
          }}
          onPointerCancel={cancelGesture}
          onLostPointerCapture={() => { if (gesture.current) cancelGesture(); }}>
          <Slider.Track className="effort-slider-track">
            <Slider.Range className="effort-slider-range" />
            <span className="effort-slider-ticks" aria-hidden="true">
              {levels.map((level, position) => <i key={level} data-filled={position <= index ? '' : undefined}
                style={{ left: `${position / Math.max(1, levels.length - 1) * 100}%` }} />)}
            </span>
          </Slider.Track>
          <Slider.Thumb className="effort-slider-thumb" aria-label={t('composer.chooseEffort')}
            aria-describedby={hintId} aria-valuetext={label} aria-disabled={disabled || levels.length < 2}
            tabIndex={brain.loading || brain.unavailable || levels.length < 2 ? -1 : 0}
            data-level={preview === null ? brain.thinking : levels[index]}>
            <span className="effort-slider-thumb-surface" aria-hidden="true" />
          </Slider.Thumb>
        </Slider.Root>
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
