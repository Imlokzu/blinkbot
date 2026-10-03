import { useEffect, useState } from 'react';
import { AnimatePresence, motion } from 'motion/react';
import { Brand } from './Brand';
import { AuthCorner } from './AuthCorner';
import { Orb } from '@/vendor/aicss';
import { Morph } from '@/components/ui/Morph';
import { Dot } from '@/components/ui/Status';
import { toolLook } from '@/lib/toolLabels';
import { Tip } from '@/components/ui/Tip';
import { useBotEvents, useEventsConnected } from '@/hooks/useBotEvents';
import { useDockSide } from '@/hooks/useDockSide';
import { useIsPhone } from '@/hooks/useMediaQuery';
import { findSection } from '@/app/sections';
import { useRoute } from '@/app/useRoute';
import { cn } from '@/lib/cn';
import { t } from '@/lib/i18n';
import { useBrainModels, useModels, useStatus } from '@/lib/queries';
import { mobileSectionLabel } from './mobileNavLabels';
import { PhoneNavigationMenu } from './PhoneNavigationMenu';

/*
 * Шапка тримає СТАН бота й нічого більше: налаштування вигляду живуть у
 * розділі «Налаштування». Так само було в старій панелі — правило перевірене,
 * переносимо як є.
 */
export function Topbar() {
  const isPhone = useIsPhone();
  const [section] = useRoute();
  const status = useStatus();
  const models = useModels();
  const brain = useBrainModels();
  const eventsLive = useEventsConnected();
  const [side] = useDockSide();
  const [activity, setActivity] = useState<string | null>(null);

  // Чим бот зайнятий прямо зараз. Подія `tool` приходить зі стрічки, а не зі
  // стріму чату, бо тулзи можуть спрацювати й без репліки людини (дрімота,
  // зір, розклад).
  useBotEvents((event) => {
    if (event.type !== 'tool') return;
    const tool = String(event.tool ?? '');
    const state = String(event.state ?? 'start');
    setActivity(state === 'start' ? tool : null);
  });

  // Підпис «зайнятий» гасне сам: якщо тулз не закрився подією (упав, або
  // процес перезапустився), вічний напис брехав би.
  useEffect(() => {
    if (!activity) return;
    const timer = setTimeout(() => setActivity(null), 20_000);
    return () => clearTimeout(timer);
  }, [activity]);

  const backendOk = status.isSuccess;
  const brainId = brain.data?.selected || brain.data?.default || '';
  const brainModel = brain.data?.models.find((model) => model.id === brainId);
  const activeModel = brainModel?.label || brainId || models.data?.active || '';

  // Keep dock-side normalization mounted while chat owns the single phone toolbar.
  if (isPhone && section === 'chat') return null;

  return (
    <header data-global-topbar className={cn("u-safe-t flex shrink-0 items-center gap-3 border-b border-line bg-surface px-3 sm:px-4", isPhone ? "min-h-[calc(48px+env(safe-area-inset-top,0px))]" : "h-14")}
            style={{ zIndex: 'var(--z-topbar)' }}>
      {isPhone ? (
        <>
          <PhoneNavigationMenu />
          <Brand compact className="shrink-0" />
          <div className="mobile-topbar__title" aria-live="polite">
            {mobileSectionLabel(section) ?? findSection(section).label}
          </div>
          <span className="mobile-topbar__action" aria-hidden="true" />
        </>
      ) : <Brand compact className="shrink-0" />}

      {/*
        Порожня середина шапки — це місце для дока, коли він стоїть зверху.
        Він потрапляє сюди порталом із DockNav (там же лишається все
        перенесення), а не окремою смугою під шапкою: друга смуга поруч
        з'їдала ще 84 px і читалась як дві випадково злиплі панелі.
      */}
      <div
        className={cn("min-w-0 flex-1 items-center justify-center gap-3", isPhone ? "hidden" : "flex")}
        // Значок під курсором виростає нижче за смугу — хай виростає.
        style={side === 'top' ? { overflow: 'visible' } : undefined}
      >
        <div id="dock-slot" className="flex items-center" />

        <AnimatePresence>
          {activity ? (
            <motion.div
              initial={{ opacity: 0, y: 4 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0, y: -4 }}
              transition={{ duration: 0.16 }}
              className="flex w-fit shrink-0 items-center gap-2 rounded-full border border-line bg-surface-2 px-3 py-1"
            >
              {/* Орб (aicss.dev/r/orbs) замість крапки: у кожної дії свій
                  рух, тож із іншого кінця кімнати видно не просто «щось
                  робить», а що саме — шукає, пише чи читає. Підпис —
                  людською мовою, бо `web_search` у шапці нічого не пояснює. */}
              <Orb variant={toolLook(activity).orb} size={15} />
              <Morph className="text-[12px] text-ink-2">
                {toolLook(activity).verb}
              </Morph>
            </motion.div>
          ) : null}
        </AnimatePresence>
      </div>

      {activeModel && !isPhone ? (
        <Tip content={t('topbar.model')} side="bottom">
          <span className="hidden max-w-[180px] truncate font-mono text-[11px] text-ink-3 sm:block">
            <Morph mono>{activeModel}</Morph>
          </span>
        </Tip>
      ) : null}

      {/* На телефоні шапка лишає лише навігацію: лампочки статусу й бейдж
          режиму їдять рядок, який і так вузький. */}
      {!isPhone ? (
        <>
          <Tip
            content={backendOk ? 'Бекенд відповідає' : 'Бекенд не відповідає'}
            side="bottom"
          >
            <span className="flex size-8 items-center justify-center">
              <Dot kind={status.isPending ? 'idle' : backendOk ? 'ok' : 'err'} />
            </span>
          </Tip>

          <Tip content={eventsLive ? 'Стрічка подій жива' : 'Стрічка подій обірвана'} side="bottom">
            <span className="flex size-8 items-center justify-center">
              <Dot kind={eventsLive ? 'ok' : 'idle'} />
            </span>
          </Tip>

          <AuthCorner />
        </>
      ) : null}
    </header>
  );
}
