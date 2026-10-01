import { useEffect } from 'react';
import { EditorContent, useEditor } from '@tiptap/react';
import { NoteEditorToolbar } from './NoteEditorToolbar';
import { DocumentWorkspace } from './DocumentImage';
import { noteExtensions } from './noteExtensions';
import type { WorkspaceLocation } from '@/panels/chat/workspaceLinks';
import './note-editor.css';

/*
 * The same Markdown document is editable here and readable by the agent.
 * Drawings remain separate scene files referenced by ordinary Markdown.
 */
export function NoteEditor({
  value,
  onChange,
  editable = true,
  workspace,
  createDrawing,
}: {
  value: string;
  onChange?: (markdown: string) => void;
  editable?: boolean;
  workspace?: { sessionId: string; path: string; location: WorkspaceLocation; isWriting?: (path: string) => boolean; canSave?: (path: string) => boolean; captureSaveGuard?: (path: string) => () => boolean };
  createDrawing?: () => Promise<string>;
}) {
  const editor = useEditor({
    extensions: noteExtensions(),
    content: value,
    contentType: 'markdown',
    editable,
    // React StrictMode must not create a second editor during initial rendering.
    immediatelyRender: false,
    onUpdate: ({ editor: instance }) => {
      onChange?.(instance.getMarkdown());
    },
    editorProps: {
      attributes: {
        class:
          'prose-note min-h-full outline-none',
      },
    },
  });

  // Loading a document is not an edit; controlled updates keep the cursor.
  useEffect(() => {
    if (!editor) return;
    if (editor.getMarkdown() === value) return;
    editor.commands.setContent(value, { contentType: 'markdown', emitUpdate: false });
  }, [editor, value]);

  useEffect(() => { editor?.setEditable(editable, false); }, [editor, editable]);

  return <DocumentWorkspace.Provider value={workspace ?? null}>
    <div className="note-editor">
      {editor && editable ? <NoteEditorToolbar editor={editor} createDrawing={createDrawing} /> : null}
      <EditorContent editor={editor} className="note-editor-content" />
    </div>
  </DocumentWorkspace.Provider>;
}
