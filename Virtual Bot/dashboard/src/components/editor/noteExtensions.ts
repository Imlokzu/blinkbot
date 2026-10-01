import StarterKit from '@tiptap/starter-kit';
import { Markdown } from '@tiptap/markdown';
import { TableKit } from '@tiptap/extension-table';
import TaskList from '@tiptap/extension-task-list';
import TaskItem from '@tiptap/extension-task-item';
import { DocumentImage } from './DocumentImage';

/** Both human editing and agent playback use the same document schema. */
export const noteExtensions = () => [
  StarterKit.configure({ link: { openOnClick: false } }), Markdown,
  TableKit.configure({ table: { resizable: true } }), TaskList,
  TaskItem.configure({ nested: true }), DocumentImage,
];
