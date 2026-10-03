/** Check media in this browser without playback or requiring cross-origin canvas access. */
export function probeWallpaperSource(url: string, kind: 'image' | 'video', signal: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    const image = kind === 'image' ? new Image() : null;
    const video = kind === 'video' ? document.createElement('video') : null;
    const media = image ?? video!;
    const readyEvent = image ? 'load' : 'loadeddata';
    let settled = false;
    const finish = (error?: Error) => {
      if (settled) return;
      settled = true;
      clearTimeout(timeout);
      media.removeEventListener(readyEvent, ready);
      media.removeEventListener('error', failed);
      signal.removeEventListener('abort', aborted);
      if (video) video.pause();
      media.removeAttribute('src');
      if (video) video.load();
      if (error) reject(error);
      else resolve();
    };
    const failed = () => finish(new Error('wallpaperSourceUnavailable'));
    const aborted = () => finish(new DOMException('Wallpaper source check was cancelled', 'AbortError'));
    const ready = () => {
      const width = image ? image.naturalWidth : video!.videoWidth;
      const height = image ? image.naturalHeight : video!.videoHeight;
      if (width > 0 && height > 0) finish();
      else failed();
    };
    const timeout = setTimeout(failed, 10_000);
    media.addEventListener(readyEvent, ready);
    media.addEventListener('error', failed);
    signal.addEventListener('abort', aborted, { once: true });
    if (signal.aborted) { aborted(); return; }
    if (video) {
      video.muted = true;
      video.defaultMuted = true;
      video.playsInline = true;
      // Stop loading as soon as the browser has decoded a valid first frame.
      video.preload = 'auto';
    }
    media.src = url;
    if (video) video.load();
  });
}
