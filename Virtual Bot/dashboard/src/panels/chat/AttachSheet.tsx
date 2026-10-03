import { useEffect, useLayoutEffect, useRef } from 'react';
import { Cable, Camera, ChevronRight, FileText, Image, ImagePlus, PanelRightOpen, Wrench, X } from 'lucide-react';
import { ContextMeter } from './ContextMeter';
import { t } from '@/locales/chat';
import { t as connectorT } from '@/locales/connectors';
import { t as imageT } from '@/locales/imageGeneration';
import './attach-sheet.css';

/*
 * Attachment actions sit above the composer without pushing its draft or
 * conversation out of view. Desktop uses compact rows; touch uses media tiles.
 * Hidden inputs stay mounted so an OS file-picker result cannot be lost.
 */

/*
 * Picked explicitly rather than `image/*`: given the generic type, iOS hands
 * over HEIC originals that neither the models nor the browser preview can
 * read; with named types it transcodes to JPEG on the way out.
 */
const IMAGE_TYPES = 'image/png,image/jpeg,image/webp,image/gif';
const FILE_TYPES = `${IMAGE_TYPES},.txt,.md,.json,.csv,.tsv,.pdf,.docx,.py,.js,.ts,.tsx,.jsx,.html,.css,.yaml,.yml,.xml,.sql,.log`;

function Tile({ icon, label, onClick }: { icon: React.ReactNode; label: string; onClick: () => void }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="attach-media-tile"
    >
      {icon}
      <span>{label}</span>
    </button>
  );
}

function Row({ icon, label, onClick }: { icon: React.ReactNode; label: string; onClick: () => void }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="attach-action-row"
    >
      {icon}
      <span>{label}</span>
      <ChevronRight className="ml-auto size-4 text-ink-3" />
    </button>
  );
}

export function AttachSheet({
  open,
  onClose,
  anchor,
  onFiles,
  onTools,
  onPanels,
  onConnectors,
  onImageGeneration,
  context,
  compact = false,
}: {
  open: boolean;
  onClose: () => void;
  /** The "+" that toggles the sheet — a tap on it is not an outside tap. */
  anchor: React.RefObject<HTMLElement | null>;
  onFiles: (files: File[]) => void;
  onTools: () => void;
  onPanels: () => void;
  onConnectors: () => void;
  onImageGeneration: () => void;
  context: React.ComponentProps<typeof ContextMeter>;
  compact?: boolean;
}) {
  const root = useRef<HTMLDivElement>(null);
  const camera = useRef<HTMLInputElement>(null);
  const photos = useRef<HTMLInputElement>(null);
  const files = useRef<HTMLInputElement>(null);

  useLayoutEffect(() => {
    const panel = root.current;
    const composer = panel?.parentElement;
    if (!open || !panel || !composer) return;
    const fit = () => {
      // A tall draft, attachment row or on-screen keyboard can leave less room
      // than a fixed viewport percentage. Scroll the menu within that space.
      const available = composer.getBoundingClientRect().top - 68;
      panel.style.maxHeight = `${Math.max(0, Math.min(480, available))}px`;
    };
    fit();
    // The actions precede the trigger in DOM order. Start keyboard navigation
    // inside the menu instead of sending Tab past it into the composer.
    panel.querySelector<HTMLButtonElement>('.attach-media-rows button, .attach-media-grid button')?.focus({ preventScroll: true });
    const observer = new ResizeObserver(fit);
    observer.observe(composer);
    window.addEventListener('resize', fit);
    window.visualViewport?.addEventListener('resize', fit);
    return () => { observer.disconnect(); window.removeEventListener('resize', fit); window.visualViewport?.removeEventListener('resize', fit); };
  }, [open]);

  useEffect(() => {
    if (!open) return;
    const onDown = (event: PointerEvent) => {
      const target = event.target as Element;
      if (root.current?.contains(target) || anchor.current?.contains(target)) return;
      // The context breakdown opens in a portal; a tap inside it belongs to
      // the sheet, and closing here would tear the popover down under the
      // finger.
      if (target.closest?.('[data-radix-popper-content-wrapper]')) return;
      onClose();
    };
    // Escape peels one layer: with the context breakdown open, it closes
    // that and leaves the sheet — Radix handles the popover's own Escape
    // first, while its content is still in the DOM.
    const onKey = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return;
      if (document.querySelector('[data-radix-popper-content-wrapper]')) return;
      onClose();
      anchor.current?.focus({ preventScroll: true });
    };
    document.addEventListener('pointerdown', onDown);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('pointerdown', onDown);
      document.removeEventListener('keydown', onKey);
    };
  }, [open, onClose, anchor]);

  /** One handler for all three inputs: hand the files over, reset, close. */
  const picked = (event: React.ChangeEvent<HTMLInputElement>) => {
    const list = event.currentTarget.files;
    if (list?.length) onFiles(Array.from(list));
    // Cleared so that picking the same photo twice still fires `change`.
    event.currentTarget.value = '';
    onClose();
  };

  return (
    <>
      {/* The inputs stay mounted while the sheet is closed: the OS picker
          returns after the sheet has already gone, and an unmounted input
          would drop the files on the floor. */}
      <input ref={camera} type="file" accept={IMAGE_TYPES} capture="environment" className="hidden" tabIndex={-1} aria-hidden="true" onChange={picked} />
      <input ref={photos} type="file" accept={IMAGE_TYPES} multiple className="hidden" tabIndex={-1} aria-hidden="true" onChange={picked} />
      <input ref={files} data-attachment-picker="files" type="file" accept={FILE_TYPES} multiple className="hidden" tabIndex={-1} aria-hidden="true" onChange={picked} />

      {open ? (
        <div
          ref={root}
          role="dialog"
          aria-label={t('composer.add')}
          id="chat-attachment-menu"
          data-attachment-menu=""
          data-popup-kind="attachments"
          data-state="open"
          className={`attach-sheet popup-shell u-pop ${compact ? 'is-compact' : ''}`}
        >
          <header className="attach-sheet-heading"><div><p>{t('composer.add')}</p><span>{t('sheet.hint')}</span></div>
            <button type="button" aria-label={t('sheet.close')} onClick={() => { onClose(); anchor.current?.focus({ preventScroll: true }); }}><X size={16} /></button>
          </header>
          {compact ? <div className="attach-media-rows">
            <Row icon={<Image />} label={t('sheet.photos')} onClick={() => photos.current?.click()} />
            <Row icon={<FileText />} label={t('sheet.files')} onClick={() => files.current?.click()} />
          </div> : <div className="attach-media-grid">
            <Tile icon={<Camera />} label={t('sheet.camera')} onClick={() => camera.current?.click()} />
            <Tile icon={<Image />} label={t('sheet.photos')} onClick={() => photos.current?.click()} />
            <Tile icon={<FileText />} label={t('sheet.files')} onClick={() => files.current?.click()} />
          </div>}
          <div className="attach-sheet-options">
          <Row icon={<ImagePlus />} label={imageT('title')} onClick={onImageGeneration} />
          <Row icon={<Cable />} label={connectorT('connectors.title')} onClick={onConnectors} />
          <Row icon={<Wrench />} label={t('sheet.tools')} onClick={onTools} />
          <Row icon={<PanelRightOpen />} label={t('sheet.panels')} onClick={onPanels} />
          </div>
          {!compact ? <div className="attach-context"><ContextMeter {...context} variant="row" /></div> : null}
        </div>
      ) : null}
    </>
  );
}
