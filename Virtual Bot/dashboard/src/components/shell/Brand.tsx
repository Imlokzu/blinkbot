import { MetalText, isMetalFxSupported } from 'metal-fx';
import { ShinyText } from '@/vendor/reactbits';
import { useTheme } from '@/hooks/useTheme';
import { cn } from '@/lib/cn';
import { BotIcon } from '@/components/ui/BotIcon';
import { t } from '@/lib/i18n';

// The mark matches the app icon and chat avatars. Keep the existing
// metal wordmark, with ShinyText as the fallback when WebGL is unavailable.
export function Brand({ compact, className }: { compact?: boolean; className?: string }) {
  const { resolved } = useTheme();

  return (
    <div className={cn('flex select-none items-center gap-2', className)}>
      <BotIcon />
      {isMetalFxSupported() ? (
        <MetalText
          font="700 17px/1 Helvetica, Arial, sans-serif"
          color="var(--c-text)"
          theme={resolved}
          strength={0.55}
        >
          {t('brand.name')}
        </MetalText>
      ) : (
        <ShinyText
          text={t('brand.name')}
          speed={7}
          spread={90}
          color="var(--c-text)"
          shineColor="var(--c-accent)"
          className="text-[17px] font-bold leading-none tracking-normal [font-family:Helvetica,Arial,sans-serif]"
        />
      )}
      {!compact ? (
        <span className="font-mono text-[11px] tracking-[0.14em] text-ink-3">{t('brand.virtual')}</span>
      ) : null}
    </div>
  );
}
