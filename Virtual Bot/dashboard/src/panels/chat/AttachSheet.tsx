import { useEffect, useLayoutEffect, useRef } from 'react';
import { Cable, Camera, FileText, Image, ImagePlus, PanelRightOpen, Wrench, X } from '../../vendor/solar-icons/compat.ts';
import { ContextMeter } from './ContextMeter';
import { t } from '@/locales/chat';
import { t as benchT } from '@/locales/workbench';
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

function Tile({ icon, label, action, onClick }: { icon: React.ReactNode; label: string; action: string; onClick: () => void }) {
  return (
    <button
      type="button"
      onClick={onClick}
      data-attachment-action={action}
      className="attach-media-tile"
    >
      {icon}
      <span>{label}</span>
    </button>
  );
}

function Row({ icon, label, action, onClick }: { icon: React.ReactNode; label: string; action: string; onClick: () => void }) {
  return (
    <button
      type="button"
      onClick={onClick}
      data-attachment-action={action}
      className="attach-action-row"
    >
      {icon}
      <span>{label}</span>
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
  onWorkbench,
  onConnectors,
  onImageGeneration,
  context,
  compact = false,
}: {
  open: boolean;
  onClose: () => void;
  /** The attachment trigger is part of the menu's interaction area. */
  anchor: React.RefObject<HTMLElement | null>;
  onFiles: (files: File[]) => void;
  onTools: () => void;
  onPanels: () => void;
  onWorkbench?: () => void;
  onConnectors: () => void;
  onImageGeneration: () => void;
  context: React.ComponentProps<typeof ContextMeter>;
  compact?: boolean;
}) {
  const root = useRef<HTMLDivElement>(null);
  const keyboardEntry = useRef(false);
  const wasOpen = useRef(false);
  const camera = useRef<HTMLInputElement>(null);
  const photos = useRef<HTMLInputElement>(null);
  const files = useRef<HTMLInputElement>(null);

  useLayoutEffect(() => {
    const panel = root.current;
    const composer = panel?.parentElement;
    if (!open) { keyboardEntry.current = false; wasOpen.current = false; return; }
    if (!panel || !composer) return;
    if (!wasOpen.current) {
      const activation = anchor.current?.dataset.attachmentActivation;
      keyboardEntry.current = activation === 'keyboard' || (!activation && Boolean(anchor.current?.matches(':focus-visible')));
      wasOpen.current = true;
    }
    const fit = () => {
      const bounds = composer.getBoundingClientRect();
      const viewport = window.visualViewport;
      const visibleTop = viewport?.offsetTop ?? 0;
      const shellBottom = document.querySelector('.app-shell')?.getBoundingClientRect().bottom ?? innerHeight;
      const visibleBottom = Math.min(shellBottom, visibleTop + (viewport?.height ?? innerHeight));
      const headerBottom = Math.max(visibleTop, ...Array.from(document.querySelectorAll('[data-global-topbar], .chat-phone-toolbar, .chat-narrow-toolbar'))
        .map(header => header.getBoundingClientRect().bottom));
      const floor = headerBottom + 8;
      const above = bounds.top - floor - 8;
      const below = visibleBottom - bounds.bottom - 16;
      // A short welcome window may fit neither side. Keep the menu usable
      // inside that visible area without moving or remounting the draft.
      const placement = above >= 180 ? 'above' : below >= 180 ? 'below' : 'viewport';
      const available = placement === 'above' ? above : placement === 'below' ? below : visibleBottom - floor - 12;
      panel.dataset.placement = placement;
      panel.style.top = placement === 'above' ? 'auto' : placement === 'below' ? 'calc(100% + 8px)' : `${floor - bounds.top}px`;
      panel.style.bottom = placement === 'above' ? 'calc(100% + 8px)' : 'auto';
      panel.style.maxHeight = `${Math.max(0, Math.min(480, available))}px`;
      const focused = document.activeElement;
      if (focused instanceof HTMLElement && focused !== panel && panel.contains(focused)) {
        const action = focused.getBoundingClientRect();
        const menu = panel.getBoundingClientRect();
        if (action.top < menu.top + 6) panel.scrollTop -= menu.top + 6 - action.top;
        else if (action.bottom > menu.bottom - 6) panel.scrollTop += action.bottom - menu.bottom + 6;
      }
    };
    fit();
    // Pointer opening does not put an orange focus frame on the first action.
    // Keyboard opening still starts on a useful choice; both enter the dialog.
    if (keyboardEntry.current) {
      panel.querySelector<HTMLButtonElement>('.attach-media-rows button, .attach-media-grid button')?.focus({ preventScroll: true });
    } else panel.focus({ preventScroll: true });
    const observer = new ResizeObserver(fit);
    observer.observe(composer);
    const movement = new MutationObserver(fit);
    const position = composer.closest('[data-chat-composer-position]');
    if (position) movement.observe(position, { attributes: true, attributeFilter: ['style'] });
    window.addEventListener('resize', fit);
    window.visualViewport?.addEventListener('resize', fit);
    window.visualViewport?.addEventListener('scroll', fit);
    return () => {
      observer.disconnect(); movement.disconnect();
      window.removeEventListener('resize', fit);
      window.visualViewport?.removeEventListener('resize', fit);
      window.visualViewport?.removeEventListener('scroll', fit);
    };
  }, [open, anchor, compact]);

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
      if (root.current?.contains(document.activeElement)) keyboardEntry.current = true;
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
          tabIndex={-1}
          aria-label={t('composer.add')}
          id="chat-attachment-menu"
          data-attachment-menu=""
          data-popup-kind="attachments"
          data-state="open"
          className={`attach-sheet popup-shell u-pop ${compact ? 'is-compact' : ''}`}
        >
          <header className="attach-sheet-heading"><p>{t('composer.add')}</p>
            <button type="button" aria-label={t('sheet.close')} onClick={() => { onClose(); anchor.current?.focus({ preventScroll: true }); }}><X size={16} /></button>
          </header>
          {compact ? <div className="attach-media-rows">
            <Row icon={<Image />} label={t('sheet.photos')} action="photos" onClick={() => photos.current?.click()} />
            <Row icon={<FileText />} label={t('sheet.files')} action="files" onClick={() => files.current?.click()} />
          </div> : <div className="attach-media-grid">
            <Tile icon={<Camera />} label={t('sheet.camera')} action="camera" onClick={() => camera.current?.click()} />
            <Tile icon={<Image />} label={t('sheet.photos')} action="photos" onClick={() => photos.current?.click()} />
            <Tile icon={<FileText />} label={t('sheet.files')} action="files" onClick={() => files.current?.click()} />
          </div>}
          <div className="attach-sheet-options">
          <Row icon={<ImagePlus />} label={imageT('title')} action="image" onClick={onImageGeneration} />
          <Row icon={<Cable />} label={connectorT('connectors.title')} action="connectors" onClick={onConnectors} />
          <Row icon={<Wrench />} label={t('sheet.tools')} action="tools" onClick={onTools} />
          <Row icon={<PanelRightOpen />} label={t('sheet.panels')} action="panels" onClick={() => {
            anchor.current?.focus({ preventScroll: true });
            onPanels();
          }} />
          {onWorkbench ? <Row icon={<PanelRightOpen />} label={benchT('wb.open')} action="workbench" onClick={onWorkbench} /> : null}
          </div>
          {!compact ? <div className="attach-context"><ContextMeter {...context} variant="row" /></div> : null}
        </div>
      ) : null}
    </>
  );
}
