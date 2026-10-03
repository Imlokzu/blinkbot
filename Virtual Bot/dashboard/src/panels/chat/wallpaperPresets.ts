import type { ChatAppearanceKey } from '@/locales/chatAppearance';

export const WALLPAPER_PRESET_IDS = ['coast-photo', 'forest-photo', 'clouds-video', 'stars-video'] as const;
export type WallpaperPresetId = typeof WALLPAPER_PRESET_IDS[number];
export interface WallpaperPreset {
  id: WallpaperPresetId;
  kind: 'image' | 'video';
  labelKey: ChatAppearanceKey;
  src: string;
  thumbnail: string;
}

const coast = new URL('./assets/wallpapers/coast-photo.jpg', import.meta.url).href;
const forest = new URL('./assets/wallpapers/forest-photo.jpg', import.meta.url).href;
export const WALLPAPER_PRESETS: readonly WallpaperPreset[] = Object.freeze([
  { id: 'coast-photo', kind: 'image', labelKey: 'preset.coastPhoto', src: coast, thumbnail: coast },
  { id: 'forest-photo', kind: 'image', labelKey: 'preset.forestPhoto', src: forest, thumbnail: forest },
  { id: 'clouds-video', kind: 'video', labelKey: 'preset.cloudsVideo',
    src: new URL('./assets/wallpapers/clouds-video.mp4', import.meta.url).href,
    thumbnail: new URL('./assets/wallpapers/clouds-video-poster.jpg', import.meta.url).href },
  { id: 'stars-video', kind: 'video', labelKey: 'preset.starsVideo',
    src: new URL('./assets/wallpapers/stars-video.mp4', import.meta.url).href,
    thumbnail: new URL('./assets/wallpapers/stars-video-poster.jpg', import.meta.url).href },
]);

export function getWallpaperPreset(value: unknown): WallpaperPreset | null {
  return WALLPAPER_PRESETS.find(preset => preset.id === value) ?? null;
}
