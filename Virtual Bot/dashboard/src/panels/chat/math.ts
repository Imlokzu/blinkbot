import { escapeCurrencyDollars, normalizeMathDelimiters } from '@assistant-ui/react-markdown';

/** Reuse the renderer's code-aware normalizer before Markdown consumes escapes. */
export function mathPreprocess(source: string): string {
  return escapeCurrencyDollars(normalizeMathDelimiters(source));
}

export const mathOptions = {
  trust: false,
  strict: 'ignore' as const,
  maxExpand: 100,
  maxSize: 20,
};

/** remark-math treats one-line $$...$$ as inline; retain the author's display intent. */
export function remarkDisplayMath() {
  return (tree: { children?: unknown[] }, file: { value: unknown }) => {
    const source = String(file.value);
    const visit = (value: unknown, quoteDepth = 0) => {
      if (!value || typeof value !== 'object') return;
      const node = value as { type?: string; value?: string; children?: unknown[];
        position?: { start?: { offset?: number }; end?: { offset?: number } }; data?: Record<string, unknown> };
      const offset = node.position?.start?.offset;
      if (node.type === 'math' && offset !== undefined) {
        const raw = source.slice(offset, node.position?.end?.offset);
        const lines = raw.trimEnd().split('\n');
        const opening = lines[0]?.trim().match(/^\${2,}/)?.[0];
        let closing = lines.at(-1)?.trim() ?? '';
        for (let depth = 0; depth < quoteDepth && closing.startsWith('>'); depth++) closing = closing.slice(1).trim();
        if (opening && (lines.length < 2 || !/^\$+$/.test(closing) || closing.length < opening.length)) {
          node.type = 'paragraph';
          node.children = [{ type: 'text', value: raw }];
          delete node.data;
          delete node.value;
        }
      }
      if (node.type === 'inlineMath' && offset !== undefined && source.startsWith('$$', offset)) {
        node.type = 'math';
        node.data = { hName: 'code', hProperties: { className: ['language-math', 'math-display'] },
          hChildren: [{ type: 'text', value: node.value ?? '' }] };
      }
      node.children?.forEach(child => visit(child, quoteDepth + (node.type === 'blockquote' ? 1 : 0)));
    };
    visit(tree);
  };
}
