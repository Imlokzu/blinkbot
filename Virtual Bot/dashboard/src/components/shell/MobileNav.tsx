import { useDockSide } from '@/hooks/useDockSide';
import { useIsPhone } from '@/hooks/useMediaQuery';
import { cn } from '@/lib/cn';
import { findSection } from '@/app/sections';
import { t } from '@/lib/i18n';
import { MOBILE_SECTION_IDS } from './mobileNavData';
import { mobileSectionLabel } from './mobileNavLabels';

export function MobileNav({ current, onNavigate }: { current: string; onNavigate: (id: string) => void }) {
  const isPhone = useIsPhone();
  const [side] = useDockSide();

  if (!isPhone) return null;

  return (
    <nav
      className="mobile-nav u-safe-b"
      data-dock={side}
      aria-label={t('nav.sections')}
    >
      <div className="mobile-nav__items">
        {MOBILE_SECTION_IDS.map((id) => {
          const item = findSection(id);
          const Icon = item.icon;
          const active = current === id;
          return (
            <button
              key={id}
              type="button"
              aria-current={active ? 'page' : undefined}
              className={cn('mobile-nav__item', active && 'mobile-nav__item--active')}
              onClick={() => onNavigate(id)}
            >
              <span className="mobile-nav__icon" aria-hidden="true">
                <Icon size={18} strokeWidth={active ? 2.1 : 1.75} />
              </span>
              <span>{mobileSectionLabel(id) ?? item.label}</span>
            </button>
          );
        })}
      </div>
    </nav>
  );
}
