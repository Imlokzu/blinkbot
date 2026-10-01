import { Fragment, type Node } from '@tiptap/pm/model';

export interface EditRange { from: number; oldTo: number; to: number }

/** ProseMirror positions include structure and marks, not Markdown delimiters. */
export function editRange(previous: Node, next: Node): EditRange | null {
  const from = previous.content.findDiffStart(next.content);
  if (from === null) return null;
  const end = previous.content.findDiffEnd(next.content)!;
  const overlap = Math.max(0, from - Math.min(end.a, end.b));
  return { from, oldTo: end.a + overlap, to: end.b + overlap };
}

/** Separate paragraph edits keep untouched paragraphs visible throughout. */
export function playbackStages(previous: Node, next: Node): Node[] {
  if (previous.eq(next)) return [];
  if (previous.childCount !== next.childCount || next.childCount > 80) return [next];
  const children: Node[] = [];
  previous.forEach((child) => children.push(child));
  const stages: Node[] = [];
  next.forEach((child, _offset, index) => {
    if (child.eq(children[index])) return;
    children[index] = child;
    stages.push(next.copy(Fragment.from([...children])));
  });
  // Keep playback bounded when a long document changes almost everywhere.
  return stages.length > 8 ? [next] : stages;
}

/** Reveal within the final structure, so tables, lists and marks never flicker. */
export function playbackFrame(next: Node, range: EditRange, cursor: number): Node {
  const visit = (node: Node, position: number): Node | null => {
    if (node.isText) {
      let cutFrom = Math.max(0, cursor - position);
      let cutTo = Math.min(node.nodeSize, range.to - position);
      // A frame must never split a UTF-16 surrogate pair.
      const text = node.text!;
      if (cutFrom > 0 && /[\uD800-\uDBFF]/.test(text[cutFrom - 1] ?? '')) cutFrom--;
      if (cutTo > 0 && /[\uD800-\uDBFF]/.test(text[cutTo - 1] ?? '')) cutTo++;
      if (cutTo <= cutFrom) return node;
      const visible = text.slice(0, cutFrom) + text.slice(cutTo);
      return visible ? node.type.schema.text(visible, node.marks) : null;
    }
    // Embeds appear when the cursor reaches them, rather than loading up front.
    if (node.isLeaf) return position >= cursor && position < range.to ? null : node;
    const children: Node[] = [];
    node.forEach((child, offset) => {
      const revealed = visit(child, position + offset + (node.type.name === 'doc' ? 0 : 1));
      if (revealed) children.push(revealed);
    });
    const content = Fragment.from(children);
    // Hiding the only embed still requires a paragraph in a document, quote
    // or table cell. Keep each frame valid before it enters a transaction.
    return node.type.validContent(content) ? node.copy(content) : node.type.createAndFill(node.attrs, content, node.marks);
  };
  return visit(next, 0)!;
}
