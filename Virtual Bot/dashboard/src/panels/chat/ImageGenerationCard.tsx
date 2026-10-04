import { useEffect, useMemo, useState } from 'react';
import { Check, CircleAlert, ImageOff, RotateCcw } from '../../vendor/solar-icons/compat.ts';
import { AnimatePresence, motion } from 'motion/react';
import { Button } from '@/components/ui/Button';
import { t } from '@/locales/imageGeneration';
import { t as chatT } from '@/locales/chat';
import type { ToolStep } from './types';
import { generationView } from './imageGenerationState';
import { ImageGenerationDither } from './ImageGenerationDither';
import { usePrivateImageResource } from './usePrivateImage';
import { useGalleryGroup } from './Gallery';
import './image-generation.css';

/** One stable surface throughout real tool work, image loading and delivery. */
export function ImageGenerationCard({ step, onRetry }: { step: ToolStep; onRetry?: () => void }) {
  const view = generationView(step);
  const [reduced, setReduced] = useState(true);
  useEffect(() => {
    // Motion's hook snapshots this preference; an in-flight card needs updates.
    const preference = window.matchMedia('(prefers-reduced-motion: reduce)');
    const update = () => setReduced(preference.matches);
    update();
    preference.addEventListener('change', update);
    return () => preference.removeEventListener('change', update);
  }, []);
  const resource = usePrivateImageResource(view.url);
  const [decoded, setDecoded] = useState({ src: '', width: 0, height: 0 });
  const [broken, setBroken] = useState('');
  const ready = Boolean(resource.src && decoded.src === resource.src);
  const unavailable = resource.failed || Boolean(resource.src && broken === resource.src);
  const state = view.status === 'complete' ? (unavailable ? 'failed' : ready ? 'complete' : 'loading') : view.status;
  const active = state === 'generating' || state === 'loading';
  const images = useMemo(() => [{ src: resource.src, originalSrc: view.url, alt: view.prompt }], [resource.src, view.url, view.prompt]);
  const { node, open } = useGalleryGroup(images, ready && state === 'complete');
  const statusText = t(unavailable ? 'motion.unavailable' : `motion.${state}`);
  const retry = unavailable ? () => { setBroken(''); resource.reload(); } : onRetry;

  return <div ref={node} className="image-generation-card" data-image-generation="" data-state={state} aria-busy={active}>
    <div className="image-generation-surface">
      {resource.src ? <button type="button" className="image-generation-open" disabled={!ready || state !== 'complete'}
        aria-label={view.prompt ? chatT('image.openNamed', { alt: view.prompt }) : chatT('image.open')} onClick={() => open(0)}>
        <motion.img key={resource.src} className="image-generation-media" src={resource.src} alt={view.prompt}
          draggable={false} initial={false}
          animate={{ opacity: ready ? 1 : 0, scale: reduced ? 1 : ready ? 1 : 1.035 }}
          transition={{ duration: reduced ? 0 : 0.65, ease: [0.22, 1, 0.36, 1] }}
          onLoad={(event) => setDecoded({ src: resource.src, width: event.currentTarget.naturalWidth, height: event.currentTarget.naturalHeight })}
          onError={() => setBroken(resource.src)} />
      </button> : null}
      <AnimatePresence initial={false}>
        {active ? <motion.div key="dither" className="image-generation-field" aria-hidden="true"
          initial={false} animate={{ opacity: 1 }} exit={{ opacity: 0 }} transition={{ duration: reduced ? 0 : 0.5 }}>
          <ImageGenerationDither reduced={reduced} />
        </motion.div> : null}
      </AnimatePresence>
      {!active && state !== 'complete' ? <div className="image-generation-terminal" aria-hidden="true">
        {state === 'interrupted' ? <ImageOff size={24} strokeWidth={1.5} /> : <CircleAlert size={24} strokeWidth={1.5} />}
      </div> : null}
      {ready && state === 'complete' ? <span className="image-generation-resolution">{decoded.width} × {decoded.height}</span> : null}
    </div>
    <div className="image-generation-caption">
      <div className="image-generation-status" role="status" aria-live="polite">
        {active ? <motion.span className="image-generation-mark" aria-hidden="true"
          animate={reduced ? { rotate: 0 } : { rotate: 360 }}
          transition={{ duration: reduced ? 0 : 2.4, ease: 'easeInOut', repeat: reduced ? 0 : Infinity }}>
          <span /><span /><span /><span />
        </motion.span> : state === 'complete' ? <Check size={14} aria-hidden="true" /> : <CircleAlert size={14} aria-hidden="true" />}
        <span>{statusText}</span>
      </div>
      {view.prompt ? <p className="image-generation-prompt" title={view.prompt}>{view.prompt}</p> : null}
      {state === 'failed' || state === 'interrupted' ? retry ? <Button type="button" variant="ghost" size="sm" className="mt-2" onClick={retry}>
        <RotateCcw size={14} aria-hidden="true" />{t('motion.retry')}
      </Button> : null : null}
    </div>
  </div>;
}
