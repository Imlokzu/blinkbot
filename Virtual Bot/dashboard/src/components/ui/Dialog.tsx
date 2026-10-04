import * as RadixDialog from '@radix-ui/react-dialog';
import { X } from '../../vendor/solar-icons/compat.ts';
import { cn } from '@/lib/cn';
import { Button } from './Button';
import { t } from '@/lib/i18n';

/*
 * Modal, bottom sheet and navigation drawer share focus trapping and
 * dismissal. The left drawer gives phone navigation a reachable layout.
 */

export const Dialog = RadixDialog.Root;
export const DialogTrigger = RadixDialog.Trigger;
export const DialogClose = RadixDialog.Close;

export function DialogContent({
  title,
  description,
  side = 'center',
  className,
  bodyClassName,
  onCloseAutoFocus,
  children,
}: {
  title: string;
  description?: string;
  side?: 'center' | 'bottom' | 'left';
  className?: string;
  bodyClassName?: string;
  onCloseAutoFocus?: React.ComponentProps<typeof RadixDialog.Content>['onCloseAutoFocus'];
  children: React.ReactNode;
}) {
  return (
    <RadixDialog.Portal>
      <RadixDialog.Overlay
        className="u-veil fixed inset-0 backdrop-blur-[2px]"
        style={{ background: 'var(--c-overlay)', zIndex: 'var(--z-modal)' }}
      />
      <RadixDialog.Content
        onCloseAutoFocus={onCloseAutoFocus}
        style={{ zIndex: 'var(--z-modal)' }}
        className={cn(
          'fixed border border-line bg-surface shadow-pop outline-none',
          // Each surface enters from the edge that matches its position.
          side === 'center' ? 'u-pop' : side === 'left' ? 'u-sheet-l' : 'u-sheet',
          side === 'center'
            ? 'left-1/2 top-1/2 max-h-[85dvh] w-[min(560px,calc(100vw-2rem))] -translate-x-1/2 -translate-y-1/2 rounded-lg'
            : side === 'left'
              ? 'u-safe-t u-safe-b inset-y-0 left-0 max-h-dvh w-[min(300px,86vw)] rounded-r-xl border-l-0 pl-[env(safe-area-inset-left,0px)]'
              : 'u-safe-b inset-x-0 bottom-0 max-h-[min(85dvh,calc(100dvh-env(safe-area-inset-top,0px)-12px))] rounded-t-xl border-b-0',
          'flex flex-col',
          className,
        )}
      >
        {side === 'bottom' ? (
          <div aria-hidden="true" className="mx-auto mt-2 h-1 w-10 shrink-0 rounded-full bg-line-strong" />
        ) : null}
        <header role="presentation" className="flex items-start justify-between gap-4 border-b border-line px-4 py-3 sm:px-5 sm:py-4">
          <div className="min-w-0">
            <RadixDialog.Title className="text-[15px] font-semibold text-ink">
              {title}
            </RadixDialog.Title>
            {description ? (
              <RadixDialog.Description className="mt-1 text-[13px] text-ink-3">
                {description}
              </RadixDialog.Description>
            ) : null}
          </div>
          <RadixDialog.Close asChild>
            <Button variant="ghost" size="icon-sm" aria-label={t('chat.close')}>
              <X />
            </Button>
          </RadixDialog.Close>
        </header>
        <div className={cn('min-h-0 flex-1 overflow-y-auto overscroll-contain px-4 py-3 sm:px-5 sm:py-4', bodyClassName)}>
          {children}
        </div>
      </RadixDialog.Content>
    </RadixDialog.Portal>
  );
}
