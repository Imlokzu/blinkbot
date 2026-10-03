import { useEffect, useId, useRef, useState, type CSSProperties } from 'react';
import { ImagePlus, LoaderCircle, Palette, RotateCcw, ShieldCheck, Trash2 } from 'lucide-react';
import { Button } from '@/components/ui/Button';
import { Dialog, DialogContent, DialogTrigger } from '@/components/ui/Dialog';
import { useIsPhone } from '@/hooks/useMediaQuery';
import { t } from '@/locales/chatAppearance';
import { BackgroundImageError, prepareBackgroundImage, type BackgroundImageErrorCode, type ChatBackground, type ChatColor } from './appearancePreferences';
import { useChatAppearance } from './useChatAppearance';
import './chat-appearance-controls.css';

const BACKGROUNDS: ChatBackground[] = ['none', 'sky', 'dusk', 'forest', 'custom'];
const COLORS: ChatColor[] = ['theme', 'rose', 'sage', 'ocean', 'lavender'];
const SWATCHES: Record<ChatColor, string> = {
  theme: 'var(--c-accent)', rose: '#b25473', sage: '#668367', ocean: '#407aa2', lavender: '#8563ae',
};

/** The settings section and the chat shortcut share the same persisted controls. */
function ChatAppearanceControls() {
  const { appearance, setAppearance, resetAppearance } = useChatAppearance();
  const id = useId();
  const input = useRef<HTMLInputElement>(null);
  const uploadGeneration = useRef(0);
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState<BackgroundImageErrorCode | null>(null);
  useEffect(() => () => { uploadGeneration.current++; }, []);

  const upload = async (file: File) => {
    const generation = ++uploadGeneration.current;
    setUploading(true);
    setError(null);
    try {
      const image = await prepareBackgroundImage(file);
      if (generation === uploadGeneration.current) setAppearance({ image, background: 'custom' });
    } catch (failure) {
      if (generation === uploadGeneration.current) {
        setError(failure instanceof BackgroundImageError ? failure.code : 'imageFailed');
      }
    } finally { if (generation === uploadGeneration.current) setUploading(false); }
  };

  return <div className="chat-appearance-controls">
    <fieldset className="chat-appearance-fieldset">
      <legend>{t('background')}</legend>
      <div className="chat-appearance-backgrounds">
        {BACKGROUNDS.map((background) => <button
          key={background}
          type="button"
          className="chat-appearance-background"
          aria-pressed={appearance.background === background}
          disabled={uploading}
          onClick={() => {
            if (background === 'custom' && !appearance.image) input.current?.click();
            else setAppearance({ background });
          }}
        >
          <span className={`chat-appearance-preview chat-appearance-preview--${background}`} aria-hidden="true"
            style={background === 'custom' && appearance.image ? { backgroundImage: `url("${appearance.image}")` } : undefined}>
            {background === 'custom' && !appearance.image ? <ImagePlus /> : null}
            <span className="chat-appearance-preview-composer" />
          </span>
          <span>{t(`background.${background}`)}</span>
        </button>)}
      </div>
      <div className="chat-appearance-upload">
        <input ref={input} type="file" accept="image/png,image/jpeg,image/webp" hidden
          onChange={(event) => {
            const file = event.target.files?.[0];
            event.target.value = '';
            if (file) void upload(file);
          }} />
        <Button size="sm" variant="outline" disabled={uploading} aria-describedby={`${id}-file-hint`}
          onClick={() => input.current?.click()}>
          {uploading ? <LoaderCircle className="chat-appearance-loading" aria-hidden="true" /> : <ImagePlus aria-hidden="true" />}
          {t(uploading ? 'uploading' : 'upload')}
        </Button>
        {appearance.image ? <Button size="icon-sm" variant="ghost" disabled={uploading} aria-label={t('removeImage')}
          title={t('removeImage')} onClick={() => setAppearance((previous) => ({
            image: null, background: previous.background === 'custom' ? 'sky' : previous.background,
          }))}><Trash2 aria-hidden="true" /></Button> : null}
        <span id={`${id}-file-hint`} className="chat-appearance-hint">{t('uploadHint')}</span>
      </div>
      {error ? <p className="chat-appearance-error" role="alert">{t(error)}</p> : null}
    </fieldset>

    <fieldset className="chat-appearance-fieldset">
      <legend>{t('color')}</legend>
      <div className="chat-appearance-colors">
        {COLORS.map((color) => <button key={color} type="button"
          className="chat-appearance-color" aria-pressed={appearance.color === color}
          aria-label={t(`color.${color}`)} title={t(`color.${color}`)}
          style={{ '--appearance-choice-color': SWATCHES[color] } as CSSProperties}
          onClick={() => setAppearance({ color })}>
          <span aria-hidden="true" /><span>{t(`color.${color}`)}</span>
        </button>)}
      </div>
    </fieldset>

    <div className="chat-appearance-sliders">
      <div className="chat-appearance-slider">
        <div><label htmlFor={`${id}-opacity`}>{t('opacity')}</label>
          <output htmlFor={`${id}-opacity`}>{t('opacityValue', { value: appearance.opacity })}</output></div>
        <input id={`${id}-opacity`} type="range" min={65} max={100} step={1} value={appearance.opacity}
          aria-valuetext={t('opacityValue', { value: appearance.opacity })}
          onChange={(event) => setAppearance({ opacity: Number(event.target.value) })} />
      </div>
      <div className="chat-appearance-slider">
        <div><label htmlFor={`${id}-blur`}>{t('blur')}</label>
          <output htmlFor={`${id}-blur`}>{t('blurValue', { value: appearance.blur })}</output></div>
        <input id={`${id}-blur`} type="range" min={0} max={16} step={1} value={appearance.blur}
          aria-valuetext={t('blurValue', { value: appearance.blur })}
          onChange={(event) => setAppearance({ blur: Number(event.target.value) })} />
      </div>
    </div>

    <div className="chat-appearance-footer">
      <p><ShieldCheck aria-hidden="true" /><span>{t('privacy')}</span></p>
      <Button size="sm" variant="ghost" disabled={uploading} onClick={() => { resetAppearance(); setError(null); }}>
        <RotateCcw aria-hidden="true" />{t('reset')}
      </Button>
    </div>
  </div>;
}

export function ChatAppearanceButton({ className }: { className?: string }) {
  const phone = useIsPhone();
  return <Dialog>
    <DialogTrigger asChild>
      <Button variant="ghost" size="icon-sm" className={className} aria-label={t('open')} title={t('open')}>
        <Palette aria-hidden="true" />
      </Button>
    </DialogTrigger>
    <DialogContent title={t('title')} description={t('description')} side={phone ? 'bottom' : 'center'}
      className="chat-appearance-dialog">
      <ChatAppearanceControls />
    </DialogContent>
  </Dialog>;
}

export function ChatAppearanceSettings() {
  const id = useId();
  return <section className="chat-appearance-settings" aria-labelledby={id}>
    <header><h3 id={id}>{t('settingsTitle')}</h3><p>{t('settingsDescription')}</p></header>
    <ChatAppearanceControls />
  </section>;
}
