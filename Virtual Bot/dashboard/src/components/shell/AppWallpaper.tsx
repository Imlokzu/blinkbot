import { useEffect, useRef, useState } from 'react';
import { useChatAppearance } from '@/panels/chat/useChatAppearance';
import { useWallpaperMedia } from '@/panels/chat/wallpaperMedia';
import './app-wallpaper.css';

/** One local background video serves every opted-in surface. */
export function AppWallpaper() {
  const { appearance } = useChatAppearance();
  const media = useWallpaperMedia(appearance.background === 'video' ? appearance.videoId : null);
  const video = useRef<HTMLVideoElement>(null);
  const [paused, setPaused] = useState(true);

  useEffect(() => {
    const root = document.documentElement;
    root.dataset.wallpaper = appearance.background;
    root.dataset.backgroundTargets = appearance.targets.join(' ');
    root.dataset.chatMaterial = appearance.material;
    root.style.setProperty('--chat-opacity', `${appearance.opacity}%`);
    root.style.setProperty('--chat-blur', `${appearance.blur}px`);
    return () => {
      delete root.dataset.wallpaper;
      delete root.dataset.backgroundTargets;
      delete root.dataset.chatMaterial;
      root.style.removeProperty('--chat-opacity');
      root.style.removeProperty('--chat-blur');
    };
  }, [appearance.background, appearance.targets, appearance.material, appearance.opacity, appearance.blur]);

  useEffect(() => {
    const motion = window.matchMedia('(prefers-reduced-motion: reduce)');
    const connection = (navigator as Navigator & { connection?: EventTarget & { saveData?: boolean } }).connection;
    const apply = () => {
      const section = window.location.hash.split(/[/?]/)[1] || 'overview';
      const visible = appearance.targets.includes('navigation')
        || (section === 'chat' ? appearance.targets.some((target) => target === 'chat' || target === 'sessions' || target === 'panels')
          : appearance.targets.includes('pages'));
      setPaused(document.hidden || motion.matches || Boolean(connection?.saveData) || !visible);
    };
    apply();
    motion.addEventListener('change', apply);
    document.addEventListener('visibilitychange', apply);
    window.addEventListener('hashchange', apply);
    connection?.addEventListener('change', apply);
    return () => {
      motion.removeEventListener('change', apply);
      document.removeEventListener('visibilitychange', apply);
      window.removeEventListener('hashchange', apply);
      connection?.removeEventListener('change', apply);
    };
  }, [appearance.targets]);

  useEffect(() => {
    const player = video.current;
    if (!player) return;
    player.muted = true;
    if (paused) player.pause();
    else void player.play().catch(() => { /* A blocked autoplay retains the local poster. */ });
    return () => player.pause();
  }, [paused, media.videoURL]);

  const image = appearance.background === 'custom' ? appearance.image
    : appearance.background === 'video' ? media.posterURL : null;
  return <div aria-hidden="true" className="app-wallpaper" data-video-paused={paused ? '' : undefined}
    style={image ? { backgroundImage: `url("${image}")` } : undefined}>
    {appearance.background === 'video' && media.videoURL ? (
      <video ref={video} src={media.videoURL} poster={media.posterURL || undefined} muted loop playsInline
        preload="metadata" tabIndex={-1} />
    ) : null}
  </div>;
}
