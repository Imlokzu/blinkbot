import { useEffect, useState } from 'react';
import { Menu } from '../../vendor/solar-icons/compat.ts';
import { Dialog, DialogContent, DialogTrigger } from '@/components/ui/Dialog';
import { Button } from '@/components/ui/Button';
import { SECTIONS } from '@/app/sections';
import { useRoute } from '@/app/useRoute';
import { cn } from '@/lib/cn';
import { t } from '@/lib/i18n';
import { mobileSectionLabel } from './mobileNavLabels';

/** Phone chat owns its menu; other pages expose the same menu in their header. */
export function PhoneNavigationMenu() {
  const [section, navigate] = useRoute();
  const [open, setOpen] = useState(false);
  useEffect(() => {
    const show = () => setOpen(section !== 'chat');
    const hide = () => setOpen(false);
    window.addEventListener('vbot:open-drawer', show);
    window.addEventListener('vbot:close-drawer', hide);
    return () => {
      window.removeEventListener('vbot:open-drawer', show);
      window.removeEventListener('vbot:close-drawer', hide);
    };
  }, [section]);

  return <Dialog open={open} onOpenChange={next => {
    if (next) window.dispatchEvent(new Event('vbot:close-drawer'));
    setOpen(next);
  }}>
    <DialogTrigger asChild>
      <Button data-phone-navigation variant="ghost" size="icon" aria-label={t('nav.menu')}
        aria-expanded={open} title={t('nav.menu')}><Menu aria-hidden="true" size={18} /></Button>
    </DialogTrigger>
    <DialogContent side="left" title={t('nav.sections')} description={t('nav.all')}
      className="phone-navigation-dialog" bodyClassName="p-2 sm:p-2" onCloseAutoFocus={event => {
        event.preventDefault();
        requestAnimationFrame(() => {
          // An edge gesture can hand navigation focus to the conversations drawer.
          const dialog = document.querySelector<HTMLElement>('[role="dialog"]:not([data-state="closed"])');
          if (dialog) {
            if (!dialog.contains(document.activeElement)) {
              dialog.querySelector<HTMLButtonElement>('button:not([disabled]):not([aria-disabled="true"])')?.focus({ preventScroll: true });
            }
            return;
          }
          // A route change can replace the chat trigger with the page-header trigger.
          document.querySelector<HTMLButtonElement>('[data-phone-navigation]')?.focus({ preventScroll: true });
        });
      }}>
      <nav aria-label={t('nav.all')}>
        {SECTIONS.map(item => {
          const Icon = item.icon;
          const active = item.id === section;
          return <button key={item.id} type="button" data-phone-section={item.id}
            aria-current={active ? 'page' : undefined}
            className={cn('flex min-h-11 w-full items-center gap-3 rounded-md px-3 text-left text-[14px] outline-none focus-visible:ring-2 focus-visible:ring-accent', active ? 'bg-accent-soft font-medium text-ink' : 'text-ink-2 hover:bg-surface-2')}
            onClick={() => { setOpen(false); navigate(item.id); }}>
            <Icon aria-hidden="true" size={17} strokeWidth={active ? 2.1 : 1.75} />
            <span>{mobileSectionLabel(item.id) ?? item.label}</span>
          </button>;
        })}
      </nav>
    </DialogContent>
  </Dialog>;
}
