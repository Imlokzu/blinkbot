import { useContext, useEffect, useId, useMemo, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { Button } from '@/components/ui/Button';
import { Textarea } from '@/components/ui/Field';
import { t } from '@/locales/intelligentUi';
import { InteractiveSessionContext, ToolOwnerContext } from './InteractiveSessionContext';
import { submitToolAnswer } from './chatSubmission';
import { intelligentUiReceipt, readIntelligentUiSource, sandboxMessage, type IntelligentUiReceipt } from './intelligentUiData';
import { intelligentUiDocument, type SandboxTheme } from './intelligentUiSandbox';
import type { ToolStep } from './types';
import './intelligent-ui.css';

function currentTheme(): SandboxTheme {
  const style = getComputedStyle(document.documentElement);
  return Object.fromEntries(Object.entries({ '--background': '--c-surface', '--surface': '--c-surface-2',
    '--text': '--c-text', '--muted': '--c-text-2', '--border': '--c-border', '--accent': '--c-accent', '--font-sans': '--f-sans',
    ...Object.fromEntries(['--c-bg', '--c-surface', '--c-surface-2', '--c-surface-3', '--c-border', '--c-border-strong',
      '--c-text', '--c-text-2', '--c-text-3', '--c-accent', '--c-accent-ink', '--c-accent-soft', '--c-err', '--f-sans', '--f-mono']
      .map(name => [name, name])),
  }).map(([target, source]) => [target, style.getPropertyValue(source).trim()]));
}

export function IntelligentUiCard({ step }: { step: ToolStep }) {
  const owner = useContext(ToolOwnerContext), session = useContext(InteractiveSessionContext);
  const receipt = intelligentUiReceipt(step);
  if (!receipt) return <section className="intelligent-ui" data-intelligent-ui="">
    <p className="intelligent-ui-label">{t('label')}</p>
    <p role="status">{t(step.status === 'active' ? 'loading' : step.status === 'interrupted' ? 'interrupted' : 'failed')}</p>
  </section>;
  return <SavedCard key={JSON.stringify([owner, session, receipt.id, receipt.sha256])} receipt={receipt} owner={owner} session={session} />;
}

function SavedCard({ receipt, owner, session }: { receipt: IntelligentUiReceipt; owner: string; session: string }) {
  const frame = useRef<HTMLIFrameElement>(null);
  const pending = useRef(false), mounted = useRef(true);
  const [generation, setGeneration] = useState(0);
  const [paused, setPaused] = useState(false);
  const [ready, setReady] = useState(false), [failed, setFailed] = useState(false);
  const [height, setHeight] = useState(receipt.height);
  const [draft, setDraft] = useState<string | null>(null);
  const [sending, setSending] = useState(false);
  const [feedback, setFeedback] = useState<'sent' | 'busy' | 'stale' | 'sendFailed' | null>(null);
  const draftId = useId();
  const channel = useMemo(() => Array.from(crypto.getRandomValues(new Uint8Array(16)), byte => byte.toString(16).padStart(2, '0')).join(''), [generation]);
  const controlRuntime = useQuery({ queryKey: ['intelligent-ui-controls'], retry: false, staleTime: Infinity,
    queryFn: () => import('virtual:blink-ui-controls').then(module => module.default),
  });
  const query = useQuery({ queryKey: ['intelligent-ui', owner, session, receipt.id, receipt.sha256], retry: false, staleTime: Infinity,
    queryFn: async ({ signal }) => {
      const response = await api<{ content?: unknown }>(`/api/workspace/file?${new URLSearchParams({ path: receipt.path, session_id: session })}`, { signal });
      return readIntelligentUiSource(response.content, receipt.sha256);
    },
  });
  // Theme changes are messaged separately so they do not reset the user's inputs.
  const source = useMemo(() => query.data && controlRuntime.data ? intelligentUiDocument(query.data, channel, currentTheme(), document.documentElement.lang, receipt.title, controlRuntime.data) : '', [query.data, controlRuntime.data, channel, receipt.title]);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);
  useEffect(() => {
    if (!source || paused) return;
    const timer = window.setTimeout(() => setFailed(true), 12000);
    const updateTheme = () => frame.current?.contentWindow?.postMessage({ channel, type: 'theme', values: currentTheme() }, '*');
    const receive = (event: MessageEvent) => {
      if (event.source !== frame.current?.contentWindow) return;
      const message = sandboxMessage(event.data, channel);
      if (!message) return;
      if (message.type === 'ready') { clearTimeout(timer); setReady(true); updateTheme(); }
      if (message.type === 'error') { clearTimeout(timer); setFailed(true); }
      if (message.type === 'resize') setHeight(message.height);
      if (message.type === 'followup') setDraft(current => current ?? message.text);
    };
    const observer = new MutationObserver(updateTheme);
    observer.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme', 'data-accent', 'style'] });
    window.addEventListener('message', receive);
    return () => { clearTimeout(timer); observer.disconnect(); window.removeEventListener('message', receive); };
  }, [channel, paused, source]);
  const restart = () => {
    setReady(false); setFailed(false); setPaused(false); setHeight(receipt.height); setGeneration(value => value + 1);
    if (query.isError) void query.refetch();
    if (controlRuntime.isError) void controlRuntime.refetch();
  };
  const send = async () => {
    if (pending.current || !session || !draft?.trim()) return;
    pending.current = true; setSending(true); setFeedback(null);
    const result = await submitToolAnswer(draft, session);
    pending.current = false;
    if (!mounted.current) return;
    setSending(false);
    if (result.accepted) { setDraft(null); setFeedback('sent'); }
    else setFeedback(result.reason === 'busy' ? 'busy' : result.reason === 'stale' ? 'stale' : 'sendFailed');
  };
  const error = failed || query.isError || controlRuntime.isError;
  return <section className="intelligent-ui" data-intelligent-ui="" aria-label={receipt.title}>
    <header className="intelligent-ui-header">
      <div><p className="intelligent-ui-label">{t('label')}</p><h3>{receipt.title}</h3></div>
      <div className="intelligent-ui-actions">
        <Button size="sm" variant="ghost" onClick={restart}>{t(error ? 'retry' : 'reset')}</Button>
        <Button size="sm" variant="quiet" aria-expanded={!paused} onClick={() => paused ? restart() : setPaused(true)}>{t(paused ? 'show' : 'hide')}</Button>
      </div>
    </header>
    <p className="intelligent-ui-summary">{receipt.summary}</p>
    {!paused && !error && !ready ? <p className="intelligent-ui-status" role="status">{t('loading')}</p> : null}
    {error ? <p className="intelligent-ui-status" role="alert">{t('failed')}</p> : null}
    {!paused && !error && source ? <iframe key={channel} ref={frame} title={receipt.title} srcDoc={source}
      sandbox="allow-scripts" referrerPolicy="no-referrer" allow="camera 'none'; microphone 'none'; geolocation 'none'; clipboard-read 'none'; clipboard-write 'none'"
      style={{ height }} /> : null}
    {!paused && !error ? <p className="intelligent-ui-hint">{t('temporary')}</p> : null}
    {draft !== null ? <div className="intelligent-ui-followup">
      <label htmlFor={draftId}>{t('followup')}</label>
      <Textarea id={draftId} value={draft} maxLength={2000} disabled={sending} onChange={event => setDraft(event.target.value)} />
      <div className="intelligent-ui-actions">
        <Button size="sm" variant="ghost" disabled={sending} onClick={() => { setDraft(null); setFeedback(null); }}>{t('cancel')}</Button>
        <Button size="sm" variant="solid" disabled={sending || !session || !draft.trim()} onClick={() => void send()}>{t(sending ? 'sending' : 'send')}</Button>
      </div>
    </div> : null}
    {feedback ? <p className="intelligent-ui-status" role="status">{t(feedback)}</p> : null}
  </section>;
}
