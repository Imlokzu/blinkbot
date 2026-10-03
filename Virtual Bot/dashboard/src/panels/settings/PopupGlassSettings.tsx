import { useId } from 'react';
import { POPUP_GLASS_KINDS } from '@/hooks/popupGlassPreferences';
import { usePopupGlassPreference } from '@/hooks/usePopupGlassPreference';
import { t } from '@/locales/popupGlass';
import './popup-glass-settings.css';

export function PopupGlassSettings({ language }: { language?: 'uk' | 'en' }) {
  const { targets, setTargets } = usePopupGlassPreference();
  const id = useId();
  const text = (key: Parameters<typeof t>[0]) => t(key, language);
  return (
    <fieldset className="popup-glass-settings" aria-describedby={`${id}-hint ${id}-local`}>
      <legend>{text('title')}</legend>
      <p id={`${id}-hint`}>{text('hint')}</p>
      <div className="popup-glass-choices">
        {POPUP_GLASS_KINDS.map(kind => (
          <label key={kind}>
            <input type="checkbox" checked={targets.includes(kind)} onChange={event => {
              const checked = event.currentTarget.checked;
              setTargets(previous => checked ? [...previous, kind] : previous.filter(target => target !== kind));
            }} />
            <span>{text(`kind.${kind}`)}</span>
          </label>
        ))}
      </div>
      <div className="popup-glass-actions">
        <button type="button" disabled={targets.length === POPUP_GLASS_KINDS.length} onClick={() => setTargets(POPUP_GLASS_KINDS)}>{text('all')}</button>
        <button type="button" disabled={!targets.length} onClick={() => setTargets([])}>{text('none')}</button>
        <span id={`${id}-local`}>{text('local')}</span>
      </div>
    </fieldset>
  );
}
