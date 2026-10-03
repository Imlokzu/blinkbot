import { useEffect, useId, useState } from 'react';
import { api } from '@/lib/api';
import { Button } from '@/components/ui/Button';
import { DialogContent } from '@/components/ui/Dialog';
import { useIsPhone } from '@/hooks/useMediaQuery';
import { statusMessage, t } from '@/locales/imageGeneration';

type Status = { available: boolean; code: string };

export function ImageGenerationDialog({ busy, onGenerate, onCloseAutoFocus }: {
  busy: boolean;
  onGenerate: (message: string) => void;
  onCloseAutoFocus: React.ComponentProps<typeof DialogContent>['onCloseAutoFocus'];
}) {
  const phone = useIsPhone();
  const id = useId();
  const [prompt, setPrompt] = useState('');
  const [status, setStatus] = useState<Status | null>(null);
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    setStatus(null);
    void api<Status>('/api/images/status', { signal: controller.signal, cache: 'no-store' }).then(
      (value) => { if (!controller.signal.aborted) setStatus(value); },
      (error: Error) => { if (!controller.signal.aborted) setStatus({ available: false, code: error.message }); },
    );
    return () => controller.abort();
  }, [attempt]);
  return <DialogContent title={t('title')} description={t('description')} className="[&_header_p]:text-ink-2" side={phone ? 'bottom' : 'center'} onCloseAutoFocus={onCloseAutoFocus}>
    <form className="flex flex-col gap-4" onSubmit={(event) => {
      event.preventDefault();
      if (busy || !status?.available || !prompt.trim()) return;
      const language = document.documentElement.lang === 'uk' ? 'uk' : 'en';
      onGenerate(`/image:${language} ${prompt.trim()}`);
    }}>
      <div className="text-[13px] text-ink-2"><p className="font-medium text-ink">{t('provider')}</p><p>{t('usage')}</p></div>
      <div className="flex flex-col gap-2"><label htmlFor={id} className="text-[13px] font-medium">{t('prompt')}</label>
        <textarea id={id} value={prompt} onChange={(event) => setPrompt(event.target.value)} maxLength={8000}
          rows={4} placeholder={t('placeholder')} required
          className="w-full resize-y rounded-md border border-line bg-surface px-3 py-2 text-[14px] outline-none focus:border-accent" />
      </div>
      <div role="status" className="text-[13px] text-ink-2">
        {status ? statusMessage(status.code) : t('checking')}
        {status?.code === 'codex_login_required' || status?.code === 'codex_missing' ? <p className="mt-2 font-mono text-[12px]">{t('login')}</p> : null}
      </div>
      <div className="flex justify-end gap-2">
        <Button type="button" variant="ghost" disabled={!status} onClick={() => setAttempt((value) => value + 1)}>{t('retry')}</Button>
        <Button type="submit" disabled={busy || !status?.available || !prompt.trim()}>{t('create')}</Button>
      </div>
    </form>
  </DialogContent>;
}
