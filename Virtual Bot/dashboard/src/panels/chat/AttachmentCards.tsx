import { useEffect, useRef, useState } from 'react';
import { Download, FileText, Image as ImageIcon, X } from 'lucide-react';
import { authHeaders } from '@/lib/auth';
import { Dialog, DialogContent, DialogTrigger } from '@/components/ui/Dialog';
import { Button } from '@/components/ui/Button';
import { useIsPhone } from '@/hooks/useMediaQuery';
import { t } from '@/locales/attachments';
import { attachmentInfo, attachmentFormat, isAttachmentImage, type AttachmentInfo } from './attachmentInfo';
import './attachments.css';

type Preview = { text: string; truncated: boolean; type: string; size: number };

function useAttachmentPreview(file: AttachmentInfo) {
  const image = isAttachmentImage(file);
  const [src, setSrc] = useState('');
  const [imageError, setImageError] = useState(false);
  // Private document text belongs to this card, never to the shared query cache.
  const [documentPreview, setDocumentPreview] = useState<Preview | null>(null);
  const [documentError, setDocumentError] = useState(false);
  useEffect(() => {
    setSrc(''); setImageError(false);
    setDocumentPreview(null); setDocumentError(false);
    const controller = new AbortController();
    let objectUrl = '';
    void (async () => {
      const headers = await authHeaders();
      if (controller.signal.aborted) return;
      const path = image ? file.url : `/api/chat/attachment-preview?url=${encodeURIComponent(file.url)}`;
      const response = await fetch(path, { headers, signal: controller.signal, cache: 'no-store', redirect: 'error' });
      if (!response.ok) throw new Error('attachment_unavailable');
      if (!image) {
        const preview = await response.json() as Preview;
        if (typeof preview?.text !== 'string') throw new Error('invalid_preview');
        if (!controller.signal.aborted) setDocumentPreview(preview);
        return;
      }
      const blob = await response.blob();
      if (controller.signal.aborted) return;
      if (!/^image\/(png|jpeg|gif|webp)$/.test(blob.type) || blob.size > 10 * 1024 * 1024) throw new Error('invalid_image');
      objectUrl = URL.createObjectURL(blob);
      setSrc(objectUrl);
    })().catch(() => {
      if (!controller.signal.aborted) {
        if (image) setImageError(true); else setDocumentError(true);
      }
    });
    return () => { controller.abort(); if (objectUrl) URL.revokeObjectURL(objectUrl); };
  }, [file.url, image]);
  return { image, src, text: documentPreview?.text ?? '', partial: documentPreview?.truncated,
    loading: image ? !src && !imageError : !documentPreview && !documentError,
    failed: image ? imageError : documentError };
}

function AttachmentCard({ file, draft, onRemove }: { file: AttachmentInfo; draft: boolean; onRemove?: () => void }) {
  const [open, setOpen] = useState(false);
  const [downloading, setDownloading] = useState(false);
  const [downloadError, setDownloadError] = useState(false);
  const downloadController = useRef<AbortController | null>(null);
  useEffect(() => () => { downloadController.current?.abort(); }, [file.url]);
  const phone = useIsPhone();
  const preview = useAttachmentPreview(file);
  const size = file.size === undefined ? '' : new Intl.NumberFormat(document.documentElement.lang, { style: 'unit', unit: file.size >= 1024 * 1024 ? 'megabyte' : 'kilobyte', maximumFractionDigits: 1 }).format(file.size / (file.size >= 1024 * 1024 ? 1024 * 1024 : 1024));
  const download = async () => {
    if (downloadController.current) return;
    const controller = new AbortController();
    downloadController.current = controller;
    setDownloading(true); setDownloadError(false);
    let url = '';
    try {
      const headers = await authHeaders();
      if (controller.signal.aborted) return;
      const response = await fetch(file.url, { headers, signal: controller.signal, cache: 'no-store', redirect: 'error' });
      if (!response.ok) throw new Error('attachment_unavailable');
      const blob = await response.blob();
      if (controller.signal.aborted) return;
      url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url; link.download = file.name; link.click();
    } catch { if (!controller.signal.aborted) setDownloadError(true); }
    finally {
      if (downloadController.current === controller) downloadController.current = null;
      if (!controller.signal.aborted) setDownloading(false);
      // Give the browser time to consume the download before revoking its URL.
      if (url) window.setTimeout(() => URL.revokeObjectURL(url), 1000);
    }
  };
  return <Dialog open={open} onOpenChange={setOpen}>
    <div className={`attachment-card ${draft ? 'is-draft prompt-bar__chip' : ''}`} data-attachment-card={file.url}>
    <DialogTrigger asChild><button type="button" className="attachment-card-open" aria-label={`${t('preview.open')} ${file.name}`}>
      <div className={`attachment-thumbnail ${preview.image ? 'is-image' : ''}`} aria-hidden="true">
        {preview.src ? <img src={preview.src} alt="" /> : preview.failed ? <FileText /> : preview.image ? <ImageIcon /> : <div className="attachment-excerpt">{preview.text || <span className="attachment-loading" />}</div>}
        {!preview.image ? <span className="attachment-format">{attachmentFormat(file)}</span> : null}
      </div>
      <span className="attachment-card-meta"><span className="attachment-card-name" title={file.name}>{file.name}</span>
        <span className="attachment-card-size">{preview.failed ? t('preview.unavailable') : [attachmentFormat(file), size].filter(Boolean).join(' · ')}</span></span>
    </button></DialogTrigger>
    {onRemove ? <button type="button" className="attachment-remove" aria-label={`${t('preview.remove')} ${file.name}`} onClick={onRemove}><X size={14} /></button> : null}
      <DialogContent title={file.name} side={phone ? 'bottom' : 'center'} className="attachment-dialog" bodyClassName="p-0 sm:p-0">
        <div className="attachment-preview-body">
          {preview.failed ? <p role="alert">{t('preview.unavailable')}</p> : preview.loading ? <p role="status">{t('preview.loading')}</p>
            : preview.src ? <img src={preview.src} alt={file.name} /> : <pre>{preview.text}</pre>}
          {preview.partial ? <p className="attachment-preview-notice">{t('preview.partial')}</p> : null}
        </div>
        <footer className="attachment-preview-footer"><span>{attachmentFormat(file)}{size ? ` · ${size}` : ''}</span>
          <Button size="sm" variant="outline" disabled={downloading} onClick={() => void download()}><Download />{t('preview.download')}</Button>
        </footer>
        {downloadError ? <p role="alert" className="px-4 pb-3 text-sm text-err">{t('preview.unavailable')}</p> : null}
      </DialogContent>
    </div>
  </Dialog>;
}

/** Identical previews before sending and in the saved conversation. */
export function AttachmentCards({ files, onRemove }: { files: readonly unknown[]; onRemove?: (index: number) => void }) {
  return <div className="attachment-cards" data-attachment-previews="">
    {files.map((value, index) => {
      const file = attachmentInfo(value);
      return file ? <AttachmentCard key={`${file.url}:${index}`} file={file} draft={Boolean(onRemove)} onRemove={onRemove ? () => onRemove(index) : undefined} /> : null;
    })}
  </div>;
}
