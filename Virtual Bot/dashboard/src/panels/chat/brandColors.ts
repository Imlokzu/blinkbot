import type { Brand } from '../../vendor/lobe-icons';
import { brandOf, type CatalogModel } from './modelCatalog.ts';

/** Maker colours with brighter inks for the dashboard's dark surfaces. */
export const BRAND_COLORS: Record<Brand, { light: string; dark: string }> = {
  anthropic: { light: '#b85a40', dark: '#e59a81' },
  openai: { light: '#0f886b', dark: '#65d6b6' },
  deepseek: { light: '#416ff2', dark: '#83a5ff' },
  moonshot: { light: '#343c55', dark: '#c5cee9' },
  minimax: { light: '#cf436f', dark: '#f58daf' },
  xiaomi: { light: '#d7620a', dark: '#ffa85e' },
  zhipu: { light: '#315be8', dark: '#88a7ff' },
  qwen: { light: '#7446dc', dark: '#bc9aff' },
  meituan: { light: '#9b7600', dark: '#f5ce53' },
  xai: { light: '#374151', dark: '#e0e4ea' },
  tencent: { light: '#086bc6', dark: '#75b8ff' },
  google: { light: '#396de1', dark: '#8eb1ff' },
  mistral: { light: '#d2580a', dark: '#ffa363' },
  meta: { light: '#086bd5', dark: '#70b2ff' },
  nvidia: { light: '#568000', dark: '#a3d747' },
};

/** CSS follows the active theme's color-scheme, including manual overrides. */
export function brandColor(brand: Brand | null): string {
  if (!brand) return 'var(--color-ink-3)';
  const { light, dark } = BRAND_COLORS[brand];
  return `light-dark(${light}, ${dark})`;
}

/** Resolve the maker through the catalog so a serving host never sets its ink. */
export function modelColor(model: CatalogModel): string {
  return brandColor(brandOf(model));
}
