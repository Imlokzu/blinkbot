import { useEffect, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { BookOpen, ChevronLeft, FileText, Settings } from '../../vendor/solar-icons/compat.ts';
import { Button } from '@/components/ui/Button';
import { Dialog, DialogContent } from '@/components/ui/Dialog';
import { useToast } from '@/components/ui/Toaster';
import { get, post } from '@/lib/api';
import { t, connectorError } from '@/locales/connectors';
import { t as uploadT } from '@/locales/attachments';
import type { Connector } from '@/panels/settings/ConnectorsSection';

type Source = { id: string; title: string; status: string };
export function ConnectorPicker({ open, onClose, onAttach }: { open: boolean; onClose: () => void; onAttach: (attachment: unknown) => void }) {
  const toast = useToast();
  const [selected, setSelected] = useState('');
  const [notebook, setNotebook] = useState<{ id: string; title: string } | null>(null);
  const [busy, setBusy] = useState(false);
  const active = useRef(true);
  useEffect(() => { active.current = true; return () => { active.current = false; }; }, []);
  const inventory = useQuery({ queryKey: ['connectors'], queryFn: () => get<{ connectors: Connector[] }>('/api/connectors'), enabled: open });
  const notebooks = useQuery({ queryKey: ['notebooklm-notebooks'], queryFn: () => get<{ notebooks: { id: string; title: string }[]; has_more?: boolean }>('/api/connectors/notebooklm/notebooks'), enabled: open && selected === 'notebooklm' });
  const sources = useQuery({ queryKey: ['notebooklm-sources', notebook?.id], queryFn: () => get<{ sources: Source[]; has_more?: boolean }>(`/api/connectors/notebooklm/notebooks/${notebook?.id}/sources`), enabled: open && !!notebook });
  const attach = async (source: Source) => {
    if (!notebook || busy) return;
    setBusy(true);
    try {
      const attachment = await post<{ truncated?: boolean }>('/api/connectors/notebooklm/attach', { notebook_id: notebook.id, source_id: source.id });
      if (!active.current) return;
      onAttach(attachment);
      if (attachment.truncated) toast.toast(uploadT('upload.truncated'));
      onClose();
    } catch (error) { toast.error(t('connectors.failed'), connectorError((error as Error).message)); }
    finally { if (active.current) setBusy(false); }
  };
  const current = !selected ? inventory : notebook ? sources : notebooks;
  const error = current.error;
  const pending = current.isFetching || busy;
  const more = notebook ? sources.data?.has_more : notebooks.data?.has_more;
  return (
    <Dialog open={open} onOpenChange={(next) => { if (!next) onClose(); }}>
      <DialogContent title={t('connectors.title')} description={t('connectors.hint')}>
        <div className="space-y-3">
          {selected ? <Button size="sm" variant="ghost" onClick={() => { if (notebook) setNotebook(null); else setSelected(''); }}><ChevronLeft />{t('connectors.back')}</Button> : null}
          {pending ? <p role="status" className="text-[13px] text-ink-3">{t('connectors.loading')}</p> : null}
          {error ? <p role="alert" className="text-[13px] text-err">{connectorError((error as Error).message)}</p> : null}
          {!selected ? (inventory.data?.connectors ?? []).map((item) => (
            <button type="button" key={item.id} className="flex min-h-14 w-full items-center gap-3 rounded-lg border border-line px-3 text-left hover:bg-surface-2"
              onClick={() => { if (item.browse) setSelected('notebooklm'); else { onClose(); location.hash = '#/settings?tab=mcp'; } }}>
              <BookOpen className="size-5 shrink-0" />
              <span className="min-w-0 flex-1"><span className="block text-[14px] text-ink">{item.kind === 'notebooklm' ? t('connectors.notebooklm') : item.name}</span>
                <span className="block text-[12px] text-ink-3">{t(item.agent_access ? 'connectors.agentReady' : 'connectors.agentMissing')}</span></span>
            </button>
          )) : !notebook ? (notebooks.data?.notebooks ?? []).map((item) => (
            <button key={item.id} type="button" className="flex min-h-12 w-full items-center gap-3 rounded-md px-3 text-left text-[14px] hover:bg-surface-2" onClick={() => setNotebook(item)}><BookOpen className="size-4 shrink-0" />{item.title}</button>
          )) : (sources.data?.sources ?? []).map((source) => (
            <Button key={source.id} className="min-h-12 w-full justify-start text-left" variant="ghost" disabled={busy || source.status !== 'ready'}
              title={source.status !== 'ready' ? t(source.status === 'error' ? 'connectors.source_error' : source.status === 'unknown' ? 'connectors.source_unknown' : 'connectors.processing') : t('connectors.attach')} onClick={() => void attach(source)}><FileText /><span className="min-w-0 truncate">{source.title}</span></Button>
          ))}
          {selected && !pending && !error && !(notebook ? sources.data?.sources.length : notebooks.data?.notebooks.length) ? <p className="text-[13px] text-ink-3">{t('connectors.empty')}</p> : null}
          {more ? <p className="text-[12px] text-warn">{t('connectors.more')}</p> : null}
          <Button variant="outline" size="sm" onClick={() => { onClose(); location.hash = '#/settings?tab=connectors'; }}><Settings />{t('connectors.setup')}</Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}
