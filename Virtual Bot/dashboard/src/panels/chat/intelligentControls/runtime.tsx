import { useLayoutEffect, useReducer, useRef, type ComponentType } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { flushSync } from 'react-dom';
import { Button, type ButtonProps } from '@/components/ui/Button';
import { Input, Textarea } from '@/components/ui/Field';
import WakeSliderRaw from '@/vendor/reactbits/WakeSlider.jsx';
import SquishSwitchRaw from '@/vendor/reactbits/SquishSwitch.jsx';
import GlideSelectRaw from '@/vendor/reactbits/GlideSelect.jsx';
import type { WakeSliderProps, SquishSwitchProps, GlideSelectProps } from '@/vendor/reactbits';
import { t } from '@/locales/intelligentUi';
import './controls.css';

const WakeSlider = WakeSliderRaw as unknown as ComponentType<WakeSliderProps>;
const SquishSwitch = SquishSwitchRaw as unknown as ComponentType<SquishSwitchProps>;
const GlideSelect = GlideSelectRaw as unknown as ComponentType<GlideSelectProps>;
type Control = HTMLInputElement | HTMLButtonElement | HTMLSelectElement | HTMLTextAreaElement;
const selector = 'button,input[type="range"],input[type="checkbox"],input[type="number"],input[type="text"],input:not([type]),textarea,select';
const syncEvent = 'blink-control-sync';
const controls = new Map<Control, { host: HTMLElement; root: Root }>();

function rangeBounds(node: HTMLInputElement) {
  const lower = Number(node.min || 0), upper = Number(node.max || 100);
  const min = Number.isFinite(lower) ? lower : 0;
  return { min, max: Math.max(min, Number.isFinite(upper) ? upper : 100) };
}

function notifyChange(node: Control, types = ['input', 'change']) {
  for (const type of types) node.dispatchEvent(new Event(type, { bubbles: true }));
}

function focusControl(host: HTMLElement, options?: FocusOptions) {
  host.querySelector<HTMLElement>('[role="slider"],button,input,textarea')?.focus(options);
}

function labelFor(node: Control): string {
  const labelledBy = (node.getAttribute('aria-labelledby') || '').split(/\s+/).map(id => document.getElementById(id)?.textContent || '').join(' ').trim();
  return node.getAttribute('aria-label') || labelledBy || Array.from(node.labels || []).map(label => label.textContent).join(' ').trim() || node.title || node.name || t('control');
}

/** Keep the model's existing DOM references, input events and click handlers. */
function NativeControl({ node, host }: { node: Control; host: HTMLElement }) {
  const [, refresh] = useReducer(value => value + 1, 0);
  const edited = useRef(false), committedValue = useRef(node.value);
  useLayoutEffect(() => {
    const sync = () => refresh();
    for (const type of ['input', 'change', syncEvent]) node.addEventListener(type, sync);
    const observer = new MutationObserver(sync);
    observer.observe(node, { attributes: true, childList: true, subtree: true, characterData: true });
    const restore: (() => void)[] = [];
    // Setting input.value in generated JavaScript does not emit a DOM event.
    for (const key of ['value', ...(node instanceof HTMLInputElement ? ['checked', 'valueAsNumber', 'valueAsDate'] : []),
      ...(node instanceof HTMLSelectElement ? ['selectedIndex'] : [])]) {
      const own = Object.getOwnPropertyDescriptor(node, key);
      const descriptor = Object.getOwnPropertyDescriptor(Object.getPrototypeOf(node), key);
      if (own || !descriptor?.get || !descriptor.set) continue;
      const set = (value: unknown) => { descriptor.set!.call(node, value); sync(); };
      Object.defineProperty(node, key, { configurable: true,
        get: () => descriptor.get!.call(node), set,
      });
      restore.push(() => {
        if (Object.getOwnPropertyDescriptor(node, key)?.set === set) delete (node as unknown as Record<string, unknown>)[key];
      });
    }
    for (const key of ['focus', ...(node instanceof HTMLInputElement ? ['stepUp', 'stepDown'] : [])]) {
      const own = Object.getOwnPropertyDescriptor(node, key);
      if (own) continue;
      const original = (node as unknown as Record<string, (...args: unknown[]) => unknown>)[key];
      if (typeof original !== 'function') continue;
      const method = (...args: unknown[]) => {
        if (key === 'focus') return focusControl(host, args[0] as FocusOptions | undefined);
        const result = original.apply(node, args); sync(); return result;
      };
      Object.defineProperty(node, key, { configurable: true, writable: true, value: method });
      restore.push(() => {
        if (Object.getOwnPropertyDescriptor(node, key)?.value === method) delete (node as unknown as Record<string, unknown>)[key];
      });
    }
    // The visible controls must not add a second bubbling event beside the
    // original element's event, especially for delegated model listeners.
    const stop = (event: Event) => event.stopPropagation();
    for (const type of ['input', 'change', 'click']) host.addEventListener(type, stop);
    const keydown = (event: KeyboardEvent) => {
      if (!(node instanceof HTMLInputElement) || node.type !== 'range' || node.matches(':disabled')) return;
      const steps: Record<string, number> = { ArrowRight: 1, ArrowUp: 1, ArrowLeft: -1, ArrowDown: -1, PageUp: 10, PageDown: -10 };
      if (!(event.key in steps) && event.key !== 'Home' && event.key !== 'End') return;
      event.preventDefault(); event.stopPropagation();
      const before = node.value, { min, max } = rangeBounds(node);
      if (event.key === 'Home' || event.key === 'End') node.value = String(event.key === 'Home' ? min : max);
      else if (node.step === 'any') node.valueAsNumber += steps[event.key] * (max / 100 - min / 100);
      else if (steps[event.key] > 0) node.stepUp(steps[event.key]);
      else node.stepDown(-steps[event.key]);
      if (node.value !== before) notifyChange(node);
    };
    host.addEventListener('keydown', keydown, true);
    return () => {
      observer.disconnect();
      for (const type of ['input', 'change', syncEvent]) node.removeEventListener(type, sync);
      for (const type of ['input', 'change', 'click']) host.removeEventListener(type, stop);
      host.removeEventListener('keydown', keydown, true);
      for (const undo of restore) undo();
    };
  }, [node, host]);
  useLayoutEffect(() => {
    // Option.selected is a property mutation, not an observed attribute change.
    const restore: (() => void)[] = [];
    if (node instanceof HTMLSelectElement) for (const option of node.options) {
      if (Object.hasOwn(option, 'selected')) continue;
      const descriptor = Object.getOwnPropertyDescriptor(HTMLOptionElement.prototype, 'selected')!;
      const set = (value: unknown) => { descriptor.set!.call(option, value); refresh(); };
      Object.defineProperty(option, 'selected', { configurable: true, get: () => descriptor.get!.call(option), set });
      restore.push(() => {
        if (Object.getOwnPropertyDescriptor(option, 'selected')?.set === set) delete (option as unknown as Record<string, unknown>).selected;
      });
    }
    if (node instanceof HTMLInputElement && node.type === 'range') {
      const slider = host.querySelector('[role="slider"]'), { min, max } = rangeBounds(node);
      slider?.setAttribute('aria-valuemin', String(min));
      slider?.setAttribute('aria-valuemax', String(max));
      slider?.setAttribute('aria-valuenow', node.value);
    }
    return () => { for (const undo of restore) undo(); };
  });
  const disabled = node.matches(':disabled');
  const change = (value: string | boolean, types = ['input', 'change']) => {
    if (disabled) return;
    if (typeof value === 'boolean' && node instanceof HTMLInputElement) {
      if (node.checked !== value) node.click();
      refresh();
    } else {
      const before = node.value;
      node.value = String(value);
      if (node instanceof HTMLInputElement && node.type === 'range' && node.value === before) return;
      notifyChange(node, types);
    }
  };
  const label = labelFor(node);
  if (node instanceof HTMLInputElement && node.type === 'range') {
    const { min, max } = rangeBounds(node);
    // Native range normalization owns snapping and bounds; percentage geometry
    // avoids the shared slider's six-decimal rounding of very small values.
    const percent = max > min ? (node.valueAsNumber / 2 - min / 2) / (max / 2 - min / 2) * 100 : 0;
    return <WakeSlider value={percent} onChange={value => change(String(min * (1 - value / 100) + max * value / 100))}
      min={0} max={100} step={0} formatValue={() => node.value} bars={28} height={46} restHeight={8} gap={3}
      fillColor="var(--c-accent)" trackColor="var(--c-surface-3)" ariaLabel={label} disabled={disabled || max <= min} />;
  }
  if (node instanceof HTMLInputElement && node.type === 'checkbox') {
    return <SquishSwitch checked={node.checked} onChange={change} disabled={disabled} ariaLabel={label}
      width={52} height={30} radius={15} trackColor="var(--c-surface-3)" trackOnColor="var(--c-accent)"
      thumbColor="var(--c-surface)" thumbOnColor="var(--c-accent-ink)" />;
  }
  if (node instanceof HTMLSelectElement && !node.multiple) {
    return <GlideSelect value={String(node.selectedIndex)} onChange={value => {
      if (disabled) return;
      node.selectedIndex = Number(value); notifyChange(node);
    }} disabled={disabled} ariaLabel={label}
      options={Array.from(node.options).flatMap((option, index) => !option.disabled && !option.hidden
        && !(option.parentElement instanceof HTMLOptGroupElement && (option.parentElement.disabled || option.parentElement.hidden))
        ? [{ value: String(index), label: option.label }] : [])}
      placeholder={node.selectedOptions[0]?.label || label} size="lg" radius={10} menuWidth={240}
      accentColor="var(--c-accent)" surfaceColor="var(--c-surface-2)" highlightColor="var(--c-surface-3)" textColor="var(--c-text)" />;
  }
  if (node instanceof HTMLButtonElement) {
    const variants = ['quiet', 'solid', 'outline', 'ghost', 'danger'];
    const variant = variants.includes(node.dataset.variant || '') ? node.dataset.variant as ButtonProps['variant'] : 'quiet';
    return <Button variant={variant} disabled={disabled} aria-label={node.getAttribute('aria-label') || undefined}
      aria-labelledby={node.getAttribute('aria-labelledby') || undefined} aria-describedby={node.getAttribute('aria-describedby') || undefined}
      aria-pressed={node.hasAttribute('aria-pressed') ? node.getAttribute('aria-pressed') === 'mixed' ? 'mixed' : node.getAttribute('aria-pressed') === 'true' : undefined}
      aria-expanded={node.hasAttribute('aria-expanded') ? node.getAttribute('aria-expanded') === 'true' : undefined}
      aria-controls={node.getAttribute('aria-controls') || undefined}
      title={node.title || undefined} onClick={() => node.click()}>{node.textContent}</Button>;
  }
  const fieldEvents = {
    onFocus: () => { committedValue.current = node.value; edited.current = false; },
    onChange: (event: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) => {
      edited.current = true; change(event.target.value, ['input']);
    },
    onBlur: () => {
      if (edited.current && node.value !== committedValue.current) notifyChange(node, ['change']);
      edited.current = false;
    },
  };
  if (node instanceof HTMLTextAreaElement) return <Textarea aria-label={label} value={node.value}
    placeholder={node.placeholder} rows={node.rows} disabled={disabled} readOnly={node.readOnly}
    maxLength={node.maxLength < 0 ? undefined : node.maxLength} minLength={node.minLength < 0 ? undefined : node.minLength}
    required={node.required} {...fieldEvents} />;
  if (node instanceof HTMLInputElement) return <Input type={node.type} aria-label={label} value={node.value}
    placeholder={node.placeholder} min={node.min} max={node.max} step={node.step} disabled={disabled} readOnly={node.readOnly}
    maxLength={node.maxLength < 0 ? undefined : node.maxLength} minLength={node.minLength < 0 ? undefined : node.minLength}
    required={node.required} pattern={node.pattern} inputMode={node.inputMode as React.HTMLAttributes<HTMLInputElement>['inputMode']} {...fieldEvents} />;
  return null;
}

function scan() {
  for (const [node, mounted] of controls) {
    if (!node.isConnected) {
      mounted.root.unmount(); mounted.host.remove(); controls.delete(node);
    } else {
      if (node.nextSibling !== mounted.host) node.after(mounted.host);
      mounted.host.hidden = node.hidden;
      node.dispatchEvent(new Event(syncEvent));
    }
  }
  for (const node of document.querySelectorAll<Control>(selector)) {
    if (controls.has(node) || node.closest('[data-blink-control]') || node.hidden
      || (node instanceof HTMLSelectElement && node.multiple)) continue;
    const host = document.createElement('span');
    host.dataset.blinkControl = node instanceof HTMLInputElement ? node.type : node.tagName.toLowerCase();
    node.after(host);
    node.dataset.blinkNative = '';
    const root = createRoot(host);
    controls.set(node, { host, root });
    flushSync(() => root.render(<NativeControl node={node} host={host} />));
  }
}

// Upgrade existing artifacts as well as controls added later by generated code.
scan();
document.addEventListener('reset', () => queueMicrotask(() => {
  for (const node of controls.keys()) node.dispatchEvent(new Event(syncEvent));
}), true);
document.addEventListener('click', event => {
  const target = event.target instanceof Element ? event.target : null;
  const label = target?.closest('label'), node = label?.control as Control | null;
  const mounted = node ? controls.get(node) : undefined;
  if (!node || !mounted || event.defaultPrevented || target === node || mounted.host.contains(target)
    || target?.closest('a,button,input,textarea,select')) return;
  event.preventDefault();
  focusControl(mounted.host);
  if (node instanceof HTMLInputElement && node.type === 'checkbox' && !node.matches(':disabled')) node.click();
});
let queued = false;
new MutationObserver(records => {
  if (records.every(record => (record.target instanceof Element ? record.target : record.target.parentElement)?.closest('[data-blink-control]'))) return;
  if (!queued) {
    queued = true;
    queueMicrotask(() => { queued = false; scan(); });
  }
}).observe(document.body, { childList: true, subtree: true, attributes: true,
  attributeFilter: ['disabled', 'hidden', 'class', 'style', 'for', 'id', 'aria-label', 'aria-labelledby'] });
