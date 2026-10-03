import { Sparkle } from 'lucide-react';
import { BRAND_ICONS, type Brand } from '@/vendor/lobe-icons';
import { cn } from '@/lib/cn';
import { brandOf, type CatalogModel } from './modelCatalog';
import { brandColor } from './brandColors';

type BrandLogoProps = ({ model: CatalogModel; brand?: never } | { brand: Brand; model?: never }) & {
  className?: string;
  /** Inherit surrounding ink in places that deliberately use one colour. */
  monochrome?: boolean;
};

/**
 * The maker's logo for a model, or a neutral mark when the maker is unknown.
 *
 * The markup is the vendored lobe-icons set — static strings shipped with
 * the bundle, never model or network data — so injecting it is safe.
 */
export function BrandLogo({ model, brand: explicitBrand, className, monochrome = false }: BrandLogoProps) {
  const brand = explicitBrand ?? (model ? brandOf(model) : null);
  if (!brand) return <Sparkle aria-hidden="true" className={cn('size-4 shrink-0 text-ink-3', className)} />;
  return (
    <svg
      aria-hidden="true"
      viewBox="0 0 24 24"
      fill="currentColor"
      fillRule="evenodd"
      data-brand={brand}
      style={monochrome ? undefined : { color: brandColor(brand) }}
      className={cn('size-4 shrink-0', className)}
      dangerouslySetInnerHTML={{ __html: BRAND_ICONS[brand] }}
    />
  );
}
