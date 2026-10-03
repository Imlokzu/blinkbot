import { useEffect, useId, useRef, useState, type CSSProperties } from 'react';
import { ImagePlus, RotateCcw, ShieldCheck, Trash2, Video } from 'lucide-react';
import { Button } from '@/components/ui/Button';
import { Switch } from '@/components/ui/Switch';
import { t } from '@/locales/chatAppearance';
import { BACKGROUND_TARGETS, BackgroundImageError, hasFullAppBackground, prepareBackgroundImage,
  type BackgroundImageErrorCode, type ChatBackground, type ChatColor } from './appearancePreferences';
import { useChatAppearance } from './useChatAppearance';
import { deleteWallpaperVideo, prepareWallpaperVideo, saveWallpaperVideo, useWallpaperMedia,
  WallpaperMediaError, type WallpaperMediaErrorCode } from './wallpaperMedia';
import './chat-appearance-controls.css';

const BACKGROUNDS: ChatBackground[] = ['none', 'sky', 'dusk', 'forest', 'custom', 'video'];
const COLORS: ChatColor[] = ['theme', 'rose', 'sage', 'ocean', 'lavender'];
const SWATCHES: Record<ChatColor, string> = {
  theme: 'var(--c-accent)', rose: '#b25473', sage: '#668367', ocean: '#407aa2', lavender: '#8563ae',
};

/** Wallpaper uploads and placement are edited only in Settings. */
function ChatAppearanceControls() {
  const { appearance, setAppearance, resetAppearance } = useChatAppearance();
  const id = useId();
  const input = useRef<HTMLInputElement>(null);
  const videoInput = useRef<HTMLInputElement>(null);
  const uploadGeneration = useRef(0);
  const [uploading, setUploading] = useState<'image' | 'video' | null>(null);
  const [error, setError] = useState<BackgroundImageErrorCode | WallpaperMediaErrorCode | null>(null);
  const media = useWallpaperMedia(appearance.videoId);
  const fullApp = hasFullAppBackground(appearance);
  useEffect(() => () => { uploadGeneration.current++; }, []);

  const upload = async (file: File) => {
    const generation = ++uploadGeneration.current;
    setUploading('image');
    setError(null);
    try {
      const image = await prepareBackgroundImage(file);
      if (generation === uploadGeneration.current) setAppearance({ image, background: 'custom' });
    } catch (failure) {
      if (generation === uploadGeneration.current) {
        setError(failure instanceof BackgroundImageError ? failure.code : 'imageFailed');
      }
    } finally { if (generation === uploadGeneration.current) setUploading(null); }
  };

  const uploadVideo = async (file: File) => {
    const generation = ++uploadGeneration.current;
    setUploading('video');
    setError(null);
    let unsavedId: string | null = null;
    try {
      const record = await prepareWallpaperVideo(file);
      if (generation !== uploadGeneration.current) return;
      unsavedId = record.id;
      await saveWallpaperVideo(record);
      if (generation !== uploadGeneration.current) return;
      let oldId: string | null = null;
      if (setAppearance(previous => {
        oldId = previous.videoId;
        return { videoId: record.id, background: 'video' };
      })) {
        unsavedId = null;
        if (oldId) void deleteWallpaperVideo(oldId).catch(() => {});
      }
    } catch (failure) {
      if (generation === uploadGeneration.current) setError(failure instanceof WallpaperMediaError ? failure.code : 'videoFailed');
    } finally {
      if (unsavedId) void deleteWallpaperVideo(unsavedId).catch(() => {});
      if (generation === uploadGeneration.current) setUploading(null);
    }
  };

  const removeVideo = () => {
    const previousId = appearance.videoId;
    if (setAppearance(previous => ({ videoId: null,
      background: previous.background === 'video' ? 'sky' : previous.background,
    })) && previousId) void deleteWallpaperVideo(previousId).catch(() => {});
    setError(null);
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
          disabled={Boolean(uploading)}
          onClick={() => {
            if (background === 'custom' && !appearance.image) input.current?.click();
            else if (background === 'video' && !appearance.videoId) videoInput.current?.click();
            else setAppearance({ background });
          }}
        >
          <span className={`chat-appearance-preview chat-appearance-preview--${background}`} aria-hidden="true"
            style={background === 'custom' && appearance.image ? { backgroundImage: `url("${appearance.image}")` }
              : background === 'video' && media.posterURL ? { backgroundImage: `url("${media.posterURL}")` } : undefined}>
            {background === 'custom' && !appearance.image ? <ImagePlus /> : null}
            {background === 'video' && !media.posterURL ? <Video /> : null}
            <span className="chat-appearance-preview-composer" />
          </span>
          <span>{t(`background.${background}`)}</span>
        </button>)}
      </div>
      <div className="chat-appearance-upload">
        <input ref={input} type="file" accept="image/png,image/jpeg,image/webp" disabled={Boolean(uploading)} hidden
          onChange={(event) => {
            const file = event.target.files?.[0];
            event.target.value = '';
            if (file) void upload(file);
          }} />
        <Button size="sm" variant="outline" disabled={Boolean(uploading)} aria-describedby={`${id}-file-hint`}
          onClick={() => input.current?.click()}>
          <ImagePlus aria-hidden="true" />{t(uploading === 'image' ? 'uploading' : 'upload')}
        </Button>
        {appearance.image ? <Button size="icon-sm" variant="ghost" disabled={Boolean(uploading)} aria-label={t('removeImage')}
          title={t('removeImage')} onClick={() => setAppearance((previous) => ({
            image: null, background: previous.background === 'custom' ? 'sky' : previous.background,
          }))}><Trash2 aria-hidden="true" /></Button> : null}
        <span id={`${id}-file-hint`} className="chat-appearance-hint">{t('uploadHint')}</span>
      </div>
      <div className="chat-appearance-upload">
        <input ref={videoInput} type="file" accept="video/mp4,video/webm" disabled={Boolean(uploading)} hidden
          onChange={event => {
            const file = event.target.files?.[0];
            event.target.value = '';
            if (file) void uploadVideo(file);
          }} />
        <Button size="sm" variant="outline" disabled={Boolean(uploading)} aria-describedby={`${id}-video-hint`}
          onClick={() => videoInput.current?.click()}>
          <Video aria-hidden="true" />{t(uploading === 'video' ? 'uploadingVideo' : appearance.videoId ? 'replaceVideo' : 'uploadVideo')}
        </Button>
        {appearance.videoId ? <Button size="icon-sm" variant="ghost" disabled={Boolean(uploading)} aria-label={t('removeVideo')}
          title={t('removeVideo')} onClick={removeVideo}><Trash2 aria-hidden="true" /></Button> : null}
        <span id={`${id}-video-hint`} className="chat-appearance-hint">{t('videoUploadHint')}</span>
      </div>
      {appearance.background === 'video' && appearance.videoId ? <div className="chat-appearance-video">
        {media.posterURL ? <img src={media.posterURL} alt={t('videoPreview')} /> : null}
        {media.loading ? <p className="chat-appearance-hint" role="status">{t('loadingVideo')}</p> : null}
        {media.error ? <p className="chat-appearance-error" role="alert">{t(media.error)}</p> : null}
        <p className="chat-appearance-hint">{t('videoPlaybackHint')}</p>
      </div> : null}
      {error ? <p className="chat-appearance-error" role="alert">{t(error)}</p> : null}
    </fieldset>

    <fieldset className="chat-appearance-fieldset">
      <legend>{t('placement')}</legend>
      <div className="chat-appearance-segments">
        <button type="button" aria-pressed={fullApp} onClick={() => setAppearance({ targets: [...BACKGROUND_TARGETS] })}>{t('placement.all')}</button>
        <button type="button" aria-pressed={!fullApp} onClick={() => { if (fullApp) setAppearance({ targets: ['chat'] }); }}>{t('placement.selected')}</button>
      </div>
      <div className="chat-appearance-targets">
        {BACKGROUND_TARGETS.map(target => <label key={target}>
          <input type="checkbox" checked={appearance.targets.includes(target)} onChange={event => {
            const checked = event.target.checked;
            setAppearance(previous => ({ targets: checked ? [...previous.targets, target] : previous.targets.filter(item => item !== target) }));
          }} />
          <span>{t(`target.${target}`)}</span>
        </label>)}
      </div>
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

    <fieldset className="chat-appearance-fieldset">
      <legend>{t('material')}</legend>
      <div className="chat-appearance-segments">
        {(['glass', 'solid'] as const).map(material => <button key={material} type="button"
          aria-pressed={appearance.material === material} onClick={() => setAppearance({ material })}>{t(`material.${material}`)}</button>)}
      </div>
      <p className="chat-appearance-hint chat-appearance-material-hint">{t('materialHint')}</p>
    </fieldset>

    <div className="chat-appearance-sliders">
      <div className="chat-appearance-slider">
        <div><label htmlFor={`${id}-opacity`}>{t('opacity')}</label>
          <output htmlFor={`${id}-opacity`}>{t('opacityValue', { value: appearance.opacity })}</output></div>
        <input id={`${id}-opacity`} type="range" min={15} max={100} step={1} value={appearance.opacity} disabled={appearance.material === 'solid'}
          aria-valuetext={t('opacityValue', { value: appearance.opacity })}
          onChange={(event) => setAppearance({ opacity: Number(event.target.value) })} />
      </div>
      <div className="chat-appearance-slider">
        <div><label htmlFor={`${id}-blur`}>{t('blur')}</label>
          <output htmlFor={`${id}-blur`}>{t('blurValue', { value: appearance.blur })}</output></div>
        <input id={`${id}-blur`} type="range" min={0} max={16} step={1} value={appearance.blur} disabled={appearance.material === 'solid'}
          aria-valuetext={t('blurValue', { value: appearance.blur })}
          onChange={(event) => setAppearance({ blur: Number(event.target.value) })} />
      </div>
    </div>

    <div className="chat-appearance-sidebar">
      <div><label htmlFor={`${id}-sidebar`}>{t('sidebarVisible')}</label><p className="chat-appearance-hint">{t('sidebarHint')}</p></div>
      <Switch id={`${id}-sidebar`} checked={appearance.sidebarVisible} label={t('sidebarVisible')}
        onChange={sidebarVisible => setAppearance({ sidebarVisible })} />
    </div>

    <div className="chat-appearance-footer">
      <p><ShieldCheck aria-hidden="true" /><span>{t('privacy')}</span></p>
      <Button size="sm" variant="ghost" disabled={Boolean(uploading)} onClick={() => {
        const previousId = appearance.videoId;
        if (resetAppearance() && previousId) void deleteWallpaperVideo(previousId).catch(() => {});
        setError(null);
      }}>
        <RotateCcw aria-hidden="true" />{t('reset')}
      </Button>
    </div>
  </div>;
}

export function ChatAppearanceSettings() {
  const id = useId();
  return <section className="chat-appearance-settings" aria-labelledby={id}>
    <header><h3 id={id}>{t('settingsTitle')}</h3><p>{t('settingsDescription')}</p></header>
    <ChatAppearanceControls />
  </section>;
}
