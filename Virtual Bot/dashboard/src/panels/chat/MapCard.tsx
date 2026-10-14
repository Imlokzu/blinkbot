import { useContext, useEffect, useId, useRef, useState } from 'react';
import type { Map as LeafletMap, TileEvent } from 'leaflet';
import { Button } from '@/components/ui/Button';
import { Plus, Minus, Compass } from '@/vendor/solar-icons/compat';
import { t } from '@/locales/maps';
import { mapData, mapLink, type MapData } from './mapData';
import { InteractiveSessionContext, ToolOwnerContext } from './InteractiveSessionContext';
import type { ToolStep } from './types';
import 'leaflet/dist/leaflet.css';
import './map-card.css';

export function MapCard({ step }: { step: ToolStep }) {
  const owner = useContext(ToolOwnerContext), session = useContext(InteractiveSessionContext);
  const data = mapData(step);
  if (!data) return <section className="map-card" data-map-card="">
    <p className="map-card-label">{t('label')}</p>
    <p role="status">{t(step.status === 'active' ? 'loading' : step.status === 'interrupted' ? 'interrupted' : 'failed')}</p>
  </section>;
  return <LocationMap key={JSON.stringify([owner, session, data])} data={data} />;
}

function LocationMap({ data }: { data: MapData }) {
  const container = useRef<HTMLDivElement>(null), map = useRef<LeafletMap | null>(null);
  const hintId = useId();
  const [visible, setVisible] = useState(false), [generation, setGeneration] = useState(0);
  const [ready, setReady] = useState(false), [loaded, setLoaded] = useState(false), [failed, setFailed] = useState(false);
  const [zoom, setZoom] = useState(data.zoom);
  useEffect(() => {
    if (!container.current) return;
    if (!('IntersectionObserver' in window)) { setVisible(true); return; }
    const observer = new IntersectionObserver(entries => {
      if (entries.some(entry => entry.isIntersecting)) { setVisible(true); observer.disconnect(); }
    });
    observer.observe(container.current);
    return () => observer.disconnect();
  }, []);
  useEffect(() => {
    if (!visible || !container.current) return;
    let cancelled = false, instance: LeafletMap | null = null, resize: ResizeObserver | null = null;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let timedOut = false;
    const pending = new Set<HTMLElement>(), errors = new Set<HTMLElement>();
    const beginLoading = () => {
      if (cancelled) return;
      clearTimeout(timer); timedOut = false;
      setFailed(errors.size > 0);
      timer = setTimeout(() => { if (!cancelled) { timedOut = true; setFailed(true); } }, 15000);
    };
    const updateTiles = () => {
      if (cancelled) return;
      if (!pending.size) { clearTimeout(timer); timedOut = false; }
      setFailed(timedOut || errors.size > 0);
    };
    const dispose = () => {
      clearTimeout(timer); resize?.disconnect(); resize = null;
      const previous = instance; instance = null;
      if (map.current === previous) map.current = null;
      previous?.remove();
    };
    setReady(false); setLoaded(false); setFailed(false);
    // Cover a stalled JavaScript chunk as well as stalled image requests.
    beginLoading();
    void import('leaflet').then(L => {
      if (cancelled || !container.current) return;
      const reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
      instance = L.map(container.current, { zoomControl: false, attributionControl: false, minZoom: 2, maxZoom: 18,
        scrollWheelZoom: false, zoomAnimation: !reduced, fadeAnimation: !reduced, markerZoomAnimation: !reduced,
        inertia: !reduced, maxBounds: [[-85, -180], [85, 180]], maxBoundsViscosity: 1,
      });
      map.current = instance;
      instance.setView([data.latitude, data.longitude], data.zoom);
      instance.on('zoomend', () => { if (!cancelled && instance) setZoom(instance.getZoom()); });
      const layer = L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
        maxZoom: 18, noWrap: true, updateWhenIdle: true, keepBuffer: 1,
        referrerPolicy: 'strict-origin-when-cross-origin',
      });
      layer.on('loading', beginLoading);
      layer.on('tileloadstart', event => pending.add(event.tile));
      layer.on('tileload', event => {
        pending.delete(event.tile); errors.delete(event.tile);
        if (!cancelled) setLoaded(true);
        updateTiles();
      });
      layer.on('tileerror', event => {
        if (!pending.delete(event.tile)) return;
        errors.add(event.tile); updateTiles();
      });
      const discardTile = (event: TileEvent) => {
        pending.delete(event.tile); errors.delete(event.tile); updateTiles();
      };
      layer.on('tileunload', discardTile);
      layer.on('tileabort', discardTile);
      layer.addTo(instance);
      const label = document.createElement('span'); label.textContent = data.title;
      L.marker([data.latitude, data.longitude], { title: data.title, alt: data.title,
        icon: L.divIcon({ className: 'map-card-pin', html: '<span></span>', iconSize: [20, 20], iconAnchor: [10, 10] }),
      }).bindTooltip(label, { permanent: true, direction: 'top', offset: [0, -10] }).addTo(instance);
      resize = new ResizeObserver(() => instance?.invalidateSize({ animate: false }));
      resize.observe(container.current);
      setReady(true); setZoom(data.zoom);
    }).catch(() => { if (!cancelled) { dispose(); setReady(false); setFailed(true); } });
    return () => { cancelled = true; dispose(); };
  }, [data.latitude, data.longitude, data.title, data.zoom, visible, generation]);
  return <section className="map-card" data-map-card="" data-zoom={zoom} aria-label={data.title}>
    <header><div><p className="map-card-label">{t('label')}</p><h3>{data.title}</h3></div>
      <div className="map-card-actions">
        <Button variant="ghost" size="icon-sm" disabled={!ready || zoom <= 2} aria-label={t('zoomOut')} onClick={() => map.current?.zoomOut()}><Minus /></Button>
        <Button variant="ghost" size="icon-sm" disabled={!ready || zoom >= 18} aria-label={t('zoomIn')} onClick={() => map.current?.zoomIn()}><Plus /></Button>
        <Button variant="quiet" size="sm" disabled={!ready} onClick={() => map.current?.setView([data.latitude, data.longitude], data.zoom, { animate: false })}><Compass />{t('reset')}</Button>
      </div>
    </header>
    <div className="map-card-viewport">
      <div className="map-card-canvas" ref={container} role="region" aria-label={data.title} aria-describedby={hintId} aria-busy={visible && !loaded && !failed} />
      <div className="map-card-attribution">© <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener noreferrer">{t('attribution')}</a></div>
    </div>
    <p id={hintId} className="sr-only">{t('hint')}</p>
    {visible && !loaded && !failed ? <p className="map-card-status" role="status">{t('loading')}</p> : null}
    {failed ? <div className="map-card-status" role="alert"><p>{ready ? t('tilesFailed') : t('failed')}</p>
      <div className="map-card-actions"><Button variant="quiet" size="sm" onClick={() => setGeneration(value => value + 1)}>{t('retry')}</Button>
        <a href={mapLink(data)} target="_blank" rel="noopener noreferrer">{t('open')}</a>
      </div>
    </div> : null}
  </section>;
}
