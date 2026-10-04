import { forwardRef } from 'react';
import * as DropdownMenu from '@radix-ui/react-dropdown-menu';
import { Check, Columns2, PanelRight, PanelRightClose } from '../../vendor/solar-icons/compat.ts';
import { Button } from '@/components/ui/Button';
import { t } from '@/locales/chatLayout';

export type RightPanelMode = 'hidden' | 'panels' | 'workbench';
const MODES = ['hidden', 'panels', 'workbench'] as const;
const ICONS = { hidden: PanelRightClose, panels: Columns2, workbench: PanelRight };

/** One explicit choice for the column that panels and the workbench share. */
export const RightPanelMenu = forwardRef<HTMLButtonElement, {
  mode: RightPanelMode;
  files: number;
  onChange: (mode: RightPanelMode) => void;
}>(function RightPanelMenu({ mode, files, onChange }, ref) {
  return (
    <DropdownMenu.Root>
      <DropdownMenu.Trigger asChild>
        <Button ref={ref} variant="ghost" size="icon-sm" data-right-panel-trigger data-panel-mode={mode}
          aria-label={t('rightPanel')} title={t('current', { mode: t(mode) })}>
          <PanelRight />
          {mode === 'workbench' && files ? <span className="font-mono text-[10px] text-ink-3" aria-hidden="true">{files}</span> : null}
        </Button>
      </DropdownMenu.Trigger>
      <DropdownMenu.Portal>
        <DropdownMenu.Content data-right-panel-menu data-popup-kind="menus" align="end" sideOffset={6} collisionPadding={12}
          aria-label={t('rightPanel')}
          className="popup-shell u-pop min-w-48 rounded-lg border border-line bg-surface p-1.5 text-[13px] text-ink shadow-pop"
          style={{ zIndex: 'var(--z-pop)', transformOrigin: 'var(--radix-dropdown-menu-content-transform-origin)' }}>
          <DropdownMenu.RadioGroup value={mode} onValueChange={(value) => onChange(value as RightPanelMode)}>
            {MODES.map((value) => {
              const Icon = ICONS[value];
              return (
                <DropdownMenu.RadioItem key={value} value={value} data-panel-choice={value}
                  className="flex min-h-11 cursor-default items-center gap-2 rounded-sm px-2.5 outline-none data-highlighted:bg-surface-2 data-highlighted:text-accent">
                  <Icon size={16} aria-hidden="true" />
                  <span className="flex-1">{t(value)}</span>
                  <DropdownMenu.ItemIndicator><Check size={14} aria-hidden="true" /></DropdownMenu.ItemIndicator>
                </DropdownMenu.RadioItem>
              );
            })}
          </DropdownMenu.RadioGroup>
        </DropdownMenu.Content>
      </DropdownMenu.Portal>
    </DropdownMenu.Root>
  );
});
