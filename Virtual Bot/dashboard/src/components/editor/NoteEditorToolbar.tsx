import { useState } from 'react';
import { useEditorState, type Editor } from '@tiptap/react';
import { Bold, Italic, Strikethrough, Code, Quote, List, ListOrdered, ListChecks, Undo2, Redo2, Table2, Link, Image, PenTool, Rows3, Columns3, Trash2, type LucideIcon } from 'lucide-react';
import { Button } from '@/components/ui/Button';
import { Dialog, DialogContent } from '@/components/ui/Dialog';
import { useToast } from '@/components/ui/Toaster';
import { t } from '@/locales/editor';
import { safeMarkdownUrl } from '@/panels/chat/workspaceLinks';

export function NoteEditorToolbar({ editor, createDrawing }: { editor: Editor; createDrawing?: () => Promise<string> }) {
  const toast = useToast();
  const [dialog, setDialog] = useState<'link' | 'image' | null>(null);
  const [url, setUrl] = useState('');
  const [creating, setCreating] = useState(false);
  const state = useEditorState({ editor, selector: ({ editor: e }) => ({
    bold: e.isActive('bold'), italic: e.isActive('italic'), strike: e.isActive('strike'), code: e.isActive('code'),
    quote: e.isActive('blockquote'), bullets: e.isActive('bulletList'), numbers: e.isActive('orderedList'),
    tasks: e.isActive('taskList'), table: e.isActive('table'),
    heading: ([1, 2, 3] as const).find((level) => e.isActive('heading', { level })) ?? 0,
    undo: e.can().undo(), redo: e.can().redo(),
  }) });
  const action = (name: Parameters<typeof t>[0], Icon: LucideIcon, run: () => void, active?: boolean, disabled = false) => (
    <Button key={name} variant={active ? 'quiet' : 'ghost'} size="icon-sm" aria-label={t(name)} title={t(name)}
      aria-pressed={active} disabled={disabled} onMouseDown={(event) => event.preventDefault()} onClick={run}>
      <Icon />
    </Button>
  );
  const insertUrl = () => {
    const address = url.trim();
    if (!address || !safeMarkdownUrl(address) || /\s/.test(address) || /^file:/i.test(address)) {
      toast.error(t('invalidUrl')); return;
    }
    if (dialog === 'image') editor.chain().focus().setImage({ src: address }).run();
    else editor.chain().focus().extendMarkRange('link').setLink({ href: address }).run();
    setDialog(null);
  };
  return <>
    <div className="note-toolbar" role="toolbar" aria-label={t('toolbar')}>
      {action('undo', Undo2, () => { editor.chain().focus().undo().run(); }, undefined, !state.undo)}
      {action('redo', Redo2, () => { editor.chain().focus().redo().run(); }, undefined, !state.redo)}
      <span className="note-toolbar-separator" />
      <select aria-label={t('paragraph')} value={state.heading} onChange={(event) => {
        const level = Number(event.target.value) as 1 | 2 | 3;
        if (level) editor.chain().focus().setHeading({ level }).run();
        else editor.chain().focus().setParagraph().run();
      }}>
        <option value="0">{t('paragraph')}</option>
        <option value="1">{t('h1')}</option><option value="2">{t('h2')}</option><option value="3">{t('h3')}</option>
      </select>
      {action('bold', Bold, () => { editor.chain().focus().toggleBold().run(); }, state.bold)}
      {action('italic', Italic, () => { editor.chain().focus().toggleItalic().run(); }, state.italic)}
      {action('strike', Strikethrough, () => { editor.chain().focus().toggleStrike().run(); }, state.strike)}
      {action('code', Code, () => { editor.chain().focus().toggleCode().run(); }, state.code)}
      <span className="note-toolbar-separator" />
      {action('bullets', List, () => { editor.chain().focus().toggleBulletList().run(); }, state.bullets)}
      {action('numbers', ListOrdered, () => { editor.chain().focus().toggleOrderedList().run(); }, state.numbers)}
      {action('tasks', ListChecks, () => { editor.chain().focus().toggleTaskList().run(); }, state.tasks)}
      {action('quote', Quote, () => { editor.chain().focus().toggleBlockquote().run(); }, state.quote)}
      {action('table', Table2, () => { editor.chain().focus().insertTable({ rows: 3, cols: 3, withHeaderRow: true }).run(); })}
      {action('link', Link, () => { setUrl(editor.getAttributes('link').href ?? ''); setDialog('link'); })}
      {action('image', Image, () => { setUrl(''); setDialog('image'); })}
      {createDrawing ? action('drawing', PenTool, () => {
        setCreating(true);
        void createDrawing().then((src) => {
          if (!editor.isDestroyed) editor.chain().focus().setImage({ src, alt: t('drawingTitle') }).run();
        }, () => toast.error(t('drawingFailed'))).finally(() => setCreating(false));
      }, undefined, creating) : null}
    </div>
    {state.table ? <div className="note-toolbar note-table-tools">
      {action('addRow', Rows3, () => { editor.chain().focus().addRowAfter().run(); })}
      {action('addColumn', Columns3, () => { editor.chain().focus().addColumnAfter().run(); })}
      {action('deleteTable', Trash2, () => { editor.chain().focus().deleteTable().run(); })}
    </div> : null}
    <Dialog open={dialog !== null} onOpenChange={(open) => { if (!open) setDialog(null); }}>
      <DialogContent title={t(dialog === 'image' ? 'image' : 'link')}>
        <form onSubmit={(event) => { event.preventDefault(); insertUrl(); }} className="space-y-4">
          <label className="block text-sm text-ink-2">{t('url')}
            <input autoFocus value={url} onChange={(event) => setUrl(event.target.value)} placeholder={t('urlPlaceholder')}
              className="mt-2 w-full rounded-md border border-line bg-surface px-3 py-2 text-ink" />
          </label>
          <div className="flex justify-end gap-2">
            <Button onClick={() => setDialog(null)}>{t('cancel')}</Button>
            <Button variant="solid" type="submit">{t('insert')}</Button>
          </div>
        </form>
      </DialogContent>
    </Dialog>
  </>;
}
